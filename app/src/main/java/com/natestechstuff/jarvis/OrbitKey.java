package com.natestechstuff.jarvis;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * NTS Orbit premium keys, checked fully offline.
 *
 * A key is 73 bytes shown as Crockford base32 in groups: ORBIT-XXXXXX-XXXXXX-… (117 characters).
 *   byte 0      (version << 4) | tier       version 1 · tier 1 PRO, 2 CREW2, 3 CREW3, 4 BALLER
 *   bytes 1-4   key id (big-endian)
 *   bytes 5-6   issue day   (days since 2026-01-01, UTC)
 *   bytes 7-8   expiry day  (same scale, 0 = never)
 *   bytes 9-72  Ed25519 signature of "NTSORBIT-KEY1" + bytes 0-8
 * Keys are signed by the key bot with a private key that never leaves its machine; the app only has
 * the public key below. Same format as keybot/orbitkey/keys.py.
 */
public final class OrbitKey {
    /** Public half of the signing key (safe to publish). */
    public static final String PUBLIC_KEY_HEX = "490ce4d07e1fd0101e2b38e4ac471a365269d15b6e5134726ca156f68efa953e";
    /** Key ids that no longer unlock (leaked keys). Hex, lower case. */
    static final String[] REVOKED = {};

    public enum Tier {
        FREE(0, "Free"), PRO(1, "Pro"), CREW2(2, "Crew 2"), CREW3(3, "Crew 3"), BALLER(4, "Baller");
        public final int code;
        public final String label;
        Tier(int code, String label) { this.code = code; this.label = label; }
        static Tier of(int code) {
            for (Tier t : values()) if (t.code == code && t != FREE) return t;
            return null;
        }
    }

    static final int VERSION = 1, PAYLOAD = 9, SIG = 64, LEN = PAYLOAD + SIG, CHARS = (LEN * 8 + 4) / 5;
    static final byte[] DOMAIN = "NTSORBIT-KEY1".getBytes(StandardCharsets.US_ASCII);
    private static final String ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    /** days between 1970-01-01 and 2026-01-01 */
    static final long EPOCH_DAY = 20454;

    /** What a key says, once its signature checked out. */
    public static final class Parsed {
        public final Tier tier;
        public final String keyId;
        public final int issuedDay, expiresDay;
        Parsed(Tier tier, String keyId, int issuedDay, int expiresDay) {
            this.tier = tier; this.keyId = keyId; this.issuedDay = issuedDay; this.expiresDay = expiresDay;
        }
    }

    /** Result of checking a key: either {@link #key} is set, or {@link #error} says why not (for the user). */
    public static final class Result {
        public final Parsed key;
        public final String error;
        Result(Parsed key, String error) { this.key = key; this.error = error; }
        public boolean ok() { return key != null; }
    }

    private OrbitKey() {}

    /** Uppercase, drop spaces/dashes/the ORBIT prefix, fix look-alike letters. */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase(Locale.US).toCharArray()) if (Character.isLetterOrDigit(c) && c < 128) b.append(c);
        String t = b.toString();
        if (t.startsWith("ORBIT")) t = t.substring(5);
        return t;
    }

    static byte[] decode(String body) {
        if (body.length() != CHARS) return null;
        BigInteger n = BigInteger.ZERO;
        for (char c : body.toCharArray()) {
            int v;
            if (c == 'O') v = 0;
            else if (c == 'I' || c == 'L') v = 1;
            else v = ALPHA.indexOf(c);
            if (v < 0) return null;
            n = n.shiftLeft(5).or(BigInteger.valueOf(v));
        }
        int pad = CHARS * 5 - LEN * 8;
        if (n.and(BigInteger.ONE.shiftLeft(pad).subtract(BigInteger.ONE)).signum() != 0) return null;
        byte[] raw = n.shiftRight(pad).toByteArray();
        byte[] out = new byte[LEN];
        int copy = Math.min(raw.length, LEN);
        System.arraycopy(raw, raw.length - copy, out, LEN - copy, copy);
        return out;
    }

    static int todayDay() { return (int) (System.currentTimeMillis() / 86_400_000L - EPOCH_DAY); }

    public static Result check(String text) { return check(text, PUBLIC_KEY_HEX, todayDay()); }

    static Result check(String text, String publicKeyHex, int today) {
        String body = normalize(text);
        if (body.isEmpty()) return new Result(null, "Paste your key first.");
        if (body.length() != CHARS) return new Result(null, "That key is " + (body.length() < CHARS ? "too short" : "too long") + ". Paste the whole thing.");
        byte[] raw = decode(body);
        if (raw == null) return new Result(null, "That doesn't look like an Orbit key.");
        int ver = (raw[0] & 0xff) >> 4;
        Tier tier = Tier.of(raw[0] & 0x0f);
        if (ver != VERSION || tier == null) return new Result(null, "This key is for a different version of Orbit.");
        byte[] payload = new byte[DOMAIN.length + PAYLOAD];
        System.arraycopy(DOMAIN, 0, payload, 0, DOMAIN.length);
        System.arraycopy(raw, 0, payload, DOMAIN.length, PAYLOAD);
        byte[] sig = new byte[SIG];
        System.arraycopy(raw, PAYLOAD, sig, 0, SIG);
        if (!Ed25519.verify(hex(publicKeyHex), payload, sig)) return new Result(null, "That key isn't valid. Check for a typo, or use /mykey in the Discord to see it again.");
        String id = String.format(Locale.US, "%02x%02x%02x%02x", raw[1], raw[2], raw[3], raw[4]);
        for (String r : REVOKED) if (r.equals(id)) return new Result(null, "This key was turned off. Ask in the Discord for a new one.");
        int issued = ((raw[5] & 0xff) << 8) | (raw[6] & 0xff);
        int expires = ((raw[7] & 0xff) << 8) | (raw[8] & 0xff);
        if (expires != 0 && today > expires) return new Result(null, "This key has expired. Run /mykey in the Discord for a fresh one.");
        return new Result(new Parsed(tier, id, issued, expires), null);
    }

    static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
