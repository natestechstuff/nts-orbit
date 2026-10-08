package com.natestechstuff.jarvis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * v2.3 tool calling for the on-device brain, in Qwen2.5's native format (pure Java, unit-tested).
 *
 *  - {@link #systemPrompt}: the tools block exactly as Qwen2.5's chat template writes it
 *    (signatures inside &lt;tools&gt;&lt;/tools&gt; in the system message) plus short rules tuned for a 1.5B model.
 *  - {@link StreamFilter}: hides the raw &lt;tool_call&gt; text from the chat bubble and the voice while it streams.
 *  - {@link #parse}: pulls the calls out of a finished reply (tolerates a missing close tag, code fences,
 *    stringified arguments, a bare JSON call with no tags).
 *  - {@link #shouldStop}: lets generation end early once a call is complete or the model starts refusing.
 *  - {@link #looksLikeRefusal}: "I'm sorry, I can't…" from the model means "hand off" (off in this app).
 */
public final class ToolCalls {
    private ToolCalls() {}

    public static final String OPEN = "<tool_call>", CLOSE = "</tool_call>";

    public static final String ASK_TAB = "ask_tab", TIME = "get_time_date", BATTERY = "get_battery",
            TIMER = "set_timer", ALARM = "set_alarm", OPEN_APP = "open_app", FLASHLIGHT = "flashlight";

    // ---------------------------------------------------------------- prompt

    /** One line per tool, exactly as Python's json.dumps (Qwen's template `tojson`) writes them. */
    static final String TOOL_ASK_TAB = "";   // hand-off tool: not offered in this app (phone tools only)
    static final String[] LOCAL_TOOLS = {
        "{\"type\": \"function\", \"function\": {\"name\": \"get_time_date\", \"description\": \"Get the current time and date.\", \"parameters\": {\"type\": \"object\", \"properties\": {}, \"required\": []}}}",
        "{\"type\": \"function\", \"function\": {\"name\": \"get_battery\", \"description\": \"Get the phone's battery level.\", \"parameters\": {\"type\": \"object\", \"properties\": {}, \"required\": []}}}",
        "{\"type\": \"function\", \"function\": {\"name\": \"set_timer\", \"description\": \"Start a countdown timer on the phone.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"seconds\": {\"type\": \"integer\", \"description\": \"Timer length in seconds\"}, \"label\": {\"type\": \"string\"}}, \"required\": [\"seconds\"]}}}",
        "{\"type\": \"function\", \"function\": {\"name\": \"set_alarm\", \"description\": \"Set an alarm on the phone.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"hour\": {\"type\": \"integer\", \"description\": \"0-23\"}, \"minute\": {\"type\": \"integer\", \"description\": \"0-59\"}, \"label\": {\"type\": \"string\"}}, \"required\": [\"hour\", \"minute\"]}}}",
        "{\"type\": \"function\", \"function\": {\"name\": \"open_app\", \"description\": \"Open an app on the phone.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\", \"description\": \"App name, e.g. YouTube\"}}, \"required\": [\"name\"]}}}",
        "{\"type\": \"function\", \"function\": {\"name\": \"flashlight\", \"description\": \"Turn the phone's flashlight on or off.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"on\": {\"type\": \"boolean\"}}, \"required\": [\"on\"]}}}",
    };

    /** Rules after the tools block (small models follow what they read last). With the hand-off on (unused here). */
    static final String RULES_WITH_TAB = "";
    /** Rules for phone tools only (what this app uses). */
    static final String RULES_LOCAL_ONLY = "How to answer: use the phone tools for timers, alarms, opening apps, the flashlight, the time and the battery. Explain things, write code and do math yourself, with no tool. You are offline, so for anything online say you can't reach the internet from here.";

    /**
     * The full system message: the user's system prompt, then Qwen2.5's own tools section, then the rules.
     * tools=false returns the base prompt unchanged (v2.2 behaviour).
     */
    public static String systemPrompt(String base, boolean tools, boolean askTab) {
        if (!tools) return base;
        askTab = askTab && !TOOL_ASK_TAB.isEmpty();   // no hand-off target in this app
        StringBuilder s = new StringBuilder(base == null ? "" : base.trim());
        s.append("\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
         .append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>");
        if (askTab) s.append('\n').append(TOOL_ASK_TAB);
        for (String t : LOCAL_TOOLS) s.append('\n').append(t);
        s.append("\n</tools>\n\nFor each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n")
         .append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>");
        s.append("\n\n").append(askTab ? RULES_WITH_TAB : RULES_LOCAL_ONLY);
        return s.toString();
    }

    // ---------------------------------------------------------------- parsed calls

    public static final class Call {
        public final String name;
        public final JSONObject args;
        public Call(String name, JSONObject args) { this.name = name; this.args = args == null ? new JSONObject() : args; }
        /** Exactly how Qwen writes it: {"name": "...", "arguments": {...}} */
        public String json() { return "{\"name\": " + quote(name) + ", \"arguments\": " + dumps(args) + "}"; }
        @Override public String toString() { return json(); }
    }

    public static final class Parsed {
        /** Text before the first tool call (or the whole reply when there is none). */
        public String text = "";
        public final List<Call> calls = new ArrayList<>();
        /** A <tool_call> tag (or a bare JSON call) was present. */
        public boolean sawCall;
        /** Tool-call blocks whose JSON couldn't be read. */
        public int malformed;
        public boolean refusal;
        public boolean ok() { return !calls.isEmpty() && malformed == 0; }
        public boolean bad() { return sawCall && calls.isEmpty(); }
    }

    public static Parsed parse(String reply) {
        Parsed p = new Parsed();
        if (reply == null) reply = "";
        int at = reply.indexOf(OPEN);
        if (at < 0) {
            String t = reply.trim();
            String unfenced = stripFence(t);
            if (unfenced.startsWith("{") && unfenced.contains("\"name\"")) {
                // bare call without tags (small models do this sometimes)
                p.sawCall = true;
                Call c = readCall(unfenced);
                if (c != null) p.calls.add(c); else p.malformed++;
                return p;
            }
            p.text = t;
            p.refusal = looksLikeRefusal(t);
            return p;
        }
        p.sawCall = true;
        p.text = reply.substring(0, at).trim();
        int i = at;
        while (i >= 0 && i < reply.length()) {
            int start = i + OPEN.length();
            int end = reply.indexOf(CLOSE, start);
            int nextOpen = reply.indexOf(OPEN, start);
            int stop = end;
            if (stop < 0 || (nextOpen >= 0 && nextOpen < stop)) stop = nextOpen;   // missing close tag
            if (stop < 0) stop = reply.length();
            String body = reply.substring(start, stop);
            if (!body.trim().isEmpty() || stop == reply.length()) {
                Call c = readCall(body);
                if (c != null) p.calls.add(c); else p.malformed++;
            }
            i = reply.indexOf(OPEN, stop);
        }
        return p;
    }

    private static String stripFence(String s) {
        s = s.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            s = nl < 0 ? "" : s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.trim();
    }

    /** One call's JSON → Call, or null. Accepts "arguments" as an object or a JSON string, or "parameters". */
    static Call readCall(String body) {
        String s = stripFence(body);
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0) return null;
        String json = b > a ? s.substring(a, b + 1) : s.substring(a);
        JSONObject o = tryObject(json);
        if (o == null) o = tryObject(balance(s.substring(a)));    // cut off mid-call: close the braces
        if (o == null) return null;
        if (o.has("function") && o.optJSONObject("function") != null) o = o.optJSONObject("function");
        String name = o.optString("name", "").trim();
        if (name.isEmpty()) return null;
        Object args = o.opt("arguments");
        if (args == null) args = o.opt("parameters");
        JSONObject argObj;
        if (args instanceof JSONObject) argObj = (JSONObject) args;
        else if (args instanceof String) {
            argObj = tryObject((String) args);
            if (argObj == null) argObj = new JSONObject();
        } else argObj = new JSONObject();
        return new Call(name, argObj);
    }

    private static JSONObject tryObject(String s) {
        if (s == null) return null;
        try {
            Object v = new JSONObject(s);
            return (JSONObject) v;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Appends missing closing quotes/braces to a JSON object that was cut off. */
    static String balance(String s) {
        StringBuilder out = new StringBuilder(s.trim());
        boolean inStr = false, esc = false;
        List<Character> stack = new ArrayList<>();
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') stack.add('}');
            else if (c == '[') stack.add(']');
            else if ((c == '}' || c == ']') && !stack.isEmpty()) stack.remove(stack.size() - 1);
        }
        if (inStr) out.append('"');
        for (int i = stack.size() - 1; i >= 0; i--) out.append(stack.get(i));
        return out.toString();
    }

    // ---------------------------------------------------------------- refusals

    private static final Pattern REFUSAL = Pattern.compile(
            "^(?:(?:i'?m|i am) (?:sorry|afraid|unable|not able|(?:currently |just )?offline)|sorry[,.! ]|unfortunately[, ]|as an ai|i can(?:'|no)?t |i cannot |i do not have |i don'?t have (?:access|the ability|real[- ]?time|live|any way|a way)|i'?m not able|i have no (?:access|way))",
            Pattern.CASE_INSENSITIVE);

    /** True when the reply opens like "I'm sorry, but I can't…" / "I don't have access to…". */
    public static boolean looksLikeRefusal(String text) {
        if (text == null) return false;
        String t = text.trim().replace('\u2019', '\'');
        return !t.isEmpty() && REFUSAL.matcher(t).find();
    }

    // ---------------------------------------------------------------- streaming

    /**
     * Decides, from the reply so far, whether to stop generating: a complete call is done (anything
     * after it is noise), or (with refusalGuard) the first sentence is a refusal that will be handed off.
     */
    public static boolean shouldStop(String soFar, boolean refusalGuard) {
        int open = soFar.indexOf(OPEN);
        if (open >= 0) {
            int close = soFar.lastIndexOf(CLOSE);
            if (close < 0 || close < soFar.lastIndexOf(OPEN)) return false;   // still inside a call
            String after = soFar.substring(close + CLOSE.length()).trim();
            if (after.isEmpty()) return false;
            return !(OPEN.startsWith(after) || after.startsWith(OPEN));      // text after the call: done
        }
        if (refusalGuard) {
            String first = firstSentence(soFar);
            return first != null && looksLikeRefusal(first);
        }
        return false;
    }

    /** The first sentence once it's complete, else null. */
    static String firstSentence(String s) {
        String t = s.replaceAll("^\\s+", "");
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || ((c == '.' || c == '!' || c == '?' || c == ',') && i > 2)) return t.substring(0, i + 1);
        }
        return t.length() >= 60 ? t : null;
    }

    /**
     * Turns the raw token stream into what may be shown and spoken. Never lets "<tool_call>" (or a
     * partial "<tool") reach the screen or the voice. With refusalGuard, holds the first sentence back
     * until it's clear it isn't "I'm sorry, I can't…" (that one is handed off instead of being spoken).
     */
    public static final class StreamFilter {
        private final boolean refusalGuard;
        private final StringBuilder all = new StringBuilder();
        private int shown = 0;            // chars of `all` already released
        private boolean inCall, refused, firstChecked, bareJson, leadDecided, holdAll;

        // v2.4 fake-claim guard: the user's text (null = off); holdText = show nothing until the end (retry round)
        private final String claimUser;
        private final boolean holdText;
        private boolean claimed, claimCleared;

        public StreamFilter(boolean refusalGuard) { this(refusalGuard, null, false); }

        public StreamFilter(boolean refusalGuard, String claimUser, boolean holdText) {
            this.refusalGuard = refusalGuard;
            this.firstChecked = !refusalGuard;
            this.claimUser = claimUser;
            this.holdText = holdText;
            this.claimCleared = claimUser == null;
        }

        /** v2.4: the reply so far claims a phone action with no tool call (it will be thrown away). */
        public boolean claimed() { return claimed; }

        public boolean inToolCall() { return inCall || bareJson; }
        public boolean refused() { return refused; }
        public String text() { return all.toString(); }

        /** Returns the newly safe-to-show text (may be ""). */
        public String push(String piece) {
            all.append(piece);
            return release(false);
        }

        /** End of stream: whatever is still safe to show. */
        public String finish() { return release(true); }

        private String release(boolean end) {
            if (inCall || refused || bareJson || claimed) return "";
            String s = all.toString();
            if (holdText && !end) {
                if (s.contains(OPEN)) inCall = true;
                return "";
            }
            if (!leadDecided) {
                String lead = s.replaceAll("^\\s+", "");
                if (lead.isEmpty() && !end) return "";
                if (lead.startsWith("`") && lead.length() < 8 && !end) return "";
                holdAll = lead.startsWith("{") || lead.startsWith("```json") || lead.matches("(?s)^```\\s*\\{.*");
                leadDecided = true;
            }
            if (holdAll) {
                // maybe a bare JSON call without tags: hold it until the end, then decide
                if (!end) return "";
                if (parse(s).sawCall) { bareJson = true; return ""; }
                holdAll = false;
            }
            int open = s.indexOf(OPEN);
            int limit;
            if (open >= 0) {
                inCall = true;
                limit = open;
            } else {
                limit = end ? s.length() : s.length() - partialTagSuffix(s);
            }
            if (!firstChecked) {
                String first = firstSentence(s.substring(0, limit));
                if (first == null && !end && open < 0) return "";       // keep holding the first sentence
                firstChecked = true;
                String check = first != null ? first : s.substring(0, limit);
                if (looksLikeRefusal(check)) { refused = true; return ""; }
            }
            if (!claimCleared) {
                // hold the opening back until it's clear it isn't "I will turn on the flashlight"
                String held = s.substring(0, limit);
                String whole = end ? held : FakeClaim.firstSentences(held, Math.max(1, FakeClaim.sentenceCount(held)));
                if (open < 0 && (end || FakeClaim.sentenceCount(held) > 0) && FakeClaim.isFake(claimUser, whole)) { claimed = true; return ""; }
                if (!end && open < 0 && held.length() < 260 && FakeClaim.sentenceCount(held) < 2) return "";
                claimCleared = true;
            }
            if (limit <= shown) return "";
            String out = s.substring(shown, limit);
            shown = limit;
            return out;
        }

        /** How many trailing chars could be the start of "<tool_call>". */
        static int partialTagSuffix(String s) {
            for (int k = Math.min(OPEN.length() - 1, s.length()); k > 0; k--) {
                if (OPEN.startsWith(s.substring(s.length() - k))) return k;
            }
            return 0;
        }
    }

    // ---------------------------------------------------------------- formatting

    public static String toolCallText(List<Call> calls) {
        StringBuilder b = new StringBuilder();
        for (Call c : calls) {
            if (b.length() > 0) b.append('\n');
            b.append(OPEN).append('\n').append(c.json()).append('\n').append(CLOSE);
        }
        return b.toString();
    }

    /** One user turn carrying every result, the way Qwen's template merges consecutive tool messages. */
    public static String toolResponseText(List<String> results) {
        StringBuilder b = new StringBuilder();
        for (String r : results) {
            if (b.length() > 0) b.append('\n');
            b.append("<tool_response>\n").append(r).append("\n</tool_response>");
        }
        return b.toString();
    }

    /** "⚙ set_timer 5 min" */
    public static String chip(Call c) {
        String n = c.name;
        JSONObject a = c.args;
        switch (n) {
            case TIMER: {
                Integer s = ToolDispatcher.intArg(a, "seconds");
                return "⚙ set_timer " + (s == null ? "?" : duration(s));
            }
            case ALARM: {
                Integer h = ToolDispatcher.intArg(a, "hour"), m = ToolDispatcher.intArg(a, "minute");
                return "⚙ set_alarm " + (h == null ? "?" : clock(h, m == null ? 0 : m));
            }
            case OPEN_APP: return "⚙ open_app " + a.optString("name", "?");
            case FLASHLIGHT: return "⚙ flashlight " + (ToolDispatcher.boolArg(a, "on", true) ? "on" : "off");
            case ASK_TAB: return "⚙ ask_tab: " + shorten(a.optString("request", ""), 60);
            default: return "⚙ " + n;
        }
    }

    static String duration(int s) {
        if (s < 60) return s + " s";
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(" h");
        if (m > 0) { if (b.length() > 0) b.append(' '); b.append(m).append(" min"); }
        if (sec > 0) { if (b.length() > 0) b.append(' '); b.append(sec).append(" s"); }
        return b.toString();
    }

    static String clock(int h, int m) {
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d %s", h12, m, h < 12 ? "AM" : "PM");
    }

    static String shorten(String s, int n) {
        s = s.trim();
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    // ---------------------------------------------------------------- JSON like Python's json.dumps

    static String quote(String s) { return JSONObject.quote(s).replace("\\/", "/"); }

    static String dumps(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            StringBuilder b = new StringBuilder("{");
            Iterator<String> it = o.keys();
            boolean first = true;
            while (it.hasNext()) {
                String k = it.next();
                if (!first) b.append(", ");
                first = false;
                b.append(quote(k)).append(": ").append(dumps(o.opt(k)));
            }
            return b.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) b.append(", ");
                b.append(dumps(a.opt(i)));
            }
            return b.append(']').toString();
        }
        if (v instanceof String) return quote((String) v);
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return v.toString();
        }
        return quote(String.valueOf(v));
    }
}
