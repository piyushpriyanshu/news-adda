"""Polite network layer: per-source intervals, conditional GET, backoff, fallbacks.

A failing source never stops the run; its state records the error so the app
can show it accurately.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from datetime import timedelta

import httpx

from .parser import FeedError, RawItem, parse_feed
from .registry import Source
from .store import Store
from .timeutil import from_iso, iso, parse_feed_time, utcnow

log = logging.getLogger("newsadda.fetch")

MAX_BACKOFF = 2 * 3600
MAX_BYTES = 5 * 1024 * 1024


@dataclass
class FetchResult:
    source: Source
    items: list[RawItem] = field(default_factory=list)
    ok: bool = False
    http_status: int | None = None
    error: str | None = None
    not_modified: bool = False
    skipped: bool = False
    url: str | None = None
    ms: int = 0
    extra_state: dict = field(default_factory=dict)


def backoff_seconds(min_interval: int, failures: int, retry_after: int | None = None) -> int:
    base = min(min_interval * (2 ** max(failures - 1, 0)), MAX_BACKOFF)
    if retry_after:
        base = max(base, min(retry_after, 6 * 3600))
    return int(base)


def _retry_after(resp: httpx.Response) -> int | None:
    v = resp.headers.get("retry-after")
    if not v:
        return None
    try:
        return int(v)
    except ValueError:
        dt, _ = parse_feed_time(v)
        return max(int((dt - utcnow()).total_seconds()), 0) if dt else None


def is_due(state: dict, now) -> bool:
    nxt = from_iso(state.get("next_poll_at"))
    return nxt is None or nxt <= now


class Fetcher:
    def __init__(self, store: Store, settings, client: httpx.AsyncClient | None = None):
        self.store = store
        self.settings = settings
        self._client = client

    def _new_client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            timeout=self.settings.http_timeout,
            follow_redirects=True,
            headers={
                "User-Agent": self.settings.user_agent,
                "Accept": "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5",
                "Accept-Language": "hi-IN,hi;q=0.9,en;q=0.6",
            },
        )

    async def fetch_all(self, sources: list[Source], force: bool = False) -> list[FetchResult]:
        own = self._client is None
        client = self._client or self._new_client()
        sem = asyncio.Semaphore(self.settings.concurrency)

        async def one(src: Source) -> FetchResult:
            async with sem:
                try:
                    return await self.fetch_source(client, src, force)
                except Exception as exc:  # never let one source break the run
                    log.exception("unexpected error for %s", src.id)
                    return FetchResult(source=src, error=f"internal: {exc!s:.200}")

        try:
            return await asyncio.gather(*(one(s) for s in sources))
        finally:
            if own:
                await client.aclose()

    async def fetch_source(self, client: httpx.AsyncClient, src: Source, force: bool) -> FetchResult:
        now = utcnow()
        state = self.store.source_state(src.id)
        if not src.enabled:
            self.store.update_source_state(src.id, status="disabled")
            return FetchResult(source=src, skipped=True)
        if not force and not is_due(state, now):
            return FetchResult(source=src, skipped=True)
        if src.kind == "x":
            from .xsource import fetch_x  # local import keeps X optional

            return await fetch_x(client, src, state, self.settings)

        urls = src.feed_urls
        if not urls:
            return FetchResult(source=src, error="no_public_feed")
        active = state.get("active_url")
        if active in urls:
            urls = [active] + [u for u in urls if u != active]

        last = FetchResult(source=src)
        for url in urls:
            headers = {}
            if url == active:
                if state.get("etag"):
                    headers["If-None-Match"] = state["etag"]
                if state.get("last_modified"):
                    headers["If-Modified-Since"] = state["last_modified"]
            t0 = time.monotonic()
            res = FetchResult(source=src, url=url)
            try:
                resp = await client.get(url, headers=headers)
                res.ms = int((time.monotonic() - t0) * 1000)
                res.http_status = resp.status_code
                if resp.status_code == 304:
                    res.ok = res.not_modified = True
                    return res
                if resp.status_code == 429 or resp.status_code >= 500:
                    res.error = f"HTTP {resp.status_code}"
                    res.extra_state["retry_after"] = _retry_after(resp)
                    return res  # server trouble: back off, don't hammer fallbacks
                if resp.status_code >= 400:
                    res.error = f"HTTP {resp.status_code}"
                    last = res
                    continue
                if len(resp.content) > MAX_BYTES:
                    res.error = "response too large"
                    last = res
                    continue
                res.items = parse_feed(resp.content, google_news=src.kind == "google_news")
                res.ok = True
                res.extra_state = {
                    "active_url": url,
                    "etag": resp.headers.get("etag"),
                    "last_modified": resp.headers.get("last-modified"),
                }
                return res
            except FeedError as exc:
                res.error = f"invalid feed: {exc!s:.150}"
                last = res
            except httpx.TimeoutException:
                res.error = "timeout"
                last = res
            except httpx.HTTPError as exc:
                res.error = f"network: {type(exc).__name__}: {exc!s:.120}"
                last = res
        return last

    def record(self, res: FetchResult, new_count: int):
        """Persist source health after the pipeline has processed a result."""
        if res.skipped:
            return
        src, now = res.source, utcnow()
        state = self.store.source_state(src.id)
        if res.ok:
            fields = dict(
                status="ok",
                last_attempt_at=iso(now),
                last_ok_at=iso(now),
                last_error=None,
                consecutive_failures=0,
                next_poll_at=iso(now + timedelta(seconds=src.min_interval)),
                last_new_count=new_count,
                total_items=state["total_items"] + new_count,
            )
            if not res.not_modified:
                fields["last_item_count"] = len(res.items)
            for k in ("active_url", "etag", "last_modified"):
                if k in res.extra_state:
                    fields[k] = res.extra_state[k]
            self.store.update_source_state(src.id, **fields)
        else:
            unavailable = res.error in ("no_public_feed", "x_token_missing")
            failures = state["consecutive_failures"] + 1
            wait = (
                6 * 3600
                if unavailable
                else backoff_seconds(src.min_interval, failures, res.extra_state.get("retry_after"))
            )
            self.store.update_source_state(
                src.id,
                status="unavailable" if unavailable else "error",
                last_attempt_at=iso(now),
                last_error=res.error,
                consecutive_failures=failures,
                next_poll_at=iso(now + timedelta(seconds=wait)),
                last_new_count=0,
            )
        self.store.log_fetch(src.id, res.ok, res.http_status, len(res.items), new_count, res.ms, res.error)
