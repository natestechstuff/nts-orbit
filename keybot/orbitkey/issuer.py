"""Decides whether a member needs a key, and issues it. No Discord code here so it can be tested alone."""
from __future__ import annotations

import secrets
from dataclasses import dataclass

from . import keys as K
from .db import KeyDB


@dataclass
class Decision:
    action: str          # "issue" | "resend" | "none"
    tier: str | None
    row: object = None   # existing DB row for resend/none


class Issuer:
    def __init__(self, db: KeyDB, private_key, expiry_days: dict[str, int] | None = None, renew_within_days: int = 5):
        self.db = db
        self.pk = private_key
        self.expiry_days = expiry_days or {}
        self.renew_within = renew_within_days

    @staticmethod
    def highest(tiers) -> str | None:
        tiers = [t for t in tiers if t in K.RANK]
        return max(tiers, key=K.RANK.get) if tiers else None

    def decide(self, user_id, role_tier: str | None, today_day: int | None = None) -> Decision:
        """role_tier = the highest key tier the member's roles grant right now (None = no paid role)."""
        if role_tier is None:
            return Decision("none", None)
        today_day = K.day_number() if today_day is None else today_day
        best = self.db.best_for(user_id, today_day)
        if best is None or K.RANK[best["tier"]] < K.RANK[role_tier]:
            return Decision("issue", role_tier, best)
        if best["expires_day"] and best["expires_day"] - today_day <= self.renew_within and K.RANK[best["tier"]] <= K.RANK[role_tier]:
            return Decision("issue", role_tier, best)   # renewal of a time-limited key
        if not best["delivered"]:
            return Decision("resend", best["tier"], best)
        return Decision("none", best["tier"], best)

    def issue(self, tier: str, user_id=None, user_name=None, source="role", note=None):
        """Signs a new key, records it, returns (row_id, formatted key, License)."""
        for _ in range(20):
            kid = secrets.randbits(32)
            if kid and not self.db.key_id_exists(kid):
                break
        key, lic = K.sign(self.pk, tier, self.expiry_days.get(tier, 0), key_id=kid)
        row_id = self.db.add(lic, key, user_id, user_name, source, note)
        return row_id, key, lic


def dm_text(key: str, tier: str, expires_day: int = 0, download_hint: str = "") -> str:
    pretty = K.PRETTY[tier]
    lines = [
        f"Thanks for supporting NTS Orbit! Here's your **{pretty}** key:",
        "",
        f"```{key}```",
        "How to use it:",
        "1. Open NTS Orbit, tap the gear (Settings).",
        "2. Tap **Premium**, paste the whole key, tap **Unlock**.",
        "It works offline. Keep it private: it's tied to your supporter tier.",
    ]
    if tier == "BALLER":
        lines.append("Baller: the Baller model section shows up in Premium once you unlock.")
    if expires_day:
        lines.append(f"This key renews automatically while you keep the role (valid to {K.day_to_date(expires_day).isoformat()}).")
    if download_hint:
        lines.append(download_hint)
    lines.append("Lost it? Use `/mykey` in the server and I'll show it again.")
    return "\n".join(lines)
