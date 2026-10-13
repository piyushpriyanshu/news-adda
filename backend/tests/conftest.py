"""Shared fixtures. All feed content here is synthetic TEST DATA built by the
tests; it is never written to the production feed."""
from __future__ import annotations

import sys
from pathlib import Path

import httpx
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from newsadda.config import Settings  # noqa: E402
from newsadda.store import Store  # noqa: E402

TEST_SOURCES = """
defaults: {min_interval: 240, up_section: true, enabled: true}
sources:
  - {id: alpha_up, name: Alpha Times, name_hi: अल्फा टाइम्स, kind: rss,
     urls: ["https://alpha.test/broken.xml", "https://alpha.test/up.xml"]}
  - {id: beta_up, name: Beta Samachar, name_hi: बीटा समाचार, kind: rss, urls: ["https://beta.test/up.xml"]}
  - {id: gamma_nat, name: Gamma National, name_hi: गामा नेशनल, kind: rss, up_section: false,
     urls: ["https://gamma.test/national.xml"]}
  - {id: delta_down, name: Delta, name_hi: डेल्टा, kind: rss, urls: ["https://delta.test/up.xml"]}
  - {id: nofeed, name: No Feed, name_hi: नो फ़ीड, kind: rss, urls: []}
  - {id: gn_test, name: Google test, name_hi: गूगल टेस्ट, kind: google_news, query: "यूपी"}
  - {id: x_test, name: X test, name_hi: एक्स टेस्ट, kind: x, handles: [someone]}
"""


def rss(items: list[dict], title="Test feed") -> bytes:
    parts = []
    for it in items:
        src = f'<source url="https://pub.test">{it["source"]}</source>' if it.get("source") else ""
        date = f"<pubDate>{it['date']}</pubDate>" if it.get("date") else ""
        desc = f"<description><![CDATA[{it['desc']}]]></description>" if it.get("desc") else ""
        parts.append(f"<item><title>{it['title']}</title><link>{it['link']}</link>{date}{desc}{src}</item>")
    return (
        '<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>'
        f"<title>{title}</title><link>https://x.test</link><description>t</description>"
        + "".join(parts)
        + "</channel></rss>"
    ).encode("utf-8")


@pytest.fixture
def settings(tmp_path):
    p = tmp_path / "sources.yaml"
    p.write_text(TEST_SOURCES, encoding="utf-8")
    s = Settings()
    s.sources_path = str(p)
    s.db_path = ":memory:"
    s.x_bearer_token = None
    s.ai_enabled = False
    s.run_scheduler = False
    s.concurrency = 4
    return s


@pytest.fixture
def store():
    return Store(":memory:")


class Router:
    """httpx MockTransport that serves canned responses per URL and records calls."""

    def __init__(self):
        self.routes: dict[str, object] = {}
        self.calls: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        key = str(request.url).split("?")[0]
        handler = self.routes.get(key)
        if handler is None:
            return httpx.Response(404, text="not found")
        if callable(handler):
            return handler(request)
        return handler

    def client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(transport=httpx.MockTransport(self))


@pytest.fixture
def router():
    return Router()
