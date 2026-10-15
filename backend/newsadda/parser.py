"""Feed parsing: RSS/Atom bytes -> RawItem records. No network here."""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import feedparser

from .textutil import strip_html
from .timeutil import parse_feed_time

_TRACKING = re.compile(r"^(utm_|fbclid$|gclid$|ref$|ref_src$|source$|from$|cmp$|ito$)", re.I)


@dataclass
class RawItem:
    url: str
    title: str
    description: str | None
    published_at: datetime | None
    published_raw: str | None
    updated_at: datetime | None
    tz_assumed: bool
    publisher: str | None = None  # set when the item arrives via an aggregator
    author: str | None = None
    handle: str | None = None  # X posts only
    publisher_domain: str | None = None  # site of the original publisher (for its icon)
    post_context: str | None = None  # X posts: quote | reply | None


class FeedError(Exception):
    """The response was not a usable feed."""


def canonical_url(url: str) -> str:
    try:
        parts = urlsplit(url.strip())
    except ValueError:
        return url.strip()
    query = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True) if not _TRACKING.match(k)]
    return urlunsplit((parts.scheme.lower() or "https", parts.netloc.lower(), parts.path, urlencode(query), ""))


def site_domain(url: str | None) -> str | None:
    """'https://api.livehindustan.com/feeds/...' -> 'livehindustan.com'."""
    if not url:
        return None
    try:
        host = urlsplit(url.strip()).netloc.lower().split(":")[0]
    except ValueError:
        return None
    for prefix in ("www.", "api.", "feeds.", "rss.", "m."):
        if host.startswith(prefix):
            host = host[len(prefix):]
    return host or None


def item_id(url: str) -> str:
    return hashlib.sha1(canonical_url(url).encode("utf-8")).hexdigest()[:16]


def _split_google_title(title: str, publisher: str | None) -> str:
    if publisher and title.endswith(" - " + publisher):
        return title[: -len(publisher) - 3].strip()
    return title


def parse_feed(content: bytes, *, google_news: bool = False) -> list[RawItem]:
    parsed = feedparser.parse(content)
    if not parsed.entries:
        if parsed.bozo and not parsed.get("feed"):
            raise FeedError(f"not a feed: {parsed.get('bozo_exception')!s:.120}")
        version = parsed.get("version") or ""
        head = content[:300].lstrip().lower()
        if not version and (head.startswith(b"<!doctype html") or head.startswith(b"<html")):
            raise FeedError("received an HTML page, not a feed")
        if not version:
            raise FeedError("no feed entries and no feed format detected")
        return []

    items: list[RawItem] = []
    for e in parsed.entries:
        link = (e.get("link") or "").strip()
        title = strip_html(e.get("title"))
        if not link or not title or not link.startswith(("http://", "https://")):
            continue
        publisher = None
        publisher_domain = None
        if google_news:
            src = e.get("source") or {}
            publisher = (src.get("title") if hasattr(src, "get") else None) or None
            href = (src.get("href") or src.get("url")) if hasattr(src, "get") else None
            publisher_domain = site_domain(href) if href else None
            title = _split_google_title(title, publisher)
            description = None  # Google's description only repeats the title/links
        else:
            description = e.get("summary") or e.get("description")
            if not description and e.get("content"):
                description = e["content"][0].get("value")
        pub_raw = e.get("published") or e.get("pubDate") or e.get("dc_date") or e.get("updated")
        published, assumed = parse_feed_time(pub_raw)
        upd_raw = e.get("updated") if e.get("published") else None
        updated, _ = parse_feed_time(upd_raw)
        if updated and published and updated <= published:
            updated = None
        items.append(
            RawItem(
                url=link,
                title=title,
                description=description,
                published_at=published,
                published_raw=pub_raw,
                updated_at=updated,
                tz_assumed=assumed,
                publisher=publisher,
                author=strip_html(e.get("author")) or None,
                publisher_domain=publisher_domain,
            )
        )
    return items


_FEED_LINK = re.compile(
    r"""<link[^>]+type=["']application/(?:rss|atom)\+xml["'][^>]*>""", re.I
)
_HREF = re.compile(r"""href=["']([^"']+)["']""", re.I)
_ANCHOR = re.compile(r"""href=["']([^"'#]+)["']""", re.I)


def discover_feed_links(html: str, base_url: str, keywords: tuple[str, ...] = ()) -> list[str]:
    """Feed URLs advertised by an HTML page (<link rel=alternate>) plus anchors
    that look like feeds and contain one of ``keywords`` (for RSS index pages)."""
    from urllib.parse import urljoin

    found: list[str] = []
    for tag in _FEED_LINK.findall(html):
        m = _HREF.search(tag)
        if m:
            found.append(urljoin(base_url, m.group(1)))
    for href in _ANCHOR.findall(html):
        low = href.lower()
        looks_feed = low.endswith((".xml", "/feed", "/feed/", ".rss")) or "rss" in low or "/feeds/" in low
        if looks_feed and (not keywords or any(k in low for k in keywords)):
            found.append(urljoin(base_url, href))
    seen, out = set(), []
    for u in found:
        if u not in seen and u.startswith("http"):
            seen.add(u)
            out.append(u)
    return out


# ---------------------------------------------------------------------------
# Google News sitemaps. Big portals list each new article here the moment it is
# published (for Google News), including portals that offer no RSS feed.

def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def parse_sitemap(content: bytes) -> tuple[list[RawItem], list[str]]:
    """Return (articles, child_sitemaps). A sitemap index yields only children.

    Uses <news:title> and <news:publication_date>; entries without a title are
    skipped (there is nothing to show)."""
    import xml.etree.ElementTree as ET

    head = content[:400].lstrip().lower()
    if head.startswith(b"<!doctype html") or head.startswith(b"<html"):
        raise FeedError("received an HTML page, not a sitemap")
    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        raise FeedError(f"not a sitemap: {exc!s:.100}") from None
    kind = _local(root.tag)
    if kind == "sitemapindex":
        kids = [(el.findtext("{*}loc") or "").strip() for el in root if _local(el.tag) == "sitemap"]
        return [], [k for k in kids if k.startswith("http")]
    if kind != "urlset":
        raise FeedError(f"not a sitemap: root <{kind}>")
    items: list[RawItem] = []
    for url_el in root:
        if _local(url_el.tag) != "url":
            continue
        loc = (url_el.findtext("{*}loc") or "").strip()
        news = next((c for c in url_el if _local(c.tag) == "news"), None)
        if not loc.startswith(("http://", "https://")) or news is None:
            continue
        title = strip_html(news.findtext("{*}title"))
        if not title:
            continue
        pub_raw = news.findtext("{*}publication_date") or url_el.findtext("{*}lastmod")
        published, assumed = parse_feed_time(pub_raw)
        items.append(RawItem(
            url=loc, title=title, description=None, published_at=published, published_raw=pub_raw,
            updated_at=None, tz_assumed=assumed, publisher=None, publisher_domain=None,
        ))
    return items, []
