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
import yaml
from pathlib import Path

from .config import Settings
from .parser import FeedError, discover_feed_links, parse_feed, parse_sitemap
from .registry import load_sources
from .timeutil import iso, utcnow

UP_KEYWORDS = ("uttar", "up-", "-up", "/up", "lucknow", "pradesh", "politics", "rajneeti", "state")
MAX_DISCOVERED = 25
PRIORITY = ("uttar-pradesh", "uttarpradesh", "lucknow", "up-election", "/up/", "uttar")


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
                    links.sort(key=lambda u: next((i for i, k in enumerate(PRIORITY) if k in u.lower()), len(PRIORITY)))
                    for link in links:
                        if link in tested or len(entry["discovered"]) >= MAX_DISCOVERED:
                            continue
                        tested.add(link)
                        entry["discovered"].append(await test_feed(client, link))
                entry["working"] = [c["url"] for c in entry["configured"] + entry["discovered"] if c.get("ok")]
                return entry

        report["sources"] = await asyncio.gather(*(per_source(s) for s in sources))
        # Candidate feeds from probes.yaml (not polled; tested here so working ones can be added).
        probes_path = Path(settings.sources_path).with_name("probes.yaml")
        probes = (yaml.safe_load(probes_path.read_text(encoding="utf-8")) or {}).get("probes", []) if probes_path.exists() else []

        async def per_probe(pr):
            async with sem:
                res = await test_feed(client, pr["url"], bool(pr.get("google")))
                res["id"] = pr["id"]
                return res

        report["probes"] = await asyncio.gather(*(per_probe(pr) for pr in probes))

        # News sitemaps: read each portal's robots.txt and test its news sitemaps.
        report["sitemaps"] = await sitemap_hunt(client)

        # Live check of gists: resolve a few Google News links and read publisher descriptions.
        report["gist_check"] = await gist_check(client, settings)
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


async def gist_check(client: httpx.AsyncClient, settings, n: int = 6) -> list[dict]:
    from .gist import Gister
    from .registry import GOOGLE_NEWS
    from urllib.parse import quote_plus

    url = GOOGLE_NEWS.format(q=quote_plus("(यूपी OR \"उत्तर प्रदेश\") (भाजपा OR सपा OR बसपा) when:1d"))
    out = []
    try:
        r = await client.get(url)
        items = parse_feed(r.content, google_news=True)[:n]
    except Exception as exc:
        return [{"error": f"feed: {exc!s:.100}"}]
    g = Gister(client, settings.user_agent)
    for it in items:
        rec = {"title": it.title, "publisher": it.publisher}
        real = await g.resolve_google(it.url)
        rec["resolved"] = real
        if real:
            gist, _ = await g.page_gist(real, it.title)
            rec["gist"] = gist
        out.append(rec)
    out.append({"stats": g.stats, "google_blocked": g.google_blocked})
    return out


SITEMAP_SITES = [
    "www.jagran.com", "www.bhaskar.com", "www.aajtak.in", "navbharattimes.indiatimes.com", "zeenews.india.com",
    "www.amarujala.com", "www.livehindustan.com", "www.patrika.com", "www.uptak.in", "www.abplive.com",
    "hindi.news18.com", "www.tv9hindi.com", "www.jansatta.com", "www.thelallantop.com", "www.etvbharat.com",
    "www.indiatv.in", "ndtv.in", "www.bharatsamachartv.in", "www.prabhatkhabar.com", "www.newstrack.com",
    "www.punjabkesari.in", "www.republicbharat.com", "www.news24online.com", "www.oneindia.com",
]
SITEMAP_GUESSES = ["/news-sitemap.xml", "/sitemap-news.xml", "/sitemap/news.xml", "/google-news-sitemap.xml",
                   "/sitemaps/news.xml", "/hindi/news-sitemap.xml"]
UP_PATH_HINT = ("uttar-pradesh", "/up/", "up-news", "lucknow", "up-uk", "uttarpradesh")


async def _test_sitemap(client: httpx.AsyncClient, url: str) -> dict:
    out: dict = {"url": url}
    try:
        r = await client.get(url)
        out["http_status"] = r.status_code
        if r.status_code != 200:
            return out
        items, children = parse_sitemap(r.content)
        out["children"] = children[:5]
        if children and not items:
            prefer = [c for c in children if any(k in c.lower() for k in ("news", "today", "latest"))] or children
            r2 = await client.get(prefer[0])
            items, _ = parse_sitemap(r2.content)
            out["followed"] = prefer[0]
        out["items"] = len(items)
        dated = sorted((i for i in items if i.published_at), key=lambda i: i.published_at, reverse=True)
        out["newest"] = iso(dated[0].published_at) if dated else None
        up = [i for i in items if any(h in i.url.lower() for h in UP_PATH_HINT)]
        out["up_items"] = len(up)
        out["sample_up"] = [(i.url, i.title[:70]) for i in up[:3]]
        out["sample"] = [(i.url, i.title[:70]) for i in items[:2]]
        out["ok"] = bool(items)
    except FeedError as exc:
        out["error"] = f"{exc!s:.100}"
    except httpx.HTTPError as exc:
        out["error"] = f"{type(exc).__name__}"
    return out


async def sitemap_hunt(client: httpx.AsyncClient) -> list[dict]:
    """Find working Google News sitemaps of big Hindi portals (from robots.txt plus common paths)."""
    sem = asyncio.Semaphore(6)

    async def per_site(host: str) -> dict:
        async with sem:
            rec: dict = {"site": host, "tested": []}
            cands: list[str] = []
            try:
                r = await client.get(f"https://{host}/robots.txt")
                for line in r.text.splitlines():
                    if line.lower().startswith("sitemap:"):
                        u = line.split(":", 1)[1].strip()
                        if "news" in u.lower() and u not in cands:
                            cands.append(u)
            except httpx.HTTPError:
                rec["robots"] = "unreachable"
            for g in SITEMAP_GUESSES:
                u = f"https://{host}{g}"
                if u not in cands:
                    cands.append(u)
            for u in cands[:8]:
                t = await _test_sitemap(client, u)
                rec["tested"].append(t)
            rec["working"] = [t["url"] for t in rec["tested"] if t.get("ok")]
            return rec

    return await asyncio.gather(*(per_site(h) for h in SITEMAP_SITES))
