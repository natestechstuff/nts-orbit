package com.natestechstuff.jarvis;

import org.json.JSONException;
import org.json.JSONObject;

/** One saved local AI server (Ollama, LM Studio, llama.cpp server, …). Plain Java, unit-tested. */
public final class LlmServer {
    public final String id;
    public final String name;
    public final String url;
    /** {@link ServerClient#AUTO}, {@link ServerClient#OLLAMA} or {@link ServerClient#OPENAI}. */
    public final String type;
    /** What auto-detect found last time (ollama / openai), "" if never tested. */
    public final String detected;
    public final String apiKey;
    public final String model;

    public LlmServer(String id, String name, String url, String type, String detected, String apiKey, String model) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name.trim();
        this.url = url == null ? "" : url.trim();
        this.type = ServerClient.OLLAMA.equals(type) || ServerClient.OPENAI.equals(type) ? type : ServerClient.AUTO;
        this.detected = ServerClient.OLLAMA.equals(detected) || ServerClient.OPENAI.equals(detected) ? detected : "";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    /** The backend to talk to: the chosen type, else what auto-detect found, else auto. */
    public String backend() {
        if (!ServerClient.AUTO.equals(type)) return type;
        return detected.isEmpty() ? ServerClient.AUTO : detected;
    }

    /** "Ollama @ 192.168.1.20:11434" */
    public String label() {
        String b = backend();
        String kind = ServerClient.OLLAMA.equals(b) ? "Ollama" : ServerClient.OPENAI.equals(b) ? "OpenAI-compatible" : "auto";
        return kind + " @ " + ServerClient.hostPort(url);
    }

    public LlmServer withModel(String m) { return new LlmServer(id, name, url, type, detected, apiKey, m); }
    public LlmServer withDetected(String d) { return new LlmServer(id, name, url, type, d, apiKey, model); }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("url", url).put("type", type)
                    .put("detected", detected).put("api_key", apiKey).put("model", model);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static LlmServer fromJson(JSONObject o) {
        return new LlmServer(o.optString("id", ""), o.optString("name", ""), o.optString("url", ""), o.optString("type", ServerClient.AUTO),
                o.optString("detected", ""), o.optString("api_key", ""), o.optString("model", ""));
    }
}
