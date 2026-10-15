"""Settings from environment variables. Secrets are never written to disk or feed."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"


def _env(name: str, default: str | None = None) -> str | None:
    v = os.environ.get(name)
    return v if v not in (None, "") else default


@dataclass
class Settings:
    db_path: str = field(default_factory=lambda: _env("NEWSADDA_DB", "var/newsadda.db"))
    sources_path: str = field(default_factory=lambda: _env("NEWSADDA_SOURCES", str(DATA_DIR / "sources.yaml")))
    entities_path: str = field(default_factory=lambda: _env("NEWSADDA_ENTITIES", str(DATA_DIR / "entities.yaml")))
    user_agent: str = field(default_factory=lambda: _env(
        "NEWSADDA_USER_AGENT",
        "NewsAddaBot/1.0 (+https://github.com/piyushpriyanshu/news-adda; Hindi UP news headline aggregator)",
    ))
    http_timeout: float = field(default_factory=lambda: float(_env("NEWSADDA_HTTP_TIMEOUT", "20")))
    concurrency: int = field(default_factory=lambda: int(_env("NEWSADDA_CONCURRENCY", "12")))
    feed_hours: int = field(default_factory=lambda: int(_env("NEWSADDA_FEED_HOURS", "36")))
    feed_limit: int = field(default_factory=lambda: int(_env("NEWSADDA_FEED_LIMIT", "1200")))
    keep_days: int = field(default_factory=lambda: int(_env("NEWSADDA_KEEP_DAYS", "7")))
    poll_tick: int = field(default_factory=lambda: int(_env("NEWSADDA_POLL_TICK", "60")))
    x_bearer_token: str | None = field(default_factory=lambda: _env("X_BEARER_TOKEN"))
    ai_api_key: str | None = field(default_factory=lambda: _env("ANTHROPIC_API_KEY"))
    ai_enabled: bool = field(default_factory=lambda: _env("NEWSADDA_AI_SUMMARY", "0") == "1")
    ai_model: str = field(default_factory=lambda: _env("NEWSADDA_AI_MODEL", "claude-haiku-5-5"))
    ai_max_per_run: int = field(default_factory=lambda: int(_env("NEWSADDA_AI_MAX_PER_RUN", "20")))
    # Short gists from publisher pages and direct links for Google News items.
    gist_enabled: bool = field(default_factory=lambda: _env("NEWSADDA_GIST", "1") == "1")
    gist_budget: int = field(default_factory=lambda: int(_env("NEWSADDA_GIST_BUDGET", "80")))
    # Minimum share of Devanagari letters in a headline (the feed is Hindi-only).
    min_hindi_share: float = field(default_factory=lambda: float(_env("NEWSADDA_MIN_HINDI", "0.5")))
    admin_token: str | None = field(default_factory=lambda: _env("NEWSADDA_ADMIN_TOKEN"))
    run_scheduler: bool = field(default_factory=lambda: _env("NEWSADDA_SCHEDULER", "1") == "1")
