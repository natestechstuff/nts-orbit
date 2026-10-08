"""Drives the bot's event handlers with fake Discord objects (no network, no token)."""
import asyncio
import os
import sys
import tempfile
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import discord
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from orbitkey import keys as K
from orbitkey.bot import KeyBot
from orbitkey.db import KeyDB
from orbitkey.issuer import Issuer

GUILD = 111
ROLES = {201: "BALLER", 202: "CREW2", 203: "CREW3"}
CREW1, OWNER_ROLE = 204, 205


class FakeGuild:
    id = GUILD
    owner_id = 999
    def __init__(self):
        self.owner = FakeMember(999, [], self)
    def get_channel(self, _):
        return None


class FakeMember:
    bot = False
    def __init__(self, uid, role_ids, guild, dms_open=True):
        self.id = uid
        self.roles = [SimpleNamespace(id=r) for r in role_ids]
        self.guild = guild
        self.dms_open = dms_open
        self.inbox = []
        self.mention = f"<@{uid}>"
    def __str__(self):
        return f"user{self.id}"
    async def send(self, text):
        if not self.dms_open:
            raise discord.Forbidden(SimpleNamespace(status=403, reason="Forbidden"), "Cannot send messages to this user")
        self.inbox.append(text)


def make_bot(d):
    cfg = {"guild_id": GUILD, "role_tiers": ROLES, "owner_role_id": OWNER_ROLE}
    pk = Ed25519PrivateKey.generate()
    bot = KeyBot(cfg, Issuer(KeyDB(os.path.join(d, "k.db")), pk))
    return bot, pk


def test_role_events():
    async def run():
        with tempfile.TemporaryDirectory() as d:
            bot, pk = make_bot(d)
            g = FakeGuild()
            before = FakeMember(1, [], g)
            # Crew 1 only: no key
            after = FakeMember(1, [CREW1], g); after.inbox = before.inbox
            await bot.on_member_update(before, after)
            assert before.inbox == []
            # gets Crew 2: one DM with a valid CREW2 key
            after2 = FakeMember(1, [CREW1, 202], g); after2.inbox = before.inbox
            await bot.on_member_update(after, after2)
            assert len(before.inbox) == 1
            key = [l for l in before.inbox[0].split("```") if l.startswith("ORBIT-")][0]
            assert K.verify(pk.public_key(), key).tier == "CREW2"
            # role re-added / unrelated update: no duplicate
            await bot.on_member_update(after, after2)
            assert len(before.inbox) == 1
            # upgrade to Baller (has Crew 2 + Baller): Baller key
            after3 = FakeMember(1, [CREW1, 202, 201], g); after3.inbox = before.inbox
            await bot.on_member_update(after2, after3)
            assert len(before.inbox) == 2
            key2 = [l for l in before.inbox[1].split("```") if l.startswith("ORBIT-")][0]
            assert K.verify(pk.public_key(), key2).tier == "BALLER"
            # closed DMs: owner is told, row marked failed
            closed_b = FakeMember(2, [], g, dms_open=False)
            closed_a = FakeMember(2, [203], g, dms_open=False)
            await bot.on_member_update(closed_b, closed_a)
            assert any("DMs are closed" in t for t in g.owner.inbox)
            row = bot.issuer.db.best_for(2)
            assert row["tier"] == "CREW3" and row["delivered"] == 0
            # bots and other guilds ignored
            other = FakeGuild(); other.id = 5
            await bot.on_member_update(FakeMember(3, [], other), FakeMember(3, [201], other))
            assert bot.issuer.db.best_for(3) is None
    asyncio.run(run())


def test_owner_check(monkeypatch):
    with tempfile.TemporaryDirectory() as d:
        bot, _ = make_bot(d)
        monkeypatch.setattr(discord, "Member", FakeMember)
        g = FakeGuild()
        assert bot.is_owner(FakeMember(5, [OWNER_ROLE], g))
        assert bot.is_owner(FakeMember(999, [], g))
        assert not bot.is_owner(FakeMember(6, [202], g))
