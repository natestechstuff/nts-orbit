package com.natestechstuff.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The main screen: a voice + text chat.
 * On-device mode: a GGUF model running fully on the phone (llama.cpp), streamed and spoken
 * sentence by sentence. Server mode: a local AI server you run (Ollama, LM Studio, llama.cpp
 * server or any OpenAI-compatible endpoint), see {@link ServerClient}.
 */
public class MainActivity extends Activity implements ChatStore.Listener {
    public static final String EXTRA_TALK = "talk";
    static final int REQ_PERMS = 7;

    private enum State { READY, LISTENING, SENDING, WAITING, THINKING, SPEAKING }

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private ChatStore store;
    private Speaker speaker;
    private SpeechRecognizer recognizer;

    private LinearLayout chat;
    private ScrollView scroll;
    private TextView status, hint, modeLocal, modeServer, handsFreeChip;
    /** the request to the local AI server that's streaming right now (stop cancels it) */
    private volatile ServerClient.Cancel serverCancel;
    private LocalBrain brain;
    private TextView streamBubble;
    private View streamWrap;
    private SpeechChunker chunker;
    private boolean askedNotif;
    private LinearLayout onboarding;
    private EditText input;
    private MicButton mic;
    private SignalBarsView bars;
    private State state = State.READY;
    private boolean heardSpeech;
    private int quietRestarts;
    private boolean pendingTalk;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Dev.ON ? Dev.wrap(base) : base);   // Pro: Pro font size
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (Dev.ON) { Dev.applyTheme(this); devFontScale = Dev.fontScale(this); }
        store = ChatStore.get(this);
        speaker = Speaker.get(this);
        brain = LocalBrain.get(this);
        setContentView(build());
        renderHistory();
        pendingTalk = getIntent().getBooleanExtra(EXTRA_TALK, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_TALK, false)) pendingTalk = true;
    }

    /** Pro: font size the activity was created with (a change in the Pro menu recreates it). */
    private float devFontScale = 1f;

    @Override
    protected void onResume() {
        super.onResume();
        if (Dev.ON) {
            if (Dev.applyTheme(this) || Math.abs(devFontScale - Dev.fontScale(this)) > 0.001f) { recreate(); return; }
            Dev.onResume(this);
        }
        store.setListener(this);
        speaker.setStateListener(speaking -> {
            if (speaking) setState(State.SPEAKING);
        });
        speaker.applySettings();
        refreshMode();
        refreshOnboarding();
        warmLocalModel();
        if (pendingTalk) {
            pendingTalk = false;
            h.postDelayed(this::startListening, 350);
        }
    }

    @Override
    protected void onPause() {
        store.setListener(null);
        speaker.setStateListener(null);
        stopListening(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout

    View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GridDrawable(Ui.dp(this, 42)));
        root.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        int pad = Ui.dp(this, 18);

        // header: NTS Orbit mark, name, settings
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
        BrandMarkView mark = new BrandMarkView(this);
        header.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 35), Ui.dp(this, 35)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(this, 10), 0, 0, 0);
        TextView name = text(Dev.ON ? "NTS Orbit Dev" : "NTS Orbit", 19, Ui.INK, Ui.groteskBold(this));
        if (Dev.ON) Dev.secretEntry(mark, name);   // tap the logo or the name 7× (or long-press) → Pro menu
        TextView sub = text("nate's tech stuff", 11, Ui.MUTED, Ui.mono(this));
        names.addView(name);
        names.addView(sub);
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        // v2.4: visible "clear chat" (was only in Settings): a fresh start once the model goes weird
        TextView clearChat = Ui.chip(this, "clear chat", Ui.CORAL);
        clearChat.setOnClickListener(v -> confirmClearChat());
        header.addView(clearChat);
        View gap = new View(this);
        header.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        TextView gear = Ui.chip(this, "settings", Ui.MUTED);
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(gear);
        col.addView(header);
        View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        col.addView(rule, new LinearLayout.LayoutParams(-1, 1));

        // mode switch
        LinearLayout modes = new LinearLayout(this);
        modes.setPadding(pad, Ui.dp(this, 12), pad, 0);
        // Server first (Ollama, LM Studio, llama.cpp …), then the on-device model
        modeServer = Ui.chip(this, "server", Ui.MUTED);
        modeLocal = Ui.chip(this, "on-device · offline", Ui.MUTED);
        modeServer.setOnClickListener(v -> {
            if (ServerStore.get(this).active() == null) startActivity(new Intent(this, ServerSettingsActivity.class));
            setMode(Prefs.MODE_SERVER);
        });
        modeServer.setOnLongClickListener(v -> { startActivity(new Intent(this, ServerSettingsActivity.class)); return true; });
        modeLocal.setOnClickListener(v -> setMode(Prefs.MODE_LOCAL));
        modes.addView(modeServer);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.leftMargin = Ui.dp(this, 8);
        modes.addView(modeLocal, mlp);
        android.widget.HorizontalScrollView modeScroll = new android.widget.HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.addView(modes);
        col.addView(modeScroll);

        // onboarding card (hidden once everything is granted)
        onboarding = new LinearLayout(this);
        onboarding.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.setMargins(pad, Ui.dp(this, 12), pad, 0);
        col.addView(onboarding, olp);

        // chat
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
        scroll.addView(chat, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // status chip + bars
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(pad, Ui.dp(this, 6), pad, 0);
        status = Ui.chip(this, "ready", Ui.MUTED);
        statusRow.addView(status);
        bars = new SignalBarsView(this);
        bars.setBars(14);
        bars.setMode(SignalBarsView.IDLE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(this, 22), 1);
        blp.leftMargin = Ui.dp(this, 12);
        statusRow.addView(bars, blp);
        col.addView(statusRow);
        hint = text("", 11, Ui.MUTED, Ui.mono(this));
        hint.setPadding(pad, Ui.dp(this, 6), pad, 0);
        col.addView(hint);

        // controls: hands-free | mic | send-box
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 4));
        handsFreeChip = Ui.chip(this, "hands-free off", Ui.MUTED);
        handsFreeChip.setOnClickListener(v -> toggleHandsFree());
        controls.addView(handsFreeChip, new LinearLayout.LayoutParams(0, -2, 1));
        mic = new MicButton(this);
        mic.setOnClickListener(v -> onMic());
        controls.addView(mic, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104)));
        TextView clear = Ui.chip(this, "stop", Ui.CORAL);
        clear.setOnClickListener(v -> { brain.stop(); cancelServer(); speaker.stop(); stopListening(true); setState(State.READY); });
        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.END);
        right.addView(clear);
        controls.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(controls);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 14));
        input = new EditText(this);
        input.setHint("type, or tap the mic");
        input.setHintTextColor(Ui.withAlpha(Ui.MUTED, 0xaa));
        input.setTextColor(Ui.INK);
        input.setTypeface(Ui.grotesk(this));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        input.setBackground(Ui.box(Ui.CREAM, Ui.LINE, 1, 14, this));
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setSingleLine(false);
        input.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEND || enter) { sendTyped(); return true; }
            return false;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView send = new TextView(this);
        send.setText("send");
        send.setTypeface(Ui.monoMedium(this));
        send.setTextColor(Ui.PAPER);
        send.setAllCaps(true);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        send.setGravity(Gravity.CENTER);
        send.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 16), Ui.dp(this, 13));
        send.setBackground(Ui.box(Ui.LIME, Ui.LIME, 0, 14, this));
        send.setOnClickListener(v -> sendTyped());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = Ui.dp(this, 8);
        inputRow.addView(send, slp);
        col.addView(inputRow);

        setState(State.READY);
        return root;
    }

    private TextView text(String s, float sp, int color, android.graphics.Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(true);
        b.setTypeface(Ui.monoMedium(this));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(Ui.PAPER);
        b.setStateListAnimator(null);
        b.setBackground(Ui.box(color, color, 0, 12, this));
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        b.setOnClickListener(l);
        return b;
    }

    // ------------------------------------------------------------------ chat bubbles

    private void renderHistory() {
        chat.removeAllViews();
        List<ChatStore.Item> items = store.all();
        if (items.isEmpty()) {
            addBubble(ChatStore.FROM_SYSTEM, prefs.isLocal()
                    ? "Tap the mic and talk. Your own model answers right here on the phone, offline, and I read it out loud."
                    : prefs.isServer() ? "Tap the mic and talk. Your local AI server answers (Ollama, LM Studio, llama.cpp …) and I read it out loud."
                    :"", "", System.currentTimeMillis());
        }
        for (ChatStore.Item it : items) {
            if (Dev.ON) addThinkPanel(it.think, false);
            addBubble(it.from, it.text, it.via, it.time);
        }
        scrollDown();
    }

    @Override
    public void onAdded(ChatStore.Item item) {
        if (Dev.ON) addThinkPanel(item.think, false);
        addBubble(item.from, item.text, item.via, item.time);
        scrollDown();
    }

    void addBubble(String from, String body, String via, long time) {
        if (ChatStore.FROM_TOOL.equals(from)) { addToolChip(toolChip(body)); return; }
        boolean me = ChatStore.FROM_ME.equals(from);
        boolean system = ChatStore.FROM_SYSTEM.equals(from);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(me ? Gravity.END : Gravity.START);
        if (system) wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        String who = me ? "you" : ChatStore.FROM_TAB.equals(from) ? "tab" : ChatStore.FROM_JARVIS.equals(from) ? "orbit" : "orbit · note";
        String clock = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(time)).toLowerCase(Locale.US);
        String label = who + (TextUtils.isEmpty(via) ? "" : " · " + via) + " · " + clock;
        TextView meta = text(label.toUpperCase(Locale.US), 10, me ? Ui.LIME : ChatStore.FROM_TAB.equals(from) ? Ui.TEAL : Ui.BLUE, Ui.monoMedium(this));
        meta.setLetterSpacing(0.06f);
        meta.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 4));
        if (!system) wrap.addView(meta, new LinearLayout.LayoutParams(-2, -2));

        TextView t = text(body, system ? 13 : 16, me ? Ui.PAPER : system ? Ui.MUTED : Ui.INK, system ? Ui.mono(this) : Ui.grotesk(this));
        if (!me && !system && !TextUtils.isEmpty(body) && (!Dev.ON || Dev.flag(this, "markdown"))) t.setText(Markdown.render(body, Ui.withAlpha(Ui.INK, 0x16)));   // v2.3
        t.setLineSpacing(0, 1.15f);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 11));
        if (system) {
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.box(Ui.withAlpha(Ui.CREAM, 0xcc), Ui.LINE, 1, 14, this));
        } else if (me) {
            t.setBackground(Ui.box(Ui.LIME, Ui.LIME, 0, new float[]{16, 16, 4, 16}, this));
        } else {
            // the site's panel look: cream box, thin line, hard offset shadow
            Drawable shadow = Ui.box(Ui.SHADOW, Ui.SHADOW, 0, new float[]{4, 16, 16, 16}, this);
            Drawable face = Ui.box(Ui.CREAM, ChatStore.FROM_TAB.equals(from) ? Ui.withAlpha(Ui.TEAL, 0x88) : Ui.LINE, 1, new float[]{4, 16, 16, 16}, this);
            LayerDrawable ld = new LayerDrawable(new Drawable[]{shadow, face});
            int o = Ui.dp(this, 5);
            ld.setLayerInset(0, o, o, 0, 0);
            ld.setLayerInset(1, 0, 0, o, o);
            t.setBackground(ld);
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14) + o, Ui.dp(this, 11) + o);
        }
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * (system ? 0.9f : 0.78f));
        t.setMaxWidth(maxW);
        wrap.addView(t, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 12);
        chat.addView(wrap, lp);
        if (Dev.ON && !Dev.animations(this)) return;
        wrap.setAlpha(0f);
        wrap.setTranslationY(Ui.dp(this, 10));
        wrap.animate().alpha(1f).translationY(0).setDuration(260).start();
    }

    // ------------------------------------------------------------------ Pro: thought process

    /** The live "thinking…" panel above the reply being streamed (Pro). */
    private View liveThinkWrap;
    private TextView liveThinkHead, liveThinkBody;

    /** A collapsible "thought process" panel above one of Orbit's replies (Pro, "Show thought process"). */
    private void addThinkPanel(String stored, boolean live) {
        if (!Dev.ON || stored == null || stored.isEmpty() || !Dev.showThinking(this)) return;
        View[] v = thinkPanel(Dev.thinkHeaderOf(stored), Dev.thinkBodyOf(stored));
        chat.addView(v[0], thinkLp());
    }

    private LinearLayout.LayoutParams thinkLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 6);
        return lp;
    }

    /** {wrap, header, body} */
    private View[] thinkPanel(String head, String body) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        final boolean[] open = {Dev.thinkExpanded(this)};
        TextView h1 = text((open[0] ? "▾ " : "▸ ") + "💭 " + head, 11, Ui.TEAL, Ui.monoMedium(this));
        h1.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        h1.setBackground(Ui.box(Ui.withAlpha(Ui.TEAL, 0x18), Ui.withAlpha(Ui.TEAL, 0x55), 1, 10, this));
        h1.setMaxWidth(maxW);
        TextView b1 = text(body.trim(), 12, Ui.MUTED, Ui.mono(this));
        b1.setLineSpacing(0, 1.12f);
        b1.setTextIsSelectable(true);
        b1.setMaxWidth(maxW);
        b1.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        b1.setBackground(Ui.box(Ui.withAlpha(Ui.CREAM, 0x99), Ui.withAlpha(Ui.TEAL, 0x33), 1, 10, this));
        b1.setVisibility(open[0] ? View.VISIBLE : View.GONE);
        h1.setOnClickListener(x -> {
            open[0] = !open[0];
            b1.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            String t = h1.getText().toString();
            h1.setText((open[0] ? "▾ " : "▸ ") + t.substring(2));
        });
        wrap.addView(h1, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Ui.dp(this, 4);
        wrap.addView(b1, blp);
        return new View[]{wrap, h1, b1};
    }

    /** Pro: a piece of thought process arrived (local <think>, server reasoning, or a tool decision). */
    private void devThinking(String piece) {
        if (piece != null && !piece.isEmpty()) Dev.think(piece);
        devRefreshLiveThink();
    }

    private void devRefreshLiveThink() {
        if (!Dev.showThinking(this) || streamWrap == null) return;
        String body = Dev.pendingThink();
        if (body.trim().isEmpty()) return;
        if (liveThinkWrap == null || liveThinkWrap.getParent() == null) {
            View[] v = thinkPanel(Dev.thinkHeader(true), body);
            liveThinkWrap = v[0]; liveThinkHead = (TextView) v[1]; liveThinkBody = (TextView) v[2];
            liveThinkBody.setVisibility(View.VISIBLE);   // live: always open while it streams
            liveThinkHead.setText("▾ 💭 " + Dev.thinkHeader(true));
            chat.addView(liveThinkWrap, Math.max(0, chat.indexOfChild(streamWrap)), thinkLp());
        } else {
            liveThinkBody.setText(body.trim());
            String t = liveThinkHead.getText().toString();
            liveThinkHead.setText(t.substring(0, 2) + "💭 " + Dev.thinkHeader(true));
        }
        scrollDown();
    }

    /** Pro: the raw model output / prompt-as-sent bubbles (not saved in the history). */
    private void devDebugBubbles() {
        // snapshot now, show right after the reply bubble lands
        final String prompt = Dev.showPrompt(this) && !Dev.lastPrompt.isEmpty() ? "prompt as sent · " + Dev.lastParams + "\n\n" + Dev.lastPrompt : null;
        final String raw = Dev.showRaw(this) && !Dev.lastRaw.isEmpty() ? "raw output · " + Dev.lastStats + "\n\n" + Dev.lastRaw : null;
        if (prompt == null && raw == null) return;
        h.post(() -> {
            if (prompt != null) addBubble(ChatStore.FROM_SYSTEM, prompt, "", System.currentTimeMillis());
            if (raw != null) addBubble(ChatStore.FROM_SYSTEM, raw, "", System.currentTimeMillis());
            scrollDown();
        });
    }

    /** v2.3: a small "⚙ set_timer 5 min" chip under the user's message. */
    private void addToolChip(String label) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.START);
        TextView chip = Ui.chip(this, label, Ui.LIME);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.85f));
        wrap.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 12);
        lp.leftMargin = Ui.dp(this, 4);
        chat.addView(wrap, lp);
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------------------ state

    private void setState(State s) {
        state = s;
        switch (s) {
            case LISTENING:
                setStatus("listening…", Ui.TEAL);
                mic.setState(MicButton.LISTENING);
                bars.setMode(SignalBarsView.BREATHE);
                break;
            case SENDING:
                setStatus("sending…", Ui.BLUE);
                mic.setState(MicButton.WAITING);
                bars.setMode(SignalBarsView.BREATHE);
                break;
            case WAITING:
                setStatus("waiting…", Ui.BLUE);
                mic.setState(MicButton.WAITING);
                bars.setMode(SignalBarsView.BREATHE);
                break;
            case THINKING:
                setStatus(prefs.isLocal() ? "my model is thinking…" : "orbit is thinking…", Ui.BLUE);
                mic.setState(MicButton.WAITING);
                bars.setMode(SignalBarsView.BREATHE);
                break;
            case SPEAKING:
                setStatus("speaking", Ui.LIME);
                mic.setState(MicButton.SPEAKING);
                bars.setMode(SignalBarsView.WAVE);
                break;
            default:
                setStatus(prefs.handsFree() ? "hands-free · ready" : "ready", Ui.MUTED);
                mic.setState(MicButton.IDLE);
                bars.setMode(SignalBarsView.IDLE);
        }
        refreshHint();
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        Ui.styleChip(status, color);
    }

    private void refreshHint() {
        if (hint == null) return;
        if (prefs.isLocal()) {
            LocalBrain.Stats st = brain.lastStats();
            if (state == State.THINKING && brain.isLoading()) hint.setText("loading your model into memory…");
            else if (prefs.modelPath().isEmpty()) hint.setText("no model yet · settings → on-device model → pick .gguf");
            else if (st != null && state == State.READY) hint.setText("offline · last reply " + st.line());
            else hint.setText("offline · runs on this phone · " + new java.io.File(prefs.modelPath()).getName());
            return;
        }
        if (prefs.isServer()) {
            LlmServer sv = ServerStore.get(this).active();
            if (sv == null) hint.setText("no server yet · tap server to add Ollama, LM Studio …");
            else hint.setText("server · " + sv.label() + (sv.model.isEmpty() ? " · pick a model in settings" : " · " + sv.model));
            return;
        }
    }

    private void setMode(String m) {
        if (!m.equals(Prefs.MODE_LOCAL)) brain.stop();
        if (!m.equals(Prefs.MODE_SERVER)) cancelServer();
        prefs.setMode(m);
        refreshMode();
        refreshOnboarding();
        warmLocalModel();
    }

    private void refreshMode() {
        String m = prefs.mode();
        Ui.styleChip(modeLocal, Prefs.MODE_LOCAL.equals(m) ? Ui.LIME : Ui.MUTED);
        LlmServer sv = ServerStore.get(this).active();
        modeServer.setText(sv == null ? "server · add one" : "server · " + sv.name);
        Ui.styleChip(modeServer, Prefs.MODE_SERVER.equals(m) ? Ui.BLUE : Ui.MUTED);
        Ui.styleChip(handsFreeChip, prefs.handsFree() ? Ui.LIME : Ui.MUTED);
        handsFreeChip.setText(prefs.handsFree() ? "hands-free on" : "hands-free off");
        refreshHint();
    }

    private void toggleHandsFree() {
        prefs.setHandsFree(!prefs.handsFree());
        refreshMode();
        if (prefs.handsFree() && state == State.READY) startListening();
        if (!prefs.handsFree() && state == State.LISTENING) { stopListening(true); setState(State.READY); }
        else if (state == State.READY) setState(State.READY);
    }

    // ------------------------------------------------------------------ onboarding

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] missingPerms() {
        List<String> m = new ArrayList<>();
        if (!has(Manifest.permission.RECORD_AUDIO)) m.add(Manifest.permission.RECORD_AUDIO);
        return m.toArray(new String[0]);
    }

    void refreshOnboarding() {
        onboarding.removeAllViews();
        if (prefs.isLocal()) { localOnboarding(); return; }
        serverOnboarding();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshOnboarding();
    }

    // ------------------------------------------------------------------ voice in

    private void onMic() {
        if (state == State.SPEAKING) {
            speaker.stop();
            setState(State.READY);
            return;
        }
        if (state == State.LISTENING) {
            stopListening(false);   // stop, but still deliver what was heard
            return;
        }
        quietRestarts = 0;
        startListening();
    }

    void startListening() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            store.add(ChatStore.FROM_SYSTEM, "No speech recognizer on this phone. Install or enable the Google app, or just type.", "");
            return;
        }
        speaker.stop();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        heardSpeech = false;
        input.setText("");
        Haptics.listen(this);
        setState(State.LISTENING);
        recognizer.startListening(i);
    }

    void stopListening(boolean cancel) {
        if (recognizer == null) return;
        if (cancel) recognizer.cancel(); else recognizer.stopListening();
        if (cancel && state == State.LISTENING) setState(State.READY);
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() { heardSpeech = true; }
        @Override public void onRmsChanged(float rms) { mic.setLevel((rms + 2f) / 12f); }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int type, Bundle params) {}

        @Override
        public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) input.setText(r.get(0));
        }

        @Override
        public void onResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String said = (r == null || r.isEmpty()) ? "" : r.get(0).trim();
            input.setText("");
            if (said.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
            if (Dev.ON && prefs.handsFree() && Dev.wakeOn(MainActivity.this)) {
                // Pro: hands-free only reacts to "hey orbit …" (Pro settings → Voice)
                String after = Dev.wakeFilter(said, Dev.wakePhrase(MainActivity.this), Dev.wakeSensitivity(MainActivity.this));
                if (after == null) { setStatus("heard \"" + Diag.snippet(said) + "\" · no wake phrase", Ui.MUTED); h.postDelayed(MainActivity.this::startListening, 300); return; }
                if (after.isEmpty()) { setStatus("yes? listening…", Ui.TEAL); h.postDelayed(MainActivity.this::startListening, 300); return; }
                said = after;
            }
            quietRestarts = 0;
            send(said);
        }

        @Override
        public void onError(int error) {
            if (state != State.LISTENING) return;
            boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (quiet && prefs.handsFree() && quietRestarts < 3) {
                quietRestarts++;
                h.postDelayed(MainActivity.this::startListening, 400);   // keep listening, hands-free
                return;
            }
            setState(State.READY);
            if (!quiet) store.add(ChatStore.FROM_SYSTEM, "Mic error (" + errorText(error) + "). Tap the mic to try again.", "");
            else if (prefs.handsFree()) setStatus("hands-free paused · tap mic", Ui.MUTED);
        }
    }

    static String errorText(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio";
            case SpeechRecognizer.ERROR_CLIENT: return "client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "no mic permission";
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recognizer busy";
            case SpeechRecognizer.ERROR_SERVER: return "server";
            default: return "code " + e;
        }
    }

    // ------------------------------------------------------------------ send

    private void sendTyped() {
        String s = input.getText().toString().trim();
        if (s.isEmpty()) return;
        input.setText("");
        if (state == State.LISTENING) stopListening(true);
        send(s);
    }

    void send(String text) {
        Haptics.send(this);
        if (prefs.isLocal()) sendToLocal(text);
        else if (prefs.isServer()) sendToServer(text);
    }

    // ------------------------------------------------------------------ server

    static final String VIA_SERVER = "server";

    private void serverOnboarding() {
        if (!prefs.isServer() || ServerStore.get(this).active() != null) {
            onboarding.setVisibility(View.GONE);
            return;
        }
        onboarding.setVisibility(View.VISIBLE);
        onboarding.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 14));
        onboarding.setBackground(Ui.box(Ui.PANEL, Ui.LINE, 1, 16, this));
        onboarding.addView(text("Connect a local AI server", 16, Ui.INK, Ui.groteskBold(this)));
        TextView body = text("Ollama, LM Studio, llama.cpp server, LocalAI, KoboldCpp, Jan, text-generation-webui or vLLM on your computer. "
                + "Same Wi-Fi, or a VPN like Tailscale. Or switch to on-device and run a model on the phone.", 13, Ui.MUTED, Ui.grotesk(this));
        body.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        onboarding.addView(body);
        onboarding.addView(button("add server", Ui.BLUE, v -> startActivity(new Intent(this, ServerSettingsActivity.class))));
    }

    private void cancelServer() {
        ServerClient.Cancel c = serverCancel;
        serverCancel = null;
        if (c != null) c.cancel();
    }

    /** Recent server-mode conversation (oldest first), as chat messages for the server. */
    private List<ServerClient.Msg> serverHistory() {
        List<ChatStore.Item> items = store.all();
        List<ServerClient.Msg> out = new ArrayList<>();
        int max = Math.max(0, prefs.historyTurns());
        for (int i = items.size() - 1; i >= 0 && out.size() < max; i--) {
            ChatStore.Item it = items.get(i);
            if (it.via == null || !it.via.startsWith(VIA_SERVER)) continue;
            if (ChatStore.FROM_ME.equals(it.from)) out.add(0, new ServerClient.Msg("user", it.text));
            else if (ChatStore.FROM_JARVIS.equals(it.from) && !it.via.endsWith("instant")) out.add(0, new ServerClient.Msg("assistant", Dev.ON ? Dev.historyText(this, it) : it.text));
        }
        while (!out.isEmpty() && "assistant".equals(out.get(0).role)) out.remove(0);
        return out;
    }

    private void sendToServer(String text) {
        final LlmServer sv = ServerStore.get(this).active();
        if (sv == null) {
            store.add(ChatStore.FROM_SYSTEM, "No server yet. Settings → Server → add one (Ollama, LM Studio, llama.cpp …).", "");
            refreshOnboarding();
            return;
        }
        cancelServer();
        speaker.stop();
        if (Dev.ON) Dev.beginTurn();
        final List<ServerClient.Msg> msgs = new ArrayList<>();
        String system = LocalBrain.systemPrompt(this, prefs);
        if (Dev.ON && Dev.forceThinking(this)) system = system + "\n\n" + Dev.FORCE_THINK_PROMPT;
        if (!system.isEmpty()) msgs.add(new ServerClient.Msg("system", system));
        msgs.addAll(serverHistory());
        store.add(ChatStore.FROM_ME, text, VIA_SERVER);
        msgs.add(new ServerClient.Msg("user", text));
        // phone actions ("turn on the flashlight", "set a 5 minute timer") still run right here, no server
        if (prefs.toolsOn() && (!Dev.ON || Dev.flag(this, "router"))) {
            ToolCalls.Call direct = ActionRouter.match(text, appLookup());
            if (direct != null) { runRouted(text, direct, "instant", VIA_SERVER); refreshHint(); return; }
        }
        setState(State.THINKING);
        startStreamBubble(VIA_SERVER);
        chunker = new SpeechChunker();
        final SpeechChunker myChunker = chunker;
        final boolean speak = prefs.speakReplies();
        final SpeechChunker.Sink sink = s -> {
            if (state != State.SPEAKING && state != State.LISTENING) setState(State.SPEAKING);
            speaker.enqueueQuiet(s);
        };
        final ServerClient.Cancel cancel = new ServerClient.Cancel();
        serverCancel = cancel;
        final ServerClient client = new ServerClient(sv.url, sv.backend(), sv.apiKey);
        final long t0 = System.currentTimeMillis();
        final int[] devPieces = {0};
        if (Dev.ON) devServerHooks(client, msgs, myChunker, cancel, t0, devPieces);
        final float temp = prefs.temperature();
        final int maxTokens = prefs.maxTokens();
        hint.setText("server · " + sv.label() + " · waiting for the first word…");
        new Thread(() -> {
            ServerClient.Result res = null;
            String err = null;
            try {
                String model = sv.model;
                if (model.isEmpty()) {
                    List<String> models = client.listModels();
                    if (models.isEmpty()) throw new java.io.IOException("The server has no models. Ollama: ollama pull qwen2.5:1.5b. LM Studio: load a model first.");
                    model = models.get(0);
                }
                res = client.chat(model, msgs, temp, maxTokens, piece -> h.post(() -> {
                    if (myChunker != chunker || cancel.isCancelled()) return;
                    if (streamBubble != null) { streamBubble.append(piece); scrollDown(); }
                    if (Dev.ON && Dev.liveStats(this)) hint.setText(Dev.liveLine(++devPieces[0], t0, 0));
                    if (speak && (!Dev.ON || Dev.flag(this, "speak_while_streaming"))) myChunker.push(piece, sink);
                }), cancel);
            } catch (java.io.IOException e) {
                err = e.getMessage();
            }
            final ServerClient.Result r = res;
            final String e = err;
            h.post(() -> {
                if (myChunker != chunker) return;
                if (serverCancel == cancel) serverCancel = null;
                removeStreamBubble();
                if (cancel.isCancelled()) {
                    if (r != null && !r.text.isEmpty()) store.add(ChatStore.FROM_JARVIS, r.text, VIA_SERVER + " · stopped");
                    setState(State.READY);
                    return;
                }
                if (e != null || r == null) {
                    store.add(ChatStore.FROM_SYSTEM, "Server: " + (e == null ? "no reply" : e), "");
                    setState(State.READY);
                    refreshHint();
                    return;
                }
                String reply = r.text.isEmpty() ? "(no reply)" : r.text;
                if (Dev.ON) { Dev.lastRaw = Dev.cap(Dev.lastServerResponse); Dev.lastStats = r.statsLine(); devDebugBubbles(); }
                store.add(ChatStore.FROM_JARVIS, reply, VIA_SERVER + " · " + r.statsLine());
                Haptics.receive(this);
                if (speak) {
                    myChunker.finish(s -> { if (state != State.LISTENING) setState(State.SPEAKING); speaker.enqueueQuiet(s); });
                    speaker.whenDrained(this::afterSpeaking);
                } else afterSpeaking();
                refreshHint();
            });
        }, "orbit-server").start();
    }

    /** Pro: timeout, streaming, extra sampling options, raw JSON capture and the thought process for a server reply. */
    private void devServerHooks(ServerClient client, List<ServerClient.Msg> msgs, SpeechChunker myChunker,
                                ServerClient.Cancel cancel, long t0, int[] pieces) {
        client.timeouts(6000, Dev.serverTimeoutMs(this));
        ServerClient.DevHooks d = new ServerClient.DevHooks();
        d.stream = Dev.serverStream(this);
        d.thinkFilter = Dev.flag(this, "server_think_filter");
        d.options = Dev.serverOptions(this);
        final StringBuilder resp = new StringBuilder();
        d.raw = new ServerClient.RawSink() {
            @Override public void onRequest(String url, String body) {
                Dev.lastServerUrl = url;
                try { Dev.lastServerRequest = Dev.cap(new org.json.JSONObject(body).toString(2)); } catch (Exception e) { Dev.lastServerRequest = Dev.cap(body); }
                StringBuilder p = new StringBuilder("POST " + url + "\n");
                for (ServerClient.Msg m : msgs) p.append("<|").append(m.role).append("|>\n").append(m.content).append("\n");
                Dev.lastPrompt = Dev.cap(p.toString());
                Dev.lastParams = "server · stream " + d.stream + (d.options == null ? "" : " · " + d.options);
            }
            @Override public void onLine(String line) {
                if (resp.length() < 64 * 1024) resp.append(line).append('\n');
                Dev.lastServerResponse = resp.toString();
            }
        };
        d.think = piece -> h.post(() -> {
            if (myChunker != chunker || cancel.isCancelled()) return;
            devThinking(piece);
            if (Dev.liveStats(this)) hint.setText(Dev.liveLine(++pieces[0], t0, 1));
        });
        client.dev(d);
    }

    // ------------------------------------------------------------------ my model (on-device)

    /** Load the model in the background (foreground service keeps it warm) so the first reply is fast. */
    private void warmLocalModel() {
        if (!prefs.isLocal() || prefs.modelPath().isEmpty()) return;
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) return;   // UI screenshot tests: no native lib
        if (android.os.Build.VERSION.SDK_INT >= 33 && !askedNotif && prefs.keepModelLoaded()
                && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            askedNotif = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS + 1);
        }
        if (prefs.keepModelLoaded()) LocalBrainService.start(this);
        if (!brain.isLoaded() && !brain.isLoading()) {
            brain.ensureLoaded(new LocalBrain.LoadListener() {
                @Override public void onLoaded(String d) { refreshHint(); prewarmTools(); }
                @Override public void onLoadFailed(String e) {
                    store.add(ChatStore.FROM_SYSTEM, "Couldn't load your model: " + e, "");
                }
            });
            refreshHint();
        } else if (brain.isLoaded()) {
            prewarmTools();
        }
    }

    /** v2.3: get the tool prompt into the model's memory before the first question. */
    private void prewarmTools() {
        if (!prefs.toolsOn() || brain.isGenerating()) return;
        if (Dev.ON && !Dev.flag(this, "prewarm")) return;
        brain.prewarm(ToolCalls.systemPrompt(LocalBrain.systemPrompt(this, prefs), true, false));
    }

    private void localOnboarding() {
        if (!prefs.modelPath().isEmpty() && new java.io.File(prefs.modelPath()).exists()) {
            onboarding.setVisibility(View.GONE);
            return;
        }
        onboarding.setVisibility(View.VISIBLE);
        onboarding.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 14));
        onboarding.setBackground(Ui.box(Ui.PANEL, Ui.LINE, 1, 16, this));
        TextView title = text("Load your model", 16, Ui.INK, Ui.groteskBold(this));
        onboarding.addView(title);
        TextView body = text("Download a small GGUF chat model to your phone (a 0.5B–3B “Instruct” model in Q4_K_M works well, e.g. Qwen2.5 1.5B Instruct) and pick it here. NTS Orbit copies it into the app and runs it offline. No model ships with the app.", 13, Ui.MUTED, Ui.grotesk(this));
        body.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        onboarding.addView(body);
        onboarding.addView(button("pick model file", Ui.LIME, v -> startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_PICK_MODEL, true))));
    }

    /** The recent local conversation (oldest first) — what the model remembers. */
    private List<LocalBrain.Turn> localHistory() {
        List<ChatStore.Item> items = store.all();
        List<LocalBrain.Turn> out = new ArrayList<>();
        int max = Math.max(0, prefs.historyTurns());
        // v2.4: fake "I turned on the flashlight" replies (no tool call before them), e.g. saved by
        // v2.3, are left out together with the message they answered, so the model can't copy them
        java.util.Set<Integer> drop = new java.util.HashSet<>();
        int lastLocal = -1;
        for (int i = 0; i < items.size(); i++) {
            ChatStore.Item it = items.get(i);
            if (it.via == null || !it.via.startsWith(VIA_LOCAL)) continue;
            if (ChatStore.FROM_JARVIS.equals(it.from)) {
                ChatStore.Item prev = lastLocal >= 0 ? items.get(lastLocal) : null;
                boolean afterTool = prev != null && ChatStore.FROM_TOOL.equals(prev.from);
                String asked = prev != null && ChatStore.FROM_ME.equals(prev.from) ? prev.text : "";
                if (!afterTool && FakeClaim.isFake(asked, it.text)) {
                    drop.add(i);
                    if (prev != null && ChatStore.FROM_ME.equals(prev.from)) drop.add(lastLocal);
                }
            }
            lastLocal = i;
        }
        for (int i = items.size() - 1; i >= 0 && out.size() < max; i--) {
            ChatStore.Item it = items.get(i);
            if (it.via == null || !it.via.startsWith(VIA_LOCAL)) continue;
            if (drop.contains(i)) continue;
            if (ChatStore.FROM_ME.equals(it.from)) out.add(0, new LocalBrain.Turn("user", it.text));
            else if (ChatStore.FROM_JARVIS.equals(it.from)) out.add(0, new LocalBrain.Turn("assistant", Dev.ON ? Dev.historyText(this, it) : it.text));
            else if (ChatStore.FROM_TOOL.equals(it.from)) {
                // v2.3: the call (and the phone's answer) go back in, so the model keeps using tools
                try {
                    org.json.JSONObject o = new org.json.JSONObject(it.text);
                    String resp = o.optString("responses", "");
                    if (!resp.isEmpty()) out.add(0, new LocalBrain.Turn("user", resp));
                    out.add(0, new LocalBrain.Turn("assistant", o.optString("calls", "")));
                } catch (Exception ignored) {
                }
            }
        }
        // the prompt must not start with a dangling assistant turn (or a tool result without its call)
        while (!out.isEmpty() && ("assistant".equals(out.get(0).role) || out.get(0).content.startsWith("<tool_response>"))) out.remove(0);
        return out;
    }

    static final String VIA_LOCAL = "my model";

    private void sendToLocal(String text) {
        if (prefs.modelPath().isEmpty()) {
            store.add(ChatStore.FROM_SYSTEM, "No model loaded yet. Settings → My model → pick a .gguf file.", "");
            refreshOnboarding();
            return;
        }
        if (brain.isGenerating()) { brain.stop(); speaker.stop(); }
        if (Dev.ON) Dev.beginTurn();
        final List<LocalBrain.Turn> history = localHistory();
        store.add(ChatStore.FROM_ME, text, VIA_LOCAL);
        history.add(new LocalBrain.Turn("user", text));
        speaker.stop();
        // v2.3: tools (Qwen2.5's native <tools> block) (the ask_tab hand-off is off in this app)
        final boolean tools = prefs.toolsOn();
        final boolean askTab = false;   // no hand-off target in this app: phone tools only
        // v2.4: obvious phone actions ("turn on the flashlight", "set a 5 minute timer") skip the model
        if (tools && (!Dev.ON || Dev.flag(this, "router"))) {
            ToolCalls.Call direct = ActionRouter.match(text, appLookup());
            if (direct != null) { runRouted(text, direct, "instant"); refreshHint(); return; }
        }
        final String system = ToolCalls.systemPrompt(LocalBrain.systemPrompt(this, prefs), tools, askTab);
        runLocal(text, history, system, tools, askTab, ROUND_FIRST, -1f, null);
        refreshHint();
    }

    static final int ROUND_FIRST = 0, ROUND_RETRY = 1, ROUND_FOLLOW_UP = 2, ROUND_CLAIM_RETRY = 3;

    /**
     * One generation. round: FIRST = the user's message, RETRY = once more after a tool call we couldn't
     * read, FOLLOW_UP = the short spoken answer after local tools ran (fallback = what to say if that fails).
     */
    private void runLocal(String userText, List<LocalBrain.Turn> history, String system, boolean tools, boolean askTab,
                          int round, float temp, String fallback) {
        setState(State.THINKING);
        startStreamBubble();
        chunker = new SpeechChunker();
        final SpeechChunker myChunker = chunker;
        final boolean speak = prefs.speakReplies();
        final boolean guard = askTab && round != ROUND_FOLLOW_UP;
        // v2.4: a tool-less "I turned on the flashlight" is held back, never shown or spoken
        final boolean claimGuard = tools && round != ROUND_FOLLOW_UP && (!Dev.ON || Dev.flag(this, "fake_claim_guard"));
        final ToolCalls.StreamFilter filter = new ToolCalls.StreamFilter(guard, claimGuard ? userText : null, round == ROUND_CLAIM_RETRY);
        final long t0 = System.currentTimeMillis();
        final int[] pieces = {0};
        final boolean[] toolHint = {false};
        final SpeechChunker.Sink sink = s -> {
            if (state != State.SPEAKING && state != State.LISTENING) setState(State.SPEAKING);
            speaker.enqueueQuiet(s);
        };
        LocalBrain.KeepGoing keep = tools ? soFar -> !ToolCalls.shouldStop(soFar, guard) && !filter.claimed() : null;
        final boolean devStream = !Dev.ON || Dev.flag(this, "speak_while_streaming");
        brain.generate(history, system, temp, keep, new LocalBrain.GenListener() {
            @Override public void onThinking(String piece) {
                if (myChunker != chunker) return;
                if (Dev.ON) { pieces[0]++; devThinking(piece); if (Dev.liveStats(MainActivity.this)) hint.setText(Dev.liveLine(pieces[0], t0, 1)); }
            }
            @Override public void onPiece(String piece) {
                if (myChunker != chunker) return;
                pieces[0]++;
                String vis = tools ? filter.push(piece) : piece;
                if (!vis.isEmpty() && streamBubble != null) { streamBubble.append(vis); scrollDown(); }
                if (tools && !toolHint[0] && (filter.inToolCall() || filter.refused() || filter.claimed())) {
                    toolHint[0] = true;
                    hint.setText(filter.refused() ? ("⚙ …") : filter.claimed() ? "⚙ doing it for real…" : "⚙ using a tool…");
                } else if (Dev.ON && !toolHint[0] && Dev.liveStats(MainActivity.this)) {
                    hint.setText(Dev.liveLine(pieces[0], t0, 0));
                } else if (!toolHint[0] && pieces[0] % 8 == 0) {
                    double secs = (System.currentTimeMillis() - t0) / 1000.0;
                    hint.setText(String.format(Locale.US, "offline · %d tok · %.1f s", pieces[0], secs));
                }
                if (speak && !vis.isEmpty() && devStream) myChunker.push(vis, sink);
            }
            @Override public void onDone(String reply, LocalBrain.Stats st) {
                if (myChunker != chunker) return;
                if (Dev.ON) devDebugBubbles();
                String tail = tools ? filter.finish() : "";
                removeStreamBubble();
                if (!tools || st.stopped) {
                    finishLocal(reply.isEmpty() ? (st.stopped ? "(stopped)" : "(no reply)") : reply, st, speak, myChunker, sink, tail);
                    return;
                }
                ToolCalls.Parsed parsed = ToolCalls.parse(reply);
                Diag.log(MainActivity.this, "my model", "jarvis", reply, "tools: " + describe(parsed, filter, round));
                if (round == ROUND_FOLLOW_UP) {
                    // after local tools: one short answer, never another tool round
                    String say = parsed.text;
                    if (parsed.sawCall || say.isEmpty() || say.length() > 400) {
                        say = fallback == null ? "Done." : fallback;
                        tail = say;   // nothing of the reply was shown/spoken as text
                    }
                    finishLocal(say, st, speak, myChunker, sink, tail);
                    return;
                }
                if (!parsed.calls.isEmpty()) {
                    runTools(userText, history, system, tools, askTab, parsed, st);
                    return;
                }
                if (parsed.sawCall) {
                    // a tool call we couldn't read: retry once (cooler), then the hand-off (off in this app)
                    if (round == ROUND_FIRST) {
                        hint.setText("⚙ tool call garbled, retrying…");
                        runLocal(userText, history, system, tools, askTab, ROUND_RETRY, 0.2f, null);
                    } else if (askTab) {
                        askTabFromJarvis(ToolDispatcher.dispatch(new ToolCalls.Call(ToolCalls.ASK_TAB,
                                ToolDispatcher.obj("request", userText)), userText, true, null));
                    } else {
                        finishLocal("Sorry, I couldn't work that one out.", st, speak, myChunker, sink, "Sorry, I couldn't work that one out.");
                    }
                    return;
                }
                if (claimGuard && (filter.claimed() || FakeClaim.isFake(userText, parsed.text.isEmpty() ? reply : parsed.text))) {
                    // v2.4: it SAID it did a phone action but called no tool. Throw the reply away.
                    onFakeClaim(userText, history, system, tools, askTab, round, reply);
                    return;
                }
                if (round == ROUND_CLAIM_RETRY && !parsed.text.isEmpty() && !filter.refused() && !parsed.refusal) {
                    // the nudged retry answered in plain words (no claim): fine, but it may only be shown now
                    finishLocal(parsed.text, st, speak, myChunker, sink, parsed.text);
                    return;
                }
                if (askTab && (filter.refused() || parsed.refusal || reply.trim().isEmpty())) {
                    // "I'm sorry, I can't…" → the hand-off (off in this app)
                    askTabFromJarvis(ToolDispatcher.dispatch(new ToolCalls.Call(ToolCalls.ASK_TAB,
                            ToolDispatcher.obj("request", userText)), userText, true, null));
                    return;
                }
                finishLocal(parsed.text.isEmpty() ? reply : parsed.text, st, speak, myChunker, sink, tail);
            }
            @Override public void onError(String error) {
                if (myChunker != chunker) return;
                removeStreamBubble();
                store.add(ChatStore.FROM_SYSTEM, "My model: " + error, "");
                setState(State.READY);
            }
        });
    }

    private static String describe(ToolCalls.Parsed p, ToolCalls.StreamFilter f, int round) {
        String r = round == ROUND_FIRST ? "" : round == ROUND_RETRY ? "retry · " : "follow-up · ";
        if (!p.calls.isEmpty()) return r + p.calls.size() + " call(s): " + p.calls.get(0).name + (p.malformed > 0 ? " (+" + p.malformed + " unreadable)" : "");
        if (p.sawCall) return r + "unreadable tool call";
        if (f.refused() || p.refusal) return r + ("refusal");
        return r + "no tool";
    }

    /** A plain spoken reply from the local model. tail = the part not yet streamed to the voice. */
    private void finishLocal(String text, LocalBrain.Stats st, boolean speak, SpeechChunker myChunker, SpeechChunker.Sink sink, String tail) {
        store.add(ChatStore.FROM_JARVIS, text, VIA_LOCAL + " · " + st.line());
        Haptics.receive(this);
        if (speak && !st.stopped) {
            if (tail != null && !tail.isEmpty()) myChunker.push(tail, sink);
            myChunker.finish(s -> { if (state != State.LISTENING) setState(State.SPEAKING); speaker.enqueueQuiet(s); });
            speaker.whenDrained(this::afterSpeaking);
        } else if (st.stopped) {
            setState(State.READY);
        } else afterSpeaking();
    }

    /**
     * v2.4: the model claimed a phone action without a tool call. Nothing of it is shown, spoken or
     * saved. First time: retry once with a nudge. Then: the router (looser), else the hand-off.
     */
    private void onFakeClaim(String userText, List<LocalBrain.Turn> history, String system, boolean tools, boolean askTab,
                             int round, String reply) {
        Diag.log(this, "my model", "fake claim", reply, round == ROUND_FIRST ? "→ retry with nudge" : "→ fallback");
        speaker.stop();
        if (round == ROUND_FIRST) {
            hint.setText("⚙ doing it for real…");
            List<LocalBrain.Turn> nudged = new ArrayList<>(history);
            if (!nudged.isEmpty() && "user".equals(nudged.get(nudged.size() - 1).role)) {
                nudged.set(nudged.size() - 1, new LocalBrain.Turn("user", nudged.get(nudged.size() - 1).content + FakeClaim.NUDGE));
            }
            runLocal(userText, nudged, system, tools, askTab, ROUND_CLAIM_RETRY, 0.2f, null);
            return;
        }
        ToolCalls.Call direct = ActionRouter.match(userText, appLookup(), false);
        if (direct != null) { runRouted(userText, direct, "rescued"); return; }
        if (askTab) {
            askTabFromJarvis(ToolDispatcher.dispatch(new ToolCalls.Call(ToolCalls.ASK_TAB,
                    ToolDispatcher.obj("request", userText)), userText, true, null));
            return;
        }
        store.add(ChatStore.FROM_SYSTEM, "My model said it did that but never called a tool, so nothing happened. Try it plainly, like \"turn on the flashlight\" or \"set a 5 minute timer\".", "");
        setState(State.READY);
    }

    private void confirmClearChat() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clear chat?")
                .setMessage("Deletes this conversation. My model forgets it too.")
                .setPositiveButton("clear", (d, w) -> {
                    brain.stop();
                    speaker.stop();
                    store.clear();
                    removeStreamBubble();
                    renderHistory();
                    setState(State.READY);
                    Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("cancel", null)
                .show();
    }

    /** v2.4: a phone action from the router: run it, show the chip, say a short canned line. No model. */
    private void runRouted(String userText, ToolCalls.Call call, String how) { runRouted(userText, call, how, VIA_LOCAL); }

    private void runRouted(String userText, ToolCalls.Call call, String how, String via) {
        if (Dev.ON) Dev.thinkNote("⚙ router (" + how + ", no model): " + call.json());
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call, userText, false,
                Dev.ON ? Dev.actions(this, new PhoneTools(this)) : new PhoneTools(this));
        if (Dev.ON) Dev.thinkNote("  → " + o.kind.name().toLowerCase(Locale.US) + (o.response == null ? "" : " " + o.response));
        Diag.log(this, "my model", "router", o.chip, how + " · " + o.kind.name().toLowerCase(Locale.US) + " " + (o.response == null ? "" : o.response));
        addToolItem(o.kind == ToolDispatcher.Kind.ERROR ? o.chip + " ✕" : o.chip,
                ToolCalls.toolCallText(java.util.Collections.singletonList(o.call)),
                ToolCalls.toolResponseText(java.util.Collections.singletonList(o.response)), via);
        String say = o.speech == null || o.speech.isEmpty() ? "Done." : o.speech;
        store.add(ChatStore.FROM_JARVIS, say, via + " · " + how);
        Haptics.receive(this);
        if (prefs.speakReplies()) {
            setState(State.SPEAKING);
            speaker.enqueue(say, null);
            speaker.whenDrained(this::afterSpeaking);
        } else afterSpeaking();
    }

    /** v2.4: installed launcher apps, matched strictly (exact label) for the router. */
    private ActionRouter.AppLookup appLookup() {
        return name -> {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<String> labels = new ArrayList<>();
                for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(main, 0)) labels.add(String.valueOf(r.loadLabel(pm)));
                return ActionRouter.strictAppMatch(name, labels);
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    /** Runs the parsed calls: local ones on the phone (then a short spoken follow-up), ask_tab → the hand-off (off in this app). */
    private void runTools(String userText, List<LocalBrain.Turn> history, String system, boolean tools, boolean askTab,
                          ToolCalls.Parsed parsed, LocalBrain.Stats st) {
        ToolDispatcher.Actions phone = Dev.ON ? Dev.actions(this, new PhoneTools(this)) : new PhoneTools(this);
        List<ToolCalls.Call> done = new ArrayList<>();
        List<String> responses = new ArrayList<>(), chips = new ArrayList<>();
        StringBuilder speech = new StringBuilder();
        ToolDispatcher.Outcome ask = null;
        int n = 0;
        for (ToolCalls.Call c : parsed.calls) {
            if (n++ >= 3) break;
            if (Dev.ON) Dev.thinkNote("⚙ tool decision: " + c.json());
            ToolDispatcher.Outcome o = ToolDispatcher.dispatch(c, userText, askTab, phone);
            if (Dev.ON) { Dev.thinkNote("  → " + o.kind.name().toLowerCase(Locale.US) + (o.response == null ? "" : " " + o.response)); devThinking(null); }
            Diag.log(this, "my model", "tool", o.chip, o.kind == ToolDispatcher.Kind.ASK_TAB ? ("→ (unavailable)") : o.kind.name().toLowerCase(Locale.US) + " " + (o.response == null ? "" : o.response));
            if (o.kind == ToolDispatcher.Kind.ASK_TAB) { if (ask == null) ask = o; continue; }
            done.add(o.call);
            responses.add(o.response);
            chips.add(o.kind == ToolDispatcher.Kind.ERROR ? o.chip + " ✕" : o.chip);
            if (speech.length() > 0) speech.append(' ');
            speech.append(o.speech);
        }
        if (!done.isEmpty()) {
            addToolItem(TextUtils.join("  ·  ", chips), ToolCalls.toolCallText(done), ToolCalls.toolResponseText(responses));
            if (ask != null) {   // a phone tool AND a hand-off: do both, no follow-up turn
                if (prefs.speakReplies()) speaker.enqueue(speech.toString(), null);
                askTabFromJarvis(ask);
                return;
            }
            List<LocalBrain.Turn> h2 = new ArrayList<>(history);
            h2.add(new LocalBrain.Turn("assistant", ToolCalls.toolCallText(done)));
            h2.add(new LocalBrain.Turn("user", ToolCalls.toolResponseText(responses)));
            runLocal(userText, h2, system, tools, askTab, ROUND_FOLLOW_UP, -1f, speech.toString());
            return;
        }
        if (ask != null) askTabFromJarvis(ask);
        else setState(State.READY);
    }

    /** ask_tab hand-off: there is no hand-off target in this app, so just go back to ready. */
    private void askTabFromJarvis(ToolDispatcher.Outcome o) {
        setState(State.READY);
    }

    private void addToolItem(String chip, String calls, String responses) { addToolItem(chip, calls, responses, VIA_LOCAL); }

    private void addToolItem(String chip, String calls, String responses, String via) {
        try {
            org.json.JSONObject o = new org.json.JSONObject().put("chip", chip).put("calls", calls).put("responses", responses == null ? "" : responses);
            store.add(ChatStore.FROM_TOOL, o.toString(), via + " · tool");
        } catch (org.json.JSONException ignored) {
        }
    }

    static String toolChip(String itemText) {
        try { return new org.json.JSONObject(itemText).optString("chip", "⚙ tool"); }
        catch (Exception e) { return "⚙ tool"; }
    }

    private void startStreamBubble() { startStreamBubble(VIA_LOCAL); }

    private void startStreamBubble(String via) {
        removeStreamBubble();
        int before = chat.getChildCount();
        addBubble(ChatStore.FROM_JARVIS, "", via, System.currentTimeMillis());
        if (chat.getChildCount() > before) {
            streamWrap = chat.getChildAt(chat.getChildCount() - 1);
            streamBubble = findBodyText(streamWrap);
        }
    }

    private static TextView findBodyText(View v) {
        // the bubble is the last TextView in the wrap (meta label first, then body)
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                TextView t = findBodyText(g.getChildAt(i));
                if (t != null) return t;
            }
            return null;
        }
        return v instanceof TextView ? (TextView) v : null;
    }

    private void removeStreamBubble() {
        if (Dev.ON && liveThinkWrap != null) { chat.removeView(liveThinkWrap); liveThinkWrap = null; }
        if (streamWrap != null) chat.removeView(streamWrap);
        streamWrap = null;
        streamBubble = null;
    }

    // ------------------------------------------------------------------ voice out

    private String speakReply(String text) {
        if (!prefs.speakReplies()) {
            afterSpeaking();
            return "in chat, not spoken: read replies out loud is off";
        }
        setState(State.SPEAKING);
        speaker.enqueue(SpeechText.forSpeech(text), this::afterSpeaking);
        return "spoken (app open)";
    }

    private void afterSpeaking() {
        if (state == State.LISTENING) return;
        setState(State.READY);
        if (prefs.handsFree()) {
            quietRestarts = 0;
            // short pause so a follow-up reply gets read before the mic opens
            h.postDelayed(() -> { if (state == State.READY) startListening(); }, 1500);
        }
    }

    // used by screenshot tests to show a realistic conversation
    void demoState(String which) {
        if ("listening".equals(which)) setState(State.LISTENING);
        else if ("waiting".equals(which)) setState(State.WAITING);
        else if ("speaking".equals(which)) setState(State.SPEAKING);
        else setState(State.READY);
    }

    ViewGroup chatView() { return chat; }
}
