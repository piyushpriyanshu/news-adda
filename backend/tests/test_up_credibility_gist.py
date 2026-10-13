"""Strict UP focus, Hindi-only, trusted publishers and gists. All feed content
here is synthetic TEST DATA."""
import asyncio
from datetime import datetime, timedelta, timezone
import json

import httpx
import pytest

from newsadda.classify import Classifier
from newsadda.entities import EntityIndex
from newsadda.export import build_feed
from newsadda.gist import _legacy_decode, description_from_page
from newsadda.pipeline import Pipeline

from .conftest import rss
from .test_pipeline import _now_rfc, standard_routes


@pytest.fixture(scope="module")
def clf():
    return Classifier(EntityIndex())


@pytest.mark.parametrize("title,up_section,expected", [
    ("गेहूं की बुवाई से पहले किसान भाई न करें ये गलती", True, False),         # generic tips in a UP feed
    ("बिहार में विधायक ने दिया इस्तीफा", True, False),                        # other state, even in a UP feed
    ("दिल्ली में कांग्रेस की बैठक", False, False),                             # national, no UP signal
    ("मेरठ में भाजपा की बैठक", False, True),                                   # UP district
    ("सपा विधायक ने दिया इस्तीफा", False, True),                              # UP-centred party
    ("दिल्ली में अखिलेश यादव ने चुनाव आयोग पर उठाए सवाल", False, True),       # UP leader, outside UP
    ("विधानसभा में हंगामा, विधायक धरने पर बैठे", True, True),                 # local-governance words in a UP feed
    ("विधानसभा में हंगामा, विधायक धरने पर बैठे", False, False),               # same words in a national feed
    ("सेक्टर 70 की गलियां हुई जगमग, प्राधिकरण ने सही कराईं लाइटें", "local", True),  # district edition
    ("पटना में मिठाई दुकान का कारीगर लापता", "local", False),               # other state in a district edition
    ("मढ़ौरा में कारीगर लापता, पुलिस तलाश में जुटी", "politics", False),     # crime with no politics, leader search
    ("शिक्षकों को समर्पित दिखा भाजपा का मंच: CM बोले", "politics", True),    # state politics in a leader search
    ("गोरखनाथ मंदिर के शक्तिपीठ में कलश स्थापना", False, True),               # UP landmark
])
def test_strict_up_rule(clf, title, up_section, expected):
    assert clf.classify(title, None, up_section=up_section).is_up is expected


def _pipeline_with(settings, router, tmp_path, items, extra_yaml=""):
    from .conftest import TEST_SOURCES
    p = tmp_path / "s.yaml"
    p.write_text(TEST_SOURCES + extra_yaml, encoding="utf-8")
    settings.sources_path = str(p)
    standard_routes(router)
    router.routes["https://news.google.com/rss/search"] = httpx.Response(200, content=rss(items))
    return Pipeline(settings, client=router.client())


def test_untrusted_aggregator_publishers_and_english_are_dropped(settings, router, tmp_path):
    items = [
        {"title": "लखनऊ में सपा का प्रदर्शन - Trusted Daily", "link": "https://news.google.com/rss/articles/t1",
         "source": "Trusted Daily", "date": _now_rfc(-2)},
        {"title": "लखनऊ में बसपा की रैली - Random Blog", "link": "https://news.google.com/rss/articles/r1",
         "source": "Random Blog", "date": _now_rfc(-2)},
        {"title": "Akhilesh Yadav attacks BJP in Lucknow - Trusted Daily", "link": "https://news.google.com/rss/articles/e1",
         "source": "Trusted Daily", "date": _now_rfc(-2)},
    ]
    p = _pipeline_with(settings, router, tmp_path, items, "\ntrusted_publishers: [Trusted Daily]\n")
    settings.gist_enabled = False
    asyncio.run(p.run_once())
    titles = [s["title"] for s in build_feed(p)["stories"]]
    assert "लखनऊ में सपा का प्रदर्शन" in titles
    assert "लखनऊ में बसपा की रैली" not in titles          # unknown publisher
    assert not any("Akhilesh" in t for t in titles)          # not Hindi
    assert p.skipped["untrusted"] == 1 and p.skipped["not_hindi"] == 1


def test_description_from_page_and_legacy_google_ids():
    page = """<html><head><title>x</title>
    <meta property="og:description" content="सपा अध्यक्ष अखिलेश यादव ने लखनऊ में कहा कि भर्ती परीक्षा में अनियमितता की जांच होनी चाहिए। उन्होंने सरकार से जवाब मांगा।">
    <meta property="og:url" content="https://pub.test/a/1"></head><body>...</body></html>"""
    gist, canon = description_from_page(page, "अखिलेश यादव का सरकार पर हमला")
    assert gist.startswith("सपा अध्यक्ष") and canon == "https://pub.test/a/1"
    same = '<head><meta name="description" content="अखिलेश यादव का सरकार पर हमला"></head>'
    assert description_from_page(same, "अखिलेश यादव का सरकार पर हमला")[0] is None  # repeats the headline
    import base64
    aid = base64.urlsafe_b64encode(b"\x08\x13\x22\x1bhttps://pub.test/story/9\xd2\x01\x00").decode().rstrip("=")
    assert _legacy_decode(aid) == "https://pub.test/story/9"


def test_gist_resolves_google_link_and_reads_publisher_description(settings, router, tmp_path):
    items = [{"title": "मायावती का भाजपा पर निशाना - Trusted Daily", "link": "https://news.google.com/rss/articles/NEWID1",
              "source": "Trusted Daily", "date": _now_rfc(-1)}]
    p = _pipeline_with(settings, router, tmp_path, items, "\ntrusted_publishers: [Trusted Daily]\n")
    settings.gist_enabled = True
    router.routes["https://news.google.com/rss/articles/NEWID1"] = httpx.Response(
        200, text='<c-wiz><div data-n-a-sg="SIG" data-n-a-ts="1700000000"></div></c-wiz>')
    inner = json.dumps(["garturlres", "https://trusted.test/up/mayawati-1", 1])
    router.routes["https://news.google.com/_/DotsSplashUi/data/batchexecute"] = httpx.Response(
        200, text=")]}'\n\n" + json.dumps([["wrb.fr", "Fbv4je", inner, None, None, None, "generic"], ["di", 1], ["af.httprm", 1]]))
    router.routes["https://trusted.test/robots.txt"] = httpx.Response(200, text="User-agent: *\nAllow: /\n")
    router.routes["https://trusted.test/up/mayawati-1"] = httpx.Response(
        200, headers={"content-type": "text/html"},
        text='<html><head><meta property="og:description" content="बसपा प्रमुख मायावती ने लखनऊ में प्रेस वार्ता कर भाजपा सरकार की नीतियों पर सवाल उठाए और कहा कि दलितों की अनदेखी हो रही है।"></head><body></body></html>')
    asyncio.run(p.run_once())
    st = next(s for s in build_feed(p)["stories"] if "मायावती" in s["title"])
    r = st["reports"][0]
    assert r["url"] == "https://trusted.test/up/mayawati-1"      # direct publisher link
    assert r["domain"] == "trusted.test"
    assert st["summary"].startswith("बसपा प्रमुख मायावती")
    assert st["summary_origin"] == "page"


def test_gist_respects_robots(settings, router, tmp_path):
    from newsadda.gist import Gister

    router.routes["https://blocked.test/robots.txt"] = httpx.Response(200, text="User-agent: *\nDisallow: /\n")
    g = Gister(router.client(), "NewsAddaBot")
    assert asyncio.run(g.page_gist("https://blocked.test/a", "शीर्षक")) == (None, None)
    assert g.stats["robots_blocked"] == 1


def test_gist_retries_next_run_when_google_refuses(settings, router, tmp_path):
    items = [{"title": "अखिलेश यादव का योगी सरकार पर हमला - Trusted Daily", "link": "https://news.google.com/rss/articles/NEWID2",
              "source": "Trusted Daily", "date": _now_rfc(-1)}]
    p = _pipeline_with(settings, router, tmp_path, items, "\ntrusted_publishers: [Trusted Daily]\n")
    settings.gist_enabled = True
    router.routes["https://news.google.com/rss/articles/NEWID2"] = httpx.Response(429, text="slow down")
    asyncio.run(p.run_once())
    assert p.gist_stats["google_blocked"] is True
    row = next(it for it in p.store.recent_items(datetime.now(timezone.utc) - timedelta(hours=6)) if "अखिलेश" in it["title"])
    assert not row["data"].get("gist_tried")  # left for the next run, not given up on


def test_english_page_descriptions_are_not_used_as_gists():
    page = ('<html><head><meta property="og:description" content="BJP leaders attack Akhilesh Yadav over controversial '
            'politics in Lucknow. Follow Dainik Bhaskar for more."><meta name="description" content="लखनऊ में भाजपा नेताओं '
            'ने सपा प्रमुख अखिलेश यादव पर पलटवार किया और कहा कि सपा की राजनीति परिवार तक सीमित है। Follow Dainik Bhaskar">'
            '</head></html>')
    gist, _ = description_from_page(page, "अखिलेश पर भाजपा का हमला")
    assert gist.startswith("लखनऊ में भाजपा नेताओं") and "Follow" not in gist
    only_en = '<html><head><meta property="og:description" content="BJP leaders attack Akhilesh Yadav over politics in Lucknow today."></head></html>'
    assert description_from_page(only_en, "अखिलेश पर भाजपा का हमला")[0] is None


def test_mixed_english_seo_and_hindi_description_keeps_only_hindi():
    from newsadda.gist import hindi_only
    d = ("CM Yogi Adityanath honors Gram Prahari in Orai. Follow Dainik Bhaskar Latest Updates. मुख्यमंत्री योगी "
         "आदित्यनाथ द्वारा प्रदेश के समस्त ग्राम प्रहरियों को संबोधित कर सम्मानित किया गया।")
    assert hindi_only(d).startswith("मुख्यमंत्री योगी") and "Orai" not in hindi_only(d)
    assert hindi_only("UP News: लखनऊ में सपा कार्यकर्ताओं ने प्रदर्शन किया और ज्ञापन सौंपा।").startswith("लखनऊ में सपा")
    assert hindi_only("who is Dharmendra Kashyap Join Samajwadi Party: यूपी चुनाव से पहले भाजपा को झटका").startswith("यूपी चुनाव")
    assert hindi_only("आज 12 अक्टूबर को डॉ. राम मनोहर लोहिया की पुण्यतिथि है।").startswith("आज 12 अक्टूबर को डॉ. राम")
    assert hindi_only("Greater Noida News: 13 से 17 अक्टूबर तक मेला लगेगा").startswith("13 से 17")
    assert hindi_only("BJP leaders attack Akhilesh Yadav over politics.") is None


def test_english_seo_tail_removed_from_hindi_headline():
    from newsadda.textutil import clean_title
    t = "आगरा-लखनऊ एक्सप्रेस-वे पर हादसा, एक सिपाही शहीद - constable dies in subrat pathak convoy crash"
    assert clean_title(t) == "आगरा-लखनऊ एक्सप्रेस-वे पर हादसा, एक सिपाही शहीद"
    assert clean_title("यूपी में भाजपा - सपा आमने-सामने") == "यूपी में भाजपा - सपा आमने-सामने"
    assert clean_title("अखिलेश यादव का बयान - BJP") == "अखिलेश यादव का बयान - BJP"


def test_gists_without_facts_are_not_used():
    page = '<html><head><meta property="og:description" content="आज की सबसे बड़ी खबर को लेकर है। वहीं, दूसरी बड़ी खबर से जुड़ी है।"></head></html>'
    assert description_from_page(page, "यूपी दिनभर, 15 बड़ी खबरें: योगी बोले")[0] is None
