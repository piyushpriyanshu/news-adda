"""Timestamp parsing. Times are stored in UTC and shown in IST by the app.

Rules (never invent a time):
* A feed date with an explicit zone is converted to UTC as-is.
* A feed date without a zone is assumed to be IST (Indian publishers) and the
  item is flagged ``tz_assumed`` so the app can say so.
* A missing or unparseable date stays ``None``; the app then shows only the
  time News Adda first detected the story.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

IST = timezone(timedelta(hours=5, minutes=30), "IST")


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def parse_feed_time(raw: str | None) -> tuple[datetime | None, bool]:
    """Return (utc_datetime or None, tz_assumed)."""
    if not raw or not raw.strip():
        return None, False
    s = raw.strip()
    dt: datetime | None = None
    try:
        dt = parsedate_to_datetime(s)
    except (TypeError, ValueError, IndexError):
        dt = None
    if dt is None:
        iso = s.replace("Z", "+00:00") if s.endswith("Z") else s
        for candidate in (iso, iso.replace(" ", "T", 1)):
            try:
                dt = datetime.fromisoformat(candidate)
                break
            except ValueError:
                continue
    if dt is None:
        return None, False
    assumed = False
    # parsedate_to_datetime returns naive for "-0000"; treat naive as IST.
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=IST)
        assumed = True
    return dt.astimezone(timezone.utc).replace(microsecond=0), assumed


def iso(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def from_iso(s: str | None) -> datetime | None:
    if not s:
        return None
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def to_ist_str(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    return dt.astimezone(IST).strftime("%d-%m-%Y %H:%M IST")
