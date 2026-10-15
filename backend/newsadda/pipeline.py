"""Ingestion pipeline: fetch -> parse -> classify -> deduplicate/group -> store."""
from __future__ import annotations

import asyncio
import logging
import re

import httpx
from datetime import datetime, timedelta

from .classify import RULES_VERSION, Classifier
from .config import Settings
from .dedup import WINDOW, Clusterer
from .entities import EntityIndex
from .fetcher import Fetcher, FetchResult
from .parser import RawItem, item_id
from .registry import Source, load_blocked_publishers, load_sources, load_trusted_publishers
from .store import Store
from .gist import hindi_only
from .textutil import devanagari_share, normalize, short_summary
from .timeutil import from_iso, iso, utcnow

log = logging.getLogger("newsadda.pipeline")

FUTURE_TOLERANCE = timedelta(minutes=10)


def _base_name(name: str) -> str:
    """'UP Tak', 'UPTak' and 'www.uptak.in' all become 'uptak'."""
    n = normalize(name.split("(")[0]).strip()
    n = re.sub(r"^www\.", "", n)
    n = re.sub(r"\.(com|in|co\.in|net|org)$", "", n)
    return re.sub(r"[^0-9a-z\u0900-\u097f]", "", n)


def _names(s: Source) -> set[str]:
    return {_base_name(x) for x in [s.name, s.name_hi, *s.aliases] if x}


def publisher_key(sources: list[Source], src: Source, publisher: str | None) -> str:
    """One stable key per outlet, however it reached us (own feed, several
    section feeds, or Google News under a slightly different name)."""
    if publisher is None and src.kind != "google_news":
        names = _names(src)
    else:
        names = {_base_name(publisher or "")}
    for s in sources:
        if s.kind in ("rss", "sitemap") and names & _names(s):
            return s.id.split("_")[0]
    return next(iter(names), "") or src.id


def publisher_domain(sources: list[Source], publisher: str | None) -> str | None:
    """Website of a publisher known by name (for Google News items)."""
    if not publisher:
        return None
    key = _base_name(publisher)
    for s in sources:
        if s.kind in ("rss", "sitemap") and key in _names(s):
            return s.domain
    return None


# Promotional/live-TV pages that are not news reports.
PROMO = re.compile(r"live\s*tv|लाइव\s*टीवी|watch\s+live|देखें\s+लाइव", re.I)


class Pipeline:
    def __init__(self, settings: Settings | None = None, store: Store | None = None, client=None):
        self.settings = settings or Settings()
        self.store = store or Store(self.settings.db_path)
        self.client = client  # optional shared httpx.AsyncClient (tests inject a mock)
        self.reload()

    def reload(self):
        """Re-read sources and entities (they are plain files, editable live)."""
        self.sources: list[Source] = load_sources(self.settings.sources_path)
        self.blocked = {_base_name(b) for b in load_blocked_publishers(self.settings.sources_path)}
        self.trusted = {_base_name(t) for t in load_trusted_publishers(self.settings.sources_path)}
        self.skipped = {"not_up": 0, "untrusted": 0, "not_hindi": 0}
        self.gist_stats: dict = {}
        self.relabel_stats: dict = getattr(self, "relabel_stats", {})
        self.entities = EntityIndex(self.settings.entities_path)
        self.classifier = Classifier(self.entities)

    @staticmethod
    def up_mode(src: Source, url: str):
        """UP-section test for items from a portal's UP pages, else the source's own."""
        if src.up_paths and re.search(src.up_paths, url or "", re.I):
            return True
        return src.up_section

    @property
    def ai_active(self) -> bool:
        return bool(self.settings.ai_enabled and self.settings.ai_api_key)

    def credible(self, src: Source, publisher: str | None) -> bool:
        """Direct publisher feeds are trusted. Items that arrive through an
        aggregator (Google News) are kept only from established outlets: those
        we also follow directly, or those on the trusted_publishers list."""
        if src.kind != "google_news":
            return True
        if not self.trusted:
            return True  # no list configured: accept everything (tests, custom setups)
        key = _base_name(publisher or "")
        if not key:
            return False
        if key in self.trusted:
            return True
        return any(s.kind in ("rss", "sitemap") and key in _names(s) for s in self.sources)

    @property
    def sources_by_id(self) -> dict[str, Source]:
        return {s.id: s for s in self.sources}

    def publisher_key(self, src: Source, publisher: str | None) -> str:
        """Stable publisher identity, so one outlet arriving through several
        feeds (or via Google News) is never counted as two sources."""
        return publisher_key(self.sources, src, publisher)

    # ------------------------------------------------------------------
    def _clusterer(self, now: datetime) -> Clusterer:
        cl = Clusterer()
        for it in self.store.recent_items(now - WINDOW):
            cl.add(cl.make_candidate(
                it["id"], it["cluster_id"], it["source_id"], from_iso(it["first_seen_at"]),
                it["title"], it["data"].get("title_entities") or it["data"]["entities"],
            ))
        return cl

    def process(self, src: Source, raw_items: list[RawItem], now: datetime | None = None,
                clusterer: Clusterer | None = None) -> tuple[int, set[str]]:
        """Store new items from one source. Returns (new_count, touched cluster ids)."""
        now = now or utcnow()
        cl = clusterer or self._clusterer(now)
        new_count, touched = 0, set()
        for raw in raw_items:
            iid = item_id(raw.url)
            existing = self.store.get_item(iid)
            if existing:
                # Publisher revised the item: keep both timestamps, bump change time.
                if raw.updated_at and iso(raw.updated_at) != existing["updated_at"]:
                    if raw.updated_at > (from_iso(existing["updated_at"]) or datetime.min.replace(tzinfo=now.tzinfo)):
                        self.store.update_item(iid, updated_at=iso(raw.updated_at), changed_at=iso(now))
                        touched.add(existing["cluster_id"])
                continue

            if raw.publisher and _base_name(raw.publisher) in self.blocked:
                continue  # social posts / aggregators surfaced by Google News
            if PROMO.search(raw.title):
                continue
            if devanagari_share(raw.title) < self.settings.min_hindi_share:
                self.skipped["not_hindi"] += 1
                continue  # the feed is Hindi-only
            if not self.credible(src, raw.publisher):
                self.skipped["untrusted"] += 1
                continue
            title_norm = " ".join(normalize(raw.title).split())
            pub_key = self.publisher_key(src, raw.publisher)
            same = self.store.find_same_title(title_norm, now - WINDOW)
            if same and self.publisher_key(
                self.sources_by_id.get(same["source_id"], src), same["publisher"]
            ) == pub_key:
                # Same headline from the same publisher (e.g. its own feed and
                # Google News): one report, not corroboration.
                continue

            summary = short_summary(raw.description, raw.title)
            c = self.classifier.classify(
                raw.title, summary, up_section=self.up_mode(src, raw.url),
                kind="post" if src.kind == "x" else "article", official_source=src.official,
            )
            if not c.is_up:
                self.skipped["not_up"] += 1
                continue
            # Gists are Hindi like the headlines; English descriptions are kept only
            # when the optional AI step will turn them into Hindi.
            shown_summary = summary if self.ai_active else hindi_only(summary)

            suspect = bool(raw.published_at and raw.published_at > now + FUTURE_TOLERANCE)
            sort_at = raw.published_at if raw.published_at and not suspect else now
            # Grouping compares headlines, so it uses entities named in the headline.
            title_entities = self.entities.tag(raw.title)
            if same:
                cluster_id = same["cluster_id"]
            else:
                cluster_id, _ = cl.assign(raw.title, title_entities, src.id, now)
                cluster_id = cluster_id or iid

            data = {
                "categories": c.categories,
                "entities": c.entities,
                "title_entities": title_entities,
                "breaking": c.breaking,
                "breaking_score": c.breaking_score,
                "reasons": c.reasons,
                "labels": c.labels,
                "statement": c.statement,
                "statement_type": c.statement_type,
                "legal_status": c.legal_status,
                "post_context": raw.post_context,
                "domain": raw.publisher_domain,
            }
            self.store.insert_item({
                "id": iid,
                "source_id": src.id,
                "url": raw.url,
                "title": raw.title,
                "title_norm": title_norm,
                "summary": shown_summary,
                "summary_origin": "publisher" if shown_summary else None,
                "publisher": raw.publisher,
                "author": raw.author,
                "handle": raw.handle,
                "kind": "post" if src.kind == "x" else "article",
                "published_at": iso(raw.published_at),
                "published_raw": raw.published_raw,
                "tz_assumed": int(raw.tz_assumed),
                "published_suspect": int(suspect),
                "updated_at": iso(raw.updated_at),
                "first_seen_at": iso(now),
                "changed_at": iso(now),
                "sort_at": iso(sort_at),
                "cluster_id": cluster_id,
                "data": data,
            })
            cl.add(cl.make_candidate(iid, cluster_id, src.id, now, raw.title, title_entities))
            new_count += 1
            touched.add(cluster_id)

        # A story that gained a report changed for every client that has it.
        for cid in touched:
            self.store.db.execute("UPDATE items SET changed_at=? WHERE cluster_id=?", (iso(now), cid))
        return new_count, touched

    def relabel_if_rules_changed(self) -> int:
        """Re-run classification on stored items after a rules/entities change.
        Grouping (cluster ids) is left untouched."""
        import hashlib
        from pathlib import Path

        ent_hash = hashlib.sha1(Path(self.settings.entities_path).read_bytes()).hexdigest()[:12]
        sig = f"{RULES_VERSION}:{ent_hash}"
        if self.store.meta("rules") == sig:
            return 0
        n = removed = cleared = 0
        src_by = self.sources_by_id
        for it in self.store.all_items():
            src = src_by.get(it["source_id"])
            c = self.classifier.classify(
                it["title"], it["summary"], up_section=self.up_mode(src, it["url"]) if src else True,
                kind=it["kind"], official_source=bool(src and src.official),
            )
            # Stories that no longer pass the UP, Hindi or credibility rules leave the feed.
            if (not c.is_up or devanagari_share(it["title"]) < self.settings.min_hindi_share
                    or (src is not None and not self.credible(src, it["publisher"]))):
                self.store.db.execute("DELETE FROM items WHERE id=?", (it["id"],))
                removed += 1
                continue
            data = dict(it["data"])
            extra: dict = {}
            if it["summary"] and (it["summary_origin"] == "page"
                                  or it["summary_origin"] == "publisher" and not self.ai_active):
                hi = hindi_only(it["summary"])
                if hi is None or len(hi) < (40 if it["summary_origin"] == "page" else 15):
                    # English gist: drop it (and re-send the story) and try the page again.
                    extra = {"summary": None, "summary_origin": None, "changed_at": iso(utcnow())}
                    cleared += 1
                    data.pop("gist_tried", None)
                elif " ".join(hi.split()) != " ".join(it["summary"].split()):
                    extra = {"summary": hi}  # English lead-in or sentences removed
                    if len(it["summary"]) - len(hi) > 40:
                        extra["changed_at"] = iso(utcnow())  # a whole English sentence: re-send now
                        cleared += 1
            data.update(categories=c.categories, entities=c.entities, breaking=c.breaking,
                        breaking_score=c.breaking_score, reasons=c.reasons, labels=c.labels,
                        statement=c.statement, statement_type=c.statement_type, legal_status=c.legal_status)
            # changed_at is left alone: re-labelling must not make the small delta feed
            # as big as the full one. Apps pick up new labels on their next full sync.
            self.store.update_item(it["id"], data=data, **extra)
            n += 1
        self.store.set_meta("rules", sig)
        self.relabel_stats = {"rules": sig, "relabelled": n, "removed": removed, "english_gists_cleared": cleared}
        log.info("re-labelled %d stored items, removed %d, cleared %d English gists, rules %s", n, removed, cleared, sig)
        return n

    async def add_gists(self, now: datetime) -> dict:
        """Direct links and 2–3 line gists for the most important recent items
        that lack them, within the per-run budget (see gist.py)."""
        from .gist import enrich_items

        recent = self.store.recent_items(now - timedelta(hours=6))
        todo = [
            it for it in recent
            if not it["data"].get("gist_tried")
            and (not it["summary"] or "news.google.com" in it["url"])
            and it["kind"] == "article"
        ]

        def priority(it: dict):
            d = it["data"]
            ents = d.get("entities", {})
            political = bool(ents.get("leaders") or ents.get("figures") or ents.get("parties")) or bool(
                {"politics", "election", "controversy"} & set(d.get("categories", [])))
            # Breaking first, then political with named leaders, then other political.
            return (not d.get("breaking"), not political, not (ents.get("leaders") or ents.get("figures")))

        todo.sort(key=lambda it: it["first_seen_at"], reverse=True)  # newest first within each group
        todo.sort(key=priority)  # stable sort keeps the newest-first order inside groups
        batch = todo[: self.settings.gist_budget]
        if not batch:
            return {}
        own = self.client is None
        client = self.client or httpx.AsyncClient(
            timeout=15, follow_redirects=True,
            headers={"User-Agent": self.settings.user_agent, "Accept-Language": "hi-IN,hi;q=0.9"},
        )
        try:
            updates, stats, attempted = await enrich_items(batch, client, self.settings.user_agent)
        finally:
            if own:
                await client.aclose()
        stamp = iso(now)
        self.gist_stats = {**stats, "candidates": len(todo), "budget": len(batch)}
        for it in batch:
            if it["id"] not in attempted:
                continue
            data = dict(it["data"])
            data["gist_tried"] = True
            upd = updates.get(it["id"], {})
            fields: dict = {"data": data}
            if "resolved_url" in upd:
                data["resolved_url"] = upd["resolved_url"]
                from .parser import site_domain

                data["domain"] = site_domain(upd["resolved_url"]) or data.get("domain")
            if upd.get("summary"):
                fields["summary"] = upd["summary"]
                fields["summary_origin"] = "page"
            if len(fields) > 1 or "resolved_url" in upd:
                fields["changed_at"] = stamp
                self.store.db.execute("UPDATE items SET changed_at=? WHERE cluster_id=?", (stamp, it["cluster_id"]))
            self.store.update_item(it["id"], **fields)
        log.info("gists: tried %d, %s", len(batch), stats)
        return stats

    async def run_once(self, force: bool = False, only: list[str] | None = None) -> list[FetchResult]:
        self.reload()
        self.relabel_if_rules_changed()
        sources = [s for s in self.sources if not only or s.id in only]
        fetcher = Fetcher(self.store, self.settings, self.client)
        results = await fetcher.fetch_all(sources, force=force)
        now = utcnow()
        cl = self._clusterer(now)
        # Direct publisher feeds first so Google News copies fold into them.
        order = sorted(results, key=lambda r: (r.source.kind == "google_news", r.source.kind == "x"))
        for res in order:
            new = 0
            if res.ok and res.items:
                try:
                    new, _ = self.process(res.source, res.items, now, cl)
                except Exception as exc:
                    log.exception("processing failed for %s", res.source.id)
                    res.ok, res.error = False, f"processing: {exc!s:.150}"
            fetcher.record(res, new)
            if not res.skipped:
                log.info("%-20s ok=%s status=%s items=%d new=%d err=%s", res.source.id, res.ok,
                         res.http_status, len(res.items), new, res.error)
        if self.settings.gist_enabled:
            try:
                await self.add_gists(now)
            except Exception:
                log.exception("gists failed; continuing without them")
        log.info("skipped this run: %s", self.skipped)
        if self.settings.ai_enabled and self.settings.ai_api_key:
            from .ai import summarize_pending

            try:
                await summarize_pending(self.store, self.settings)
            except Exception:
                log.exception("AI summaries failed; continuing without them")
        self.store.prune(self.settings.keep_days)
        self.store.commit()
        return results


def run_sync(settings: Settings | None = None, force: bool = False) -> list[FetchResult]:
    p = Pipeline(settings)
    try:
        return asyncio.run(p.run_once(force=force))
    finally:
        p.store.close()
