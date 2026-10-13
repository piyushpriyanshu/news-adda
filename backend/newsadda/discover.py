"""Live check of every configured feed URL plus feed autodiscovery.

Fetches each candidate URL, parses it, and reports item counts and the newest
item time. Also scans each source's listing pages for advertised feed links
that look UP-related and tests those too. Listing pages are fetched only when
robots.txt allows it. Output is a JSON report used to maintain sources.yaml.
"""
from __future__ import annotations

import asyncio
import json
from urllib.parse import urlsplit
from urllib.robotparser import RobotFileParser

import httpx

from .config import Settings
from .parser import FeedError, discover_feed_links, parse_feed
from .registry import load_sources
from .timeutil import iso, utcnow

UP_KEYWORDS = ("uttar", "up-", "-up", "/up", "lucknow", "pradesh", "politics", "rajneeti", "state")
MAX_DISCOVERED = 12


async def _robots_ok(client: httpx.AsyncClient, url: str, ua: str, cache: dict) -> bool:
    parts = urlsplit(url)
    root = f"{parts.scheme}://{parts.netloc}"
    if root not in cache:
        rp = RobotFileParser()
        try:
            r = await client.get(root + "/robots.txt")
            rp.parse(r.text.splitlines() if r.status_code == 200 else [])
        except httpx.HTTPError:
            rp.parse([])
        cache[root] = rp
    return cache[root].can_fetch(ua, url)


async def test_feed(client: httpx.AsyncClient, url: str, google: bool = False) -> dict:
    out = {"url": url, "ok": False}
    try:
        r = await client.get(url)
        out["http_status"] = r.status_code
        out["final_url"] = str(r.url)
        if r.status_code != 200:
            out["error"] = f"HTTP {r.status_code}"
            return out
        items = parse_feed(r.content, google_news=google)
        out["items"] = len(items)
        dated = [i.published_at for i in items if i.published_at]
        out["newest"] = iso(max(dated)) if dated else None
        out["undated"] = len(items) - len(dated)
        out["sample_titles"] = [i.title for i in items[:3]]
        out["ok"] = len(items) > 0
        if not items:
            out["error"] = "feed has no items"
    except FeedError as exc:
        out["error"] = f"invalid feed: {exc}"
    except httpx.HTTPError as exc:
        out["error"] = f"{type(exc).__name__}: {exc!s:.120}"
    return out


async def discover(settings: Settings | None = None, only: list[str] | None = None) -> dict:
    settings = settings or Settings()
    sources = [s for s in load_sources(settings.sources_path) if not only or s.id in only]
    robots: dict = {}
    report = {"generated_at": iso(utcnow()), "sources": []}
    async with httpx.AsyncClient(
        timeout=settings.http_timeout, follow_redirects=True,
        headers={"User-Agent": settings.user_agent, "Accept-Language": "hi-IN,hi;q=0.9"},
    ) as client:
        sem = asyncio.Semaphore(settings.concurrency)

        async def per_source(src):
            async with sem:
                entry = {"id": src.id, "kind": src.kind, "configured": [], "discovered": [], "listing": []}
                if src.kind == "x":
                    entry["note"] = "X API source; tested only when X_BEARER_TOKEN is set"
                    return entry
                for u in src.feed_urls:
                    entry["configured"].append(await test_feed(client, u, src.kind == "google_news"))
                tested = {c["url"] for c in entry["configured"]}
                for page in src.listing_pages:
                    rec = {"page": page}
                    if not await _robots_ok(client, page, settings.user_agent, robots):
                        rec["error"] = "disallowed by robots.txt"
                        entry["listing"].append(rec)
                        continue
                    try:
                        r = await client.get(page)
                        rec["http_status"] = r.status_code
                        links = discover_feed_links(r.text, str(r.url), UP_KEYWORDS) if r.status_code == 200 else []
                        rec["links_found"] = len(links)
                    except httpx.HTTPError as exc:
                        rec["error"] = f"{type(exc).__name__}"
                        links = []
                    entry["listing"].append(rec)
                    for link in links:
                        if link in tested or len(entry["discovered"]) >= MAX_DISCOVERED:
                            continue
                        tested.add(link)
                        entry["discovered"].append(await test_feed(client, link))
                entry["working"] = [c["url"] for c in entry["configured"] + entry["discovered"] if c.get("ok")]
                return entry

        report["sources"] = await asyncio.gather(*(per_source(s) for s in sources))
    report["summary"] = {
        s["id"]: (s["working"][0] if s.get("working") else None) for s in report["sources"] if s["kind"] != "x"
    }
    return report


def run(out_path: str | None = None, only: list[str] | None = None) -> dict:
    report = asyncio.run(discover(only=only))
    text = json.dumps(report, ensure_ascii=False, indent=1)
    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(text)
    return report
