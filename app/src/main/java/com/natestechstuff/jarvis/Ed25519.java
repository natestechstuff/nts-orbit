package com.natestechstuff.jarvis;

import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * Ed25519 signature VERIFY only (RFC 8032), plain Java so it works on every Android version
 * the app supports (the platform only has Ed25519 on newer releases). Used to check Pro keys offline.
 * Not constant-time; that's fine here because it only ever handles public data.
 */
public final class Ed25519 {
    private Ed25519() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.multiply(TWO).mod(P);
    private static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] G;
    static {
        BigInteger gy = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger gx = recoverX(gy, 0);
        G = new BigInteger[]{gx, gy, BigInteger.ONE, gx.multiply(gy).mod(P)};
    }

    /** True only if {@code sig} is a valid Ed25519 signature of {@code msg} by {@code pub}. Never throws on bad input. */
    public static boolean verify(byte[] pub, byte[] msg, byte[] sig) {
        try {
            if (pub == null || sig == null || msg == null || pub.length != 32 || sig.length != 64) return false;
            BigInteger[] a = decompress(pub);
            byte[] rs = new byte[32];
            System.arraycopy(sig, 0, rs, 0, 32);
            BigInteger[] r = decompress(rs);
            if (a == null || r == null) return false;
            byte[] sb = new byte[32];
            System.arraycopy(sig, 32, sb, 0, 32);
            BigInteger s = le(sb);
            if (s.compareTo(L) >= 0) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(rs);
            md.update(pub);
            md.update(msg);
            BigInteger h = le(md.digest()).mod(L);
            BigInteger[] sB = mul(s, G);
            BigInteger[] hA = mul(h, a);
            return equal(sB, add(r, hA));
        } catch (Exception e) {
            return false;
        }
    }

    private static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length + 1];   // leading 0 = positive
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(D2).multiply(q[3]).mod(P);
        BigInteger d = p[2].multiply(TWO).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), hh = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(hh).mod(P), f.multiply(g).mod(P), e.multiply(hh).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] p) {
        BigInteger[] q = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (s.testBit(i)) q = add(q, p);
        }
        return q;
    }

    private static boolean equal(BigInteger[] p, BigInteger[] q) {
        return p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0
                && p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0;
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        if (y.compareTo(P) >= 0) return null;
        BigInteger y2 = y.multiply(y);
        BigInteger x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        if (x2.signum() == 0) return sign != 0 ? null : BigInteger.ZERO;
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null;
        if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x);
        return x;
    }

    private static BigInteger[] decompress(byte[] s) {
        if (s.length != 32) return null;
        BigInteger y = le(s);
        int sign = y.testBit(255) ? 1 : 0;
        y = y.clearBit(255);
        BigInteger x = recoverX(y, sign);
        if (x == null) return null;
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }
}
