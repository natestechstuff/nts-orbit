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
        if (path.isEmpty() || !new File(path).exists()) {
            post(l, null, "No model picked yet. Settings → My model → pick a .gguf file.");
            return;
        }
        final String k = key(path, ctx, threads);
        if (k.equals(loadedKey)) { post(l, description, null); return; }
        loading = true;
        worker.execute(() -> {
            String err = LlamaBridge.init(app);
            if (err == null) {
                if (handle == 0) handle = LlamaBridge.nativeCreate();
                if (!k.equals(loadedKey)) {
                    loadedKey = null;
                    prewarmedSystem = null;
                    err = LlamaBridge.nativeLoad(handle, path, ctx, threads);
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
