from datetime import datetime, timezone

from newsadda.textutil import content_terms, normalize, short_summary, tokens
from newsadda.timeutil import IST, iso, parse_feed_time, to_ist_str


def test_normalize_folds_nukta_and_chandrabindu():
    assert normalize("चंद्रशेखर आज़ाद") == normalize("चंद्रशेखर आजाद")
    assert normalize("बाँदा") == normalize("बांदा")
    assert normalize("Yogi") == "yogi"


def test_tokens_keep_matras_together():
    assert tokens("अखिलेश यादव ने कहा- भाजपा सरकार विफल") == ["अखिलेश", "यादव", "ने", "कहा", "भाजपा", "सरकार", "विफल"]
    assert tokens("लखनऊ: CM Yogi") == ["लखनऊ", "cm", "yogi"]


def test_content_terms_drop_stopwords_and_stem_plurals():
    terms = content_terms("किसानों के आरोपों पर सरकार का जवाब")
    assert "किसान" in terms and "आरोप" in terms
    assert "के" not in terms and "पर" not in terms


def test_summary_trims_and_skips_title_repeats():
    title = "अखिलेश यादव का सरकार पर हमला"
    assert short_summary(title, title) is None
    assert short_summary("<p>छोटा</p>", title) is None
    desc = ("<p>सपा अध्यक्ष ने लखनऊ में प्रेस वार्ता में बेरोजगारी का मुद्दा उठाया। उन्होंने कहा कि युवाओं को "
            "रोजगार नहीं मिल रहा। " + "आगे की जानकारी। " * 30 + "</p>")
    s = short_summary(desc, title)
    assert s.startswith("सपा अध्यक्ष ने लखनऊ")
    assert len(s) <= 241
    assert "<p>" not in s


def test_feed_time_with_ist_offset():
    dt, assumed = parse_feed_time("Tue, 13 Oct 2026 13:05:00 +0530")
    assert dt == datetime(2026, 10, 13, 7, 35, tzinfo=timezone.utc)
    assert assumed is False


def test_feed_time_gmt_and_iso():
    assert parse_feed_time("Tue, 13 Oct 2026 07:35:00 GMT")[0] == datetime(2026, 10, 13, 7, 35, tzinfo=timezone.utc)
    assert parse_feed_time("2026-10-13T07:35:00Z")[0] == datetime(2026, 10, 13, 7, 35, tzinfo=timezone.utc)
    assert parse_feed_time("2026-10-13T13:05:00+05:30")[0] == datetime(2026, 10, 13, 7, 35, tzinfo=timezone.utc)


def test_feed_time_without_zone_is_assumed_ist_and_flagged():
    dt, assumed = parse_feed_time("2026-10-13 13:05:00")
    assert assumed is True
    assert dt == datetime(2026, 10, 13, 7, 35, tzinfo=timezone.utc)


def test_missing_or_garbage_time_is_none_never_invented():
    assert parse_feed_time(None) == (None, False)
    assert parse_feed_time("") == (None, False)
    assert parse_feed_time("कल शाम") == (None, False)


def test_ist_display_and_iso_roundtrip():
    dt = datetime(2026, 10, 13, 7, 35, tzinfo=timezone.utc)
    assert to_ist_str(dt) == "13-10-2026 13:05 IST"
    assert iso(dt.astimezone(IST)) == "2026-10-13T07:35:00Z"
