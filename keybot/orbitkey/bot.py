"""Orbit key bot: DMs a premium key when a member gets a supporter role.

Config (JSON, path in ORBIT_KEYBOT_CONFIG, default /etc/orbit-keybot/config.json):
    guild_id, role_tiers {role_id: tier}, owner_role_id, db_path, signing_key_path,
    notify_channel_id (optional: where to log failed DMs; else the server owner gets a DM),
    expiry_days {tier: days} (optional, 0/absent = keys never expire), download_hint (optional)
Token: $CREDENTIALS_DIRECTORY/token (systemd LoadCredential) or $DISCORD_TOKEN.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import sys

import discord
from discord import app_commands
from discord.ext import tasks

from . import keys as K
from .db import KeyDB
from .issuer import Issuer, dm_text

log = logging.getLogger("orbit-keybot")


def load_config(path: str | None = None) -> dict:
    path = path or os.environ.get("ORBIT_KEYBOT_CONFIG", "/etc/orbit-keybot/config.json")
    with open(path) as f:
        cfg = json.load(f)
    cfg["role_tiers"] = {int(k): v for k, v in cfg["role_tiers"].items()}
    for t in cfg["role_tiers"].values():
        if t not in K.TIERS:
            raise SystemExit(f"config: unknown tier {t}")
    return cfg


def read_token() -> str:
    cd = os.environ.get("CREDENTIALS_DIRECTORY")
    if cd and os.path.exists(os.path.join(cd, "token")):
        with open(os.path.join(cd, "token")) as f:
            return f.read().strip()
    tok = os.environ.get("DISCORD_TOKEN", "").strip()
    if not tok:
        raise SystemExit("no bot token (LoadCredential=token:… or DISCORD_TOKEN)")
    return tok


def signing_key_path(cfg: dict) -> str:
    cd = os.environ.get("CREDENTIALS_DIRECTORY")
    if cd and os.path.exists(os.path.join(cd, "signing_key")):
        return os.path.join(cd, "signing_key")
    return cfg["signing_key_path"]


class KeyBot(discord.Client):
    def __init__(self, cfg: dict, issuer: Issuer):
        intents = discord.Intents.none()
        intents.guilds = True
        intents.members = True          # needs "Server Members Intent" ON in the Developer Portal
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.cfg = cfg
        self.issuer = issuer
        self.guild_obj = discord.Object(id=int(cfg["guild_id"]))
        self.tree = app_commands.CommandTree(self)
        self.lock = asyncio.Lock()
        _register_commands(self)

    # ------------------------------------------------------------ helpers
    def role_tier(self, member: discord.Member) -> str | None:
        return Issuer.highest(self.cfg["role_tiers"].get(r.id) for r in member.roles)

    def is_owner(self, member) -> bool:
        if not isinstance(member, discord.Member):
            return False
        if member.guild.owner_id == member.id:
            return True
        oid = self.cfg.get("owner_role_id")
        return bool(oid) and any(r.id == int(oid) for r in member.roles)

    async def notify_owner(self, guild: discord.Guild, text: str) -> None:
        log.warning(text)
        ch_id = self.cfg.get("notify_channel_id")
        try:
            if ch_id:
                ch = guild.get_channel(int(ch_id)) or await guild.fetch_channel(int(ch_id))
                await ch.send(text)
                return
            owner = guild.owner or await guild.fetch_member(guild.owner_id)
            await owner.send(text)
        except discord.HTTPException as e:
            log.error("could not notify owner: %s", e)

    async def deliver(self, member: discord.Member, row_id: int, key: str, tier: str, expires_day: int) -> bool:
        try:
            await member.send(dm_text(key, tier, expires_day, self.cfg.get("download_hint", "")))
            self.issuer.db.mark_delivered(row_id, True)
            log.info("DM'd %s key %s to %s (%s)", tier, row_id, member, member.id)
            return True
        except discord.Forbidden as e:
            self.issuer.db.mark_delivered(row_id, False, "dm closed: %s" % e)
            await self.notify_owner(member.guild,
                f"Couldn't DM {member.mention} ({member}) their {K.PRETTY[tier]} key: their DMs are closed. "
                f"They can run /mykey in the server to see it.")
            return False
        except discord.HTTPException as e:
            self.issuer.db.mark_delivered(row_id, False, str(e)[:200])
            log.error("DM to %s failed: %s", member.id, e)
            return False

    async def handle_member(self, member: discord.Member, source: str = "role") -> str:
        """Gives the member the key their roles call for. Returns what happened."""
        if member.bot:
            return "bot"
        async with self.lock:
            d = self.issuer.decide(member.id, self.role_tier(member))
            if d.action == "issue":
                row_id, key, lic = self.issuer.issue(d.tier, member.id, str(member), source)
                await self.deliver(member, row_id, key, d.tier, lic.expires_day)
                return "issued " + d.tier
            if d.action == "resend":
                await self.deliver(member, d.row["id"], d.row["license_key"], d.row["tier"], d.row["expires_day"])
                return "resent " + d.tier
            return "none"

    # ------------------------------------------------------------ events
    def invite_url(self) -> str:
        return ("https://discord.com/oauth2/authorize?client_id=%s&scope=bot+applications.commands&permissions=3072"
                % (self.application_id or "APP_ID"))

    async def sync_commands(self) -> bool:
        try:
            await self.tree.sync(guild=self.guild_obj)
            log.info("slash commands synced to guild %s", self.guild_obj.id)
            return True
        except discord.Forbidden as e:
            log.error("can't add slash commands to guild %s (%s). Invite the bot with the "
                      "applications.commands scope: %s", self.guild_obj.id, e, self.invite_url())
            return False

    async def setup_hook(self) -> None:
        self.tree.copy_global_to(guild=self.guild_obj)
        await self.sync_commands()
        self.sweep.start()

    async def on_ready(self):
        log.info("logged in as %s (%s)", self.user, self.user.id if self.user else "?")
        if self.get_guild(self.guild_obj.id) is None:
            log.error("the bot is not in guild %s yet. Invite it: %s", self.guild_obj.id, self.invite_url())

    async def on_guild_join(self, guild: discord.Guild):
        if guild.id == self.guild_obj.id:
            log.info("joined the server, syncing commands and checking members")
            await self.sync_commands()
            if self.sweep.is_running():
                self.sweep.restart()

    async def on_member_update(self, before: discord.Member, after: discord.Member):
        if after.guild.id != self.guild_obj.id:
            return
        added = {r.id for r in after.roles} - {r.id for r in before.roles}
        if any(rid in self.cfg["role_tiers"] for rid in added):
            await self.handle_member(after, "role")

    @tasks.loop(hours=6)
    async def sweep(self):
        """Catches roles given while the bot was offline, retries failed DMs, renews expiring keys."""
        guild = self.get_guild(self.guild_obj.id)
        if guild is None:
            return
        n = 0
        async for m in guild.fetch_members(limit=None):
            if self.role_tier(m) is None:
                continue
            d = self.issuer.decide(m.id, self.role_tier(m))
            if d.action == "issue" or (d.action == "resend" and not (d.row["deliver_error"] or "").startswith("dm closed")):
                await self.handle_member(m, "renew" if d.row is not None and d.action == "issue" else "role")
                n += 1
        log.info("sweep done, %d members handled", n)

    @sweep.before_loop
    async def _before_sweep(self):
        await self.wait_until_ready()


async def _ack(inter: discord.Interaction) -> None:
    """Defers right away (Discord drops an interaction not answered within 3 s) and logs how late we were."""
    age = (discord.utils.utcnow() - inter.created_at).total_seconds()
    t0 = asyncio.get_running_loop().time()
    try:
        await inter.response.defer(ephemeral=True, thinking=True)
    finally:
        log.info("/%s from %s: arrived %.2fs after it was sent, defer took %.2fs, gateway latency %.2fs",
                 inter.command.name if inter.command else "?", inter.user, age,
                 asyncio.get_running_loop().time() - t0, inter.client.latency)


def _register_commands(bot: KeyBot) -> None:
    tier_choices = [app_commands.Choice(name=K.PRETTY[t], value=t) for t in K.TIERS]

    @bot.tree.command(name="genkey", description="(Owner) Make an NTS Orbit premium key")
    @app_commands.describe(tier="Which tier", user="Send it to this member (optional)", note="Who it's for, e.g. a Pro sale (optional)")
    @app_commands.choices(tier=tier_choices)
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def genkey(inter: discord.Interaction, tier: app_commands.Choice[str], user: discord.Member | None = None, note: str | None = None):
        await _ack(inter)
        if not bot.is_owner(inter.user):
            await inter.followup.send("Only the owner can make keys.", ephemeral=True)
            return
        row_id, key, lic = bot.issuer.issue(tier.value, user.id if user else None, str(user) if user else None,
                                            "genkey", note or f"by {inter.user}")
        msg = f"{K.PRETTY[tier.value]} key (id {lic.key_id_hex}):\n```{key}```"
        if user is not None:
            ok = await bot.deliver(user, row_id, key, tier.value, lic.expires_day)
            msg += f"\nDM'd to {user.mention}." if ok else f"\nCouldn't DM {user.mention}; send it to them yourself."
        else:
            bot.issuer.db.mark_delivered(row_id, True)
        await inter.followup.send(msg, ephemeral=True)

    @bot.tree.command(name="mykey", description="Show your NTS Orbit premium key again")
    @app_commands.guild_only()
    async def mykey(inter: discord.Interaction):
        await _ack(inter)
        member = inter.user
        if isinstance(member, discord.Member) and bot.role_tier(member) is not None:
            d = bot.issuer.decide(member.id, bot.role_tier(member))
            if d.action == "issue":
                async with bot.lock:
                    row_id, key, lic = bot.issuer.issue(d.tier, member.id, str(member), "mykey")
                    bot.issuer.db.mark_delivered(row_id, True)
                await inter.followup.send(dm_text(key, d.tier, lic.expires_day, bot.cfg.get("download_hint", "")), ephemeral=True)
                return
        row = bot.issuer.db.best_for(member.id)
        if row is None:
            await inter.followup.send(
                "No key on file for you. Keys come with Crew 2, Crew 3 and Baller (or a Pro purchase).", ephemeral=True)
            return
        if not row["delivered"]:
            bot.issuer.db.mark_delivered(row["id"], True)
        await inter.followup.send(dm_text(row["license_key"], row["tier"], row["expires_day"], bot.cfg.get("download_hint", "")), ephemeral=True)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s", stream=sys.stdout)
    cfg = load_config()
    issuer = Issuer(KeyDB(cfg["db_path"]), K.load_private_key(signing_key_path(cfg)),
                    {k: int(v) for k, v in cfg.get("expiry_days", {}).items()})
    try:
        KeyBot(cfg, issuer).run(read_token(), log_handler=None)
    except discord.PrivilegedIntentsRequired:
        log.error("Discord refused the Server Members intent. Turn ON 'Server Members Intent' in the "
                  "Developer Portal (your app -> Bot -> Privileged Gateway Intents). Retrying in a minute.")
        sys.exit(3)
    except discord.LoginFailure:
        log.error("Discord rejected the bot token (reset it in the Developer Portal and save the new one).")
        sys.exit(4)


if __name__ == "__main__":
    main()
