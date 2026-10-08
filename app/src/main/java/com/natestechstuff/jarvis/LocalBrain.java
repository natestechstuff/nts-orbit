package com.natestechstuff.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "My model": runs your GGUF on the phone through llama.cpp. One model, kept warm in memory
 * (LocalBrainService keeps the process alive), one generation at a time on a background thread.
 * The KV cache keeps the system prompt + recent chat, so follow-up voice replies start fast.
 */
public final class LocalBrain {
    public static final class Stats {
        public int promptTokens, reusedTokens, genTokens, droppedTurns;
        /** Pro: tokens spent inside <think></think> (part of genTokens). */
        public int thinkTokens;
        public double promptMs, genMs;
        public boolean stopped, hitLimit;
        public double tokensPerSec() { return genMs > 0 ? genTokens * 1000.0 / genMs : 0; }
        public String line() {
            return String.format(Locale.US, "%.1f tok/s · %d tok", tokensPerSec(), genTokens);
        }
    }

    public interface LoadListener { void onLoaded(String description); void onLoadFailed(String error); }

    public interface GenListener {
        void onPiece(String text);                 // main thread, whole UTF-8 characters
        void onDone(String reply, Stats stats);    // main thread
        void onError(String error);                // main thread
        /** Pro only: a piece of the model's thought process (<think>…</think>), main thread. */
        default void onThinking(String text) {}
    }

    public static final class Turn {
        public final String role, content;
        public Turn(String role, String content) { this.role = role; this.content = content; }
    }

    private static LocalBrain instance;

    public static synchronized LocalBrain get(Context c) {
        if (instance == null) instance = new LocalBrain(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jarvis-llm");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private long handle;
    private volatile String loadedKey;      // path|ctx|threads currently in memory
    private volatile String description = "";
    private volatile boolean loading, generating;
    private volatile String lastError;
    private volatile Stats lastStats;

    private LocalBrain(Context c) { app = c; }

    public boolean isLoaded() { return loadedKey != null; }
    public boolean isLoading() { return loading; }
    public boolean isGenerating() { return generating; }
    public String description() { return description; }
    public String lastError() { return lastError; }
    public Stats lastStats() { return lastStats; }

    private static String key(String path, int ctx, int threads) { return path + "|" + ctx + "|" + threads; }

    /** Loads (or keeps) the model chosen in settings. Listener runs on the main thread. */
    public void ensureLoaded(LoadListener l) {
        final Prefs p = new Prefs(app);
        final String path = p.modelPath();
        final int ctx = p.contextSize(), threads = p.threads();
        final int batch = Dev.ON ? Dev.batch(app) : 512;
        if (path.isEmpty() || !new File(path).exists()) {
            post(l, null, "No model picked yet. Settings → My model → pick a .gguf file.");
            return;
        }
        final String k = Dev.ON ? key(path, ctx, threads) + "|" + batch : key(path, ctx, threads);
        if (k.equals(loadedKey)) { post(l, description, null); return; }
        loading = true;
        worker.execute(() -> {
            String err = LlamaBridge.init(app);
            if (err == null) {
                if (handle == 0) handle = LlamaBridge.nativeCreate();
                if (!k.equals(loadedKey)) {
                    loadedKey = null;
                    prewarmedSystem = null;
                    err = Dev.ON ? LlamaBridge.nativeLoad2(handle, path, ctx, threads, batch)
                            : LlamaBridge.nativeLoad(handle, path, ctx, threads);
                    if (err == null || err.isEmpty()) {
                        err = null;
                        loadedKey = k;
                        description = new File(path).getName() + " · " + LlamaBridge.nativeDescribe(handle);
                    }
                }
            }
            loading = false;
            lastError = err;
            post(l, description, err);
        });
    }

    private void post(LoadListener l, String desc, String err) {
        if (l == null) return;
        main.post(() -> { if (err == null) l.onLoaded(desc); else l.onLoadFailed(err); });
    }

    public void unload() {
        worker.execute(() -> {
            if (handle != 0) LlamaBridge.nativeUnload(handle);
            loadedKey = null;
            description = "";
        });
    }

    /** Pro: the loaded GGUF's metadata ("key = value" lines), on the main thread. */
    public void metadata(java.util.function.Consumer<String> out) {
        worker.execute(() -> {
            String m = handle != 0 && loadedKey != null ? LlamaBridge.nativeMeta(handle) : "";
            main.post(() -> out.accept(m == null || m.isEmpty() ? "No model in memory. Load it first." : m));
        });
    }

    public void stop() {
        long h = handle;
        if (h != 0 && generating && !prewarming) LlamaBridge.nativeStop(h);
    }

    private volatile boolean prewarming;
    private volatile String prewarmedSystem;
    public boolean isPrewarming() { return prewarming; }

    /**
     * v2.3: reads the (long, tool-carrying) system prompt into the KV cache right after the model loads,
     * so the first question doesn't wait for ~800 prompt tokens. Generates one token and stops.
     * A real message sent meanwhile simply queues behind it (stop() leaves a prewarm alone).
     */
    public void prewarm(String system) {
        if (system == null || system.equals(prewarmedSystem) || prewarming) return;
        prewarming = true;
        generate(new ArrayList<>(), system, 0f, soFar -> false, new GenListener() {
            @Override public void onPiece(String text) {}
            @Override public void onDone(String reply, Stats stats) { prewarming = false; prewarmedSystem = system; }
            @Override public void onError(String error) { prewarming = false; }
        });
    }

    /** v2.3: decides on the generation thread whether to keep going, from the reply so far. */
    public interface KeepGoing { boolean test(String replySoFar); }

    /** history: oldest first, ending with the new user message. The system prompt is added here. */
    public void generate(List<Turn> history, GenListener l) {
        generate(history, null, -1f, null, l);
    }

    /**
     * v2.3: system = full system message (null = settings / default), temperature &lt; 0 = settings,
     * keepGoing = early stop (e.g. once a tool call is complete). An early stop is not reported as "stopped".
     */
    public void generate(List<Turn> history, String system, float temperature, KeepGoing keepGoing, GenListener l) {
        final Prefs p = new Prefs(app);
        ensureLoaded(new LoadListener() {
            @Override public void onLoaded(String d) { run(p, history, system, temperature, keepGoing, l); }
            @Override public void onLoadFailed(String e) { l.onError(e); }
        });
    }

    private void run(Prefs p, List<Turn> history, String systemOverride, float tempOverride, KeepGoing keepGoing, GenListener l) {
        if (Dev.ON) { runDev(p, history, systemOverride, tempOverride, keepGoing, l); return; }
        generating = true;
        final String system = systemOverride != null ? systemOverride : systemPrompt(app, p);
        final float temp = tempOverride >= 0 ? tempOverride : p.temperature();
        final boolean[] earlyStop = {false};
        worker.execute(() -> {
            List<String> roles = new ArrayList<>(), contents = new ArrayList<>();
            roles.add("system"); contents.add(system);
            for (Turn t : history) { roles.add(t.role); contents.add(t.content); }
            final StringBuilder reply = new StringBuilder();
            String[] err = new String[1];
            double[] r = LlamaBridge.nativeGenerate(handle, roles.toArray(new String[0]), contents.toArray(new String[0]),
                    p.maxTokens(), temp, 40, 0.9f, 0.05f, 1.1f,
                    bytes -> {
                        String piece = new String(bytes, StandardCharsets.UTF_8);
                        reply.append(piece);
                        main.post(() -> l.onPiece(piece));
                        if (keepGoing != null && !keepGoing.test(reply.toString())) { earlyStop[0] = true; return false; }
                        return true;
                    }, err);
            final Stats st = new Stats();
            st.promptTokens = (int) r[0]; st.reusedTokens = (int) r[1]; st.genTokens = (int) r[2];
            st.promptMs = r[3]; st.genMs = r[4]; st.stopped = r[5] > 0 && !earlyStop[0]; st.hitLimit = r[6] > 0; st.droppedTurns = (int) r[7];
            if (!(keepGoing != null && history.isEmpty())) lastStats = st;
            generating = false;
            final String e = err[0];
            main.post(() -> {
                if (e != null && reply.length() == 0) l.onError(e);
                else l.onDone(reply.toString().trim(), st);
            });
        });
    }

    /**
     * Pro: the same generation with every Pro knob (sampling, seed, stop strings, thinking).
     * <think>…</think> never reaches the answer: it goes to onThinking() (and the thought panel) instead.
     */
    private void runDev(Prefs p, List<Turn> history, String systemOverride, float tempOverride, KeepGoing keepGoing, GenListener l) {
        generating = true;
        String sys = systemOverride != null ? systemOverride : systemPrompt(app, p);
        sys = Dev.filterToolPrompt(app, sys);
        final boolean prewarm = keepGoing != null && history.isEmpty();
        final boolean force = Dev.forceThinking(app) && !prewarm;
        final String sysBase = sys;
        final String system = force ? sys + "\n\n" + Dev.FORCE_THINK_PROMPT : sys;
        final float temp = tempOverride >= 0 ? tempOverride : p.temperature();
        final int topK = Dev.topK(app), repN = Dev.repeatLastN(app), maxThink = Dev.maxThinkTokens(app);
        final float topP = Dev.topP(app), minP = Dev.minP(app), rep = Dev.repeatPenalty(app);
        final long seed = Dev.seed(app);
        final List<String> stops = Dev.stops(app);
        final int maxTokens = p.maxTokens();
        worker.execute(() -> {
            List<String> roles = new ArrayList<>(), contents = new ArrayList<>();
            roles.add("system"); contents.add(system);
            for (Turn t : history) { roles.add(t.role); contents.add(t.content); }
            String[] ra = roles.toArray(new String[0]), ca = contents.toArray(new String[0]);
            String params = String.format(Locale.US, "temp %.2f · top-k %d · top-p %.2f · min-p %.2f · repeat %.2f/%d · seed %s · max %d · ctx %d · threads %d · batch %d%s%s",
                    temp, topK, topP, minP, rep, repN, seed < 0 ? "random" : String.valueOf(seed), maxTokens, p.contextSize(), p.threads(), Dev.batch(app),
                    stops.isEmpty() ? "" : " · stops " + stops.size(), force ? " · force-think" : "");
            if (!prewarm) { Dev.lastPrompt = Dev.cap(Dev.chatml(ra, ca)); Dev.lastParams = params; }
            final StringBuilder raw = new StringBuilder(), visible = new StringBuilder();
            final ServerClient.ThinkFilter tf = new ServerClient.ThinkFilter();
            final boolean[] earlyStop = {false}, thinkCapped = {false}, stopString = {false};
            final int[] thinkToks = {0};
            LlamaBridge.PieceCallback cb = bytes -> {
                String piece = new String(bytes, StandardCharsets.UTF_8);
                raw.append(piece);
                String vis = tf.push(piece);
                String th = tf.takeThought();
                if (!th.isEmpty() || (tf.inThink() && vis.isEmpty())) {
                    thinkToks[0]++;
                    if (!th.isEmpty()) main.post(() -> l.onThinking(th));
                }
                if (!vis.isEmpty()) {
                    visible.append(vis);
                    main.post(() -> l.onPiece(vis));
                }
                for (String s : stops) if (visible.indexOf(s) >= 0) { stopString[0] = true; earlyStop[0] = true; return false; }
                if (maxThink > 0 && tf.inThink() && thinkToks[0] >= maxThink) { thinkCapped[0] = true; earlyStop[0] = true; return false; }
                if (keepGoing != null && !keepGoing.test(visible.toString())) { earlyStop[0] = true; return false; }
                return true;
            };
            String[] err = new String[1];
            double[] r = LlamaBridge.nativeGenerate2(handle, ra, ca, maxTokens, temp, topK, topP, minP, rep, repN, seed, cb, err);
            if (thinkCapped[0] && err[0] == null) {
                // thinking ran past "max thinking tokens": answer now, without thinking
                main.post(() -> l.onThinking("\n… (thinking cut at " + maxThink + " tokens)\n"));
                raw.append("\n[thinking cut at ").append(maxThink).append(" tokens → answering without thinking]\n");
                ca[0] = sysBase + "\n\nDo not think out loud. Answer directly. /no_think";
                final ServerClient.ThinkFilter tf2 = new ServerClient.ThinkFilter();
                earlyStop[0] = false;
                double[] r2 = LlamaBridge.nativeGenerate2(handle, ra, ca, maxTokens, temp, topK, topP, minP, rep, repN, seed, bytes -> {
                    String piece = new String(bytes, StandardCharsets.UTF_8);
                    raw.append(piece);
                    String vis = tf2.push(piece);
                    tf2.takeThought();
                    if (!vis.isEmpty()) { visible.append(vis); main.post(() -> l.onPiece(vis)); }
                    for (String s : stops) if (visible.indexOf(s) >= 0) { stopString[0] = true; earlyStop[0] = true; return false; }
                    if (keepGoing != null && !keepGoing.test(visible.toString())) { earlyStop[0] = true; return false; }
                    return true;
                }, err);
                for (int k = 0; k < 5; k++) r[k] += r2[k];
                r[5] = r2[5]; r[6] = r2[6];
                String tail2 = tf2.finish();
                if (!tail2.isEmpty()) { visible.append(tail2); main.post(() -> l.onPiece(tail2)); }
            } else {
                String tail = tf.finish();
                if (!tail.isEmpty()) { visible.append(tail); main.post(() -> l.onPiece(tail)); }
            }
            String reply = visible.toString();
            if (stopString[0]) for (String s : stops) { int k = reply.indexOf(s); if (k >= 0) reply = reply.substring(0, k); }
            final Stats st = new Stats();
            st.promptTokens = (int) r[0]; st.reusedTokens = (int) r[1]; st.genTokens = (int) r[2];
            st.promptMs = r[3]; st.genMs = r[4]; st.stopped = r[5] > 0 && !earlyStop[0]; st.hitLimit = r[6] > 0; st.droppedTurns = (int) r[7];
            st.thinkTokens = thinkToks[0];
            if (!prewarm) {
                lastStats = st;
                Dev.lastRaw = Dev.cap(raw.toString());
                Dev.lastStats = String.format(Locale.US, "prompt %d tok (%d reused) in %.0f ms · gen %d tok in %.0f ms = %.1f tok/s · think %d tok%s%s",
                        st.promptTokens, st.reusedTokens, st.promptMs, st.genTokens, st.genMs, st.tokensPerSec(), st.thinkTokens,
                        st.hitLimit ? " · hit max tokens" : "", st.droppedTurns > 0 ? " · dropped " + st.droppedTurns + " old turns" : "");
            }
            generating = false;
            final String e = err[0];
            final String out = reply;
            main.post(() -> {
                if (e != null && out.isEmpty()) l.onError(e);
                else l.onDone(out.trim(), st);
            });
        });
    }

    public static String defaultSystemPrompt(Context c) {
        try (InputStream in = c.getResources().openRawResource(R.raw.jarvis_system_prompt)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8").trim();
        } catch (Exception e) {
            return"You are Orbit, a casual, helpful AI assistant. Keep replies to 1-3 short sentences.";
        }
    }

    static String systemPrompt(Context c, Prefs p) {
        String custom = p.systemPrompt();
        return custom.isEmpty() ? defaultSystemPrompt(c) : custom;
    }
}
