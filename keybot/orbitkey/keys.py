"""Key format (version 1). Must match app/src/main/java/.../OrbitKey.java.

A key is 73 bytes, shown as Crockford base32 in dash-separated groups:

    ORBIT-XXXXXX-XXXXXX-...   (117 base32 characters)

    byte 0      (version << 4) | tier          version = 1
    bytes 1-4   key id, big-endian uint32      random, unique per key
    bytes 5-6   issue day, big-endian uint16   days since 2026-01-01 (UTC)
    bytes 7-8   expiry day, big-endian uint16  days since 2026-01-01, 0 = never expires
    bytes 9-72  Ed25519 signature over  b"NTSORBIT-KEY1" + bytes 0-8

The app ships only the public key and checks the signature offline. The private key
(32-byte seed) stays on the machine that runs the bot.
"""
from __future__ import annotations

import datetime as _dt
import os
import secrets
import struct
from dataclasses import dataclass

VERSION = 1
DOMAIN = b"NTSORBIT-KEY1"
EPOCH = _dt.date(2026, 1, 1)
PAYLOAD_LEN = 9
SIG_LEN = 64
KEY_LEN = PAYLOAD_LEN + SIG_LEN
PREFIX = "ORBIT"
GROUP = 6

TIERS = {"PRO": 1, "CREW2": 2, "CREW3": 3, "BALLER": 4}
TIER_NAMES = {v: k for k, v in TIERS.items()}
# rank used for upgrades: higher number = higher tier
RANK = dict(TIERS)
PRETTY = {"PRO": "Pro", "CREW2": "Crew 2", "CREW3": "Crew 3", "BALLER": "Baller"}

_ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # Crockford
_DEC = {c: i for i, c in enumerate(_ALPHA)}
_DEC.update({"O": 0, "I": 1, "L": 1})


class KeyError_(ValueError):
    pass


def b32encode(data: bytes) -> str:
    n = int.from_bytes(data, "big")
    bits = len(data) * 8
    nchars = (bits + 4) // 5
    n <<= nchars * 5 - bits  # left-align, pad low bits with zeros
    out = []
    for i in range(nchars):
        out.append(_ALPHA[(n >> (5 * (nchars - 1 - i))) & 31])
    return "".join(out)


def b32decode(s: str, nbytes: int) -> bytes:
    nchars = (nbytes * 8 + 4) // 5
    if len(s) != nchars:
        raise KeyError_("wrong length")
    n = 0
    for ch in s:
        if ch not in _DEC:
            raise KeyError_("bad character " + ch)
        n = (n << 5) | _DEC[ch]
    pad = nchars * 5 - nbytes * 8
    if n & ((1 << pad) - 1):
        raise KeyError_("bad padding")
    return (n >> pad).to_bytes(nbytes, "big")


def day_number(d: _dt.date | None = None) -> int:
    d = d or _dt.datetime.now(_dt.timezone.utc).date()
    return (d - EPOCH).days


def day_to_date(n: int) -> _dt.date:
    return EPOCH + _dt.timedelta(days=n)


def format_key(raw: bytes) -> str:
    s = b32encode(raw)
    return PREFIX + "-" + "-".join(s[i:i + GROUP] for i in range(0, len(s), GROUP))


def normalize(text: str) -> str:
    t = "".join(ch for ch in text.upper() if ch.isalnum())
    if t.startswith(PREFIX):
        t = t[len(PREFIX):]
    return t


@dataclass(frozen=True)
class License:
    tier: str
    key_id: int
    issued_day: int
    expires_day: int  # 0 = never

    @property
    def key_id_hex(self) -> str:
        return "%08x" % self.key_id

    def payload(self) -> bytes:
        return bytes([(VERSION << 4) | TIERS[self.tier]]) + struct.pack(">IHH", self.key_id, self.issued_day, self.expires_day)


def sign(private_key, tier: str, expires_days: int = 0, key_id: int | None = None, today: _dt.date | None = None) -> tuple[str, License]:
    """private_key: cryptography Ed25519PrivateKey. Returns (formatted key, License)."""
    if tier not in TIERS:
        raise KeyError_("unknown tier " + tier)
    issued = day_number(today)
    lic = License(tier, key_id if key_id is not None else secrets.randbits(32), issued,
                  issued + expires_days if expires_days else 0)
    p = lic.payload()
    sig = private_key.sign(DOMAIN + p)
    return format_key(p + sig), lic


def parse(text: str) -> tuple[License, bytes, bytes]:
    raw = b32decode(normalize(text), KEY_LEN)
    p, sig = raw[:PAYLOAD_LEN], raw[PAYLOAD_LEN:]
    ver, tier = p[0] >> 4, p[0] & 15
    if ver != VERSION:
        raise KeyError_("unsupported key version")
    if tier not in TIER_NAMES:
        raise KeyError_("unknown tier")
    kid, issued, exp = struct.unpack(">IHH", p[1:])
    return License(TIER_NAMES[tier], kid, issued, exp), p, sig


def verify(public_key, text: str, today: _dt.date | None = None) -> License:
    """public_key: cryptography Ed25519PublicKey. Raises KeyError_ if invalid or expired."""
    from cryptography.exceptions import InvalidSignature
    lic, p, sig = parse(text)
    try:
        public_key.verify(sig, DOMAIN + p)
    except InvalidSignature:
        raise KeyError_("signature does not match")
    if lic.expires_day and day_number(today) > lic.expires_day:
        raise KeyError_("key expired")
    return lic


# ---------------------------------------------------------------- signing key file

def load_private_key(path: str):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    with open(path, "rb") as f:
        seed = bytes.fromhex(f.read().decode().strip())
    if len(seed) != 32:
        raise ValueError("signing key file must hold a 32-byte hex seed")
    return Ed25519PrivateKey.from_private_bytes(seed)


def create_private_key(path: str) -> None:
    """Writes a new random seed with mode 0400. Refuses to overwrite."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    k = Ed25519PrivateKey.generate()
    seed = k.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(fd, "w") as f:
        f.write(seed.hex() + "\n")


def public_hex(private_key) -> str:
    from cryptography.hazmat.primitives import serialization
    return private_key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()


def public_from_hex(h: str):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    return Ed25519PublicKey.from_public_bytes(bytes.fromhex(h))
