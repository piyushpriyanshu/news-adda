import asyncio

import httpx
from fastapi.testclient import TestClient

from newsadda.api import create_app
from newsadda.pipeline import Pipeline
from newsadda.xsource import build_query, parse_x_response

from .test_pipeline import standard_routes


def _client(settings, router):
    standard_routes(router)
    p = Pipeline(settings, client=router.client())
    asyncio.run(p.run_once())
    return TestClient(create_app(settings, p)), p


def test_feed_endpoint_etag_and_since(settings, router):
    c, _ = _client(settings, router)
    r = c.get("/feed.json")
    assert r.status_code == 200
    doc = r.json()
    assert doc["supports_since"] is True and doc["stories"]
    r2 = c.get("/feed.json", headers={"If-None-Match": r.headers["etag"]})
    assert r2.status_code == 304
    inc = c.get("/feed.json", params={"since": doc["cursor"]}).json()
    assert inc["incremental"] is True
    assert {s["id"] for s in inc["stories"]} <= {s["id"] for s in doc["stories"]}
    assert c.get("/feed.json", params={"since": "kal"}).status_code == 400


def test_story_search_and_filters(settings, router):
    c, _ = _client(settings, router)
    assert c.get("/api/v1/stories", params={"q": "इस्तीफा"}).json()["total"] == 1
    assert c.get("/api/v1/stories", params={"district": "azamgarh"}).json()["total"] == 1
    assert c.get("/api/v1/stories", params={"source": "beta_up"}).json()["total"] >= 1
    assert c.get("/api/v1/stories", params={"breaking": True}).json()["total"] >= 1
    assert c.get("/api/v1/stories", params={"party": "sp"}).json()["total"] >= 1
    assert c.get("/api/v1/stories", params={"q": "कोईनहीं"}).json()["total"] == 0


def test_sources_entities_health_and_admin(settings, router):
    c, _ = _client(settings, router)
    assert c.get("/health").json()["ok"] is True
    ids = {s["id"] for s in c.get("/api/v1/sources").json()["sources"]}
    assert {"alpha_up", "x_test"} <= ids
    assert len(c.get("/api/v1/entities").json()["districts"]) == 75
    assert c.post("/api/v1/refresh").status_code == 401


def test_x_query_and_parsing_keeps_attribution_and_context():
    assert build_query(["a", "b"]) == "(from:a OR from:b) -is:retweet"
    payload = {
        "data": [
            {"id": "1", "author_id": "u1", "text": "लखनऊ में आज कार्यकर्ता सम्मेलन।", "created_at": "2026-10-13T07:00:00.000Z"},
            {"id": "2", "author_id": "u1", "text": "इस बयान पर हमारा जवाब", "created_at": "2026-10-13T07:05:00.000Z",
             "referenced_tweets": [{"type": "quoted", "id": "9"}]},
            {"id": "3", "author_id": "unknown", "text": "no author resolved"},
        ],
        "includes": {"users": [{"id": "u1", "name": "Test Leader", "username": "testleader"}]},
    }
    items = parse_x_response(payload)
    assert len(items) == 2  # unattributable post dropped
    assert items[0].url == "https://x.com/testleader/status/1"
    assert items[0].handle == "testleader" and items[0].publisher == "Test Leader (@testleader)"
    assert items[1].post_context == "quote"
    assert items[0].published_at.isoformat() == "2026-10-13T07:00:00+00:00"


def test_x_source_works_when_token_present(settings, router):
    settings.x_bearer_token = "test-token"
    standard_routes(router)

    def xapi(req):
        assert req.headers["authorization"] == "Bearer test-token"
        return httpx.Response(200, json={
            "data": [{"id": "5", "author_id": "u1", "text": "अखिलेश यादव: बेरोजगारी पर सरकार जवाब दे",
                      "created_at": "2026-10-13T07:00:00.000Z"}],
            "includes": {"users": [{"id": "u1", "name": "Leader", "username": "leader"}]},
            "meta": {"newest_id": "5"},
        })

    router.routes["https://api.x.com/2/tweets/search/recent"] = xapi
    p = Pipeline(settings, client=router.client())
    asyncio.run(p.run_once())
    assert p.store.source_state("x_test")["status"] == "ok"
    assert p.store.source_state("x_test")["etag"] == "5"  # since_id kept for next poll
