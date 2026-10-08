package com.natestechstuff.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

/** All settings in one place (SharedPreferences). */
public final class Prefs {
    /** On-device GGUF model through llama.cpp. */
    public static final String MODE_LOCAL = "local";
    /** A local AI server (Ollama, LM Studio, llama.cpp server, …), see {@link ServerStore}. */
    public static final String MODE_SERVER = "server";

    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    public String mode() {
        String m = p.getString("mode", MODE_LOCAL);
        // only on-device and server exist; anything else (e.g. restored old prefs) falls back to on-device
        if (!MODE_LOCAL.equals(m) && !MODE_SERVER.equals(m)) return MODE_LOCAL;
        return m;
    }
    public boolean isServer() { return MODE_SERVER.equals(mode()); }
    public boolean isLocal() { return MODE_LOCAL.equals(mode()); }

    // ---- My model (on-device)
    public String modelPath() { return p.getString("model_path", ""); }
    public void setModelPath(String s) { p.edit().putString("model_path", s).apply(); }
    public int threads() { return p.getInt("model_threads", 4); }
    public int contextSize() { return p.getInt("model_ctx", 2048); }
    public int maxTokens() { return p.getInt("model_max_tokens", 320); }
    public float temperature() { return p.getFloat("model_temp", 0.7f); }
    public boolean keepModelLoaded() { return p.getBoolean("model_keep_loaded", true); }
    public String systemPrompt() { return p.getString("model_system_prompt", ""); }
    /** How many recent messages of the local chat go back into the prompt. */
    public int historyTurns() { return p.getInt("model_history", 8); }
    /** v2.3: tool calling on the on-device brain (timer, alarm, apps, flashlight, time, battery). */
    public boolean toolsOn() { return p.getBoolean("tools_on", true); }
    public void setMode(String m) { p.edit().putString("mode", m).apply(); }

    public float speechRate() { return p.getFloat("speech_rate", 1.05f); }
    public float pitch() { return p.getFloat("pitch", 1.0f); }
    public String voiceName() { return p.getString("voice", ""); }

    public boolean handsFree() { return p.getBoolean("hands_free", false); }
    public void setHandsFree(boolean b) { p.edit().putBoolean("hands_free", b).apply(); }
    public boolean speakReplies() { return p.getBoolean("speak_replies", true); }
    public boolean haptics() { return p.getBoolean("haptics", true); }
    public boolean onboarded() { return p.getBoolean("onboarded", false); }
    public void setOnboarded() { p.edit().putBoolean("onboarded", true).apply(); }

    public SharedPreferences.Editor edit() { return p.edit(); }
}
