package com.natestechstuff.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The saved local AI servers and which one is active (SharedPreferences, JSON). */
public final class ServerStore {
    private static ServerStore inst;
    private final SharedPreferences p;

    private ServerStore(Context c) {
        p = c.getApplicationContext().getSharedPreferences("servers", Context.MODE_PRIVATE);
    }

    public static synchronized ServerStore get(Context c) {
        if (inst == null) inst = new ServerStore(c);
        return inst;
    }

    public synchronized List<LlmServer> all() {
        return decode(p.getString("list", "[]"));
    }

    static List<LlmServer> decode(String json) {
        List<LlmServer> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(LlmServer.fromJson(o));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String encode(List<LlmServer> list) {
        JSONArray a = new JSONArray();
        for (LlmServer s : list) a.put(s.toJson());
        return a.toString();
    }

    public synchronized LlmServer find(String id) {
        for (LlmServer s : all()) if (s.id.equals(id)) return s;
        return null;
    }

    /** The server Orbit talks to in Server mode (the first saved one if none was picked). */
    public synchronized LlmServer active() {
        List<LlmServer> list = all();
        if (list.isEmpty()) return null;
        String id = p.getString("active", "");
        for (LlmServer s : list) if (s.id.equals(id)) return s;
        return list.get(0);
    }

    public synchronized void setActive(String id) { p.edit().putString("active", id).apply(); }

    /** Adds or replaces (same id). A server without an id gets one. Returns the saved server. */
    public synchronized LlmServer save(LlmServer s) {
        if (s.id.isEmpty()) s = new LlmServer(UUID.randomUUID().toString(), s.name, s.url, s.type, s.detected, s.apiKey, s.model);
        List<LlmServer> list = all();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) { list.set(i, s); replaced = true; }
        }
        if (!replaced) list.add(s);
        p.edit().putString("list", encode(list)).apply();
        if (list.size() == 1) setActive(s.id);
        return s;
    }

    public synchronized void delete(String id) {
        List<LlmServer> list = all();
        List<LlmServer> keep = new ArrayList<>();
        for (LlmServer s : list) if (!s.id.equals(id)) keep.add(s);
        SharedPreferences.Editor e = p.edit().putString("list", encode(keep));
        if (id.equals(p.getString("active", ""))) e.putString("active", keep.isEmpty() ? "" : keep.get(0).id);
        e.apply();
    }
}
