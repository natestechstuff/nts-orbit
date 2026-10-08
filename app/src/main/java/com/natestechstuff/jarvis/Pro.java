package com.natestechstuff.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one place that says what this install unlocked. Gate features with {@link #isPro(Context)}
 * (every paid tier: Pro, Crew 2, Crew 3, Baller) or {@link #tier(Context)} for tier-specific extras.
 * The key text is stored and re-checked on every launch, so editing prefs alone unlocks nothing.
 */
public final class Pro {
    private static final String PREF_KEY = "license_key";
    private static volatile OrbitKey.Parsed cached;
    private static volatile boolean loaded;

    private Pro() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    /** Reads + verifies the saved key once per process (cheap after that). Also flips {@link Dev#ON}. */
    public static synchronized void init(Context c) {
        if (loaded) return;
        String k = sp(c).getString(PREF_KEY, "");
        OrbitKey.Result r = k.isEmpty() ? null : OrbitKey.check(k);
        cached = r != null && r.ok() ? r.key : null;
        loaded = true;
        Dev.ON = cached != null;
    }

    public static OrbitKey.Tier tier(Context c) {
        init(c);
        OrbitKey.Parsed p = cached;
        return p == null ? OrbitKey.Tier.FREE : p.tier;
    }

    /** True for every paid tier. */
    public static boolean isPro(Context c) { return tier(c) != OrbitKey.Tier.FREE; }

    public static boolean isBaller(Context c) { return tier(c) == OrbitKey.Tier.BALLER; }

    public static String tierLabel(Context c) { return tier(c).label; }

    /** The unlocked key's details, or null. */
    public static OrbitKey.Parsed key(Context c) { init(c); return cached; }

    /** Checks and saves a key. On failure nothing changes and {@code error} says why. */
    public static synchronized OrbitKey.Result unlock(Context c, String text) {
        OrbitKey.Result r = OrbitKey.check(text);
        if (r.ok()) {
            sp(c).edit().putString(PREF_KEY, OrbitKey.normalize(text)).apply();
            cached = r.key;
            loaded = true;
            Dev.ON = true;
        }
        return r;
    }

    /** Removes the key from this phone (Pro settings stay saved but go back to defaults in use). */
    public static synchronized void remove(Context c) {
        sp(c).edit().remove(PREF_KEY).apply();
        cached = null;
        loaded = true;
        Dev.ON = false;
    }

    /** Pretty, groupable form of the saved key (for "show my key"). */
    public static String savedKeyText(Context c) {
        String k = sp(c).getString(PREF_KEY, "");
        if (k.isEmpty()) return "";
        StringBuilder b = new StringBuilder("ORBIT");
        for (int i = 0; i < k.length(); i += 6) b.append('-').append(k, i, Math.min(k.length(), i + 6));
        return b.toString();
    }

    /** test hook */
    static synchronized void resetForTests() { cached = null; loaded = false; Dev.ON = false; }
}
