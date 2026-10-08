package com.natestechstuff.jarvis;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings → Server: saved local AI servers (Ollama, LM Studio, llama.cpp server, LocalAI, KoboldCpp, Jan,
 * text-generation-webui, vLLM …), which one Orbit uses, and an editor with test connection + model picker.
 */
public class ServerSettingsActivity extends Activity {
    /** name, API type, default port */
    static final String[][] PRESETS = {
            {"Ollama", ServerClient.OLLAMA, "11434"},
            {"LM Studio", ServerClient.OPENAI, "1234"},
            {"llama.cpp", ServerClient.OPENAI, "8080"},
            {"LocalAI", ServerClient.OPENAI, "8080"},
            {"KoboldCpp", ServerClient.OPENAI, "5001"},
            {"Jan", ServerClient.OPENAI, "1337"},
            {"text-gen-webui", ServerClient.OPENAI, "5000"},
            {"vLLM", ServerClient.OPENAI, "8000"},
            {"other", ServerClient.AUTO, ""},
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private ServerStore store;
    private LinearLayout col, list, editor;
    private EditText name, url, key, model;
    private TextView status, typeAuto, typeOllama, typeOpenAi;
    private LinearLayout modelChips;
    private final List<TextView> presetChips = new ArrayList<>();
    private String editId = "", editType = ServerClient.AUTO, editDetected = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = ServerStore.get(this);
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
        title.setText("Server");
        title.setTypeface(Ui.groteskBold(this));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(Ui.INK);
        col.addView(title);
        TextView sub = small("Your own AI server on your computer: Ollama, LM Studio, llama.cpp server, LocalAI, KoboldCpp, Jan, "
                + "text-generation-webui, vLLM, or anything OpenAI-compatible. Same Wi-Fi, or a VPN like Tailscale.");
        sub.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(sub);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        col.addView(editor);
        renderList();
        if (store.all().isEmpty()) openEditor(null);
    }

    // ------------------------------------------------------------------ saved servers

    private void renderList() {
        list.removeAllViews();
        List<LlmServer> all = store.all();
        LlmServer active = store.active();
        for (LlmServer s : all) {
            LinearLayout card = card();
            LinearLayout top = new LinearLayout(this);
            top.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView n = text(s.name.isEmpty() ? ServerClient.hostPort(s.url) : s.name, 17, Ui.INK, Ui.groteskBold(this));
            top.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            boolean on = active != null && active.id.equals(s.id);
            if (on) top.addView(Ui.chip(this, "✓ in use", Ui.LIME));
            card.addView(top);
            TextView d = text(s.label() + "\nmodel: " + (s.model.isEmpty() ? "(none picked)" : s.model)
                    + (s.apiKey.isEmpty() ? "" : "\napi key: set"), 12, Ui.MUTED, Ui.mono(this));
            d.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
            card.addView(d);
            LinearLayout row = new LinearLayout(this);
            if (!on) {
                TextView use = Ui.chip(this, "use this", Ui.BLUE);
                use.setOnClickListener(x -> { store.setActive(s.id); new Prefs(this).setMode(Prefs.MODE_SERVER); renderList(); });
                row.addView(use);
            }
            TextView edit = Ui.chip(this, "edit", Ui.TEAL);
            edit.setOnClickListener(x -> openEditor(s));
            row.addView(edit, gap(row));
            TextView del = Ui.chip(this, "delete", Ui.CORAL);
            del.setOnClickListener(x -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Delete " + (s.name.isEmpty() ? "this server" : s.name) + "?")
                    .setPositiveButton("delete", (dd, w) -> { store.delete(s.id); renderList(); })
                    .setNegativeButton("cancel", null).show());
            row.addView(del, gap(row));
            card.addView(row);
        }
        TextView add = Ui.chip(this, "+ add server", Ui.LIME);
        add.setOnClickListener(x -> openEditor(null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 16);
        list.addView(add, lp);
    }

    // ------------------------------------------------------------------ editor

    void openEditor(LlmServer s) {
        editor.removeAllViews();
        presetChips.clear();
        editId = s == null ? "" : s.id;
        editType = s == null ? ServerClient.AUTO : s.type;
        editDetected = s == null ? "" : s.detected;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.box(Ui.CREAM, Ui.LINE, 1, 16, this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(this, 18);
        editor.addView(card, clp);
        card.addView(Ui.chip(this, s == null ? "add a server" : "edit server", Ui.BLUE), new LinearLayout.LayoutParams(-2, -2));

        label(card, "What are you running?");
        LinearLayout presets = new LinearLayout(this);
        for (String[] p : PRESETS) {
            TextView c = Ui.chip(this, p[0], Ui.MUTED);
            c.setOnClickListener(x -> applyPreset(p, c));
            presetChips.add(c);
            presets.addView(c, presets.getChildCount() == 0 ? new LinearLayout.LayoutParams(-2, -2) : gap(presets));
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(presets);
        card.addView(hs);

        name = field(card, "Name", s == null ? "" : s.name, InputType.TYPE_CLASS_TEXT);
        name.setHint("Gaming PC");
        url = field(card, "Address (IP or name, with port)", s == null ? "" : s.url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("192.168.1.20:11434");

        label(card, "API");
        LinearLayout types = new LinearLayout(this);
        typeAuto = Ui.chip(this, "auto-detect", Ui.MUTED);
        typeOllama = Ui.chip(this, "Ollama", Ui.MUTED);
        typeOpenAi = Ui.chip(this, "OpenAI-compatible", Ui.MUTED);
        typeAuto.setOnClickListener(x -> setType(ServerClient.AUTO));
        typeOllama.setOnClickListener(x -> setType(ServerClient.OLLAMA));
        typeOpenAi.setOnClickListener(x -> setType(ServerClient.OPENAI));
        types.addView(typeAuto);
        types.addView(typeOllama, gap(types));
        types.addView(typeOpenAi, gap(types));
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        ts.addView(types);
        card.addView(ts);
        setType(editType);

        key = field(card, "API key (optional, most local servers don't need one)", s == null ? "" : s.apiKey,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        model = field(card, "Model", s == null ? "" : s.model, InputType.TYPE_CLASS_TEXT);
        model.setHint("test connection to list them");
        modelChips = new LinearLayout(this);
        modelChips.setOrientation(LinearLayout.VERTICAL);
        card.addView(modelChips);

        LinearLayout row = new LinearLayout(this);
        TextView test = Ui.chip(this, "test connection", Ui.BLUE);
        test.setOnClickListener(x -> testConnection());
        row.addView(test);
        TextView hi = Ui.chip(this, "▶ test reply", Ui.TEAL);
        hi.setOnClickListener(x -> testReply());
        row.addView(hi, gap(row));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = Ui.dp(this, 14);
        card.addView(row, rlp);

        status = text("", 13, Ui.INK, Ui.mono(this));
        status.setTextIsSelectable(true);
        status.setPadding(0, Ui.dp(this, 10), 0, 0);
        card.addView(status);

        LinearLayout save = new LinearLayout(this);
        TextView ok = Ui.chip(this, "save", Ui.LIME);
        ok.setOnClickListener(x -> save());
        save.addView(ok);
        if (!store.all().isEmpty()) {
            TextView cancel = Ui.chip(this, "cancel", Ui.MUTED);
            cancel.setOnClickListener(x -> { editor.removeAllViews(); renderList(); });
            save.addView(cancel, gap(save));
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = Ui.dp(this, 16);
        card.addView(save, slp);
    }

    private void applyPreset(String[] p, TextView chip) {
        for (TextView c : presetChips) Ui.styleChip(c, c == chip ? Ui.LIME : Ui.MUTED);
        setType(p[1]);
        if (name.getText().toString().trim().isEmpty() && !"other".equals(p[0])) name.setText(p[0]);
        if (!p[2].isEmpty()) url.setText(withPort(url.getText().toString(), p[2]));
        url.requestFocus();
        url.setSelection(url.getText().length());
    }

    /** "192.168.1.20" + 11434 → "192.168.1.20:11434"; an address that already has a port gets the preset's. */
    static String withPort(String typed, String port) {
        String t = typed == null ? "" : typed.trim();
        String scheme = "";
        int s = t.indexOf("://");
        if (s >= 0) { scheme = t.substring(0, s + 3); t = t.substring(s + 3); }
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        if (t.startsWith("[")) {   // [ipv6]:port
            int e = t.indexOf(']');
            t = e > 0 ? t.substring(0, e + 1) : t;
        } else if (t.indexOf(':') >= 0 && t.indexOf(':') == t.lastIndexOf(':')) {
            t = t.substring(0, t.indexOf(':'));
        }
        if (t.isEmpty()) return scheme + ":" + port;
        return scheme + t + ":" + port;
    }

    private void setType(String t) {
        editType = t;
        Ui.styleChip(typeAuto, ServerClient.AUTO.equals(t) ? Ui.LIME : Ui.MUTED);
        Ui.styleChip(typeOllama, ServerClient.OLLAMA.equals(t) ? Ui.LIME : Ui.MUTED);
        Ui.styleChip(typeOpenAi, ServerClient.OPENAI.equals(t) ? Ui.LIME : Ui.MUTED);
    }

    private LlmServer fromForm() {
        return new LlmServer(editId, name.getText().toString(), url.getText().toString(), editType, editDetected,
                key.getText().toString(), model.getText().toString());
    }

    private void testConnection() {
        LlmServer s = fromForm();
        if (s.url.isEmpty()) { setStatus("Enter the server's address first, like 192.168.1.20:11434", Ui.CORAL); return; }
        setStatus("connecting to " + ServerClient.hostPort(s.url) + "…", Ui.MUTED);
        ServerClient c = new ServerClient(s.url, s.type, s.apiKey).timeouts(5000, 15000);
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ServerClient.Probe p = c.probe();
                long ms = System.currentTimeMillis() - t0;
                h.post(() -> {
                    editDetected = p.backend;
                    String kind = ServerClient.OLLAMA.equals(p.backend) ? "Ollama" : "OpenAI-compatible";
                    if (p.models.isEmpty()) {
                        setStatus("✓ connected: " + kind + " (" + ms + " ms), but it has no models yet. "
                                + (ServerClient.OLLAMA.equals(p.backend) ? "Run: ollama pull qwen2.5:1.5b" : "Load a model in the server first."), Ui.CORAL);
                    } else {
                        setStatus("✓ connected: " + kind + " · " + p.models.size() + " model" + (p.models.size() == 1 ? "" : "s") + " · " + ms + " ms", Ui.LIME);
                    }
                    showModels(p.models);
                });
            } catch (Exception e) {
                String m = e.getMessage();
                h.post(() -> setStatus("✗ " + m, Ui.CORAL));
            }
        }, "orbit-probe").start();
    }

    void showModels(List<String> models) {
        modelChips.removeAllViews();
        if (models.isEmpty()) return;
        String cur = model.getText().toString().trim();
        if (cur.isEmpty() || !models.contains(cur)) model.setText(models.get(0));
        label(modelChips, "Models on this server (tap one)");
        List<TextView> chips = new ArrayList<>();
        for (String m : models) {
            TextView c = Ui.chip(this, m, m.equals(model.getText().toString().trim()) ? Ui.LIME : Ui.MUTED);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            c.setOnClickListener(x -> {
                model.setText(m);
                for (TextView o : chips) Ui.styleChip(o, o == c ? Ui.LIME : Ui.MUTED);
            });
            chips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(this, 6);
            modelChips.addView(c, lp);
        }
    }

    private void testReply() {
        LlmServer s = fromForm();
        if (s.url.isEmpty()) { setStatus("Enter the server's address first", Ui.CORAL); return; }
        setStatus("asking " + (s.model.isEmpty() ? "the first model" : s.model) + " to say hi…", Ui.MUTED);
        ServerClient c = new ServerClient(s.url, s.backend(), s.apiKey).timeouts(6000, 120_000);
        final StringBuilder out = new StringBuilder();
        new Thread(() -> {
            try {
                String m = s.model;
                if (m.isEmpty()) {
                    List<String> ms = c.listModels();
                    if (ms.isEmpty()) throw new java.io.IOException("The server has no models yet.");
                    m = ms.get(0);
                }
                List<ServerClient.Msg> msgs = new ArrayList<>();
                msgs.add(new ServerClient.Msg("user", "Say hi in one short sentence."));
                ServerClient.Result r = c.chat(m, msgs, 0.7f, 60, piece -> h.post(() -> {
                    out.append(piece);
                    setStatus(out.toString(), Ui.INK);
                }), null);
                h.post(() -> setStatus("“" + r.text + "”\n✓ " + r.statsLine() + String.format(Locale.US, " · %.1f s", r.millis / 1000.0), Ui.LIME));
            } catch (Exception e) {
                String m = e.getMessage();
                h.post(() -> setStatus("✗ " + m, Ui.CORAL));
            }
        }, "orbit-test-reply").start();
    }

    private void save() {
        LlmServer s = fromForm();
        if (s.url.isEmpty()) { setStatus("Enter the server's address first, like 192.168.1.20:11434", Ui.CORAL); return; }
        if (s.name.isEmpty()) s = new LlmServer(s.id, ServerClient.hostPort(s.url), s.url, s.type, s.detected, s.apiKey, s.model);
        boolean first = store.all().isEmpty();
        LlmServer saved = store.save(s);
        Prefs p = new Prefs(this);
        if (first || editId.isEmpty()) { store.setActive(saved.id); p.setMode(Prefs.MODE_SERVER); }
        Toast.makeText(this, "Saved " + saved.name, Toast.LENGTH_SHORT).show();
        editor.removeAllViews();
        renderList();
    }

    // ------------------------------------------------------------------ bits

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        card.setBackground(Ui.box(Ui.CREAM, Ui.LINE, 1, 16, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        list.addView(card, lp);
        return card;
    }

    private LinearLayout.LayoutParams gap(LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(this, 8);
        return lp;
    }

    private TextView text(String s, float sp, int color, android.graphics.Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private TextView small(String s) {
        TextView t = text(s, 12, Ui.MUTED, Ui.mono(this));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private void label(LinearLayout parent, String s) {
        TextView t = text(s, 13, Ui.MUTED, Ui.grotesk(this));
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
        e.setHintTextColor(Ui.withAlpha(Ui.MUTED, 0x99));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        e.setBackground(Ui.box(Ui.PAPER, Ui.LINE, 1, 12, this));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    // used by screenshot tests
    void demoStatus(String s, boolean ok) { setStatus(s, ok ? Ui.LIME : Ui.CORAL); }
}
