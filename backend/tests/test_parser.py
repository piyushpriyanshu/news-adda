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


def test_google_news_sitemap_parsed_and_index_followed():
    from newsadda.parser import parse_sitemap
    xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
 <url><loc>https://portal.test/uttar-pradesh/lucknow/sp-protest-123</loc>
  <news:news><news:publication><news:name>Portal</news:name><news:language>hi</news:language></news:publication>
  <news:publication_date>2026-10-15T09:40:00+05:30</news:publication_date>
  <news:title>&#2354;&#2326;&#2344;&#2314; &#2350;&#2375;&#2306; &#2360;&#2346;&#2366; &#2325;&#2366; &#2346;&#2381;&#2352;&#2342;&#2352;&#2381;&#2358;&#2344;</news:title></news:news></url>
 <url><loc>https://portal.test/no-news-tag</loc><lastmod>2026-10-15T09:00:00+05:30</lastmod></url>
</urlset>"""
    items, kids = parse_sitemap(xml)
    assert kids == [] and len(items) == 1
    assert items[0].title == "लखनऊ में सपा का प्रदर्शन"
    assert items[0].published_at.isoformat() == "2026-10-15T04:10:00+00:00"
    idx = b"""<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><sitemap><loc>https://portal.test/news-1.xml</loc></sitemap></sitemapindex>"""
    assert parse_sitemap(idx) == ([], ["https://portal.test/news-1.xml"])
