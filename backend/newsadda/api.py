"""FastAPI server: serves the feed and runs the ingestion scheduler.

Endpoints
  GET  /health                     liveness + last run time
  GET  /feed.json[?since=ISO]      app feed (full, or only stories changed after `since`)
  GET  /api/v1/stories             search & filters over collected stories
  GET  /api/v1/sources             per-source health
  GET  /api/v1/entities            leaders, figures, parties, districts, seats
  POST /api/v1/refresh             run ingestion now (needs NEWSADDA_ADMIN_TOKEN)
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from contextlib import asynccontextmanager
from datetime import timedelta

from fastapi import FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.middleware.gzip import GZipMiddleware

from .config import Settings
from .export import build_feed, build_stories, source_status
from .pipeline import Pipeline
from .textutil import normalize
from .timeutil import from_iso, iso, utcnow

log = logging.getLogger("newsadda.api")


def create_app(settings: Settings | None = None, pipeline: Pipeline | None = None) -> FastAPI:
    settings = settings or Settings()
    state = {"pipeline": pipeline or Pipeline(settings), "last_run": None, "running": asyncio.Lock()}

    async def run_ingest(force=False):
        async with state["running"]:
            await state["pipeline"].run_once(force=force)
            state["last_run"] = iso(utcnow())

    async def scheduler():
        while True:
            try:
                await run_ingest()
            except Exception:
                log.exception("ingestion run failed; will retry next tick")
            await asyncio.sleep(settings.poll_tick)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        task = asyncio.create_task(scheduler()) if settings.run_scheduler else None
        yield
        if task:
            task.cancel()

    app = FastAPI(title="News Adda API", version="1.0", lifespan=lifespan)
    app.add_middleware(GZipMiddleware, minimum_size=1024)

    @app.get("/health")
    async def health():
        return {"ok": True, "last_run": state["last_run"], "now": iso(utcnow())}

    @app.get("/feed.json")
    async def feed(request: Request, since: str | None = None):
        since_dt = None
        if since:
            try:
                since_dt = from_iso(since)
            except ValueError:
                raise HTTPException(400, "since must be ISO-8601")
        doc = build_feed(state["pipeline"], since=since_dt)
        doc["supports_since"] = True
        body = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        stable = {k: v for k, v in doc.items() if k != "generated_at"}
        etag = '"' + hashlib.sha1(json.dumps(stable, sort_keys=True).encode()).hexdigest()[:20] + '"'
        if request.headers.get("if-none-match") == etag:
            return Response(status_code=304, headers={"ETag": etag})
        return Response(body, media_type="application/json; charset=utf-8",
                        headers={"ETag": etag, "Cache-Control": "no-cache"})

    @app.get("/api/v1/sources")
    async def sources():
        return {"sources": source_status(state["pipeline"])}

    @app.get("/api/v1/entities")
    async def entities():
        return state["pipeline"].entities.public()

    @app.get("/api/v1/stories")
    async def stories(
        q: str | None = None,
        leader: str | None = None,
        figure: str | None = None,
        party: str | None = None,
        district: str | None = None,
        seat: str | None = None,
        source: str | None = None,
        category: str | None = None,
        label: str | None = None,
        breaking: bool | None = None,
        statement: bool | None = None,
        date_from: str | None = Query(None, alias="from"),
        date_to: str | None = Query(None, alias="to"),
        limit: int = Query(50, ge=1, le=500),
        offset: int = Query(0, ge=0),
    ):
        p = state["pipeline"]
        items = p.store.items_for_feed(utcnow() - timedelta(days=settings.keep_days), 5000)
        result = build_stories(p, items)
        nq = normalize(q).strip() if q else None
        f_from, f_to = from_iso(date_from) if date_from else None, from_iso(date_to) if date_to else None

        def keep(s: dict) -> bool:
            e = s["entities"]
            if leader and leader not in e["leaders"]:
                return False
            if figure and figure not in e["figures"]:
                return False
            if party and party not in e["parties"]:
                return False
            if district and district not in e["districts"]:
                return False
            if seat and seat not in e["seats"]:
                return False
            if source and not any(r["source_id"] == source for r in s["reports"]):
                return False
            if category and category not in s["categories"]:
                return False
            if label and label not in s["labels"]:
                return False
            if breaking is not None and s["breaking"] != breaking:
                return False
            if statement is not None and s["statement"] != statement:
                return False
            t = from_iso(s["sort_at"])
            if f_from and t < f_from or f_to and t > f_to:
                return False
            if nq:
                hay = normalize(" ".join([s["title"], s["summary"] or ""] + [r["title"] for r in s["reports"]]))
                if not all(w in hay for w in nq.split()):
                    return False
            return True

        matched = [s for s in result if keep(s)]
        return {"total": len(matched), "stories": matched[offset: offset + limit]}

    @app.post("/api/v1/refresh")
    async def refresh(authorization: str | None = Header(None)):
        if not settings.admin_token or authorization != f"Bearer {settings.admin_token}":
            raise HTTPException(401, "admin token required")
        await run_ingest(force=True)
        return {"ok": True, "last_run": state["last_run"]}

    return app


app = None


def get_app() -> FastAPI:
    global app
    if app is None:
        app = create_app()
    return app
