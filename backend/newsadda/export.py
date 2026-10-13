"""Build the feed document served to the app (feed.json / GET /feed.json).

The feed is a list of *stories*. A story groups reports of the same
development from one or more publishers; every report keeps its own link,
publisher and timestamps.
"""
from __future__ import annotations

import gzip
import json
from collections import OrderedDict
from datetime import datetime, timedelta
from pathlib import Path

from .classify import CATEGORY_NAMES
from .pipeline import Pipeline, publisher_key
from .textutil import strip_html
from .timeutil import from_iso, iso, utcnow

FEED_VERSION = 1
SWARM_WINDOW = timedelta(hours=2)
SWARM_MIN = 3

STATUS_TEXT = {
    "ok": "Working",
    "error": "Error",
    "unavailable": "Unavailable",
    "disabled": "Off",
    "pending": "Not checked yet",
}
ERROR_TEXT = {
    "no_public_feed": "No public feed found for this publisher; covered via Google News where possible",
    "x_token_missing": "X API key not set, so X posts are not collected",
    "timeout": "Timed out",
}


def _report(it: dict, pipeline: Pipeline) -> dict:
    src = pipeline.sources_by_id.get(it["source_id"])
    return {
        "id": it["id"],
        "source_id": it["source_id"],
        "source_name": src.name if src else it["source_id"],
        "publisher": it["publisher"] or (src.name if src else None),
        "via": src.via if src else None,
        "kind": it["kind"],
        "url": it["url"],
        "title": it["title"],
        "summary": strip_html(it["summary"]) or None,
        "summary_origin": it["summary_origin"],
        "author": it["author"],
        "handle": it["handle"],
        "post_context": it["data"].get("post_context"),
        "published_at": it["published_at"],
        "published_raw": it["published_raw"],
        "tz_assumed": bool(it["tz_assumed"]),
        "published_suspect": bool(it["published_suspect"]),
        "updated_at": it["updated_at"],
        "first_seen_at": it["first_seen_at"],
    }


def build_stories(pipeline: Pipeline, items: list[dict]) -> list[dict]:
    clusters: "OrderedDict[str, list[dict]]" = OrderedDict()
    for it in items:
        clusters.setdefault(it["cluster_id"], []).append(it)

    # Make sure every story carries all of its reports, even those outside the slice.
    stories = []
    for cid, members in clusters.items():
        full = pipeline.store.cluster_items(cid)
        members = full or members
        members.sort(key=lambda m: m["first_seen_at"])
        lead = members[0]
        pubs = {}
        for m in members:
            src = pipeline.sources_by_id.get(m["source_id"])
            key = publisher_key(pipeline.sources, src, m["publisher"]) if src else m["source_id"]
            pubs.setdefault(key, m)

        def union(field: str) -> list[str]:
            out: list[str] = []
            for m in members:
                for v in m["data"].get(field, []):
                    if v not in out:
                        out.append(v)
            return out

        entities = {
            g: list(dict.fromkeys(e for m in members for e in m["data"]["entities"].get(g, [])))
            for g in ("leaders", "figures", "parties", "districts", "seats")
        }
        categories = [c for c in union("categories") if c != "other"] or ["other"]
        labels = union("labels")
        legal = union("legal_status")
        reasons = union("reasons")
        breaking = any(m["data"]["breaking"] for m in members)
        political = bool(entities["leaders"] or entities["figures"] or entities["parties"]) or any(
            c in categories for c in ("politics", "election", "controversy")
        )
        first = from_iso(lead["first_seen_at"])
        swarm = {
            k for k, m in pubs.items() if from_iso(m["first_seen_at"]) - first <= SWARM_WINDOW
        }
        if political and len(swarm) >= SWARM_MIN:
            breaking = True
            reasons.append(f"swarm:{len(swarm)}")
        corroboration = "multi" if len(pubs) >= 2 else "single"
        if corroboration == "multi":
            labels.append("multi_source")
        elif political and (breaking or "allegation" in labels or legal):
            # Single-source political claims are flagged; ordinary local crime reports are not.
            labels.append("awaiting_confirmation")
        statements = [m for m in members if m["data"]["statement"]]
        summary_item = next((m for m in members if m["summary"]), None)
        sort_at = min(m["sort_at"] for m in members)
        stories.append({
            "id": cid,
            "title": lead["title"],
            "summary": strip_html(summary_item["summary"]) if summary_item else None,
            "summary_origin": summary_item["summary_origin"] if summary_item else None,
            "headline_only": summary_item is None,
            "kind": "post" if all(m["kind"] == "post" for m in members) else "article",
            "sort_at": sort_at,
            "first_seen_at": lead["first_seen_at"],
            "last_report_at": max(m["first_seen_at"] for m in members),
            "changed_at": max(m["changed_at"] for m in members),
            "categories": categories,
                "entities": entities,
            "breaking": breaking,
            "reasons": reasons,
            "labels": labels,
            "legal_status": legal,
            "statement": bool(statements),
            "statement_type": statements[0]["data"]["statement_type"] if statements else None,
            "corroboration": corroboration,
            "publisher_count": len(pubs),
            "reports": [_report(m, pipeline) for m in members],
        })
    stories.sort(key=lambda s: s["sort_at"], reverse=True)
    return stories


def source_status(pipeline: Pipeline) -> list[dict]:
    states = pipeline.store.all_source_states()
    out = []
    for s in pipeline.sources:
        st = states.get(s.id, {})
        status = st.get("status", "pending") if s.enabled else "disabled"
        err = st.get("last_error")
        out.append({
            **s.public(),
            "status": status,
            "status_text": STATUS_TEXT.get(status, status),
            "last_ok_at": st.get("last_ok_at"),
            "last_attempt_at": st.get("last_attempt_at"),
            "error": err,
            "error_text": ERROR_TEXT.get(err or "", err),
            "consecutive_failures": st.get("consecutive_failures", 0),
            "last_item_count": st.get("last_item_count", 0),
            "total_items": st.get("total_items", 0),
            "active_url": st.get("active_url"),
        })
    return out


def build_feed(pipeline: Pipeline, since: datetime | None = None, now: datetime | None = None) -> dict:
    now = now or utcnow()
    s = pipeline.settings
    items = pipeline.store.items_for_feed(now - timedelta(hours=s.feed_hours), s.feed_limit, since)
    stories = build_stories(pipeline, items)
    if since is not None:
        stories = [st for st in stories if from_iso(st["changed_at"]) >= since]
    x_src = next((x for x in pipeline.sources if x.kind == "x"), None)
    return {
        "version": FEED_VERSION,
        "generated_at": iso(now),
        "cursor": pipeline.store.max_changed_at() or iso(now),
        "incremental": since is not None,
        "supports_since": False,  # overridden by the API server
        "window_hours": s.feed_hours,
        "story_count": len(stories),
        "x_available": bool(s.x_bearer_token) and bool(x_src and x_src.enabled),
        "ai_summaries": bool(s.ai_enabled and s.ai_api_key),
        "sources": source_status(pipeline),
        "entities": pipeline.entities.public(),
        "categories": CATEGORY_NAMES,
        "stories": stories,
    }


def write_feed(pipeline: Pipeline, out_dir: str | Path) -> Path:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    feed = build_feed(pipeline)
    body = json.dumps(feed, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    (out / "feed.json").write_bytes(body)
    (out / "feed.json.gz").write_bytes(gzip.compress(body, 6))
    health = {"generated_at": feed["generated_at"], "story_count": feed["story_count"], "sources": feed["sources"]}
    (out / "health.json").write_text(json.dumps(health, ensure_ascii=False, indent=1), encoding="utf-8")
    return out / "feed.json"
