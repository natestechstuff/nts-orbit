"""SQLite record of every key issued, so nobody gets duplicates."""
from __future__ import annotations

import datetime as _dt
import os
import sqlite3

from . import keys as K

SCHEMA = """
CREATE TABLE IF NOT EXISTS keys (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    key_id        TEXT NOT NULL UNIQUE,
    tier          TEXT NOT NULL,
    license_key   TEXT NOT NULL,
    user_id       TEXT,            -- Discord user id, NULL for standalone sales
    user_name     TEXT,
    source        TEXT NOT NULL,   -- role | genkey | mykey | renew | cli
    note          TEXT,
    issued_at     TEXT NOT NULL,   -- UTC ISO time
    expires_day   INTEGER NOT NULL DEFAULT 0,
    delivered     INTEGER NOT NULL DEFAULT 0,
    delivered_at  TEXT,
    deliver_error TEXT
);
CREATE INDEX IF NOT EXISTS keys_user ON keys(user_id);
"""


def _now() -> str:
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat()


class KeyDB:
    def __init__(self, path: str):
        new = not os.path.exists(path)
        self.c = sqlite3.connect(path)
        self.c.row_factory = sqlite3.Row
        self.c.executescript(SCHEMA)
        if new:
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass

    def add(self, lic: K.License, license_key: str, user_id=None, user_name=None, source="cli", note=None) -> int:
        cur = self.c.execute(
            "INSERT INTO keys(key_id,tier,license_key,user_id,user_name,source,note,issued_at,expires_day) VALUES(?,?,?,?,?,?,?,?,?)",
            (lic.key_id_hex, lic.tier, license_key, str(user_id) if user_id else None, user_name, source, note, _now(), lic.expires_day))
        self.c.commit()
        return cur.lastrowid

    def mark_delivered(self, row_id: int, ok: bool, error: str | None = None) -> None:
        self.c.execute("UPDATE keys SET delivered=?, delivered_at=?, deliver_error=? WHERE id=?",
                       (1 if ok else 0, _now() if ok else None, error, row_id))
        self.c.commit()

    def best_for(self, user_id, today_day: int | None = None):
        """Highest-tier key this user holds that has not expired (newest first within a tier)."""
        today_day = K.day_number() if today_day is None else today_day
        rows = self.c.execute("SELECT * FROM keys WHERE user_id=? ORDER BY id DESC", (str(user_id),)).fetchall()
        live = [r for r in rows if not r["expires_day"] or r["expires_day"] >= today_day]
        if not live:
            return None
        return max(live, key=lambda r: (K.RANK[r["tier"]], r["id"]))

    def all(self, limit: int = 50):
        return self.c.execute("SELECT * FROM keys ORDER BY id DESC LIMIT ?", (limit,)).fetchall()

    def key_id_exists(self, key_id: int) -> bool:
        return self.c.execute("SELECT 1 FROM keys WHERE key_id=?", ("%08x" % key_id,)).fetchone() is not None
