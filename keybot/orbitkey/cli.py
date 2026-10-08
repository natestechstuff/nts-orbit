"""orbitkey CLI.

    python -m orbitkey.cli init-key PATH           make a new signing key (refuses to overwrite)
    python -m orbitkey.cli pubkey  [--key PATH]    print the public key (hex) to put in the app
    python -m orbitkey.cli gen --tier PRO [--days N] [--note TEXT] [--user ID]   make + record a key
    python -m orbitkey.cli verify KEY [--pub HEX | --key PATH]
    python -m orbitkey.cli list [--limit N]
Paths default to the bot config (ORBIT_KEYBOT_CONFIG or /etc/orbit-keybot/config.json).
"""
from __future__ import annotations

import argparse
import json
import os
import sys

from . import keys as K


def _cfg():
    p = os.environ.get("ORBIT_KEYBOT_CONFIG", "/etc/orbit-keybot/config.json")
    if os.path.exists(p):
        with open(p) as f:
            return json.load(f)
    return {}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="orbitkey")
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("init-key"); a.add_argument("path")
    a = sub.add_parser("pubkey"); a.add_argument("--key")
    a = sub.add_parser("gen"); a.add_argument("--tier", required=True, choices=list(K.TIERS)); a.add_argument("--days", type=int, default=0)
    a.add_argument("--note"); a.add_argument("--user"); a.add_argument("--key"); a.add_argument("--db"); a.add_argument("--no-db", action="store_true")
    a = sub.add_parser("verify"); a.add_argument("license"); a.add_argument("--pub"); a.add_argument("--key")
    a = sub.add_parser("list"); a.add_argument("--limit", type=int, default=30); a.add_argument("--db")
    args = ap.parse_args(argv)
    cfg = _cfg()

    if args.cmd == "init-key":
        K.create_private_key(args.path)
        print("public key:", K.public_hex(K.load_private_key(args.path)))
        return 0
    if args.cmd == "pubkey":
        print(K.public_hex(K.load_private_key(args.key or cfg["signing_key_path"])))
        return 0
    if args.cmd == "gen":
        pk = K.load_private_key(args.key or cfg["signing_key_path"])
        if args.no_db:
            key, lic = K.sign(pk, args.tier, args.days)
        else:
            from .db import KeyDB
            from .issuer import Issuer
            iss = Issuer(KeyDB(args.db or cfg["db_path"]), pk, {args.tier: args.days})
            row_id, key, lic = iss.issue(args.tier, args.user, None, "cli", args.note)
            iss.db.mark_delivered(row_id, True)
        print(key)
        print(f"tier={lic.tier} id={lic.key_id_hex} issued={K.day_to_date(lic.issued_day)} "
              f"expires={'never' if not lic.expires_day else K.day_to_date(lic.expires_day)}", file=sys.stderr)
        return 0
    if args.cmd == "verify":
        pub = K.public_from_hex(args.pub) if args.pub else K.load_private_key(args.key or cfg["signing_key_path"]).public_key()
        try:
            lic = K.verify(pub, args.license)
        except K.KeyError_ as e:
            print("INVALID:", e)
            return 1
        print(f"VALID tier={lic.tier} id={lic.key_id_hex} issued={K.day_to_date(lic.issued_day)} "
              f"expires={'never' if not lic.expires_day else K.day_to_date(lic.expires_day)}")
        return 0
    if args.cmd == "list":
        from .db import KeyDB
        for r in KeyDB(args.db or cfg["db_path"]).all(args.limit):
            print(r["id"], r["key_id"], r["tier"], r["user_name"] or r["user_id"] or "-", r["source"],
                  "delivered" if r["delivered"] else ("FAILED " + (r["deliver_error"] or "")), r["issued_at"], r["note"] or "")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
