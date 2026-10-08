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
        Item(String from, String text, String via, long time) {
            this.from = from; this.text = text; this.via = via; this.time = time;
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
        Item it = new Item(from, text, via == null ? "" : via, System.currentTimeMillis());
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

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray a = new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                items.add(new Item(o.optString("from"), o.optString("text"), o.optString("via"), o.optLong("time")));
            }
        } catch (Exception ignored) {
            // corrupt history: start fresh rather than crash
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                a.put(new JSONObject().put("from", it.from).put("text", it.text).put("via", it.via).put("time", it.time));
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
