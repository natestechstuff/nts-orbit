package com.natestechstuff.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v2.2 Diagnostics: the last 20 things Jarvis saw from Messages and what it did with each
 * (spoken / skipped + why). Shown in Settings so you can screenshot it if replies go quiet.
 * The list logic is pure Java (unit-tested); storage is a private SharedPreferences string.
 */
public final class Diag {
    public static final int MAX = 20;
    private static final char F = '\u001f', R = '\u001e';

    public static final class Entry {
        public long at;
        public final String title, sender, text, decision;
        public int count = 1;

        public Entry(long at, String title, String sender, String text, String decision) {
            this.at = at;
            this.title = clean(title);
            this.sender = clean(sender);
            this.text = snippet(text);
            this.decision = clean(decision);
        }

        boolean sameAs(Entry o) {
            return title.equals(o.title) && sender.equals(o.sender) && text.equals(o.text) && decision.equals(o.decision);
        }

        public String line() {
            String t = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(at));
            StringBuilder sb = new StringBuilder(t);
            if (count > 1) sb.append(" ×").append(count);
            sb.append("  ").append(title.isEmpty() ? "—" : title);
            if (!sender.isEmpty()) sb.append(" · from ").append(sender);
            if (!text.isEmpty()) sb.append("\n  “").append(text).append("”");
            sb.append("\n  → ").append(decision);
            return sb.toString();
        }
    }

    private Diag() {}

    static String clean(CharSequence s) {
        if (s == null) return "";
        return s.toString().replace(F, ' ').replace(R, ' ').replaceAll("\\s+", " ").trim();
    }

    /** First 40 characters, one line. */
    public static String snippet(CharSequence s) {
        String c = clean(s);
        return c.length() <= 40 ? c : c.substring(0, 40) + "…";
    }

    /** Append, collapsing an identical repeat into "×n", keeping the newest MAX. */
    public static List<Entry> append(List<Entry> list, Entry e) {
        List<Entry> out = new ArrayList<>(list);
        if (!out.isEmpty() && out.get(out.size() - 1).sameAs(e)) {
            Entry last = out.get(out.size() - 1);
            last.count++;
            last.at = e.at;
        } else {
            out.add(e);
        }
        while (out.size() > MAX) out.remove(0);
        return out;
    }

    public static String encode(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            if (sb.length() > 0) sb.append(R);
            sb.append(e.at).append(F).append(e.count).append(F).append(e.title).append(F)
                    .append(e.sender).append(F).append(e.text).append(F).append(e.decision);
        }
        return sb.toString();
    }

    public static List<Entry> decode(String s) {
        List<Entry> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String rec : s.split(String.valueOf(R))) {
            String[] f = rec.split(String.valueOf(F), -1);
            if (f.length < 6) continue;
            try {
                Entry e = new Entry(Long.parseLong(f[0]), f[2], f[3], f[4], f[5]);
                e.count = Integer.parseInt(f[1]);
                out.add(e);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // ---------------------------------------------------------------- storage

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE);
    }

    public static synchronized void log(Context c, CharSequence title, CharSequence sender, CharSequence text, String decision) {
        try {
            SharedPreferences p = sp(c);
            List<Entry> list = append(decode(p.getString("events", "")),
                    new Entry(System.currentTimeMillis(), clean(title), clean(sender), text == null ? "" : text.toString(), decision));
            p.edit().putString("events", encode(list)).apply();
        } catch (Exception ignored) {
            // diagnostics must never break the app
        }
    }

    /** Newest first. */
    public static synchronized List<Entry> read(Context c) {
        List<Entry> l = decode(sp(c).getString("events", ""));
        java.util.Collections.reverse(l);
        return l;
    }

    public static synchronized void clear(Context c) {
        sp(c).edit().remove("events").apply();
    }
}
