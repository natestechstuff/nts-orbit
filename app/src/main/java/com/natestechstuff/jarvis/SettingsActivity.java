package com.natestechstuff.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.Voice;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Settings: server, brain, on-device model, voice + speed, haptics, history. */
public class SettingsActivity extends Activity {
    public static final String EXTRA_PICK_MODEL = "pick_model";
    static final int REQ_PICK_MODEL = 31;
    private Prefs prefs;
    private TextView modelInfo, importChip, brainLocal, brainServer, serverSummary;
    private EditText sysPrompt;
    private boolean importing;
    private LinearLayout col;
    private boolean devOpened;   // Pro menu opened once (7-tap shortcut hint)
    private Spinner voice;
    private List<Voice> voices = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Dev.ON ? Dev.wrap(base) : base);   // Pro: Pro font size
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (Dev.ON) Dev.applyTheme(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GridDrawable(Ui.dp(this, 42)));
        root.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        col.setPadding(pad, pad, pad, Ui.dp(this, 40));
        sv.addView(col);
        root.addView(sv);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTypeface(Ui.groteskBold(this));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(Ui.INK);
        col.addView(title);

        buildPremiumSection();
        buildServerSection();   // Server is the first thing in Settings

        LinearLayout brainSec = section("brain · who answers", Ui.LIME);
        LinearLayout row = new LinearLayout(this);
        TextView bnote = new TextView(this);
        brainServer = Ui.chip(this, "server", Ui.MUTED);
        brainLocal = Ui.chip(this, "on-device", Ui.MUTED);
        brainServer.setOnClickListener(x -> setBrain(Prefs.MODE_SERVER));
        brainLocal.setOnClickListener(x -> setBrain(Prefs.MODE_LOCAL));
        row.addView(brainServer);
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(-2, -2); r1.leftMargin = Ui.dp(this, 8);
        row.addView(brainLocal, r1);
        bnote.setText("server = Ollama, LM Studio, llama.cpp or another local AI server you run · on-device = a .gguf model, offline on this phone. Speech-to-text and the voice always run on the phone.");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2); rlp.topMargin = Ui.dp(this, 6);
        brainSec.addView(row, rlp);
        bnote.setTypeface(Ui.mono(this));
        bnote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bnote.setTextColor(Ui.MUTED);
        bnote.setPadding(0, Ui.dp(this, 8), 0, 0);
        brainSec.addView(bnote);
        refreshBrain();

        LinearLayout mm = section("on-device model", Ui.LIME);
        modelInfo = new TextView(this);
        modelInfo.setTypeface(Ui.mono(this));
        modelInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modelInfo.setTextColor(Ui.INK);
        modelInfo.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        mm.addView(modelInfo);
        LinearLayout mrow = new LinearLayout(this);
        importChip = Ui.chip(this, "pick .gguf file", Ui.LIME);
        importChip.setOnClickListener(x -> pickModel());
        mrow.addView(importChip);
        TextView testChip = Ui.chip(this, "▶ test", Ui.TEAL);
        testChip.setOnClickListener(x -> testModel(testChip));
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(-2, -2); m1.leftMargin = Ui.dp(this, 8);
        mrow.addView(testChip, m1);
        TextView delChip = Ui.chip(this, "delete", Ui.CORAL);
        delChip.setOnClickListener(x -> deleteModel());
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(-2, -2); m2.leftMargin = Ui.dp(this, 8);
        mrow.addView(delChip, m2);
        mm.addView(mrow);
        choices(mm, "Threads (CPU cores used)", "model_threads", prefs.threads(), new int[]{2, 4, 6, 8}, new String[]{"2", "4", "6", "8"});
        choices(mm, "Context (how much chat it remembers)", "model_ctx", prefs.contextSize(), new int[]{1024, 2048, 4096}, new String[]{"1024", "2048", "4096"});
        choices(mm, "Max reply length (tokens) · 1024 works best with context 4096", "model_max_tokens", prefs.maxTokens(), new int[]{160, 320, 640, 1024}, new String[]{"160", "320", "640", "1024"});
        Switch keep = new Switch(this);
        keep.setText("Keep model loaded in the background (faster replies, shows a notification)");
        keep.setTypeface(Ui.grotesk(this));
        keep.setTextColor(Ui.INK);
        keep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        keep.setChecked(prefs.keepModelLoaded());
        keep.setPadding(0, Ui.dp(this, 12), 0, 0);
        keep.setOnCheckedChangeListener((bb, on) -> {
            prefs.edit().putBoolean("model_keep_loaded", on).apply();
            if (on && prefs.isLocal() && !prefs.modelPath().isEmpty()) LocalBrainService.start(this);
            if (!on) LocalBrainService.stop(this);
        });
        mm.addView(keep, new LinearLayout.LayoutParams(-1, -2));
        toggle(mm, "Tools: timer, alarm, open apps, flashlight, time, battery", "tools_on", prefs.toolsOn());
        sysPrompt = field(mm,"Orbit personality (system prompt, also sent to servers) · blank = default", prefs.systemPrompt(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        sysPrompt.setHint(LocalBrain.defaultSystemPrompt(this));
        sysPrompt.setMaxLines(6);
        sysPrompt.setSingleLine(false);
        refreshModelInfo();
        if (getIntent().getBooleanExtra(EXTRA_PICK_MODEL, false) && b == null) h.postDelayed(this::pickModel, 300);


        LinearLayout v = section("voice", Ui.LIME);
        label(v, "Voice");
        voice = new Spinner(this);
        voice.setBackground(Ui.box(Ui.PAPER, Ui.LINE, 1, 12, this));
        v.addView(voice, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        slider(v, "Speed", "speech_rate", prefs.speechRate(), 0.6f, 1.8f);
        slider(v, "Pitch", "pitch", prefs.pitch(), 0.7f, 1.4f);
        toggle(v, "Read replies out loud", "speak_replies", prefs.speakReplies());
        TextView test = Ui.chip(this, "▶ test voice", Ui.LIME);
        test.setOnClickListener(x -> { save(); Speaker.get(this).applySettings(); Speaker.get(this).speak("Orbit online. Sounding good?", null); });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 12);
        v.addView(test, tlp);


        LinearLayout other = section("other", Ui.CORAL);
        toggle(other, "Haptics on send / reply", "haptics", prefs.haptics());
        TextView clear = Ui.chip(this, "clear chat history", Ui.CORAL);
        clear.setOnClickListener(x -> { ChatStore.get(this).clear(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show(); });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Ui.dp(this, 12);
        other.addView(clear, clp);


        TextView foot = new TextView(this);
        foot.setText("nts orbit " + ("public ") + "v" + BuildConfig.VERSION_NAME + " · nate's tech stuff\ncode, curiosity & too many tabs");
        foot.setTypeface(Ui.mono(this));
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        foot.setTextColor(Ui.MUTED);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = Ui.dp(this, 28);
        col.addView(foot, flp);

        loadVoices(0);
    }

    /** Premium: tier badge + "Enter key", and the way into Pro settings once unlocked. */
    private void buildPremiumSection() {
        boolean pro = Pro.isPro(this);
        LinearLayout p = section("premium", Ui.LIME);
        TextView note = new TextView(this);
        note.setText(pro ? "Unlocked: " + Pro.tierLabel(this) + ". Thanks for supporting Orbit!"
                : "Have a key from the Discord (Crew 2, Crew 3, Baller) or a Pro purchase? Enter it to unlock Pro settings.");
        note.setTypeface(Ui.grotesk(this));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setTextColor(Ui.MUTED);
        note.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        p.addView(note);
        LinearLayout row = new LinearLayout(this);
        TextView key = Ui.chip(this, pro ? "★ " + Pro.tierLabel(this).toLowerCase(java.util.Locale.US) + " · manage key" : "enter key →", pro ? Ui.LIME : Ui.CORAL);
        key.setOnClickListener(x -> { save(); devOpened = true; startActivity(new Intent(this, PremiumActivity.class)); });
        row.addView(key);
        if (pro) {
            TextView open = Ui.chip(this, "pro settings →", Ui.TEAL);
            open.setOnClickListener(x -> { save(); devOpened = true; Dev.open(this); });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
            olp.leftMargin = Ui.dp(this, 8);
            row.addView(open, olp);
        }
        p.addView(row);
    }

    private void loadVoices(int attempt) {
        Speaker sp = Speaker.get(this);
        if (!sp.isReady() && attempt < 20) {
            if (attempt == 0) fillVoices();   // show "Phone default" right away, refresh when TTS is up
            h.postDelayed(() -> loadVoices(attempt + 1), 250);
            return;
        }
        fillVoices();
    }

    private void fillVoices() {
        Speaker sp = Speaker.get(this);
        voices = sp.voices();
        List<String> labels = new ArrayList<>();
        labels.add("Phone default");
        int sel = 0;
        for (int i = 0; i < voices.size(); i++) {
            Voice vc = voices.get(i);
            labels.add(vc.getLocale().getDisplayCountry() + " · " + vc.getName());
            if (vc.getName().equals(prefs.voiceName())) sel = i + 1;
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int pos, View cv, ViewGroup parent) { return style(super.getView(pos, cv, parent)); }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) { return style(super.getDropDownView(pos, cv, parent)); }
            private View style(View x) {
                TextView t = (TextView) x;
                t.setTypeface(Ui.mono(SettingsActivity.this));
                t.setTextColor(Ui.INK);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                t.setPadding(Ui.dp(SettingsActivity.this, 12), Ui.dp(SettingsActivity.this, 10), Ui.dp(SettingsActivity.this, 12), Ui.dp(SettingsActivity.this, 10));
                return t;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voice.setAdapter(ad);
        voice.setSelection(sel);
        voice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                prefs.edit().putString("voice", pos == 0 ? "" : voices.get(pos - 1).getName()).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private LinearLayout section(String title, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.box(Ui.CREAM, Ui.LINE, 1, 16, this));
        card.addView(Ui.chip(this, title, color), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 18);
        col.addView(card, lp);
        return card;
    }

    private void label(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.grotesk(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        parent.addView(t);
    }

    private EditText field(LinearLayout parent, String labelText, String value, int type) {
        label(parent, labelText);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTypeface(Ui.mono(this));
        e.setTextColor(Ui.INK);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        e.setBackground(Ui.box(Ui.PAPER, Ui.LINE, 1, 12, this));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private void slider(LinearLayout parent, String labelText, String key, float value, float min, float max) {
        label(parent, labelText + String.format(java.util.Locale.US, "  ·  %.2fx", value));
        TextView lbl = (TextView) parent.getChildAt(parent.getChildCount() - 1);
        SeekBar s = new SeekBar(this);
        s.setMax(100);
        s.setProgress(Math.round((value - min) / (max - min) * 100));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                float v = min + (max - min) * p / 100f;
                lbl.setText(labelText + String.format(java.util.Locale.US, "  ·  %.2fx", v));
                prefs.edit().putFloat(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { Speaker.get(SettingsActivity.this).applySettings(); }
        });
        parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
    }

    private void toggle(LinearLayout parent, String labelText, String key, boolean value) {
        Switch sw = new Switch(this);
        sw.setText(labelText);
        sw.setTypeface(Ui.grotesk(this));
        sw.setTextColor(Ui.INK);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setChecked(value);
        sw.setPadding(0, Ui.dp(this, 12), 0, 0);
        sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        parent.addView(sw, new LinearLayout.LayoutParams(-1, -2));
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        if (sysPrompt != null) e.putString("model_system_prompt", sysPrompt.getText().toString().trim());
        e.apply();
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    // ------------------------------------------------------------------ server

    private void buildServerSection() {
        LinearLayout sec = section("server", Ui.BLUE);
        serverSummary = new TextView(this);
        serverSummary.setTypeface(Ui.mono(this));
        serverSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        serverSummary.setTextColor(Ui.INK);
        serverSummary.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        sec.addView(serverSummary);
        TextView manage = Ui.chip(this, "manage servers", Ui.BLUE);
        manage.setOnClickListener(x -> startActivity(new Intent(this, ServerSettingsActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 10);
        sec.addView(manage, lp);
        refreshServerSummary();
    }

    private void refreshServerSummary() {
        if (serverSummary == null) return;
        java.util.List<LlmServer> all = ServerStore.get(this).all();
        LlmServer a = ServerStore.get(this).active();
        if (a == null) {
            serverSummary.setText("No server yet. Add Ollama, LM Studio, llama.cpp server, LocalAI, KoboldCpp, Jan, text-generation-webui or vLLM.");
        } else {
            serverSummary.setText("using: " + a.name + "\n" + a.label() + "\nmodel: " + (a.model.isEmpty() ? "(none picked)" : a.model)
                    + (all.size() > 1 ? "\n" + all.size() + " servers saved" : ""));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pro: back from the Pro menu → rebuild, so these fields show (and later save) its values
        if (devOpened) { devOpened = false; recreate(); return; }
        refreshServerSummary();
        if (brainLocal != null) refreshBrain();
    }

    // ------------------------------------------------------------------ brain + my model

    private void setBrain(String m) {
        if (Prefs.MODE_SERVER.equals(m) && ServerStore.get(this).active() == null) startActivity(new Intent(this, ServerSettingsActivity.class));
        prefs.setMode(m);
        refreshBrain();
        if (Prefs.MODE_LOCAL.equals(m) && prefs.keepModelLoaded() && !prefs.modelPath().isEmpty()) LocalBrainService.start(this);
        if (!Prefs.MODE_LOCAL.equals(m)) LocalBrainService.stop(this);
    }

    private void refreshBrain() {
        String m = prefs.mode();
        Ui.styleChip(brainLocal, Prefs.MODE_LOCAL.equals(m) ? Ui.LIME : Ui.MUTED);
        Ui.styleChip(brainServer, Prefs.MODE_SERVER.equals(m) ? Ui.BLUE : Ui.MUTED);
    }

    private void refreshModelInfo() {
        String path = prefs.modelPath();
        java.io.File f = new java.io.File(path);
        if (path.isEmpty() || !f.exists()) {
            modelInfo.setText("No model yet. Download a .gguf chat model to your phone and pick it here.");
            return;
        }
        LocalBrain lb = LocalBrain.get(this);
        String state = lb.isLoaded() ? "loaded · " + lb.description() : lb.isLoading() ? "loading…" : "not loaded yet (loads on first question)";
        LocalBrain.Stats st = lb.lastStats();
        modelInfo.setText(f.getName() + " · " + (f.length() >> 20) + " MB\n" + state + (st != null ? "\nlast reply: " + st.line() : ""));
    }

    private void pickModel() {
        if (importing) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");   // .gguf has no registered MIME type
        try {
            startActivityForResult(i, REQ_PICK_MODEL);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker found", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_MODEL || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        String name = ModelStore.displayName(this, uri);
        if (!name.toLowerCase(java.util.Locale.US).endsWith(".gguf")) {
            Toast.makeText(this, "Heads up: " + name + " doesn't end in .gguf, checking it anyway", Toast.LENGTH_LONG).show();
        }
        importing = true;
        importChip.setText("copying…");
        LocalBrain.get(this).unload();
        ModelStore.importModel(this, uri, new ModelStore.Progress() {
            @Override public void onProgress(long copied, long total) {
                importChip.setText(total > 0 ? "copying " + (copied * 100 / total) + "%" : "copying " + (copied >> 20) + " MB");
            }
            @Override public void onDone(java.io.File model) {
                importing = false;
                importChip.setText("pick .gguf file");
                prefs.setModelPath(model.getAbsolutePath());
                if (!prefs.isLocal()) { prefs.setMode(Prefs.MODE_LOCAL); refreshBrain(); }
                modelInfo.setText(model.getName() + " · " + (model.length() >> 20) + " MB\nloading…");
                LocalBrain.get(SettingsActivity.this).ensureLoaded(new LocalBrain.LoadListener() {
                    @Override public void onLoaded(String d) {
                        refreshModelInfo();
                        Toast.makeText(SettingsActivity.this, "Model ready. Go talk to it!", Toast.LENGTH_SHORT).show();
                        if (prefs.keepModelLoaded()) LocalBrainService.start(SettingsActivity.this);
                    }
                    @Override public void onLoadFailed(String e) { modelInfo.setText("Copied, but it didn't load: " + e); }
                });
            }
            @Override public void onError(String error) {
                importing = false;
                importChip.setText("pick .gguf file");
                modelInfo.setText("Import failed: " + error);
            }
        });
    }

    private void deleteModel() {
        LocalBrain.get(this).unload();
        LocalBrainService.stop(this);
        java.io.File[] all = ModelStore.dir(this).listFiles();
        if (all != null) for (java.io.File f : all) //noinspection ResultOfMethodCallIgnored
            f.delete();
        prefs.setModelPath("");
        refreshModelInfo();
        Toast.makeText(this, "Model deleted", Toast.LENGTH_SHORT).show();
    }

    private void testModel(TextView chip) {
        save();
        if (prefs.modelPath().isEmpty()) { Toast.makeText(this, "Pick a model first", Toast.LENGTH_SHORT).show(); return; }
        chip.setText("thinking…");
        java.util.List<LocalBrain.Turn> h1 = new ArrayList<>();
        h1.add(new LocalBrain.Turn("user","Say hi in one short sentence."));
        final StringBuilder out = new StringBuilder();
        LocalBrain.get(this).generate(h1, new LocalBrain.GenListener() {
            @Override public void onPiece(String t) { out.append(t); modelInfo.setText(out); }
            @Override public void onDone(String reply, LocalBrain.Stats st) {
                chip.setText("▶ test");
                refreshModelInfo();
                modelInfo.append("\n\n\u201c" + reply + "\u201d");
                Speaker.get(SettingsActivity.this).speak(SpeechText.forSpeech(reply), null);
            }
            @Override public void onError(String e) { chip.setText("▶ test"); modelInfo.setText("Test failed: " + e); }
        });
    }

    private void choices(LinearLayout parent, String labelText, String key, int current, int[] values, String[] labels) {
        label(parent, labelText);
        LinearLayout r = new LinearLayout(this);
        final TextView[] chips = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final int v = values[i];
            chips[i] = Ui.chip(this, labels[i], v == current ? Ui.LIME : Ui.MUTED);
            final int idx = i;
            chips[i].setOnClickListener(x -> {
                prefs.edit().putInt(key, v).apply();
                for (int k = 0; k < chips.length; k++) Ui.styleChip(chips[k], k == idx ? Ui.LIME : Ui.MUTED);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            r.addView(chips[i], lp);
        }
        parent.addView(r);
    }
}
