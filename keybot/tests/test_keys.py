import datetime as dt
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from orbitkey import keys as K
from orbitkey.db import KeyDB
from orbitkey.issuer import Issuer, dm_text


def test_b32_roundtrip():
    for n in (1, 5, 9, 73):
        data = os.urandom(n)
        assert K.b32decode(K.b32encode(data), n) == data


@pytest.mark.parametrize("tier", list(K.TIERS))
def test_sign_verify_each_tier(tier):
    pk = Ed25519PrivateKey.generate()
    key, lic = K.sign(pk, tier)
    assert key.startswith("ORBIT-") and len(K.normalize(key)) == 117
    got = K.verify(pk.public_key(), key)
    assert got == lic and got.tier == tier
    # typing slop: lower case, spaces, O for 0, missing prefix
    sloppy = key.lower().replace("-", " ").replace("0", "o")
    assert K.verify(pk.public_key(), sloppy) == lic


def test_tamper_and_wrong_key_fail():
    pk = Ed25519PrivateKey.generate()
    key, _ = K.sign(pk, "CREW2")
    body = K.normalize(key)
    for i in (0, 1, 5, 20, 60, 116):
        c = body[i]
        bad = body[:i] + ("A" if c != "A" else "B") + body[i + 1:]
        with pytest.raises(K.KeyError_):
            K.verify(pk.public_key(), bad)
    with pytest.raises(K.KeyError_):
        K.verify(Ed25519PrivateKey.generate().public_key(), key)
    with pytest.raises(K.KeyError_):
        K.verify(pk.public_key(), key[:-1])


def test_upgrade_from_pro_to_baller_by_tier_byte_fails():
    pk = Ed25519PrivateKey.generate()
    key, lic = K.sign(pk, "PRO")
    raw = bytearray(K.b32decode(K.normalize(key), K.KEY_LEN))
    raw[0] = (1 << 4) | K.TIERS["BALLER"]
    with pytest.raises(K.KeyError_):
        K.verify(pk.public_key(), K.format_key(bytes(raw)))


def test_expiry():
    pk = Ed25519PrivateKey.generate()
    today = dt.date(2026, 10, 8)
    key, lic = K.sign(pk, "CREW3", expires_days=35, today=today)
    assert K.verify(pk.public_key(), key, today=today + dt.timedelta(days=35)).tier == "CREW3"
    with pytest.raises(K.KeyError_):
        K.verify(pk.public_key(), key, today=today + dt.timedelta(days=36))


def test_issuer_dedupe_and_upgrade():
    with tempfile.TemporaryDirectory() as d:
        iss = Issuer(KeyDB(os.path.join(d, "k.db")), Ed25519PrivateKey.generate())
        assert iss.decide(1, None).action == "none"
        dec = iss.decide(1, "CREW2"); assert (dec.action, dec.tier) == ("issue", "CREW2")
        rid, key, lic = iss.issue("CREW2", 1, "a", "role")
        assert iss.decide(1, "CREW2").action == "resend"          # not delivered yet
        iss.db.mark_delivered(rid, True)
        assert iss.decide(1, "CREW2").action == "none"            # no duplicates
        assert iss.decide(1, "CREW2").tier == "CREW2"
        dec = iss.decide(1, "BALLER"); assert (dec.action, dec.tier) == ("issue", "BALLER")   # upgrade
        rid2, _, _ = iss.issue("BALLER", 1, "a", "role"); iss.db.mark_delivered(rid2, True)
        assert iss.decide(1, "CREW3").action == "none"            # downgrade keeps Baller key
        assert iss.db.best_for(1)["tier"] == "BALLER"
        assert Issuer.highest(["CREW2", None, "BALLER", "CREW3"]) == "BALLER"
        assert "Baller" in dm_text(key, "BALLER")


def test_issuer_renews_expiring_keys():
    with tempfile.TemporaryDirectory() as d:
        iss = Issuer(KeyDB(os.path.join(d, "k.db")), Ed25519PrivateKey.generate(), {"CREW2": 35})
        rid, key, lic = iss.issue("CREW2", 7, "b", "role"); iss.db.mark_delivered(rid, True)
        assert lic.expires_day == lic.issued_day + 35
        assert iss.decide(7, "CREW2", today_day=lic.issued_day + 10).action == "none"
        assert iss.decide(7, "CREW2", today_day=lic.issued_day + 31).action == "issue"


def test_key_file_roundtrip():
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "signing.key")
        K.create_private_key(p)
        assert oct(os.stat(p).st_mode & 0o777) == "0o400"
        with pytest.raises(FileExistsError):
            K.create_private_key(p)
        pk = K.load_private_key(p)
        key, _ = K.sign(pk, "PRO")
        assert K.verify(K.public_from_hex(K.public_hex(pk)), key).tier == "PRO"
