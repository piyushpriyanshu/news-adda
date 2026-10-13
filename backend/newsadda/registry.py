"""Source registry loaded from sources.yaml."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote_plus

import yaml

GOOGLE_NEWS = "https://news.google.com/rss/search?q={q}&hl=hi&gl=IN&ceid=IN:hi"


@dataclass
class Source:
    id: str
    name: str
    name_hi: str
    kind: str = "rss"
    urls: list[str] = field(default_factory=list)
    listing_pages: list[str] = field(default_factory=list)
    query: str | None = None
    handles: list[str] = field(default_factory=list)
    up_section: bool = True
    min_interval: int = 240
    enabled: bool = True
    official: bool = False
    status_note: str | None = None

    @property
    def feed_urls(self) -> list[str]:
        if self.kind == "google_news" and self.query:
            return [GOOGLE_NEWS.format(q=quote_plus(self.query))]
        return list(self.urls)

    @property
    def via(self) -> str | None:
        return "Google News" if self.kind == "google_news" else None

    def public(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "name_hi": self.name_hi,
            "kind": self.kind,
            "via": self.via,
            "enabled": self.enabled,
            "min_interval": self.min_interval,
            "note": self.status_note,
        }


def load_sources(path: str | Path) -> list[Source]:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    defaults = raw.get("defaults", {})
    out, seen = [], set()
    for entry in raw.get("sources", []):
        merged = {**defaults, **entry}
        allowed = Source.__dataclass_fields__.keys()
        unknown = set(merged) - set(allowed)
        if unknown:
            raise ValueError(f"source {entry.get('id')}: unknown keys {sorted(unknown)}")
        src = Source(**merged)
        if src.id in seen:
            raise ValueError(f"duplicate source id {src.id}")
        if src.kind not in ("rss", "google_news", "x"):
            raise ValueError(f"source {src.id}: unknown kind {src.kind}")
        seen.add(src.id)
        out.append(src)
    return out
