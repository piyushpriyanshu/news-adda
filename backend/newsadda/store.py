"""SQLite storage with simple versioned migrations (PRAGMA user_version)."""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

from .timeutil import from_iso, iso, utcnow

MIGRATIONS: list[str] = [
    # v1
    """
    CREATE TABLE source_state (
        id TEXT PRIMARY KEY,
        active_url TEXT,
        etag TEXT,
        last_modified TEXT,
        status TEXT NOT NULL DEFAULT 'pending',   -- ok | error | disabled | unavailable | pending
        last_attempt_at TEXT,
        last_ok_at TEXT,
        last_error TEXT,
        consecutive_failures INTEGER NOT NULL DEFAULT 0,
        next_poll_at TEXT,
        last_item_count INTEGER NOT NULL DEFAULT 0,
        last_new_count INTEGER NOT NULL DEFAULT 0,
        total_items INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE items (
        id TEXT PRIMARY KEY,
        source_id TEXT NOT NULL,
        url TEXT NOT NULL,
        title TEXT NOT NULL,
        title_norm TEXT NOT NULL,
        summary TEXT,
        summary_origin TEXT,          -- publisher | ai | NULL
        publisher TEXT,               -- original publisher when via aggregator
        author TEXT,
        handle TEXT,
        kind TEXT NOT NULL DEFAULT 'article',   -- article | post
        published_at TEXT,
        published_raw TEXT,
        tz_assumed INTEGER NOT NULL DEFAULT 0,
        published_suspect INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT,
        first_seen_at TEXT NOT NULL,
        changed_at TEXT NOT NULL,
        sort_at TEXT NOT NULL,
        cluster_id TEXT NOT NULL,
        data TEXT NOT NULL            -- JSON: classification output
    );
    CREATE INDEX items_sort ON items(sort_at DESC);
    CREATE INDEX items_changed ON items(changed_at);
    CREATE INDEX items_cluster ON items(cluster_id);
    CREATE INDEX items_title ON items(title_norm);
    CREATE TABLE fetch_log (
        source_id TEXT NOT NULL,
        at TEXT NOT NULL,
        ok INTEGER NOT NULL,
        http_status INTEGER,
        items INTEGER,
        new_items INTEGER,
        ms INTEGER,
        error TEXT
    );
    CREATE INDEX fetch_log_at ON fetch_log(at);
    """,
    # v2
    """
    CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    """,
]


class Store:
    def __init__(self, path: str | Path = ":memory:"):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL" if self.path != ":memory:" else "PRAGMA journal_mode=MEMORY")
        self.migrate()

    def migrate(self) -> int:
        version = self.db.execute("PRAGMA user_version").fetchone()[0]
        for i in range(version, len(MIGRATIONS)):
            self.db.executescript(MIGRATIONS[i])
            self.db.execute(f"PRAGMA user_version = {i + 1}")
        self.db.commit()
        return len(MIGRATIONS)

    def close(self):
        self.db.commit()
        self.db.close()

    # ---- source state -------------------------------------------------
    def source_state(self, sid: str) -> dict:
        row = self.db.execute("SELECT * FROM source_state WHERE id=?", (sid,)).fetchone()
        if row is None:
            self.db.execute("INSERT INTO source_state(id) VALUES (?)", (sid,))
            row = self.db.execute("SELECT * FROM source_state WHERE id=?", (sid,)).fetchone()
        return dict(row)

    def update_source_state(self, sid: str, **fields):
        self.source_state(sid)
        cols = ", ".join(f"{k}=?" for k in fields)
        self.db.execute(f"UPDATE source_state SET {cols} WHERE id=?", (*fields.values(), sid))

    def all_source_states(self) -> dict[str, dict]:
        return {r["id"]: dict(r) for r in self.db.execute("SELECT * FROM source_state")}

    def log_fetch(self, sid, ok, http_status, items, new_items, ms, error):
        self.db.execute(
            "INSERT INTO fetch_log VALUES (?,?,?,?,?,?,?,?)",
            (sid, iso(utcnow()), int(ok), http_status, items, new_items, ms, error),
        )

    def meta(self, key: str) -> str | None:
        row = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_meta(self, key: str, value: str):
        self.db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", (key, value))

    def all_items(self) -> list[dict]:
        return [self._row(r) for r in self.db.execute("SELECT * FROM items")]

    # ---- items --------------------------------------------------------
    def has_item(self, item_id: str) -> bool:
        return self.db.execute("SELECT 1 FROM items WHERE id=?", (item_id,)).fetchone() is not None

    def get_item(self, item_id: str) -> dict | None:
        row = self.db.execute("SELECT * FROM items WHERE id=?", (item_id,)).fetchone()
        return self._row(row) if row else None

    def find_same_title(self, title_norm: str, since: datetime) -> dict | None:
        row = self.db.execute(
            "SELECT * FROM items WHERE title_norm=? AND first_seen_at>=? LIMIT 1", (title_norm, iso(since))
        ).fetchone()
        return self._row(row) if row else None

    def insert_item(self, rec: dict):
        rec = dict(rec)
        rec["data"] = json.dumps(rec["data"], ensure_ascii=False)
        cols = ", ".join(rec)
        self.db.execute(f"INSERT INTO items({cols}) VALUES ({', '.join('?' * len(rec))})", tuple(rec.values()))

    def update_item(self, item_id: str, **fields):
        if "data" in fields:
            fields["data"] = json.dumps(fields["data"], ensure_ascii=False)
        cols = ", ".join(f"{k}=?" for k in fields)
        self.db.execute(f"UPDATE items SET {cols} WHERE id=?", (*fields.values(), item_id))

    def recent_items(self, since: datetime) -> list[dict]:
        rows = self.db.execute(
            "SELECT * FROM items WHERE first_seen_at>=? ORDER BY sort_at DESC", (iso(since),)
        ).fetchall()
        return [self._row(r) for r in rows]

    def cluster_items(self, cluster_id: str) -> list[dict]:
        rows = self.db.execute("SELECT * FROM items WHERE cluster_id=? ORDER BY first_seen_at", (cluster_id,))
        return [self._row(r) for r in rows]

    def items_for_feed(self, since_sort: datetime, limit: int, changed_after: datetime | None = None) -> list[dict]:
        q = "SELECT * FROM items WHERE sort_at>=?"
        args: list = [iso(since_sort)]
        if changed_after is not None:
            q += " AND changed_at>=?"
            args.append(iso(changed_after))
        q += " ORDER BY sort_at DESC LIMIT ?"
        args.append(limit)
        return [self._row(r) for r in self.db.execute(q, args)]

    def max_changed_at(self) -> str | None:
        return self.db.execute("SELECT MAX(changed_at) FROM items").fetchone()[0]

    def prune(self, keep_days: int = 7):
        cutoff = iso(utcnow() - timedelta(days=keep_days))
        self.db.execute("DELETE FROM items WHERE first_seen_at<?", (cutoff,))
        self.db.execute("DELETE FROM fetch_log WHERE at<?", (iso(utcnow() - timedelta(days=2)),))

    def commit(self):
        self.db.commit()

    @staticmethod
    def _row(row: sqlite3.Row) -> dict:
        d = dict(row)
        d["data"] = json.loads(d["data"])
        return d


def parse_dt(s: str | None):
    return from_iso(s)
