"""Short gists and direct links for important stories.

Many items arrive headline-only (Google News gives no description) and their
link points at news.google.com. For the most important new items in each run
this module:

1. resolves a Google News link to the publisher's real article URL, and
2. reads that article page's own short description (the og:description /
   description meta tag publishers write for link previews) as a 2–3 line
   gist, trimmed and attributed.

It never rewrites or invents text, honours robots.txt for article pages, works
within a per-run budget, and stops early if a site starts refusing requests.
Everything here is best-effort: on any failure the story simply stays
headline-only with its Google News link.
"""
from __future__ import annotations

import asyncio
import base64
import html
import json
import logging
import re
from urllib.parse import quote, urlsplit
from urllib.robotparser import RobotFileParser

import httpx

from .textutil import content_terms, jaccard, normalize, strip_html

log = logging.getLogger("newsadda.gist")

GN_HOST = "news.google.com"
MAX_PAGE = 800_000
GIST_LIMIT = 300

_META = re.compile(r"<meta\s[^>]*>", re.I)
_ATTR = re.compile(r'([a-zA-Z:_-]+)\s*=\s*("([^"]*)"|\'([^\']*)\')')
_ARTICLE_ID = re.compile(r"/(?:rss/)?articles/([^?/#]+)")


def _meta_tags(page: str) -> dict[str, str]:
    out: dict[str, str] = {}
    head = page[: page.lower().find("</head>") + 7] if "</head>" in page.lower() else page[:200_000]
    for tag in _META.findall(head):
        attrs = {m.group(1).lower(): (m.group(3) if m.group(3) is not None else m.group(4)) for m in _ATTR.finditer(tag)}
        key = (attrs.get("property") or attrs.get("name") or "").lower()
        if key and "content" in attrs and key not in out:
            out[key] = html.unescape(attrs["content"] or "")
    return out


def description_from_page(page: str, title: str) -> tuple[str | None, str | None]:
    """(gist, canonical_url) from an article page's meta tags."""
    tags = _meta_tags(page)
    canonical = tags.get("og:url")
    for key in ("og:description", "description", "twitter:description"):
        text = strip_html(tags.get(key))
        if not text or len(text) < 40:
            continue
        nt, nd = normalize(title).strip(), normalize(text).strip()
        if nd == nt or nd.startswith(nt) and len(nd) - len(nt) < 25:
            continue
        if jaccard(content_terms(title), content_terms(text)) > 0.8:
            continue
        if len(text) > GIST_LIMIT:
            cut = text[:GIST_LIMIT]
            sp = max(cut.rfind("।"), cut.rfind(". "))
            text = (cut[: sp + 1] if sp > GIST_LIMIT * 0.5 else cut[: cut.rfind(" ")]).rstrip(" ,;:-") + ("" if sp > GIST_LIMIT * 0.5 else "…")
        return text, canonical
    return None, canonical


def _legacy_decode(article_id: str) -> str | None:
    """Older Google News ids embed the target URL in base64."""
    try:
        raw = base64.urlsafe_b64decode(article_id + "=" * (-len(article_id) % 4))
    except Exception:
        return None
    m = re.search(rb"https?://[\x21-\x7e]+", raw)
    if not m:
        return None
    url = m.group(0).decode("ascii", "ignore")
    return None if "AU_yqL" in url else url


class Gister:
    def __init__(self, client: httpx.AsyncClient, user_agent: str):
        self.client = client
        self.ua = user_agent
        self.robots: dict[str, RobotFileParser] = {}
        self.google_blocked = False
        self.stats = {"resolved": 0, "gists": 0, "robots_blocked": 0, "errors": 0, "google_blocked": False}

    async def resolve_google(self, url: str) -> str | None:
        if self.google_blocked:
            return None
        m = _ARTICLE_ID.search(urlsplit(url).path)
        if not m:
            return None
        aid = m.group(1)
        legacy = _legacy_decode(aid)
        if legacy:
            return legacy
        try:
            r = await self.client.get(f"https://news.google.com/rss/articles/{aid}")
            if r.status_code == 429:
                self._block()
                return None
            sg = re.search(r'data-n-a-sg="([^"]+)"', r.text)
            ts = re.search(r'data-n-a-ts="([^"]+)"', r.text)
            if not (sg and ts):
                return None
            inner = (
                '["garturlreq",[["X","X",["X","X"],null,null,1,1,"US:en",null,1,null,null,null,null,null,0,1],'
                f'"X","X",1,[1,1,1],1,1,null,0,0,null,0],"{aid}",{ts.group(1)},"{sg.group(1)}"]'
            )
            payload = "f.req=" + quote(json.dumps([[["Fbv4je", inner, None, "generic"]]]))
            r2 = await self.client.post(
                "https://news.google.com/_/DotsSplashUi/data/batchexecute",
                content=payload,
                headers={"Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"},
            )
            if r2.status_code == 429:
                self._block()
                return None
            body = r2.text.split("\n\n", 1)[1] if "\n\n" in r2.text else r2.text
            data = json.loads(body)[:-2]
            target = json.loads(data[0][2])[1]
            return target if isinstance(target, str) and target.startswith("http") else None
        except Exception as exc:  # format changes, network trouble
            self.stats["errors"] += 1
            log.debug("google resolve failed: %s", exc)
            return None

    def _block(self) -> None:
        self.google_blocked = True
        self.stats["google_blocked"] = True

    async def _allowed(self, url: str) -> bool:
        parts = urlsplit(url)
        root = f"{parts.scheme}://{parts.netloc}"
        rp = self.robots.get(root)
        if rp is None:
            rp = RobotFileParser()
            try:
                r = await self.client.get(root + "/robots.txt")
                rp.parse(r.text.splitlines() if r.status_code == 200 else [])
            except httpx.HTTPError:
                rp.parse([])
            self.robots[root] = rp
        return rp.can_fetch(self.ua, url)

    async def page_gist(self, url: str, title: str) -> tuple[str | None, str | None]:
        if not await self._allowed(url):
            self.stats["robots_blocked"] += 1
            return None, None
        try:
            async with self.client.stream("GET", url, headers={"Accept": "text/html"}) as r:
                if r.status_code != 200 or "html" not in r.headers.get("content-type", "html"):
                    return None, None
                buf = b""
                async for chunk in r.aiter_bytes():
                    buf += chunk
                    if b"</head>" in buf.lower() or len(buf) > MAX_PAGE:
                        break
            page = buf.decode(r.encoding or "utf-8", "replace")
            return description_from_page(page, title)
        except Exception as exc:
            self.stats["errors"] += 1
            log.debug("page fetch failed for %s: %s", url, exc)
            return None, None

    async def enrich(self, item: dict) -> dict:
        """Returns fields to update: resolved_url, summary."""
        url = item["url"]
        out: dict = {}
        if urlsplit(url).netloc.endswith(GN_HOST):
            real = await self.resolve_google(url)
            if not real:
                return out
            out["resolved_url"] = real
            self.stats["resolved"] += 1
            url = real
        if not item.get("summary"):
            gist, _ = await self.page_gist(url, item["title"])
            if gist:
                out["summary"] = gist
                self.stats["gists"] += 1
        return out


async def enrich_items(items: list[dict], client: httpx.AsyncClient, user_agent: str, concurrency: int = 6,
                       deadline: float = 45.0) -> tuple[dict, dict, set]:
    """Enrich a prioritised list of items.

    Returns ({item_id: updates}, stats, attempted_ids). Items not attempted —
    because the time budget ran out (so the feed is never held back for long)
    or Google started refusing requests — are left for the next run.
    """
    g = Gister(client, user_agent)
    sem = asyncio.Semaphore(concurrency)
    results: dict[str, dict] = {}
    attempted: set[str] = set()
    loop = asyncio.get_running_loop()
    stop_at = loop.time() + deadline

    async def one(it):
        async with sem:
            if loop.time() > stop_at:
                return
            google = urlsplit(it["url"]).netloc.endswith(GN_HOST)
            if google and g.google_blocked:
                return
            try:
                upd = await asyncio.wait_for(g.enrich(it), timeout=max(1.0, stop_at - loop.time() + 10))
            except asyncio.TimeoutError:
                g.stats["errors"] += 1
                return
            if google and g.google_blocked and "resolved_url" not in upd:
                return  # refused, not failed: try again next run
            attempted.add(it["id"])
            if upd:
                results[it["id"]] = upd

    await asyncio.gather(*(one(it) for it in items))
    g.stats["attempted"] = len(attempted)
    return results, g.stats, attempted
