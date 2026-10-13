import asyncio
import json
import time
from datetime import timedelta

import httpx

from newsadda.export import build_feed, write_feed
from newsadda.pipeline import Pipeline
from newsadda.timeutil import from_iso, iso, utcnow

from .conftest import rss


def _now_rfc(delta_min=0):
    t = utcnow() + timedelta(minutes=delta_min)
    return t.strftime("%a, %d %b %Y %H:%M:%S GMT")


def make(settings, router):
    return Pipeline(settings, client=router.client())


def run(p, **kw):
    return asyncio.run(p.run_once(**kw))


def standard_routes(router):
    router.routes["https://alpha.test/up.xml"] = httpx.Response(200, content=rss([
        {"title": "आजमगढ़ में सपा विधायक ने दिया इस्तीफा", "link": "https://alpha.test/a1",
         "date": _now_rfc(-20), "desc": "आजमगढ़ के सपा विधायक ने पार्टी नेतृत्व को इस्तीफा भेजा। समर्थकों में चर्चा तेज है।"},
        {"title": "लखनऊ में बारिश का अलर्ट", "link": "https://alpha.test/a2"},  # headline-only, no date
    ]), headers={"ETag": '"alpha-v1"'})
    router.routes["https://beta.test/up.xml"] = httpx.Response(200, content=rss([
        {"title": "आजमगढ़: सपा विधायक का इस्तीफा, पार्टी को झटका", "link": "https://beta.test/b1",
         "date": _now_rfc(-5)},
    ]))
    router.routes["https://gamma.test/national.xml"] = httpx.Response(200, content=rss([
        {"title": "महाराष्ट्र में बारिश से जनजीवन प्रभावित", "link": "https://gamma.test/g1", "date": _now_rfc(-3)},
        {"title": "उत्तर प्रदेश में नई खनन नीति को मंजूरी", "link": "https://gamma.test/g2", "date": _now_rfc(-3)},
    ]))
    router.routes["https://delta.test/up.xml"] = httpx.Response(503, text="down")
    router.routes["https://news.google.com/rss/search"] = httpx.Response(200, content=rss([
        # Same headline as Beta's own feed, via Google News: must not count twice.
        {"title": "आजमगढ़: सपा विधायक का इस्तीफा, पार्टी को झटका - बीटा समाचार",
         "link": "https://news.google.com/rss/articles/zz1", "source": "बीटा समाचार", "date": _now_rfc(-4)},
    ]))


def test_full_run_groups_reports_and_keeps_attribution(settings, router):
    standard_routes(router)
    p = make(settings, router)
    run(p)
    feed = build_feed(p)
    titles = [s["title"] for s in feed["stories"]]
    assert "महाराष्ट्र में बारिश से जनजीवन प्रभावित" not in titles  # non-UP national item dropped
    assert "उत्तर प्रदेश में नई खनन नीति को मंजूरी" in titles
    resign = next(s for s in feed["stories"] if "इस्तीफा" in s["title"])
    urls = {r["url"] for r in resign["reports"]}
    assert {"https://alpha.test/a1", "https://beta.test/b1"} <= urls  # both links preserved
    assert "https://news.google.com/rss/articles/zz1" not in urls  # same publisher, same headline
    assert resign["publisher_count"] == 2
    assert resign["corroboration"] == "multi"
    assert "multi_source" in resign["labels"]
    assert resign["breaking"] and "resignation" in resign["reasons"]
    assert resign["summary"].startswith("आजमगढ़ के सपा विधायक")


def test_headline_only_story_is_kept_with_detection_time(settings, router):
    standard_routes(router)
    p = make(settings, router)
    run(p)
    feed = build_feed(p)
    rain = next(s for s in feed["stories"] if s["title"] == "लखनऊ में बारिश का अलर्ट")
    assert rain["headline_only"] is True
    r = rain["reports"][0]
    assert r["published_at"] is None  # never invented
    assert r["first_seen_at"] is not None
    assert rain["entities"]["districts"] == ["lucknow"]


def test_failing_and_missing_sources_do_not_stop_others(settings, router):
    standard_routes(router)
    p = make(settings, router)
    results = run(p)
    by = {r.source.id: r for r in results}
    assert by["alpha_up"].ok and by["beta_up"].ok
    assert not by["delta_down"].ok and by["delta_down"].error == "HTTP 503"
    status = {s["id"]: s for s in build_feed(p)["sources"]}
    assert status["delta_down"]["status"] == "error"
    assert status["nofeed"]["status"] == "unavailable"
    assert status["x_test"]["status"] == "unavailable"  # no X token
    assert status["alpha_up"]["status"] == "ok"
    # Backoff: the failing source is not due again immediately.
    st = p.store.source_state("delta_down")
    assert from_iso(st["next_poll_at"]) > utcnow() + timedelta(minutes=3)


def test_fallback_url_used_and_remembered(settings, router):
    standard_routes(router)
    p = make(settings, router)
    run(p)
    assert p.store.source_state("alpha_up")["active_url"] == "https://alpha.test/up.xml"
    assert any(str(c.url) == "https://alpha.test/broken.xml" for c in router.calls)


def test_conditional_request_and_poll_interval(settings, router):
    standard_routes(router)
    p = make(settings, router)
    run(p)
    n = len(router.calls)
    run(p)  # nothing due yet: no requests at all
    assert len(router.calls) == n

    seen = {}

    def alpha(req):
        seen["inm"] = req.headers.get("if-none-match")
        return httpx.Response(304)

    router.routes["https://alpha.test/up.xml"] = alpha
    run(p, force=True)
    assert seen["inm"] == '"alpha-v1"'
    assert p.store.source_state("alpha_up")["status"] == "ok"
    # The broken URL is not retried once a working one is known.
    assert sum(1 for c in router.calls if str(c.url).endswith("broken.xml")) == 1


def test_html_error_page_marks_source_error(settings, router):
    standard_routes(router)
    router.routes["https://beta.test/up.xml"] = httpx.Response(200, text="<!DOCTYPE html><html>blocked</html>")
    p = make(settings, router)
    results = {r.source.id: r for r in run(p)}
    assert not results["beta_up"].ok
    assert "invalid feed" in results["beta_up"].error


def test_future_timestamp_is_flagged_and_not_used_for_ordering(settings, router):
    standard_routes(router)
    router.routes["https://beta.test/up.xml"] = httpx.Response(200, content=rss([
        {"title": "बलिया में भाजपा की बैठक", "link": "https://beta.test/f1", "date": _now_rfc(+330)},
    ]))
    p = make(settings, router)
    run(p)
    st = next(s for s in build_feed(p)["stories"] if s["title"] == "बलिया में भाजपा की बैठक")
    rep = st["reports"][0]
    assert rep["published_suspect"] is True
    assert from_iso(st["sort_at"]) <= utcnow() + timedelta(seconds=5)


def test_incremental_feed_since_cursor(settings, router):
    standard_routes(router)
    p = make(settings, router)
    run(p)
    full = build_feed(p)
    cursor = from_iso(full["cursor"])
    # Inclusive cursor: stories changed exactly at the cursor may be re-sent (clients upsert).
    assert len(build_feed(p, since=cursor)["stories"]) <= len(full["stories"])
    time.sleep(1.1)
    cursor = from_iso(build_feed(p)["generated_at"])
    later = utcnow() + timedelta(minutes=1)
    router.routes["https://beta.test/up.xml"] = httpx.Response(200, content=rss([
        {"title": "मैनपुरी में डिंपल यादव की जनसभा", "link": "https://beta.test/b9", "date": _now_rfc(0)},
    ]))
    asyncio.run(p.run_once(force=True, only=["beta_up"]))
    inc = build_feed(p, since=cursor, now=later)
    assert [s["title"] for s in inc["stories"]] == ["मैनपुरी में डिंपल यादव की जनसभा"]
    assert inc["incremental"] is True


def test_single_source_allegation_awaits_confirmation(settings, router):
    standard_routes(router)
    router.routes["https://beta.test/up.xml"] = httpx.Response(200, content=rss([
        {"title": "अखिलेश यादव का आरोप- भर्ती में धांधली", "link": "https://beta.test/c1", "date": _now_rfc(-1)},
    ]))
    p = make(settings, router)
    run(p)
    st = next(s for s in build_feed(p)["stories"] if "धांधली" in s["title"])
    assert st["corroboration"] == "single"
    assert "awaiting_confirmation" in st["labels"] and "allegation" in st["labels"]
    assert st["statement"] and "akhilesh" in st["entities"]["leaders"]


def test_write_feed_files(settings, router, tmp_path):
    standard_routes(router)
    p = make(settings, router)
    run(p)
    path = write_feed(p, tmp_path / "out")
    doc = json.loads(path.read_text(encoding="utf-8"))
    assert doc["version"] == 1 and doc["stories"]
    assert (tmp_path / "out" / "feed.json.gz").exists()
    assert {"leaders", "districts", "figures", "parties", "seats"} <= set(doc["entities"])
    assert len(doc["entities"]["districts"]) == 75
    for st in doc["stories"]:
        for r in st["reports"]:
            assert r["url"].startswith("https://")
            assert r["source_name"]


def test_publisher_aliases_blocklist_and_promo(settings, router, tmp_path):
    from newsadda.pipeline import publisher_key
    from newsadda.registry import load_sources

    srcs = load_sources("newsadda/data/sources.yaml")
    by = {s.id: s for s in srcs}
    gn = by["gn_up_politics"]
    assert publisher_key(srcs, gn, "UPTak") == publisher_key(srcs, gn, "UP Tak") == "uptak"
    assert publisher_key(srcs, gn, "TV9 Hindi") == publisher_key(srcs, by["tv9_up"], None) == "tv9"
    assert publisher_key(srcs, by["amarujala_lucknow"], None) == publisher_key(srcs, by["amarujala_up"], None)
    assert publisher_key(srcs, gn, "Jagran") == "jagran"

    standard_routes(router)
    router.routes["https://news.google.com/rss/search"] = httpx.Response(200, content=rss([
        {"title": "अखिलेश यादव की रैली - Facebook", "link": "https://news.google.com/rss/articles/fb",
         "source": "Facebook", "date": _now_rfc(-2)},
        {"title": "AajTak Live TV: देखें लाइव - AajTak", "link": "https://news.google.com/rss/articles/tv",
         "source": "AajTak", "date": _now_rfc(-2)},
    ]))
    settings.sources_path = str(tmp_path / "s.yaml")
    from .conftest import TEST_SOURCES
    (tmp_path / "s.yaml").write_text(TEST_SOURCES + "\nblocked_publishers: [Facebook]\n", encoding="utf-8")
    p = make(settings, router)
    run(p)
    titles = [s["title"] for s in build_feed(p)["stories"]]
    assert not any("Facebook" in t or "रैली" in t for t in titles)
    assert not any("Live TV" in t for t in titles)


def test_live_blog_is_not_breaking_by_label_alone():
    from newsadda.classify import Classifier
    from newsadda.entities import EntityIndex

    c = Classifier(EntityIndex()).classify("UP News Today Live: उत्तर प्रदेश ब्रेकिंग न्यूज़, भाजपा", None, up_section=True)
    assert not c.breaking


def test_real_registry_and_entities_load():
    from newsadda.entities import EntityIndex
    from newsadda.registry import load_sources

    srcs = load_sources("newsadda/data/sources.yaml")
    assert len({s.id for s in srcs}) == len(srcs) > 20
    assert all(s.feed_urls or s.kind == "x" or s.kind == "rss" for s in srcs)
    e = EntityIndex()
    assert len(e.districts) == 75 and len(e.leaders) >= 12


def test_rules_change_relabels_stored_items(settings, router):
    standard_routes(router)
    p = make(settings, router)
    run(p)
    assert p.relabel_if_rules_changed() == 0  # unchanged rules: nothing to do
    p.store.set_meta("rules", "old")
    n = p.relabel_if_rules_changed()
    assert n == len(p.store.all_items()) > 0
    assert p.relabel_if_rules_changed() == 0


def test_entities_have_english_names():
    from newsadda.entities import EntityIndex

    pub = EntityIndex().public()
    d = {x["id"]: x for x in pub["districts"]}
    assert d["lucknow"]["name"] == "Lucknow" and d["lucknow"]["name_hi"] == "लखनऊ"
    assert {x["id"]: x["name"] for x in pub["parties"]}["bjp"] == "BJP"
    assert {x["id"]: x["name"] for x in pub["leaders"]}["yogi"] == "Yogi Adityanath"
    assert all(x["name"] for g in pub.values() for x in g)
