package com.natestechstuff.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * App screens to PNG (Robolectric native graphics). Only runs when asked:
 *   ./gradlew testDebugUnitTest -Pscreenshots=/path/to/out
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class PublicScreenshotTest {
    private File out;
    private Application app;

    @Before public void setUp() {
        String dir = System.getProperty("screenshots");
        Assume.assumeTrue("screenshots not requested", dir != null && !dir.isEmpty());
        out = new File(dir);
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ServerStore st = ServerStore.get(app);
        for (LlmServer s : st.all()) st.delete(s.id);
    }

    private void shoot(Activity a, String name, long advanceMs) throws Exception {
        ShadowLooper.idleMainLooper(advanceMs, java.util.concurrent.TimeUnit.MILLISECONDS);
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

    private void seedServers() {
        ServerStore st = ServerStore.get(app);
        LlmServer pc = st.save(new LlmServer("", "Gaming PC", "192.168.1.20:11434", ServerClient.AUTO, ServerClient.OLLAMA, "", "qwen2.5:7b"));
        st.save(new LlmServer("", "MacBook · LM Studio", "192.168.1.31:1234", ServerClient.OPENAI, "", "", "qwen2.5-7b-instruct"));
        st.setActive(pc.id);
    }

    @Test public void serverChat() throws Exception {
        seedServers();
        new Prefs(app).setMode(Prefs.MODE_SERVER);
        ChatStore s = ChatStore.get(app);
        s.clear();
        s.add(ChatStore.FROM_ME, "how do I undo my last git commit", "server");
        s.add(ChatStore.FROM_JARVIS, "Use **git reset** and keep your changes:\n```bash\ngit reset --soft HEAD~1\n```\n- already pushed? use `git revert` instead",
                "server · qwen2.5:7b · 38.4 tok/s · 41 tok");
        s.add(ChatStore.FROM_ME, "turn on the flashlight", "server");
        s.add(ChatStore.FROM_TOOL, new org.json.JSONObject().put("chip", "⚙ flashlight on").put("calls", "").put("responses", "").toString(), "server · tool");
        s.add(ChatStore.FROM_JARVIS, "Flashlight's on.", "server · instant");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shoot(c.get(), "p1_main_server", 800);
    }

    @Test public void serverSetupCard() throws Exception {
        new Prefs(app).setMode(Prefs.MODE_SERVER);
        ChatStore.get(app).clear();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shoot(c.get(), "p2_main_no_server_yet", 800);
    }

    @Test public void serverSettingsList() throws Exception {
        seedServers();
        ActivityController<ServerSettingsActivity> c = Robolectric.buildActivity(ServerSettingsActivity.class).setup();
        shoot(c.get(), "p3_server_settings_list", 600);
    }

    @Test public void serverSettingsEditor() throws Exception {
        ActivityController<ServerSettingsActivity> c = Robolectric.buildActivity(ServerSettingsActivity.class).setup();
        ServerSettingsActivity a = c.get();
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        // fill the form the way a person would: Ollama preset, an address, then a successful test
        android.widget.EditText url = findEdit(a.getWindow().getDecorView(), "192.168.1.20:11434");
        if (url != null) url.setText("192.168.1.20:11434");
        a.demoStatus("✓ connected: Ollama · 3 models · 42 ms", true);
        a.showModels(Arrays.asList("llama3.2:3b", "qwen2.5-coder:7b", "qwen2.5:7b"));
        shoot(a, "p4_server_settings_add", 600);
        // and the bottom of the editor: test buttons, result, save
        View sv = ((android.view.ViewGroup) a.getWindow().getDecorView().findViewById(android.R.id.content)).getChildAt(0);
        android.widget.ScrollView scroll = (android.widget.ScrollView) ((android.view.ViewGroup) sv).getChildAt(0);
        scroll.scrollTo(0, 100000);
        shoot(a, "p4b_server_settings_test", 300);
    }

    @Test public void settings() throws Exception {
        seedServers();
        new Prefs(app).setMode(Prefs.MODE_SERVER);
        ActivityController<SettingsActivity> c = Robolectric.buildActivity(SettingsActivity.class).setup();
        shoot(c.get(), "p5_settings", 600);
    }

    @Test public void onDeviceSetup() throws Exception {
        ChatStore.get(app).clear();
        Prefs p = new Prefs(app);
        p.setModelPath("");
        p.setMode(Prefs.MODE_LOCAL);
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shoot(c.get(), "p6_on_device_setup", 800);
    }

    private static android.widget.EditText findEdit(View v, String hint) {
        if (v instanceof android.widget.EditText) {
            CharSequence hh = ((android.widget.EditText) v).getHint();
            return hh != null && hint.contentEquals(hh) ? (android.widget.EditText) v : null;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText e = findEdit(g.getChildAt(i), hint);
                if (e != null) return e;
            }
        }
        return null;
    }
}
