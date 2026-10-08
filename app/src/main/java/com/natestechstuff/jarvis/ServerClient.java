package com.natestechstuff.jarvis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Chat with a local AI server, streamed. Two wire formats cover the local-AI world:
 *  - Ollama's native API: GET /api/tags (models), POST /api/chat (NDJSON stream). Default port 11434.
 *  - OpenAI-compatible: GET /v1/models, POST /v1/chat/completions (SSE stream). LM Studio (:1234),
 *    llama.cpp server (:8080), LocalAI (:8080), KoboldCpp (:5001), Jan (:1337),
 *    text-generation-webui (:5000), vLLM (:8000) … and Ollama's own /v1 too.
 * "auto" tries Ollama's /api/tags first, then /v1/models.
 * Blocking, no Android classes: call it off the main thread. Unit-tested against a local mock server.
 */
public final class ServerClient {
    public static final String AUTO = "auto", OLLAMA = "ollama", OPENAI = "openai";

    public static final class Msg {
        public final String role, content;
        public Msg(String role, String content) { this.role = role; this.content = content == null ? "" : content; }
    }

    /** Streamed visible text, piece by piece (called on the request thread). */
    public interface Sink { void onPiece(String piece); }

    /** Lets another thread stop a running request (closes its connection). */
    public static final class Cancel {
        private volatile boolean cancelled;
        private volatile HttpURLConnection conn;
        public void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) {
                try { c.disconnect(); } catch (Exception ignored) {}
            }
        }
        public boolean isCancelled() { return cancelled; }
        void attach(HttpURLConnection c) { conn = c; if (cancelled) c.disconnect(); }
    }

    public static final class Result {
        public final String text, model, backend;
        public final int tokens;
        public final long millis;
        /** tokens per second as the server measured it (Ollama), else from wall clock; 0 if unknown */
        public final double tokPerSec;
        Result(String text, String model, String backend, int tokens, long millis, double tokPerSec) {
            this.text = text; this.model = model; this.backend = backend; this.tokens = tokens; this.millis = millis; this.tokPerSec = tokPerSec;
        }
        public String statsLine() {
            StringBuilder b = new StringBuilder(model);
            if (tokPerSec > 0) b.append(String.format(Locale.US, " · %.1f tok/s", tokPerSec));
            if (tokens > 0) b.append(" · ").append(tokens).append(" tok");
            return b.toString();
        }
    }

    /** What a test connection found. */
    public static final class Probe {
        public final String backend;
        public final List<String> models;
        public Probe(String backend, List<String> models) { this.backend = backend; this.models = models; }
    }

    private final String base;
    private final String type;
    private final String apiKey;
    private int connectTimeoutMs = 6000;
    private int readTimeoutMs = 180_000;   // a big model can take a while to load before the first token

    public ServerClient(String url, String type, String apiKey) {
        this.base = normalizeBase(url);
        this.type = OLLAMA.equals(type) || OPENAI.equals(type) ? type : AUTO;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public ServerClient timeouts(int connectMs, int readMs) { connectTimeoutMs = connectMs; readTimeoutMs = readMs; return this; }

    public String baseUrl() { return base; }

    // ---------------------------------------------------------------- urls

    /**
     * "192.168.1.20:11434" → "http://192.168.1.20:11434"; trailing "/", "/v1", "/api", "/v1/chat/completions"
     * and "/api/chat" are dropped so people can paste whatever their server's docs show.
     */
    public static String normalizeBase(String url) {
        String b = url == null ? "" : url.trim();
        if (b.isEmpty()) return "";
        if (!b.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) b = "http://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String low = b.toLowerCase(Locale.US);
        for (String tail : new String[]{"/v1/chat/completions", "/chat/completions", "/v1/models", "/api/chat", "/api/tags", "/api/generate", "/v1", "/api"}) {
            if (low.endsWith(tail)) {
                b = b.substring(0, b.length() - tail.length());
                low = b.toLowerCase(Locale.US);
            }
        }
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** "http://192.168.1.20:11434/x" → "192.168.1.20:11434" (for labels and errors). */
    public static String hostPort(String url) {
        String b = normalizeBase(url);
        int s = b.indexOf("://");
        if (s >= 0) b = b.substring(s + 3);
        int slash = b.indexOf('/');
        return slash >= 0 ? b.substring(0, slash) : b;
    }

    // ---------------------------------------------------------------- probe / models

    /** Which API answers, and its models. Throws IOException with a human-readable reason. */
    public Probe probe() throws IOException {
        if (base.isEmpty()) throw new IOException("Enter the server address first, like 192.168.1.20:11434");
        if (OLLAMA.equals(type)) return new Probe(OLLAMA, ollamaModels());
        if (OPENAI.equals(type)) return new Probe(OPENAI, openAiModels());
        String ollamaWhy;
        try {
            return new Probe(OLLAMA, ollamaModels());
        } catch (HttpError | WrongApi e) {
            ollamaWhy = e instanceof HttpError ? "HTTP " + ((HttpError) e).code : "not Ollama's format";   // something answered, just not Ollama
        }
        try {
            return new Probe(OPENAI, openAiModels());
        } catch (HttpError | WrongApi e) {
            if (e instanceof HttpError && (((HttpError) e).code == 401 || ((HttpError) e).code == 403)) throw e;
            String openAiWhy = e instanceof HttpError ? "HTTP " + ((HttpError) e).code : "not OpenAI's format";
            throw new IOException("Something answers at " + hostPort(base) + " but it isn't an Ollama or OpenAI-compatible API "
                    + "(/api/tags: " + ollamaWhy + ", /v1/models: " + openAiWhy + "). Check the port.");
        }
    }

    public List<String> listModels() throws IOException {
        return probe().models;
    }

    List<String> ollamaModels() throws IOException {
        return parseOllamaTags(get("/api/tags", OLLAMA));
    }

    List<String> openAiModels() throws IOException {
        return parseOpenAiModels(get("/v1/models", OPENAI));
    }

    public static List<String> parseOllamaTags(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray a = o.optJSONArray("models");
            if (a == null) throw new WrongApi("This doesn't look like Ollama (no model list at /api/tags)");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m == null) continue;
                String n = m.optString("name", m.optString("model", ""));
                if (!n.isEmpty()) out.add(n);
            }
            Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
            return out;
        } catch (JSONException e) {
            throw new WrongApi("This doesn't look like Ollama (/api/tags didn't return JSON)");
        }
    }

    public static List<String> parseOpenAiModels(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray a = o.optJSONArray("data");
            if (a == null) a = o.optJSONArray("models");
            if (a == null) throw new WrongApi("This doesn't look like an OpenAI-compatible server (no model list at /v1/models)");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                Object item = a.opt(i);
                String n = "";
                if (item instanceof JSONObject) {
                    JSONObject m = (JSONObject) item;
                    n = m.optString("id", m.optString("name", m.optString("model", "")));
                } else if (item instanceof String) {
                    n = (String) item;
                }
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
            return out;
        } catch (JSONException e) {
            throw new WrongApi("This doesn't look like an OpenAI-compatible server (/v1/models didn't return JSON)");
        }
    }

    // ---------------------------------------------------------------- chat

    /** Streams one reply. Visible text goes to the sink as it arrives (thinking blocks are hidden). */
    public Result chat(String model, List<Msg> messages, float temperature, int maxTokens, Sink sink, Cancel cancel) throws IOException {
        if (base.isEmpty()) throw new IOException("Set the server address in Settings → Server");
        if (model == null || model.trim().isEmpty()) throw new IOException("Pick a model for this server in Settings → Server");
        String backend = type;
        if (AUTO.equals(backend)) backend = probe().backend;
        return OLLAMA.equals(backend)
                ? chatOllama(model.trim(), messages, temperature, maxTokens, sink, cancel)
                : chatOpenAi(model.trim(), messages, temperature, maxTokens, sink, cancel);
    }

    static String ollamaBody(String model, List<Msg> messages, float temperature, int maxTokens) {
        try {
            JSONObject opts = new JSONObject();
            if (temperature >= 0) opts.put("temperature", (double) temperature);
            if (maxTokens > 0) opts.put("num_predict", maxTokens);
            return new JSONObject().put("model", model).put("messages", msgs(messages)).put("stream", true).put("options", opts).toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static String openAiBody(String model, List<Msg> messages, float temperature, int maxTokens) {
        try {
            JSONObject o = new JSONObject().put("model", model).put("messages", msgs(messages)).put("stream", true);
            if (temperature >= 0) o.put("temperature", (double) temperature);
            if (maxTokens > 0) o.put("max_tokens", maxTokens);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JSONArray msgs(List<Msg> messages) throws JSONException {
        JSONArray a = new JSONArray();
        for (Msg m : messages) a.put(new JSONObject().put("role", m.role).put("content", m.content));
        return a;
    }

    private Result chatOllama(String model, List<Msg> messages, float temperature, int maxTokens, Sink sink, Cancel cancel) throws IOException {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = post("/api/chat", ollamaBody(model, messages, temperature, maxTokens), OLLAMA, cancel);
        StringBuilder all = new StringBuilder();
        ThinkFilter think = new ThinkFilter();
        OllamaChunk last = null;
        int pieces = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancel != null && cancel.isCancelled()) break;
                if (line.trim().isEmpty()) continue;
                OllamaChunk ch = parseOllamaLine(line);
                if (ch.error != null) throw new IOException(explainModelError(ch.error, model, OLLAMA));
                if (!ch.content.isEmpty()) {
                    pieces++;
                    String vis = think.push(ch.content);
                    if (!vis.isEmpty()) { all.append(vis); if (sink != null) sink.onPiece(vis); }
                }
                if (ch.done) { last = ch; break; }
            }
        } catch (IOException e) {
            if (cancel != null && cancel.isCancelled()) return new Result(all.toString().trim(), model, OLLAMA, pieces, System.currentTimeMillis() - t0, 0);
            throw friendly(e, OLLAMA);
        } finally {
            c.disconnect();
        }
        String tail = think.finish();
        if (!tail.isEmpty()) { all.append(tail); if (sink != null) sink.onPiece(tail); }
        long ms = System.currentTimeMillis() - t0;
        int tokens = last != null && last.evalCount > 0 ? last.evalCount : pieces;
        double tps = last != null && last.evalCount > 0 && last.evalDurationNs > 0
                ? last.evalCount / (last.evalDurationNs / 1e9) : (ms > 0 ? pieces * 1000.0 / ms : 0);
        return new Result(all.toString().trim(), model, OLLAMA, tokens, ms, tps);
    }

    private Result chatOpenAi(String model, List<Msg> messages, float temperature, int maxTokens, Sink sink, Cancel cancel) throws IOException {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = post("/v1/chat/completions", openAiBody(model, messages, temperature, maxTokens), OPENAI, cancel);
        StringBuilder all = new StringBuilder();
        ThinkFilter think = new ThinkFilter();
        int pieces = 0, usageTokens = 0;
        String ctype = String.valueOf(c.getContentType()).toLowerCase(Locale.US);
        try {
            if (ctype.contains("application/json")) {
                // server ignored "stream": true and sent one JSON answer
                SseChunk ch = parseOpenAiJson(read(c.getInputStream()));
                if (ch.error != null) throw new IOException(explainModelError(ch.error, model, OPENAI));
                String vis = think.push(ch.content) + think.finish();
                all.append(vis);
                if (sink != null && !vis.isEmpty()) sink.onPiece(vis);
                usageTokens = ch.completionTokens;
            } else {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (cancel != null && cancel.isCancelled()) break;
                        SseChunk ch = parseSseLine(line);
                        if (ch == null) continue;
                        if (ch.error != null) throw new IOException(explainModelError(ch.error, model, OPENAI));
                        if (ch.completionTokens > 0) usageTokens = ch.completionTokens;
                        if (!ch.content.isEmpty()) {
                            pieces++;
                            String vis = think.push(ch.content);
                            if (!vis.isEmpty()) { all.append(vis); if (sink != null) sink.onPiece(vis); }
                        }
                        if (ch.done) break;
                    }
                }
                String tail = think.finish();
                if (!tail.isEmpty()) { all.append(tail); if (sink != null) sink.onPiece(tail); }
            }
        } catch (IOException e) {
            if (cancel != null && cancel.isCancelled()) return new Result(all.toString().trim(), model, OPENAI, pieces, System.currentTimeMillis() - t0, 0);
            throw friendly(e, OPENAI);
        } finally {
            c.disconnect();
        }
        long ms = System.currentTimeMillis() - t0;
        int tokens = usageTokens > 0 ? usageTokens : pieces;
        return new Result(all.toString().trim(), model, OPENAI, tokens, ms, ms > 0 && tokens > 0 ? tokens * 1000.0 / ms : 0);
    }

    // ---------------------------------------------------------------- wire parsing (unit-tested)

    public static final class OllamaChunk {
        public String content = "", thinking = "", error;
        public boolean done;
        public int evalCount;
        public long evalDurationNs;
    }

    /** One NDJSON line of /api/chat. */
    public static OllamaChunk parseOllamaLine(String line) throws IOException {
        OllamaChunk ch = new OllamaChunk();
        try {
            JSONObject o = new JSONObject(line.trim());
            if (o.has("error")) { ch.error = errorText(o.opt("error")); return ch; }
            JSONObject m = o.optJSONObject("message");
            if (m != null) {
                ch.content = m.optString("content", "");
                ch.thinking = m.optString("thinking", "");
            } else {
                ch.content = o.optString("response", "");   // /api/generate style, just in case
            }
            ch.done = o.optBoolean("done", false);
            ch.evalCount = o.optInt("eval_count", 0);
            ch.evalDurationNs = o.optLong("eval_duration", 0L);
            return ch;
        } catch (JSONException e) {
            throw new IOException("Ollama sent something that isn't JSON: " + shorten(line, 80));
        }
    }

    public static final class SseChunk {
        public String content = "", error;
        public boolean done;
        public int completionTokens;
    }

    /** One line of an OpenAI-style SSE stream. null = nothing in it (blank, comment, event:, id:). */
    public static SseChunk parseSseLine(String line) throws IOException {
        if (line == null) return null;
        String l = line.trim();
        if (l.isEmpty() || l.startsWith(":") || l.startsWith("event:") || l.startsWith("id:") || l.startsWith("retry:")) return null;
        if (!l.startsWith("data:")) {
            // some servers send a bare JSON error instead of an event stream
            if (l.startsWith("{")) return parseOpenAiJson(l);
            return null;
        }
        String data = l.substring(5).trim();
        if (data.equals("[DONE]")) { SseChunk ch = new SseChunk(); ch.done = true; return ch; }
        if (data.isEmpty()) return null;
        return parseOpenAiJson(data);
    }

    /** A streamed chunk (delta) or a whole non-streamed completion (message), or an error object. */
    public static SseChunk parseOpenAiJson(String json) throws IOException {
        SseChunk ch = new SseChunk();
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error") && !o.isNull("error")) { ch.error = errorText(o.opt("error")); return ch; }
            JSONArray choices = o.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c0 = choices.optJSONObject(0);
                if (c0 != null) {
                    JSONObject delta = c0.optJSONObject("delta");
                    JSONObject msg = c0.optJSONObject("message");
                    if (delta != null && !delta.isNull("content")) ch.content = delta.optString("content", "");
                    else if (msg != null && !msg.isNull("content")) ch.content = msg.optString("content", "");
                    else if (!c0.isNull("text")) ch.content = c0.optString("text", "");   // legacy completions shape
                }
            }
            JSONObject usage = o.optJSONObject("usage");
            if (usage != null) ch.completionTokens = usage.optInt("completion_tokens", 0);
            return ch;
        } catch (JSONException e) {
            throw new IOException("The server sent something that isn't JSON: " + shorten(json, 80));
        }
    }

    static String errorText(Object err) {
        if (err instanceof JSONObject) {
            JSONObject e = (JSONObject) err;
            String m = e.optString("message", "");
            if (!m.isEmpty()) return m;
            return e.toString();
        }
        return String.valueOf(err);
    }

    /** "model 'x' not found" → tell them how to get it. */
    static String explainModelError(String err, String model, String backend) {
        String low = err.toLowerCase(Locale.US);
        if (low.contains("not found") && (low.contains("model") || low.contains(model.toLowerCase(Locale.US)))) {
            return OLLAMA.equals(backend)
                    ? "The model \"" + model + "\" isn't on this Ollama server. Pull it first (ollama pull " + model + ") or pick another one in Settings → Server."
                    : "The server doesn't have the model \"" + model + "\". Load it in the server (LM Studio: load the model), or pick another one in Settings → Server.";
        }
        return "The server said: " + err;
    }

    /** Hides <think>…</think> blocks (reasoning models) from what's shown and spoken, across chunk borders. */
    public static final class ThinkFilter {
        private static final String OPEN = "<think>", CLOSE = "</think>";
        private final StringBuilder pending = new StringBuilder();
        private boolean inThink;
        private boolean started;   // any visible text yet (leading whitespace after a think block is dropped)

        public String push(String piece) {
            pending.append(piece);
            StringBuilder out = new StringBuilder();
            while (true) {
                String p = pending.toString();
                if (inThink) {
                    int e = p.indexOf(CLOSE);
                    if (e < 0) {
                        // keep only what could be the start of "</think>"
                        int keep = partialSuffix(p, CLOSE);
                        pending.setLength(0);
                        pending.append(p.substring(p.length() - keep));
                        break;
                    }
                    pending.setLength(0);
                    pending.append(p.substring(e + CLOSE.length()));
                    inThink = false;
                    continue;
                }
                int s = p.indexOf(OPEN);
                if (s >= 0) {
                    out.append(p, 0, s);
                    pending.setLength(0);
                    pending.append(p.substring(s + OPEN.length()));
                    inThink = true;
                    continue;
                }
                int keep = partialSuffix(p, OPEN);
                out.append(p, 0, p.length() - keep);
                pending.setLength(0);
                pending.append(p.substring(p.length() - keep));
                break;
            }
            return visible(out.toString());
        }

        public String finish() {
            String rest = inThink ? "" : pending.toString();
            pending.setLength(0);
            return visible(rest);
        }

        private String visible(String s) {
            if (!started) {
                int i = 0;
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                s = s.substring(i);
                if (!s.isEmpty()) started = true;
            }
            return s;
        }

        private static int partialSuffix(String s, String tag) {
            for (int k = Math.min(tag.length() - 1, s.length()); k > 0; k--) {
                if (s.endsWith(tag.substring(0, k))) return k;
            }
            return 0;
        }
    }

    // ---------------------------------------------------------------- http

    /** Something answered, but not in this API's format. */
    static final class WrongApi extends IOException {
        WrongApi(String msg) { super(msg); }
    }

    /** An HTTP error answer (something is listening, it said no). */
    static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }

    private String get(String path, String backend) throws IOException {
        HttpURLConnection c = open(path, "GET", 0);
        try {
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw httpError(code, text, path, backend, null);
            return text;
        } catch (HttpError e) {
            throw e;
        } catch (IOException e) {
            throw friendly(e, backend);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection post(String path, String body, String backend, Cancel cancel) throws IOException {
        HttpURLConnection c = open(path, "POST", 0);
        if (cancel != null) cancel.attach(c);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/x-ndjson, text/event-stream, application/json");
        c.setDoOutput(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try {
            try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
            int code = c.getResponseCode();
            if (code >= 400) {
                String text = read(c.getErrorStream());
                c.disconnect();
                String model = "";
                try { model = new JSONObject(body).optString("model", ""); } catch (JSONException ignored) {}
                throw httpError(code, text, path, backend, model);
            }
            return c;
        } catch (HttpError e) {
            throw e;
        } catch (IOException e) {
            c.disconnect();
            if (cancel != null && cancel.isCancelled()) throw new IOException("stopped");
            throw friendly(e, backend);
        }
    }

    private HttpURLConnection open(String path, String method, int unused) throws IOException {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
        } catch (java.net.MalformedURLException e) {
            throw new IOException("That server address doesn't look right: " + base);
        }
        c.setRequestMethod(method);
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("Accept", "application/json");
        if (!apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
        return c;
    }

    /** Turns an HTTP error answer into something a person can act on. */
    HttpError httpError(int code, String body, String path, String backend, String model) {
        String detail = "";
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("error")) detail = errorText(o.opt("error"));
            else if (o.has("detail")) detail = String.valueOf(o.opt("detail"));
            else if (o.has("message")) detail = o.optString("message");
        } catch (Exception e) {
            detail = shorten(body.trim(), 160);
        }
        String where = hostPort(base);
        String msg;
        if (code == 401 || code == 403) {
            msg = apiKey.isEmpty()
                    ? "The server at " + where + " wants an API key (HTTP " + code + "). Add it in Settings → Server."
                    : "The server at " + where + " rejected the API key (HTTP " + code + "). Check the key in Settings → Server.";
        } else if (code == 404 && model != null && !model.isEmpty() && detail.toLowerCase(Locale.US).contains("not found")) {
            msg = explainModelError(detail, model, backend);
        } else if (code == 404) {
            msg = "Nothing at " + where + path + " (HTTP 404). Is this the right port, and the right server type?";
        } else if (code == 400 && model != null && !model.isEmpty() && detail.toLowerCase(Locale.US).contains("model")) {
            msg = explainModelError(detail, model, backend);
        } else {
            msg = "The server at " + where + " answered HTTP " + code + (detail.isEmpty() ? "" : ": " + detail);
        }
        return new HttpError(code, msg);
    }

    /** Network failures in plain words. */
    IOException friendly(IOException e, String backend) {
        if (e instanceof HttpError) return e;
        String where = hostPort(base);
        String m = String.valueOf(e.getMessage());
        if (e instanceof UnknownHostException) {
            return new IOException("Can't find \"" + where + "\". Check the address (and that Tailscale or your VPN is on, if you use one).");
        }
        if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            String hint = OLLAMA.equals(backend)
                    ? " If Ollama runs on another computer, start it with OLLAMA_HOST=0.0.0.0 so it listens on the network."
                    : " If it runs on another computer, make sure it listens on the network, not just localhost (LM Studio: \"Serve on Local Network\"; llama.cpp: --host 0.0.0.0).";
            return new IOException("Couldn't connect to " + where + ". Is the server running, and is the phone on the same network?" + hint);
        }
        if (e instanceof SocketTimeoutException) {
            return m.toLowerCase(Locale.US).contains("connect")
                    ? new IOException("No answer from " + where + " (connection timed out). Same Wi-Fi? A firewall can block the port too.")
                    : new IOException("The server at " + where + " stopped answering (timed out). A big model may still be loading, try again.");
        }
        if (e instanceof UnknownServiceException && m.toLowerCase(Locale.US).contains("cleartext")) {
            return new IOException("Android blocked plain http to " + where + ".");
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return new IOException("HTTPS to " + where + " failed (" + m + "). If the server is plain http, use http:// in the address.");
        }
        return new IOException("Problem talking to " + where + ": " + m);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    static String shorten(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
