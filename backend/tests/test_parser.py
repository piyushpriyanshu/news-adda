import pytest

from newsadda.parser import FeedError, canonical_url, discover_feed_links, item_id, parse_feed

from .conftest import rss


def test_parse_rss_items_dates_and_headline_only():
    body = rss([
        {"title": "लखनऊ में सपा का प्रदर्शन", "link": "https://a.test/1?utm_source=rss",
         "date": "Tue, 13 Oct 2026 13:05:00 +0530", "desc": "सपा कार्यकर्ताओं ने विधानसभा के बाहर धरना दिया और नारेबाजी की।"},
        {"title": "बिना तारीख की खबर", "link": "https://a.test/2"},
    ])
    items = parse_feed(body)
    assert len(items) == 2
    assert items[0].published_at.isoformat() == "2026-10-13T07:35:00+00:00"
    assert items[1].published_at is None and items[1].description is None  # headline-only kept


def test_google_news_title_publisher_split():
    body = rss([{"title": "योगी कैबिनेट की बैठक - अमर उजाला", "link": "https://news.google.com/rss/articles/abc",
                 "source": "अमर उजाला", "date": "Tue, 13 Oct 2026 07:00:00 GMT", "desc": "<a>x</a>"}])
    (item,) = parse_feed(body, google_news=True)
    assert item.title == "योगी कैबिनेट की बैठक"
    assert item.publisher == "अमर उजाला"
    assert item.description is None


def test_html_page_is_rejected_not_parsed_as_news():
    with pytest.raises(FeedError):
        parse_feed(b"<!DOCTYPE html><html><body>Access denied</body></html>")


def test_empty_valid_feed_returns_no_items():
    assert parse_feed(rss([])) == []


def test_canonical_url_strips_tracking():
    a = canonical_url("https://A.test/story?id=5&utm_source=rss&utm_medium=x#top")
    assert a == "https://a.test/story?id=5"
    assert item_id("https://a.test/story?id=5&utm_campaign=z") == item_id("https://a.test/story?id=5")


def test_discover_feed_links():
    html = """<html><head><link rel="alternate" type="application/rss+xml" href="/rss/up.xml"></head>
    <body><a href="/rss/uttar-pradesh.xml">UP</a><a href="/rss/sports.xml">Sports</a><a href="/about">x</a></body></html>"""
    links = discover_feed_links(html, "https://site.test/rss", ("uttar",))
    assert "https://site.test/rss/up.xml" in links
    assert "https://site.test/rss/uttar-pradesh.xml" in links
    assert all("sports" not in l for l in links)
