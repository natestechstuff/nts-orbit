package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Offline key check. The keys below are signed with a TEST key pair (seed 00 01 02 … 1f), never the
 * real one, so they don't unlock the app. -PorbitKeys=file runs real keys through the shipped public key.
 */
public class OrbitKeyTest {
    static final String TEST_PUB = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8";
    static final int DAY = 280;   // Oct 8, 2026
    static final String PRO = "ORBIT-248H24-8H04C0-0011XT-E02B14-7AWM2Y-N92BEP-PMMJHJ-KJQ8GE-SQ6CVQ-JKPP1K-EJWTJW-EFCAQT-7JEJJ3-K9BZ21-74GFJN-Q9ZS49-8EPF6Y-STJC2S-WS68XM-N04";
    static final String CREW2 = "ORBIT-28H248-H204C0-004VXX-9PRWT8-YBCHJ3-ZAC9J5-7ZEKHX-8N60DY-RN7GJR-6XTKFH-3EB7AF-N3DNW4-5NQ641-H31YP3-SCQX7F-YBR360-A0QHWD-SQ7T3R-AQNJ85-SGT";
    static final String CREW3 = "ORBIT-2CSK6C-SK04C0-006JPS-W5EB47-552CMN-3DBQHK-YX1B03-G4NAG7-797N0Q-AQ2FYK-CR1J3J-RCZZTN-X3C2K5-AQXMF8-DSECT7-D7JEK8-06QWX3-QXB7YV-PVHN03-D0R";
    static final String BALLER = "ORBIT-2H248H-2404C0-0023DM-CQZ8SC-22JSCQ-29DT8A-Y0P6F5-83F3Z3-8YN7DD-QE6VY9-0STCD6-PVDHEW-M8RX09-NY8JKV-ESBF36-VEVF9K-0356BJ-DEYTAS-8XDHX9-90G";
    /** CREW3, issued Oct 8 2026, valid 30 days */
    static final String EXPIRING = "ORBIT-2DANAN-AN04C0-2DQJB9-NFWEJ5-HX4DM0-1TS2G3-0GN6Q0-G92TFY-JNTBNR-MXRRPF-DEK1ZB-B7FYX5-CFFPY5-53EAM4-1G44QJ-20KQSD-YCZ2E2-WZNESE-SZW22M-90Y";

    static byte[] hex(String s) { return OrbitKey.hex(s); }

    @Test public void ed25519_rfc8032_vectors() {
        assertTrue(Ed25519.verify(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), new byte[0],
                hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")));
        assertTrue(Ed25519.verify(hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"), new byte[]{0x72},
                hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")));
        // wrong message / flipped bit
        assertFalse(Ed25519.verify(hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"), new byte[]{0x73},
                hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")));
        byte[] sig = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        sig[40] ^= 1;
        assertFalse(Ed25519.verify(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), new byte[0], sig));
        assertFalse(Ed25519.verify(new byte[31], new byte[0], new byte[64]));
        assertFalse(Ed25519.verify(new byte[32], new byte[0], new byte[63]));
    }

    @Test public void every_tier_verifies() {
        String[] keys = {PRO, CREW2, CREW3, BALLER};
        OrbitKey.Tier[] tiers = {OrbitKey.Tier.PRO, OrbitKey.Tier.CREW2, OrbitKey.Tier.CREW3, OrbitKey.Tier.BALLER};
        for (int i = 0; i < keys.length; i++) {
            OrbitKey.Result r = OrbitKey.check(keys[i], TEST_PUB, DAY);
            assertTrue(keys[i] + " " + r.error, r.ok());
            assertEquals(tiers[i], r.key.tier);
            assertEquals(DAY, r.key.issuedDay);
            assertEquals(0, r.key.expiresDay);
        }
        assertEquals("44444444", OrbitKey.check(BALLER, TEST_PUB, DAY).key.keyId);
    }

    @Test public void sloppy_typing_still_works() {
        String s = "  " + CREW2.toLowerCase().replace("-", " ").replace('0', 'o').replace('1', 'l') + "\n";
        assertTrue(OrbitKey.check(s, TEST_PUB, DAY).ok());
        assertTrue(OrbitKey.check(CREW2.substring("ORBIT-".length()), TEST_PUB, DAY).ok());
    }

    @Test public void tampered_keys_fail() {
        String body = OrbitKey.normalize(PRO);
        for (int i = 0; i < body.length(); i += 7) {
            char c = body.charAt(i);
            String bad = body.substring(0, i) + (c == 'A' ? 'B' : 'A') + body.substring(i + 1);
            assertFalse("pos " + i, OrbitKey.check(bad, TEST_PUB, DAY).ok());
        }
        assertFalse(OrbitKey.check(PRO.substring(0, PRO.length() - 1), TEST_PUB, DAY).ok());
        assertFalse(OrbitKey.check(PRO + "A", TEST_PUB, DAY).ok());
        assertFalse(OrbitKey.check("", TEST_PUB, DAY).ok());
        assertFalse(OrbitKey.check("hello", TEST_PUB, DAY).ok());
    }

    @Test public void pro_key_cannot_be_turned_into_baller() {
        byte[] raw = OrbitKey.decode(OrbitKey.normalize(PRO));
        raw[0] = (byte) ((1 << 4) | 4);
        StringBuilder b = new StringBuilder();
        java.math.BigInteger n = new java.math.BigInteger(1, raw).shiftLeft(OrbitKey.CHARS * 5 - OrbitKey.LEN * 8);
        String a = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        for (int i = OrbitKey.CHARS - 1; i >= 0; i--) b.append(a.charAt(n.shiftRight(5 * i).intValue() & 31));
        assertNotNull(OrbitKey.decode(b.toString()));
        assertFalse(OrbitKey.check(b.toString(), TEST_PUB, DAY).ok());
    }

    @Test public void test_keys_do_not_unlock_the_real_app() {
        assertFalse(OrbitKey.check(BALLER).ok());
        assertFalse(TEST_PUB.equals(OrbitKey.PUBLIC_KEY_HEX));
    }

    @Test public void expiry() {
        assertTrue(OrbitKey.check(EXPIRING, TEST_PUB, DAY + 30).ok());
        OrbitKey.Result r = OrbitKey.check(EXPIRING, TEST_PUB, DAY + 31);
        assertFalse(r.ok());
        assertTrue(r.error.contains("expired"));
    }

    @Test public void wrong_signer_fails() {
        assertFalse(OrbitKey.check(PRO, OrbitKey.PUBLIC_KEY_HEX, DAY).ok());
    }

    @Test public void day_epoch_matches_bot() {
        // 2026-01-01 is day 0 for both the bot and the app
        assertEquals(java.time.LocalDate.of(2026, 1, 1).toEpochDay(), OrbitKey.EPOCH_DAY);
        assertEquals("Oct 8, 2026", PremiumActivity.day(DAY));
    }

    /** Real keys made by the key bot's CLI (never committed): ./gradlew test -PorbitKeys=/path/keys.txt */
    @Test public void real_keys_from_the_bot() throws Exception {
        String path = System.getProperty("orbitKeys");
        Assume.assumeTrue(path != null && !path.isEmpty());
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        String[] want = {"PRO", "CREW2", "CREW3", "BALLER"};
        int n = 0;
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            OrbitKey.Result r = OrbitKey.check(l);
            assertTrue(l + " → " + r.error, r.ok());
            assertEquals(want[n % 4], r.key.tier.name());
            String body = OrbitKey.normalize(l);
            String bad = body.substring(0, 60) + (body.charAt(60) == 'Q' ? 'R' : 'Q') + body.substring(61);
            assertFalse(OrbitKey.check(bad).ok());
            n++;
        }
        assertEquals(4, n);
    }
}
