package com.natestechstuff.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pro settings and their hooks (the "Pro settings" menu, {@link ProMenuActivity}).
 * {@link #ON} is true only while a valid premium key is unlocked ({@link Pro}); when it's false
 * every {@code if (Dev.ON)} block is skipped and the app behaves exactly like the free version.
 * All values live in the normal "jarvis" SharedPreferences under "dev_*" keys and are read
 * fresh on every use, so changes take effect without a reinstall.
 */
public final class Dev {
    /** Pro unlocked on this phone (set by {@link Pro}; the menu and every hook below only run when true). */
    public static volatile boolean ON = false;
    public static final String MENU_CLASS = "com.natestechstuff.jarvis.ProMenuActivity";

    private Dev() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    static int i(Context c, String k, int d) { try { return sp(c).getInt(k, d); } catch (ClassCastException e) { return d; } }
    static float f(Context c, String k, float d) { try { return sp(c).getFloat(k, d); } catch (ClassCastException e) { return d; } }
    static long l(Context c, String k, long d) { try { return sp(c).getLong(k, d); } catch (ClassCastException e) { return d; } }
    static boolean b(Context c, String k, boolean d) { try { return sp(c).getBoolean(k, d); } catch (ClassCastException e) { return d; } }
    static String s(Context c, String k, String d) { try { return sp(c).getString(k, d); } catch (ClassCastException e) { return d; } }

    // ------------------------------------------------------------------ defaults (shown in the menu)

    public static final int DEF_TOP_K = 40, DEF_REPEAT_LAST_N = 64, DEF_BATCH = 512, DEF_SERVER_TIMEOUT_S = 180;
    public static final float DEF_TOP_P = 0.9f, DEF_MIN_P = 0.05f, DEF_REPEAT = 1.1f;
    public static final long DEF_SEED = -1;   // random

    /** Main-app keys the Pro menu also edits (reset to the app's defaults by "Reset all"). */
    public static final String[] SHARED_KEYS = {"model_temp", "model_ctx", "model_threads", "model_max_tokens",
            "model_system_prompt", "model_history", "tools_on", "speech_rate", "pitch"};

    // ------------------------------------------------------------------ model / inference

    public static int topK(Context c) { return i(c, "dev_top_k", DEF_TOP_K); }
    public static float topP(Context c) { return f(c, "dev_top_p", DEF_TOP_P); }
    public static float minP(Context c) { return f(c, "dev_min_p", DEF_MIN_P); }
    public static float repeatPenalty(Context c) { return f(c, "dev_repeat", DEF_REPEAT); }
    public static int repeatLastN(Context c) { return i(c, "dev_repeat_last_n", DEF_REPEAT_LAST_N); }
    public static long seed(Context c) { return l(c, "dev_seed", DEF_SEED); }
    public static int batch(Context c) { return i(c, "dev_batch", DEF_BATCH); }
    /** One stop string per line ("\n" written as \n). */
    public static List<String> stops(Context c) {
        List<String> out = new ArrayList<>();
        for (String line : s(c, "dev_stops", "").split("\n")) {
            if (line.trim().isEmpty()) continue;
            out.add(line.replace("\\n", "\n"));
        }
        return out;
    }

    // ------------------------------------------------------------------ thinking / thought process

    /** "Show thought process": OFF by default. Thinking is always stripped from the answer either way. */
    public static boolean showThinking(Context c) { return b(c, "dev_think_show", false); }
    /** Asks the model to reason inside <think></think> before answering (for models that don't on their own). */
    public static boolean forceThinking(Context c) { return b(c, "dev_think_force", false); }
    public static boolean thinkExpanded(Context c) { return b(c, "dev_think_expanded", false); }
    /** Keep thinking out of the chat history sent back to the model (default on). */
    public static boolean thinkOutOfHistory(Context c) { return b(c, "dev_think_out_of_history", true); }
    /** 0 = no limit. */
    public static int maxThinkTokens(Context c) { return i(c, "dev_think_max", 0); }

    public static final String FORCE_THINK_PROMPT =
            "Before every answer, think step by step inside <think></think> tags: what the user wants, what you know, "
            + "and whether a tool is needed. Keep the thinking short. After </think>, give only the final answer "
            + "(or the tool call).";

    // live thought process of the reply being generated right now (main thread)
    public interface ThinkListener { void onThink(String piece); }
    private static final StringBuilder pendingThink = new StringBuilder();
    private static long thinkStartMs, thinkEndMs;
    private static int thinkTokens;

    /** A new user message: the thought process starts empty. */
    public static synchronized void beginTurn() { pendingThink.setLength(0); thinkTokens = 0; thinkStartMs = 0; thinkEndMs = 0; }
    public static synchronized void think(String piece) {
        if (thinkStartMs == 0) thinkStartMs = System.currentTimeMillis();
        thinkEndMs = System.currentTimeMillis();
        thinkTokens++;
        pendingThink.append(piece);
    }
    /** A line in the thought panel that isn't model text, e.g. a tool decision. */
    public static synchronized void thinkNote(String line) {
        if (pendingThink.length() > 0 && pendingThink.charAt(pendingThink.length() - 1) != '\n') pendingThink.append('\n');
        pendingThink.append(line).append('\n');
    }
    public static synchronized String pendingThink() { return pendingThink.toString(); }
    public static synchronized String thinkHeader(boolean live) {
        double s = thinkStartMs == 0 ? 0 : (thinkEndMs - thinkStartMs) / 1000.0;
        if (live) return String.format(Locale.US, "thinking… %d tok · %.1f s", thinkTokens, s);
        return thinkTokens > 0 ? String.format(Locale.US, "thought for %.1f s · %d tok", s, thinkTokens) : "thought process";
    }
    /** Called by ChatStore when Orbit's reply is saved: the thought process goes with it. */
    public static synchronized String takePendingThink() {
        String t = pendingThink.toString().trim();
        String head = t.isEmpty() ? "" : thinkHeader(false);
        pendingThink.setLength(0);
        thinkTokens = 0; thinkStartMs = 0; thinkEndMs = 0;
        return t.isEmpty() ? "" : head + "\u0000" + t;
    }
    public static String thinkHeaderOf(String stored) { int k = stored.indexOf('\u0000'); return k < 0 ? "thought process" : stored.substring(0, k); }
    public static String thinkBodyOf(String stored) { int k = stored.indexOf('\u0000'); return k < 0 ? stored : stored.substring(k + 1); }

    /** What goes back to the model for one of Orbit's earlier replies. */
    public static String historyText(Context c, ChatStore.Item it) {
        if (!ON || it.think == null || it.think.isEmpty() || thinkOutOfHistory(c)) return it.text;
        return "<think>\n" + thinkBodyOf(it.think).trim() + "\n</think>\n" + it.text;
    }

    // ------------------------------------------------------------------ chat / debugging

    public static boolean liveStats(Context c) { return b(c, "dev_live_stats", true); }
    public static boolean showRaw(Context c) { return b(c, "dev_show_raw", false); }
    public static boolean showPrompt(Context c) { return b(c, "dev_show_prompt", false); }

    public static volatile String lastPrompt = "", lastRaw = "", lastParams = "", lastStats = "";
    public static volatile String lastServerRequest = "", lastServerResponse = "", lastServerUrl = "";
    private static final int MAX_CAPTURE = 64 * 1024;

    static String cap(String s) { return s.length() > MAX_CAPTURE ? s.substring(0, MAX_CAPTURE) + "\n… (cut at 64 KB)" : s; }

    /** The prompt exactly as the on-device model reads it (Qwen ChatML, see jarvis_llm.cpp chatml()). */
    public static String chatml(String[] roles, String[] contents) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < roles.length; k++)
            b.append("<|im_start|>").append(roles[k]).append('\n').append(contents[k]).append("<|im_end|>\n");
        b.append("<|im_start|>assistant\n");
        return b.toString();
    }

    public static String liveLine(int tokens, long t0, int think) {
        double secs = Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);
        return String.format(Locale.US, "⏱ %d tok · %.1f tok/s · %.1f s%s", tokens, tokens / secs, secs,
                think > 0 ? " · think " + think : "");
    }

    // ------------------------------------------------------------------ server

    public static int serverTimeoutMs(Context c) { return Math.max(5, i(c, "dev_server_timeout_s", DEF_SERVER_TIMEOUT_S)) * 1000; }
    public static boolean serverStream(Context c) { return b(c, "dev_server_stream", true); }
    /** Send the Pro sampling settings (top-p, top-k, seed, stops …) to servers too. */
    public static boolean serverSendSampling(Context c) { return b(c, "dev_server_sampling", false); }

    public static JSONObject serverOptions(Context c) {
        if (!serverSendSampling(c)) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("top_p", (double) topP(c));
            o.put("top_k", topK(c));
            o.put("min_p", (double) minP(c));
            o.put("repeat_penalty", (double) repeatPenalty(c));
            if (seed(c) >= 0) o.put("seed", seed(c));
            List<String> st = stops(c);
            if (!st.isEmpty()) o.put("stop", new JSONArray(st));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tools

    public static final String[] TOOLS = {ToolCalls.TIMER, ToolCalls.ALARM, ToolCalls.FLASHLIGHT, ToolCalls.OPEN_APP, ToolCalls.TIME, ToolCalls.BATTERY};
    public static boolean toolEnabled(Context c, String name) { return b(c, "dev_tool_" + name, true); }
    public static boolean dryRun(Context c) { return b(c, "dev_tools_dry_run", false); }

    /** Removes disabled tools from the <tools> block of a system prompt. */
    public static String filterToolPrompt(Context c, String system) {
        if (!ON || system == null || !system.contains("<tools>")) return system;
        StringBuilder out = new StringBuilder();
        for (String line : system.split("\n", -1)) {
            boolean drop = false;
            if (line.startsWith("{\"type\": \"function\"")) {
                for (String t : TOOLS) if (line.contains("\"name\": \"" + t + "\"") && !toolEnabled(c, t)) drop = true;
            }
            if (!drop) out.append(line).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /** The phone's actions as the Pro menu wants them: some off, or all "dry run" (shown, not done). */
    public static ToolDispatcher.Actions actions(Context c, ToolDispatcher.Actions real) {
        if (!ON) return real;
        return new DevActions(c.getApplicationContext(), real);
    }

    static final class DevActions implements ToolDispatcher.Actions {
        private final Context c;
        private final ToolDispatcher.Actions real;
        DevActions(Context c, ToolDispatcher.Actions real) { this.c = c; this.real = real; }
        private String off(String t) { return toolEnabled(c, t) ? null : t + " is turned off in the Pro menu."; }
        private void dry(String what) { thinkNote("dry run · not executed: " + what); }

        @Override public String timeDate() {
            // reading the clock changes nothing, so dry run still answers
            if (off(ToolCalls.TIME) != null) return "unavailable (" + off(ToolCalls.TIME) + ")";
            return real.timeDate();
        }
        @Override public int[] battery() {
            if (off(ToolCalls.BATTERY) != null) return null;
            return real.battery();
        }
        @Override public String setTimer(int seconds, String label) {
            if (off(ToolCalls.TIMER) != null) return off(ToolCalls.TIMER);
            if (dryRun(c)) { dry("set_timer " + seconds + " s" + (label == null || label.isEmpty() ? "" : " \"" + label + "\"")); return null; }
            return real.setTimer(seconds, label);
        }
        @Override public String setAlarm(int hour, int minute, String label) {
            if (off(ToolCalls.ALARM) != null) return off(ToolCalls.ALARM);
            if (dryRun(c)) { dry(String.format(Locale.US, "set_alarm %02d:%02d", hour, minute)); return null; }
            return real.setAlarm(hour, minute, label);
        }
        @Override public String openApp(String name) {
            if (!toolEnabled(c, ToolCalls.OPEN_APP)) return null;
            if (dryRun(c)) { dry("open_app " + name); return name + " (dry run)"; }
            return real.openApp(name);
        }
        @Override public String flashlight(boolean on) {
            if (off(ToolCalls.FLASHLIGHT) != null) return off(ToolCalls.FLASHLIGHT);
            if (dryRun(c)) { dry("flashlight " + (on ? "on" : "off")); return null; }
            return real.flashlight(on);
        }
    }

    // ------------------------------------------------------------------ voice / wake phrase

    public static boolean wakeOn(Context c) { return b(c, "dev_wake_on", false); }
    public static String wakePhrase(Context c) { String p = s(c, "dev_wake_phrase", "orbit").trim(); return p.isEmpty() ? "orbit" : p; }
    /** 0 = strict (must start with the phrase), 1 = normal (anywhere, 1 typo), 2 = loose (anywhere, 2 typos). */
    public static int wakeSensitivity(Context c) { return i(c, "dev_wake_sens", 1); }

    /**
     * Hands-free wake phrase: returns what to send (the words after the phrase), or null to ignore the
     * utterance. "hey orbit set a timer" → "set a timer".
     */
    public static String wakeFilter(String said, String phrase, int sensitivity) {
        if (said == null) return null;
        String[] words = said.trim().split("\\s+");
        String[] want = phrase.toLowerCase(Locale.US).trim().split("\\s+");
        int lastStart = sensitivity <= 0 ? Math.min(1, words.length - want.length) : words.length - want.length;  // strict: first word, or after "hey"/"ok"
        for (int s = 0; s <= lastStart; s++) {
            if (sensitivity <= 0 && s == 1 && !isGreeting(words[0])) break;
            boolean ok = true;
            for (int k = 0; k < want.length && ok; k++) {
                String w = norm(words[s + k]);
                int allowed = sensitivity <= 0 ? 0 : sensitivity == 1 ? (want[k].length() >= 4 ? 1 : 0) : (want[k].length() >= 6 ? 2 : 1);
                ok = edits(w, want[k]) <= allowed;
            }
            if (ok) {
                StringBuilder rest = new StringBuilder();
                for (int k = s + want.length; k < words.length; k++) rest.append(rest.length() == 0 ? "" : " ").append(words[k]);
                String r = rest.toString().replaceAll("^[,.!?:;\\s]+", "").trim();
                return r;
            }
        }
        return null;
    }
    private static boolean isGreeting(String w) { w = norm(w); return w.equals("hey") || w.equals("ok") || w.equals("okay") || w.equals("yo") || w.equals("hi"); }
    private static String norm(String w) { return w.toLowerCase(Locale.US).replaceAll("[^a-z0-9']", ""); }
    static int edits(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i2 = 1; i2 <= a.length(); i2++) {
            cur[0] = i2;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i2 - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // ------------------------------------------------------------------ UI

    public static final String[] THEMES = {"default", "amoled", "midnight", "warm"};
    public static final String[] ACCENTS = {"lime", "teal", "blue", "coral", "pink", "purple", "amber"};
    private static int[] orig;   // PAPER, CREAM, PANEL, LINE, SHADOW, LIME
    private static String applied = "";

    public static String theme(Context c) { return s(c, "dev_theme", "default"); }
    public static String accent(Context c) { return s(c, "dev_accent", "lime"); }
    public static float fontScale(Context c) { return f(c, "dev_font_scale", 1.0f); }
    public static boolean animations(Context c) { return !ON || b(c, "dev_animations", true); }
    public static boolean overlay(Context c) { return b(c, "dev_overlay", false); }

    /** Applies the theme/accent override to Ui's colors. Returns true if anything changed since last time. */
    public static boolean applyTheme(Context c) {
        if (!ON) return false;
        if (orig == null) orig = new int[]{Ui.PAPER, Ui.CREAM, Ui.PANEL, Ui.LINE, Ui.SHADOW, Ui.LIME};
        String key = theme(c) + "|" + accent(c);
        if (key.equals(applied)) return false;
        applied = key;
        int[] t = orig.clone();
        switch (theme(c)) {
            case "amoled": t = new int[]{0xff000000, 0xff0c110f, 0xff141d19, 0xff26322c, 0xff18211d, t[5]}; break;
            case "midnight": t = new int[]{0xff0f1424, 0xff161d33, 0xff1e2742, 0xff2e3a5c, 0xff222b45, t[5]}; break;
            case "warm": t = new int[]{0xff1b1612, 0xff251e18, 0xff33291f, 0xff4a3c2f, 0xff2e251c, t[5]}; break;
            default: break;
        }
        switch (accent(c)) {
            case "teal": t[5] = 0xff55c5b1; break;
            case "blue": t[5] = 0xff83aaff; break;
            case "coral": t[5] = 0xfff48b70; break;
            case "pink": t[5] = 0xffff7eb6; break;
            case "purple": t[5] = 0xffb58cff; break;
            case "amber": t[5] = 0xffffc857; break;
            default: break;
        }
        Ui.PAPER = t[0]; Ui.CREAM = t[1]; Ui.PANEL = t[2]; Ui.LINE = t[3]; Ui.SHADOW = t[4]; Ui.LIME = t[5];
        return true;
    }

    /** attachBaseContext hook: the Pro font size. */
    public static Context wrap(Context base) {
        if (!ON) return base;
        float scale = base.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getFloat("dev_font_scale", 1.0f);
        if (Math.abs(scale - 1f) < 0.01f) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = cfg.fontScale * scale;
        return base.createConfigurationContext(cfg);
    }

    /** Opens the Pro menu (only while Pro is unlocked). */
    public static void open(Context c) {
        if (!ON) return;
        Intent i = new Intent().setClassName(c.getPackageName(), MENU_CLASS);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Tap the logo/title 7 times (or long-press it) to open the Pro menu. */
    public static void secretEntry(View... views) {
        if (!ON) return;
        final int[] taps = {0};
        final long[] last = {0};
        View.OnClickListener l = v -> {
            long now = System.currentTimeMillis();
            if (now - last[0] > 1500) taps[0] = 0;
            last[0] = now;
            taps[0]++;
            int left = 7 - taps[0];
            if (left <= 0) { taps[0] = 0; open(v.getContext()); }
            else if (left <= 4) Toast.makeText(v.getContext(), left + (left == 1 ? " tap" : " taps") + " away from Pro settings", Toast.LENGTH_SHORT).show();
        };
        for (View v : views) {
            v.setOnClickListener(l);
            v.setOnLongClickListener(x -> { open(x.getContext()); return true; });
        }
    }

    // ------------------------------------------------------------------ FPS / memory overlay

    private static final String OVERLAY_TAG = "dev_overlay";

    /** onResume hook: shows or removes the FPS/memory overlay. */
    public static void onResume(Activity a) {
        if (!ON) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View old = decor.findViewWithTag(OVERLAY_TAG);
        if (!overlay(a)) { if (old != null) decor.removeView(old); return; }
        if (old != null) return;
        final TextView tv = new TextView(a);
        tv.setTag(OVERLAY_TAG);
        tv.setTextSize(10);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(Color.parseColor("#c9ec72"));
        tv.setBackgroundColor(0xcc000000);
        tv.setPadding(12, 6, 12, 6);
        tv.setClickable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.topMargin = (int) (a.getResources().getDisplayMetrics().density * 34);
        decor.addView(tv, lp);
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) {   // JVM screenshot tests: no real vsync, so no frame loop
            Runtime rt = Runtime.getRuntime();
            tv.setText(String.format(Locale.US, "60 fps · java %d/%d MB · native %d MB", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, Debug.getNativeHeapAllocatedSize() >> 20));
            return;
        }
        final int[] frames = {0};
        final long[] t0 = {System.nanoTime()};
        final Handler h = new Handler(Looper.getMainLooper());
        Choreographer.FrameCallback fc = new Choreographer.FrameCallback() {
            @Override public void doFrame(long ns) {
                if (tv.getParent() == null) return;
                frames[0]++;
                long dt = ns - t0[0];
                if (dt >= 1_000_000_000L) {
                    double fps = frames[0] * 1e9 / dt;
                    frames[0] = 0; t0[0] = ns;
                    Runtime rt = Runtime.getRuntime();
                    long javaUsed = (rt.totalMemory() - rt.freeMemory()) >> 20;
                    long nat = Debug.getNativeHeapAllocatedSize() >> 20;
                    tv.setText(String.format(Locale.US, "%.0f fps · java %d/%d MB · native %d MB", fps, javaUsed, rt.maxMemory() >> 20, nat));
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        Choreographer.getInstance().postFrameCallback(fc);
        // keeps frames coming even when nothing animates, so the counter shows real idle fps
        h.post(new Runnable() { @Override public void run() { if (tv.getParent() == null) return; tv.invalidate(); h.postDelayed(this, 16); } });
    }

    // ------------------------------------------------------------------ feature flags

    /** Experimental code paths, all on by default (= what the public app does). */
    public static final String[][] FLAGS = {
            {"router", "Instant phone actions (\"set a 5 min timer\" skips the model)"},
            {"fake_claim_guard", "Fake-claim guard (block \"I turned on the flashlight\" without a tool call)"},
            {"prewarm", "Prewarm the tool prompt into the KV cache after loading"},
            {"markdown", "Markdown in Orbit's bubbles"},
            {"server_think_filter", "Hide <think> blocks from server replies"},
            {"speak_while_streaming", "Start speaking before the reply is finished"},
    };
    public static boolean flag(Context c, String name) { return !ON || b(c, "dev_flag_" + name, true); }

    // ------------------------------------------------------------------ reset

    public static void resetAll(Context c) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("dev_")) e.remove(k);
        for (String k : SHARED_KEYS) e.remove(k);
        e.apply();
    }
}
