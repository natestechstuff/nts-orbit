package com.natestechstuff.jarvis;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * NTS Orbit Pro: the Pro settings menu. Settings → Pro settings, or tap the Orbit logo 7×.
 * Only opens while a Pro key is unlocked (see {@link License}).
 * Everything here is saved right away (SharedPreferences) and used on the next reply,
 * the next model load or the next time a screen opens — no reinstall.
 */
public class ProMenuActivity extends Activity {
    public static final String EXTRA_PAGE = "page";
    static final int REQ_GGUF = 41, REQ_EXPORT_JSON = 42, REQ_EXPORT_TXT = 43, REQ_IMPORT = 44;

    static final String[][] PAGES = {
            {"model", "Model & inference", "sampling · prompt · context · threads · seed · stops · GGUF"},
            {"thinking", "Thought process", "show thinking · force thinking · limits"},
            {"server", "Server", "URL · key · model · timeout · stream"},
            {"chat", "Chat", "live tok/s · export / import history"},
            {"tools", "Tools", "turn each phone action on or off"},
            {"voice", "Voice & wake", "wake phrase · sensitivity · TTS · mic test"},
            {"ui", "Look", "theme · accent · font size · animations"},
            {"bench", "Benchmark", "speed test on the current brain"},
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private LinearLayout col;
    private ScrollView scroll;
    private String page = "home";
    private SpeechRecognizer micTest;

    @Override
    protected void attachBaseContext(Context base) { super.attachBaseContext(Dev.wrap(base)); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dev.applyTheme(this);
        sp = Dev.sp(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GridDrawable(Ui.dp(this, 42)));
        root.setFitsSystemWindows(true);
        scroll = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        col.setPadding(pad, pad, pad, Ui.dp(this, 48));
        scroll.addView(col);
        root.addView(scroll);
        setContentView(root);
        String p = b != null ? b.getString(EXTRA_PAGE) : getIntent().getStringExtra(EXTRA_PAGE);
        showPage(p == null ? "home" : p);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString(EXTRA_PAGE, page); }

    @Override
    protected void onResume() { super.onResume(); Dev.onResume(this); }

    @Override
    protected void onDestroy() {
        stopMicTest();
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!"home".equals(page)) { showPage("home"); return; }
        super.onBackPressed();
    }

    /** Builds one page of the menu (public for the screenshot tests). */
    public void showPage(String p) {
        stopMicTest();
        page = p;
        col.removeAllViews();
        switch (p) {
            case "model": pageModel(); break;
            case "thinking": pageThinking(); break;
            case "server": pageServer(); break;
            case "chat": pageChat(); break;
            case "tools": pageTools(); break;
            case "voice": pageVoice(); break;
            case "ui": pageUi(); break;
            case "bench": pageBench(); break;
            default: page = "home"; pageHome();
        }
        scroll.scrollTo(0, 0);
    }

    // ================================================================== building blocks

    private void title(String t, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (!"home".equals(page)) {
            TextView back = Ui.chip(this, "← back", Ui.MUTED);
            back.setOnClickListener(x -> showPage("home"));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.rightMargin = Ui.dp(this, 10);
            row.addView(back, blp);
        }
        TextView tt = new TextView(this);
        tt.setText(t);
        tt.setTypeface(Ui.groteskBold(this));
        tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, "home".equals(page) ? 28 : 24);
        tt.setTextColor(Ui.INK);
        row.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = Ui.chip(this, "pro", Ui.LIME);
        row.addView(badge);
        col.addView(row);
        if (sub != null) {
            TextView s = mono(col, sub);
            s.setPadding(0, Ui.dp(this, 6), 0, 0);
        }
    }

    private LinearLayout section(String name, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.box(Ui.CREAM, Ui.LINE, 1, 16, this));
        card.addView(Ui.chip(this, name, color), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        col.addView(card, lp);
        return card;
    }

    private TextView label(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.grotesk(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 5));
        parent.addView(t);
        return t;
    }

    private TextView mono(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.mono(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(Ui.MUTED);
        t.setTextIsSelectable(true);
        parent.addView(t);
        return t;
    }

    /** A selectable monospace block (raw JSON, logs, prompts). */
    private TextView block(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.mono(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTextColor(Ui.INK);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        t.setBackground(Ui.box(Ui.PAPER, Ui.LINE, 1, 10, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 8);
        parent.addView(t, lp);
        return t;
    }

    private EditText edit(LinearLayout parent, String value, int type, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setTypeface(Ui.mono(this));
        e.setTextColor(Ui.INK);
        e.setHintTextColor(Ui.withAlpha(Ui.MUTED, 0x99));
        if (hint != null) e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        e.setBackground(Ui.box(Ui.PAPER, Ui.LINE, 1, 12, this));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private interface Saver { boolean save(String text); }

    private EditText live(EditText e, Saver s) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b2, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b2, int d) {}
            @Override public void afterTextChanged(Editable ed) {
                boolean ok = s.save(ed.toString().trim());
                e.setBackground(Ui.box(Ui.PAPER, ok ? Ui.LINE : Ui.CORAL, 1, 12, ProMenuActivity.this));
            }
        });
        return e;
    }

    private static final int NUM = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    private static final int MULTI = TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;

    /** Two number fields side by side read better than one long column. */
    private LinearLayout pair(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private LinearLayout cell(LinearLayout row) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 10);
        row.addView(c, lp);
        return c;
    }

    private void floatField(LinearLayout parent, String labelText, String key, float def, float min, float max) {
        label(parent, labelText);
        float cur = sp.getFloat(key, def);
        live(edit(parent, fmt(cur), NUM, "default " + fmt(def)), t -> {
            if (t.isEmpty()) { sp.edit().remove(key).apply(); return true; }
            try {
                float v = Float.parseFloat(t);
                if (v < min || v > max) return false;
                sp.edit().putFloat(key, v).apply();
                return true;
            } catch (NumberFormatException e) { return false; }
        });
    }

    private void intField(LinearLayout parent, String labelText, String key, int def, int min, int max) {
        label(parent, labelText);
        int cur = sp.getInt(key, def);
        live(edit(parent, String.valueOf(cur), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED, "default " + def), t -> {
            if (t.isEmpty()) { sp.edit().remove(key).apply(); return true; }
            try {
                int v = Integer.parseInt(t);
                if (v < min || v > max) return false;
                sp.edit().putInt(key, v).apply();
                return true;
            } catch (NumberFormatException e) { return false; }
        });
    }

    private static String fmt(float f) {
        String s = String.format(Locale.US, "%.3f", f);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length() - 1);
        return s;
    }

    private Switch toggle(LinearLayout parent, String labelText, String key, boolean def, Runnable after) {
        Switch s = new Switch(this);
        s.setText(labelText);
        s.setTypeface(Ui.grotesk(this));
        s.setTextColor(Ui.INK);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        s.setChecked(sp.getBoolean(key, def));
        s.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        s.setOnCheckedChangeListener((b, on) -> { sp.edit().putBoolean(key, on).apply(); if (after != null) after.run(); });
        parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private void choice(LinearLayout parent, String labelText, String key, String[] values, String[] labels, String def, Runnable after) {
        label(parent, labelText);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        parent.addView(hs);
        List<TextView> chips = new ArrayList<>();
        String cur = sp.getString(key, def);
        for (int k = 0; k < values.length; k++) {
            final String v = values[k];
            TextView c = Ui.chip(this, labels[k], v.equals(cur) ? Ui.LIME : Ui.MUTED);
            c.setOnClickListener(x -> {
                sp.edit().putString(key, v).apply();
                for (int j = 0; j < chips.size(); j++) Ui.styleChip(chips.get(j), values[j].equals(v) ? Ui.LIME : Ui.MUTED);
                if (after != null) after.run();
            });
            chips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (k > 0) lp.leftMargin = Ui.dp(this, 6);
            row.addView(c, lp);
        }
    }

    private void intChoice(LinearLayout parent, String labelText, String key, int[] values, int def) {
        label(parent, labelText);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        parent.addView(hs);
        List<TextView> chips = new ArrayList<>();
        int cur = sp.getInt(key, def);
        for (int k = 0; k < values.length; k++) {
            final int v = values[k];
            TextView c = Ui.chip(this, String.valueOf(v), v == cur ? Ui.LIME : Ui.MUTED);
            c.setOnClickListener(x -> {
                sp.edit().putInt(key, v).apply();
                for (int j = 0; j < chips.size(); j++) Ui.styleChip(chips.get(j), values[j] == v ? Ui.LIME : Ui.MUTED);
            });
            chips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (k > 0) lp.leftMargin = Ui.dp(this, 6);
            row.addView(c, lp);
        }
    }

    private LinearLayout buttons(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 12);
        parent.addView(hs, lp);
        return row;
    }

    private TextView button(LinearLayout row, String text, int color, View.OnClickListener l) {
        TextView c = Ui.chip(this, text, color);
        c.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 8);
        row.addView(c, lp);
        return c;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void copy(String what, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(what, text));
        toast(what + " copied");
    }

    private void share(String subject, String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text);
        try { startActivity(Intent.createChooser(i, subject)); } catch (Exception e) { toast("Nothing to share with"); }
    }

    private void confirm(String q, String yes, Runnable r) {
        new AlertDialog.Builder(this).setMessage(q).setPositiveButton(yes, (d, w) -> r.run()).setNegativeButton("cancel", null).show();
    }

    // ================================================================== home

    private void pageHome() {
        title("Pro settings", "nts orbit v" + BuildConfig.VERSION_NAME + " · " + Pro.tierLabel(this)
                + "\nchanges save instantly and apply on the next reply / model load");
        for (String[] p : PAGES) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 13));
            card.setBackground(Ui.box(Ui.CREAM, Ui.LINE, 1, 14, this));
            TextView t = new TextView(this);
            t.setText(p[1] + "  →");
            t.setTypeface(Ui.groteskMedium(this));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTextColor(Ui.INK);
            card.addView(t);
            TextView s = new TextView(this);
            s.setText(p[2]);
            s.setTypeface(Ui.mono(this));
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            s.setTextColor(Ui.MUTED);
            card.addView(s);
            final String id = p[0];
            card.setOnClickListener(x -> showPage(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 10);
            col.addView(card, lp);
        }
        LinearLayout r = section("reset", Ui.CORAL);
        mono(r, "Puts every Pro setting back to the app's defaults (sampling, prompt, context, threads, tools, voice, look). Your model file, servers and chat stay.");
        button(buttons(r), "reset all to defaults", Ui.CORAL, x -> confirm("Reset every Pro setting to its default?", "reset", () -> {
            Dev.resetAll(this);
            Speaker.get(this).applySettings();
            toast("All Pro settings reset");
            showPage("home");
        }));
    }

    // ================================================================== model & inference

    private TextView modelStatus;

    private void pageModel() {
        title("Model & inference", "on-device llama.cpp · context/threads/batch reload the model on the next reply");
        Prefs p = new Prefs(this);
        LocalBrain brain = LocalBrain.get(this);

        LinearLayout st = section("model", Ui.LIME);
        modelStatus = mono(st, "");
        refreshModelStatus();
        LinearLayout r1 = buttons(st);
        button(r1, "pick any .gguf", Ui.LIME, x -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, REQ_GGUF); } catch (Exception e) { toast("No file picker on this phone"); }
        });
        button(r1, "metadata", Ui.TEAL, x -> brain.metadata(m -> { modelStatus.setText(m); }));
        button(r1, "unload", Ui.CORAL, x -> { brain.stop(); brain.unload(); LocalBrainService.stop(this); h.postDelayed(this::refreshModelStatus, 300); toast("Model unloaded"); });
        button(r1, "reload", Ui.BLUE, x -> { brain.unload(); reload(); });
        File[] files = ModelStore.dir(this).listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".gguf"));
        if (files != null && files.length > 0) {
            label(st, "Models on this phone (tap to use)");
            for (File f : files) {
                LinearLayout row = buttons(st);
                boolean active = f.getAbsolutePath().equals(p.modelPath());
                button(row, (active ? "● " : "") + f.getName() + " · " + (f.length() >> 20) + " MB", active ? Ui.LIME : Ui.MUTED, x -> {
                    p.setModelPath(f.getAbsolutePath());
                    brain.unload();
                    reload();
                    showPage("model");
                });
                if (!active) button(row, "delete", Ui.CORAL, x -> confirm("Delete " + f.getName() + "?", "delete", () -> { //noinspection ResultOfMethodCallIgnored
                    f.delete(); showPage("model"); }));
            }
        }
        label(st, "Or load from a path (must be readable by the app)");
        EditText path = edit(st, p.modelPath(), TEXT, "/data/…/model.gguf");
        button(buttons(st), "use this path", Ui.BLUE, x -> {
            String s = path.getText().toString().trim();
            if (!new File(s).canRead()) { toast("Can't read that file. Use \"pick any .gguf\" instead."); return; }
            p.setModelPath(s); brain.unload(); reload();
        });

        LinearLayout sys = section("system prompt", Ui.TEAL);
        mono(sys, "blank = the built-in Orbit personality. Also sent to servers.");
        String custom = p.systemPrompt();
        EditText prompt = edit(sys, custom.isEmpty() ? LocalBrain.defaultSystemPrompt(this) : custom, MULTI, null);
        prompt.setSingleLine(false);
        prompt.setMinLines(5);
        prompt.setMaxLines(14);
        prompt.setGravity(Gravity.TOP);
        LinearLayout pr = buttons(sys);
        button(pr, "save", Ui.LIME, x -> {
            String v = prompt.getText().toString().trim();
            sp.edit().putString("model_system_prompt", v.equals(LocalBrain.defaultSystemPrompt(this)) ? "" : v).apply();
            toast("System prompt saved");
        });
        button(pr, "reset to default", Ui.CORAL, x -> { sp.edit().remove("model_system_prompt").apply(); prompt.setText(LocalBrain.defaultSystemPrompt(this)); toast("Back to the default prompt"); });
        button(pr, "view full prompt (with tools)", Ui.BLUE, x -> {
            String full = Dev.filterToolPrompt(this, ToolCalls.systemPrompt(LocalBrain.systemPrompt(this, p), p.toolsOn(), false));
            new AlertDialog.Builder(this).setTitle("system prompt as sent").setMessage(full).setPositiveButton("copy", (d, w) -> copy("Prompt", full)).setNegativeButton("close", null).show();
        });

        LinearLayout smp = section("sampling", Ui.LIME);
        LinearLayout a = pair(smp);
        floatField(cell(a), "Temperature", "model_temp", 0.7f, 0f, 5f);
        floatField(cell(a), "Top-p", "dev_top_p", Dev.DEF_TOP_P, 0f, 1f);
        LinearLayout b2 = pair(smp);
        intField(cell(b2), "Top-k (0 = off)", "dev_top_k", Dev.DEF_TOP_K, 0, 1000);
        floatField(cell(b2), "Min-p", "dev_min_p", Dev.DEF_MIN_P, 0f, 1f);
        LinearLayout c2 = pair(smp);
        floatField(cell(c2), "Repeat penalty", "dev_repeat", Dev.DEF_REPEAT, 0.5f, 3f);
        intField(cell(c2), "Repeat window", "dev_repeat_last_n", Dev.DEF_REPEAT_LAST_N, 0, 4096);
        LinearLayout d2 = pair(smp);
        intField(cell(d2), "Max new tokens", "model_max_tokens", 320, 1, 8192);
        label(cell(d2), "Seed (-1 = random)");
        LinearLayout seedCell = (LinearLayout) d2.getChildAt(1);
        live(edit(seedCell, String.valueOf(sp.getLong("dev_seed", Dev.DEF_SEED)), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED, "default -1"), t -> {
            if (t.isEmpty()) { sp.edit().remove("dev_seed").apply(); return true; }
            try { sp.edit().putLong("dev_seed", Long.parseLong(t)).apply(); return true; } catch (NumberFormatException e) { return false; }
        });
        label(smp, "Stop strings (one per line, \\n = newline)");
        EditText stops = edit(smp, sp.getString("dev_stops", ""), MULTI, "e.g. User:");
        stops.setSingleLine(false);
        stops.setMinLines(2);
        live(stops, t -> { sp.edit().putString("dev_stops", t).apply(); return true; });

        LinearLayout ld = section("load", Ui.BLUE);
        LinearLayout e2 = pair(ld);
        intField(cell(e2), "Context size", "model_ctx", 2048, 512, 32768);
        intField(cell(e2), "Threads", "model_threads", 4, 1, 16);
        LinearLayout f2 = pair(ld);
        intField(cell(f2), "Batch size", "dev_batch", Dev.DEF_BATCH, 32, 4096);
        intField(cell(f2), "History messages", "model_history", 8, 0, 64);
        toggle(ld, "Tool calling (timer, alarm, apps, flashlight, time, battery)", "tools_on", true, null);
    }

    private void refreshModelStatus() {
        if (modelStatus == null) return;
        Prefs p = new Prefs(this);
        LocalBrain brain = LocalBrain.get(this);
        LocalBrain.Stats st = brain.lastStats();
        String path = p.modelPath();
        modelStatus.setText((path.isEmpty() ? "no model picked" : new File(path).getName() + (new File(path).exists() ? " · " + (new File(path).length() >> 20) + " MB" : " · file missing"))
                + "\n" + (brain.isLoading() ? "loading…" : brain.isLoaded() ? "in memory · " + brain.description() : "not loaded")
                + (brain.lastError() != null ? "\nlast error: " + brain.lastError() : "")
                + (st != null ? "\nlast reply: " + st.line() + " · prompt " + st.promptTokens + " tok (" + st.reusedTokens + " cached)" : ""));
    }

    private void reload() {
        LocalBrain.get(this).ensureLoaded(new LocalBrain.LoadListener() {
            @Override public void onLoaded(String d) { toast("Loaded"); refreshModelStatus(); }
            @Override public void onLoadFailed(String e) { toast(e); refreshModelStatus(); }
        });
        h.postDelayed(this::refreshModelStatus, 200);
    }

    // ================================================================== thinking

    private void pageThinking() {
        title("Thought process", "<think>…</think> from reasoning models (Qwen3, DeepSeek-R1 …) and server reasoning fields. The answer never contains it.");
        LinearLayout t = section("thinking", Ui.TEAL);
        toggle(t, "Show thought process (panel above each reply)", "dev_think_show", false, null);
        toggle(t, "Expanded by default (off = collapsed, tap to open)", "dev_think_expanded", false, null);
        toggle(t, "Force thinking (think-first prompt, for models that don't think on their own)", "dev_think_force", false, null);
        toggle(t, "Keep thinking out of the history sent back to the model", "dev_think_out_of_history", true, null);
        intField(t, "Max thinking tokens (0 = no limit · past it, it answers without thinking)", "dev_think_max", 0, 0, 8192);
        mono(t, "Tool decisions (which tool, which arguments, what happened) also show in the panel. Dry runs say so.");
        LinearLayout last = section("last thought process", Ui.MUTED);
        List<ChatStore.Item> items = ChatStore.get(this).all();
        String lt = "";
        for (int i = items.size() - 1; i >= 0; i--) if (!items.get(i).think.isEmpty()) { lt = Dev.thinkHeaderOf(items.get(i).think) + "\n\n" + Dev.thinkBodyOf(items.get(i).think); break; }
        block(last, lt.isEmpty() ? "(none yet)" : lt);
    }

    // ================================================================== server

    private TextView serverOut;

    private void pageServer() {
        title("Server", "Ollama · LM Studio · llama.cpp server · any OpenAI-compatible API");
        ServerStore store = ServerStore.get(this);
        LlmServer sv = store.active();
        LinearLayout s = section("active server", Ui.BLUE);
        if (sv == null) {
            mono(s, "No server yet.");
            button(buttons(s), "add a server", Ui.BLUE, x -> startActivity(new Intent(this, ServerSettingsActivity.class)));
        } else {
            mono(s, sv.name + " · " + sv.label());
            label(s, "Base URL");
            EditText url = edit(s, sv.url, TEXT | InputType.TYPE_TEXT_VARIATION_URI, "192.168.1.20:11434");
            label(s, "API key (blank = none)");
            EditText key = edit(s, sv.apiKey, TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, "sk-…");
            label(s, "Model name");
            EditText model = edit(s, sv.model, TEXT, "qwen2.5:7b");
            final String[] type = {sv.type};
            label(s, "API");
            LinearLayout trow = buttons(s);
            String[] types = {ServerClient.AUTO, ServerClient.OLLAMA, ServerClient.OPENAI};
            List<TextView> tchips = new ArrayList<>();
            for (String ty : types) {
                TextView c = button(trow, ty.equals(ServerClient.OPENAI) ? "openai-compatible" : ty, ty.equals(type[0]) ? Ui.LIME : Ui.MUTED, null);
                c.setOnClickListener(x -> { type[0] = ty; for (int j = 0; j < 3; j++) Ui.styleChip(tchips.get(j), types[j].equals(ty) ? Ui.LIME : Ui.MUTED); });
                tchips.add(c);
            }
            LinearLayout br = buttons(s);
            button(br, "save", Ui.LIME, x -> {
                store.save(new LlmServer(sv.id, sv.name, ServerClient.normalizeBase(url.getText().toString().trim()), type[0], sv.detected,
                        key.getText().toString().trim(), model.getText().toString().trim()));
                toast("Server saved");
            });
            button(br, "test connection", Ui.TEAL, x -> testServer(url.getText().toString().trim(), type[0], key.getText().toString().trim()));
            button(br, "all servers", Ui.MUTED, x -> startActivity(new Intent(this, ServerSettingsActivity.class)));
            serverOut = mono(s, "");
        }
        LinearLayout o = section("request", Ui.LIME);
        intField(o, "Timeout (seconds, waiting for the reply)", "dev_server_timeout_s", Dev.DEF_SERVER_TIMEOUT_S, 5, 3600);
        toggle(o, "Streaming (off = one JSON answer)", "dev_server_stream", true, null);
        toggle(o, "Send the Pro sampling settings (top-p, top-k, min-p, repeat, seed, stops)", "dev_server_sampling", false, null);
    }

    private void testServer(String url, String type, String key) {
        if (serverOut == null) return;
        serverOut.setText("testing…");
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String out;
            try {
                ServerClient c = new ServerClient(url, type, key).timeouts(6000, 15000);
                ServerClient.Probe pr = c.probe();
                long ms = System.currentTimeMillis() - t0;
                out = "✓ " + pr.backend + " · " + pr.models.size() + " models · " + ms + " ms\n" + android.text.TextUtils.join("\n", pr.models);
            } catch (Exception e) {
                out = "✕ " + e.getMessage();
            }
            final String o = out;
            h.post(() -> { if (serverOut != null) serverOut.setText(o); });
        }, "dev-test").start();
    }

    private void pageChat() {
        title("Chat", null);
        LinearLayout v = section("show in the chat", Ui.LIME);
        toggle(v, "Live token count + tokens/sec while it answers", "dev_live_stats", true, null);
        LinearLayout hst = section("history", Ui.CORAL);
        mono(hst, ChatStore.get(this).all().size() + " messages saved");
        LinearLayout hb = buttons(hst);
        button(hb, "export json", Ui.LIME, x -> create(REQ_EXPORT_JSON, "application/json", "orbit-chat-" + stamp() + ".json"));
        button(hb, "export text", Ui.LIME, x -> create(REQ_EXPORT_TXT, "text/plain", "orbit-chat-" + stamp() + ".txt"));
        button(hb, "share text", Ui.TEAL, x -> share("NTS Orbit chat", chatText()));
        LinearLayout hb2 = buttons(hst);
        button(hb2, "import json", Ui.BLUE, x -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, REQ_IMPORT); } catch (Exception e) { toast("No file picker on this phone"); }
        });
        button(hb2, "clear history", Ui.CORAL, x -> confirm("Clear the whole chat history?", "clear", () -> { ChatStore.get(this).clear(); toast("History cleared"); showPage("chat"); }));
    }

    private static String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()); }

    private void create(int req, String mime, String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(i, req); } catch (Exception e) { toast("No file picker on this phone"); }
    }

    static String chatText(List<ChatStore.Item> items) {
        StringBuilder b = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (ChatStore.Item it : items) {
            String who = ChatStore.FROM_ME.equals(it.from) ? "you" : ChatStore.FROM_JARVIS.equals(it.from) ? "orbit" : it.from;
            b.append('[').append(f.format(new Date(it.time))).append("] ").append(who);
            if (!it.via.isEmpty()) b.append(" (").append(it.via).append(')');
            b.append(":\n");
            if (!it.think.isEmpty()) b.append("  💭 ").append(Dev.thinkBodyOf(it.think).trim().replace("\n", "\n  ")).append('\n');
            b.append(ChatStore.FROM_TOOL.equals(it.from) ? MainActivity.toolChip(it.text) : it.text).append("\n\n");
        }
        return b.toString();
    }

    private String chatText() { return chatText(ChatStore.get(this).all()); }

    // ================================================================== tools

    private TextView toolOut;

    private void pageTools() {
        title("Tools", "phone actions the model (and the instant router) can use");
        LinearLayout t = section("tools", Ui.LIME);
        toggle(t, "Tool calling on", "tools_on", true, null);
        String[][] names = {{ToolCalls.TIMER, "Timer"}, {ToolCalls.ALARM, "Alarm"}, {ToolCalls.FLASHLIGHT, "Flashlight"},
                {ToolCalls.OPEN_APP, "Open app"}, {ToolCalls.TIME, "Time & date"}, {ToolCalls.BATTERY, "Battery"}};
        label(t, "Each action (off = removed from the model's tool list, and refused if called)");
        for (String[] n : names) toggle(t, n[1] + "  ·  " + n[0], "dev_tool_" + n[0], true, null);
    }

    // ================================================================== voice

    private TextView micOut;
    private ProgressBar micLevel;

    private void pageVoice() {
        title("Voice & wake", null);
        LinearLayout w = section("wake phrase (hands-free)", Ui.LIME);
        mono(w, "With hands-free on, Orbit only answers when you start with the phrase (\"hey orbit, set a timer\"). Uses the phone's speech recognizer, no extra mic access.");
        toggle(w, "Wake phrase on", "dev_wake_on", false, null);
        label(w, "Phrase");
        live(edit(w, Dev.wakePhrase(this), TEXT, "orbit"), t -> { sp.edit().putString("dev_wake_phrase", t).apply(); return true; });
        label(w, "Sensitivity");
        LinearLayout sr = buttons(w);
        String[] sl = {"strict · must start with it", "normal · anywhere, 1 typo", "loose · anywhere, 2 typos"};
        List<TextView> sc = new ArrayList<>();
        int cur = Dev.wakeSensitivity(this);
        for (int k = 0; k < 3; k++) {
            final int v = k;
            TextView c = button(sr, sl[k], k == cur ? Ui.LIME : Ui.MUTED, null);
            c.setOnClickListener(x -> { sp.edit().putInt("dev_wake_sens", v).apply(); for (int j = 0; j < 3; j++) Ui.styleChip(sc.get(j), j == v ? Ui.LIME : Ui.MUTED); });
            sc.add(c);
        }
        toggle(w, "Hands-free on", "hands_free", false, null);
        label(w, "Try a sentence");
        EditText tryIt = edit(w, "hey orbit what time is it", TEXT, null);
        TextView tryOut = mono(w, "");
        button(buttons(w), "check", Ui.TEAL, x -> {
            String r = Dev.wakeFilter(tryIt.getText().toString(), Dev.wakePhrase(this), Dev.wakeSensitivity(this));
            tryOut.setText(r == null ? "✕ ignored (no wake phrase)" : r.isEmpty() ? "✓ wake phrase only → \"yes?\"" : "✓ sends: " + r);
        });

        LinearLayout t = section("text to speech", Ui.TEAL);
        LinearLayout a = pair(t);
        floatField(cell(a), "Speed (0.1–4)", "speech_rate", 1.05f, 0.1f, 4f);
        floatField(cell(a), "Pitch (0.1–4)", "pitch", 1.0f, 0.1f, 4f);
        toggle(t, "Read replies out loud", "speak_replies", true, null);
        button(buttons(t), "▶ test voice", Ui.TEAL, x -> { Speaker.get(this).applySettings(); Speaker.get(this).speak("Orbit online. Testing speed and pitch.", null); });

        LinearLayout m = section("mic test", Ui.BLUE);
        micLevel = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        micLevel.setMax(100);
        m.addView(micLevel, new LinearLayout.LayoutParams(-1, Ui.dp(this, 18)));
        micOut = mono(m, "level + what the recognizer hears");
        LinearLayout mr = buttons(m);
        button(mr, "● start", Ui.BLUE, x -> startMicTest());
        button(mr, "stop", Ui.CORAL, x -> stopMicTest());
    }

    private void startMicTest() {
        stopMicTest();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { micOut.setText("✕ no speech recognizer on this phone"); return; }
        micTest = SpeechRecognizer.createSpeechRecognizer(this);
        final long t0 = System.currentTimeMillis();
        micTest.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { micOut.setText("listening… say something"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) { if (micLevel != null) micLevel.setProgress((int) Math.max(0, Math.min(100, (rms + 2) * 8))); }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int e) { micOut.setText("✕ " + MainActivity.errorText(e)); }
            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                micOut.setText("✓ \"" + (r == null || r.isEmpty() ? "" : r.get(0)) + "\"" + (conf != null && conf.length > 0 ? String.format(Locale.US, " · confidence %.2f", conf[0]) : "")
                        + " · " + (System.currentTimeMillis() - t0) + " ms" + (r != null && r.size() > 1 ? "\nalternatives: " + r.subList(1, r.size()) : ""));
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) micOut.setText("… " + r.get(0));
            }
            @Override public void onEvent(int t, Bundle p) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        micTest.startListening(i);
    }

    private void stopMicTest() {
        if (micTest != null) { try { micTest.destroy(); } catch (Exception ignored) {} micTest = null; }
    }

    // ================================================================== look

    private void pageUi() {
        title("Look", "theme, accent and font size apply when you go back");
        LinearLayout t = section("theme", Ui.LIME);
        choice(t, "Background theme", "dev_theme", Dev.THEMES, new String[]{"default", "amoled black", "midnight", "warm"}, "default", this::restyle);
        choice(t, "Accent", "dev_accent", Dev.ACCENTS, Dev.ACCENTS, "lime", this::restyle);
        label(t, "Font size");
        String[] fs = {"0.85", "1.0", "1.15", "1.3", "1.5"};
        LinearLayout fr = buttons(t);
        List<TextView> fc = new ArrayList<>();
        for (String f : fs) {
            float v = Float.parseFloat(f);
            TextView c = button(fr, f + "×", Math.abs(Dev.fontScale(this) - v) < 0.01f ? Ui.LIME : Ui.MUTED, null);
            c.setOnClickListener(x -> { sp.edit().putFloat("dev_font_scale", v).apply(); for (int j = 0; j < fs.length; j++) Ui.styleChip(fc.get(j), fs[j].equals(f) ? Ui.LIME : Ui.MUTED); recreate(); });
            fc.add(c);
        }
        LinearLayout m = section("motion & overlay", Ui.TEAL);
        toggle(m, "Animations (bubbles, mic, signal bars)", "dev_animations", true, null);
        toggle(m, "Haptics", "haptics", true, null);
    }

    private void restyle() { Dev.applyTheme(this); recreate(); }

    // ================================================================== diagnostics

    private TextView benchOut;

    private void pageBench() {
        title("Benchmark", null);
        LinearLayout b = section("benchmark", Ui.TEAL);
        mono(b, "Fixed prompt, greedy, on the current brain (" + (new Prefs(this).isServer() ? "server" : "on-device") + "). Reports prompt speed and generation tok/s.");
        benchOut = block(b, "(not run yet)");
        button(buttons(b), "▶ run benchmark", Ui.TEAL, x -> runBenchmark());
    }

    static final String BENCH_PROMPT = "Write the numbers from 1 to 40 separated by commas, nothing else.";

    private void runBenchmark() {
        benchOut.setText("running… (loads the model first if needed)");
        Prefs p = new Prefs(this);
        long t0 = System.currentTimeMillis();
        if (p.isServer()) {
            LlmServer sv = ServerStore.get(this).active();
            if (sv == null) { benchOut.setText("✕ no server"); return; }
            new Thread(() -> {
                String out;
                try {
                    ServerClient c = new ServerClient(sv.url, sv.backend(), sv.apiKey).timeouts(6000, Dev.serverTimeoutMs(this));
                    List<ServerClient.Msg> msgs = new ArrayList<>();
                    msgs.add(new ServerClient.Msg("user", BENCH_PROMPT));
                    String model = sv.model.isEmpty() ? c.listModels().get(0) : sv.model;
                    ServerClient.Result r = c.chat(model, msgs, 0f, 160, null, null);
                    out = "server · " + r.statsLine() + " · total " + (System.currentTimeMillis() - t0) + " ms";
                } catch (Exception e) {
                    out = "✕ " + e.getMessage();
                }
                final String o = out;
                h.post(() -> { if (benchOut != null) benchOut.setText(o); });
            }, "dev-bench").start();
            return;
        }
        List<LocalBrain.Turn> hist = new ArrayList<>();
        hist.add(new LocalBrain.Turn("user", BENCH_PROMPT));
        LocalBrain.get(this).generate(hist, "You follow instructions exactly.", 0f, null, new LocalBrain.GenListener() {
            int n;
            @Override public void onPiece(String t) { n++; if (benchOut != null && n % 10 == 0) benchOut.setText("generating… " + n + " tok"); }
            @Override public void onDone(String reply, LocalBrain.Stats st) {
                int fresh = st.promptTokens - st.reusedTokens;
                String o = String.format(Locale.US, "gen  %.2f tok/s (%d tok in %.0f ms)\nprompt %.1f tok/s (%d tok in %.0f ms, %d cached)\ntotal %d ms · threads %d · ctx %d · batch %d",
                        st.tokensPerSec(), st.genTokens, st.genMs, st.promptMs > 0 ? fresh * 1000.0 / st.promptMs : 0, fresh, st.promptMs, st.reusedTokens,
                        System.currentTimeMillis() - t0, p.threads(), p.contextSize(), Dev.batch(ProMenuActivity.this));
                if (benchOut != null) benchOut.setText(o);
            }
            @Override public void onError(String e) { if (benchOut != null) benchOut.setText("✕ " + e); }
        });
    }

    // ================================================================== files

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (req) {
            case REQ_GGUF: importGguf(uri); break;
            case REQ_EXPORT_JSON: write(uri, ChatStore.get(this).exportJson()); break;
            case REQ_EXPORT_TXT: write(uri, chatText()); break;
            case REQ_IMPORT: importChat(uri); break;
            default: break;
        }
    }

    private void write(Uri uri, String text) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new java.io.IOException("can't open");
            os.write(text.getBytes(StandardCharsets.UTF_8));
            toast("Exported");
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private void importChat(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("can't open");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            int count = ChatStore.get(this).importJson(b.toString("UTF-8"));
            toast("Imported " + count + " messages");
            showPage("chat");
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    private void importGguf(Uri uri) {
        if (modelStatus != null) modelStatus.setText("copying…");
        ModelStore.importModel(this, uri, new ModelStore.Progress() {
            @Override public void onProgress(long copied, long total) {
                if (modelStatus != null) modelStatus.setText(String.format(Locale.US, "copying… %d / %d MB", copied >> 20, total >> 20));
            }
            @Override public void onDone(File model) {
                new Prefs(ProMenuActivity.this).setModelPath(model.getAbsolutePath());
                LocalBrain.get(ProMenuActivity.this).unload();
                reload();
                showPage("model");
            }
            @Override public void onError(String error) { if (modelStatus != null) modelStatus.setText("✕ " + error); }
        });
    }
}
