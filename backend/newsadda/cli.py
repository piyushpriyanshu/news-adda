"""Command line: python -m newsadda <command>

  run [--force] [--export DIR]   one ingestion pass (optionally write feed files)
  export DIR                     write feed.json / feed.json.gz / health.json
  serve [--host H] [--port P]    FastAPI server with built-in scheduler
  discover [--out FILE] [ids..]  live-test feed URLs and autodiscover feeds
  sources                        print source health table
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="newsadda")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--force", action="store_true", help="ignore per-source poll intervals")
    r.add_argument("--export", metavar="DIR")
    r.add_argument("--only", nargs="*")
    e = sub.add_parser("export")
    e.add_argument("dir")
    s = sub.add_parser("serve")
    s.add_argument("--host", default="0.0.0.0")
    s.add_argument("--port", type=int, default=8000)
    d = sub.add_parser("discover")
    d.add_argument("--out")
    d.add_argument("ids", nargs="*")
    sub.add_parser("sources")
    args = ap.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    if args.cmd == "serve":
        import uvicorn

        from .api import create_app

        uvicorn.run(create_app(), host=args.host, port=args.port, log_level="info")
        return 0

    if args.cmd == "discover":
        from .discover import run

        report = run(args.out, args.ids or None)
        for sid, url in report["summary"].items():
            print(f"{sid:22} {'OK  ' + url if url else 'NONE'}")
        return 0

    from .export import source_status, write_feed
    from .pipeline import Pipeline

    p = Pipeline()
    try:
        if args.cmd == "run":
            results = asyncio.run(p.run_once(force=args.force, only=args.only))
            ok = sum(1 for x in results if x.ok)
            tried = sum(1 for x in results if not x.skipped)
            print(f"sources ok: {ok}/{tried} (skipped {len(results) - tried} not yet due)")
            if args.export:
                print("wrote", write_feed(p, args.export))
        elif args.cmd == "export":
            print("wrote", write_feed(p, args.dir))
        elif args.cmd == "sources":
            for s in source_status(p):
                print(f"{s['id']:22} {s['status']:12} items={s['last_item_count']:<4} {s['error'] or ''}")
    finally:
        p.store.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
