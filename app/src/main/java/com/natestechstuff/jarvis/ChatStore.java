package com.natestechstuff.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Conversation history, saved as JSON in the app's private storage. */
public final class ChatStore {
    public static final String FROM_ME = "me", FROM_TAB = "tab", FROM_JARVIS = "jarvis", FROM_SYSTEM = "system";
    /** v2.3: a tool-call chip. text = JSON {"chip": "...", "calls": "<tool_call>…", "responses": "<tool_response>…" | ""} */
    public static final String FROM_TOOL = "tool";
    private static final int MAX = 300;
    private static ChatStore instance;

    public static final class Item {
        public final String from, text, via;
        public final long time;
        /** Pro: Orbit's thought process for this reply ("" everywhere else). */
        public final String think;
        Item(String from, String text, String via, long time) { this(from, text, via, time, ""); }
        Item(String from, String text, String via, long time, String think) {
            this.from = from; this.text = text; this.via = via; this.time = time; this.think = think == null ? "" : think;
        }
    }

    public interface Listener { void onAdded(Item item); }

    private final File file;
    private final List<Item> items = new ArrayList<>();
    private Listener listener;

    public static synchronized ChatStore get(Context c) {
        if (instance == null) instance = new ChatStore(c.getApplicationContext());
        return instance;
    }

    private ChatStore(Context c) {
        file = new File(c.getFilesDir(), "chat.json");
        load();
    }

    public synchronized List<Item> all() { return new ArrayList<>(items); }

    public void setListener(Listener l) { listener = l; }

    public synchronized Item add(String from, String text, String via) {
        // Pro: Orbit's reply carries the thought process collected while it was generated
        String think = Dev.ON && FROM_JARVIS.equals(from) ? Dev.takePendingThink() : "";
        Item it = new Item(from, text, via == null ? "" : via, System.currentTimeMillis(), think);
        items.add(it);
        while (items.size() > MAX) items.remove(0);
        save();
        if (listener != null) listener.onAdded(it);
        return it;
    }

    public synchronized void clear() {
        items.clear();
        save();
    }

    /** Pro: the history as JSON (the same format as chat.json). */
    public synchronized String exportJson() {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject().put("from", it.from).put("text", it.text).put("via", it.via).put("time", it.time);
                if (!it.think.isEmpty()) o.put("think", it.think);
                a.put(o);
            }
            return a.toString(2);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Pro: replaces the history with an exported JSON array. Returns how many messages were read. */
    public synchronized int importJson(String json) throws org.json.JSONException {
        JSONArray a = new JSONArray(json);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            in.add(new Item(o.optString("from", FROM_SYSTEM), o.optString("text"), o.optString("via"),
                    o.optLong("time", System.currentTimeMillis()), o.optString("think", "")));
        }
        items.clear();
        items.addAll(in);
        while (items.size() > MAX) items.remove(0);
        save();
        return in.size();
    }

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray a = new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                items.add(new Item(o.optString("from"), o.optString("text"), o.optString("via"), o.optLong("time"), o.optString("think", "")));
            }
        } catch (Exception ignored) {
            // corrupt history: start fresh rather than crash
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject().put("from", it.from).put("text", it.text).put("via", it.via).put("time", it.time);
                if (!it.think.isEmpty()) o.put("think", it.think);
                a.put(o);
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (Exception ignored) {
        }
    }
}
