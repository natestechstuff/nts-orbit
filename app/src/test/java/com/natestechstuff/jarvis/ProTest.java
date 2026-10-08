package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Premium: free by default, a good key unlocks Pro (and the Pro menu), a bad one changes nothing. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class ProTest {
    private Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Pro.resetForTests();
        Pro.remove(app);
        Pro.resetForTests();
    }

    @After public void tearDown() { Pro.remove(app); Pro.resetForTests(); }

    /** real keys from the key bot (PRO, CREW2, CREW3, BALLER), or skip */
    static List<String> realKeys() throws Exception {
        String path = System.getProperty("orbitKeys");
        Assume.assumeTrue("needs -PorbitKeys", path != null && !path.isEmpty());
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }

    @Test public void freeByDefault() {
        assertFalse(Pro.isPro(app));
        assertEquals(OrbitKey.Tier.FREE, Pro.tier(app));
        assertFalse(Dev.ON);
        // Pro menu doesn't open without a key
        Activity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Dev.open(a);
        assertNull(Shadows.shadowOf(a).getNextStartedActivity());
        // default feature behaviour untouched: every flag reads as the free app's default
        assertTrue(Dev.flag(app, "markdown"));
    }

    @Test public void badKeyChangesNothing() {
        OrbitKey.Result r = Pro.unlock(app, OrbitKeyTest.BALLER);   // test-signed: not valid for the real app
        assertFalse(r.ok());
        assertNotNull(r.error);
        assertFalse(Pro.isPro(app));
        assertFalse(Dev.ON);
        PremiumActivity p = Robolectric.buildActivity(PremiumActivity.class).setup().get();
        assertNotNull(p.tryUnlock("ORBIT-NOPE"));
        assertFalse(Pro.isPro(app));
    }

    @Test public void realKeysUnlockEachTier() throws Exception {
        List<String> keys = realKeys();
        OrbitKey.Tier[] want = {OrbitKey.Tier.PRO, OrbitKey.Tier.CREW2, OrbitKey.Tier.CREW3, OrbitKey.Tier.BALLER};
        for (int i = 0; i < 4; i++) {
            Pro.remove(app);
            OrbitKey.Result r = Pro.unlock(app, keys.get(i));
            assertTrue(r.error, r.ok());
            assertEquals(want[i], Pro.tier(app));
            assertTrue(Pro.isPro(app));
            assertTrue(Dev.ON);
            assertEquals(i == 3, Pro.isBaller(app));
            // survives a restart: re-read from prefs and re-verified
            Pro.resetForTests();
            assertEquals(want[i], Pro.tier(app));
        }
        // with Pro on, the Pro menu opens from Settings
        Activity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Dev.open(a);
        Intent next = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(Dev.MENU_CLASS, next.getComponent().getClassName());
        Pro.remove(app);
        assertFalse(Pro.isPro(app));
        assertFalse(Dev.ON);
    }

    @Test public void proMenuPagesBuild() throws Exception {
        List<String> keys = realKeys();
        assertTrue(Pro.unlock(app, keys.get(0)).ok());
        ProMenuActivity m = Robolectric.buildActivity(ProMenuActivity.class).setup().get();
        for (String[] p : ProMenuActivity.PAGES) m.showPage(p[0]);
        m.showPage("home");
        for (String[] p : ProMenuActivity.PAGES) {
            assertNotEquals("tab", p[0]);
            assertNotEquals("diag", p[0]);
            assertNotEquals("flags", p[0]);
        }
    }

    // ------------------------------------------------------------ screenshots (-Pscreenshots=dir)

    private void shoot(Activity a, String name) throws Exception {
        String dir = System.getProperty("screenshots");
        if (dir == null || dir.isEmpty()) return;
        File out = new File(dir);
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        View root = a.getWindow().getDecorView();
        int w = a.getResources().getDisplayMetrics().widthPixels;
        int h = a.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        try (FileOutputStream fo = new FileOutputStream(new File(out, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }

    @Test public void screens() throws Exception {
        Assume.assumeTrue(System.getProperty("screenshots") != null);
        shoot(Robolectric.buildActivity(SettingsActivity.class).setup().get(), "k1_settings_free");
        PremiumActivity p = Robolectric.buildActivity(PremiumActivity.class).setup().get();
        shoot(p, "k2_premium_enter_key");
        p.tryUnlock(OrbitKeyTest.PRO);
        shoot(p, "k3_premium_bad_key");
        List<String> keys = realKeys();
        assertTrue(Pro.unlock(app, keys.get(1)).ok());
        shoot(Robolectric.buildActivity(PremiumActivity.class).setup().get(), "k4_premium_crew2");
        shoot(Robolectric.buildActivity(SettingsActivity.class).setup().get(), "k5_settings_pro");
        Pro.remove(app);
        assertTrue(Pro.unlock(app, keys.get(3)).ok());
        shoot(Robolectric.buildActivity(PremiumActivity.class).setup().get(), "k6_premium_baller");
        ProMenuActivity m = Robolectric.buildActivity(ProMenuActivity.class).setup().get();
        shoot(m, "k7_pro_menu");
    }
}
