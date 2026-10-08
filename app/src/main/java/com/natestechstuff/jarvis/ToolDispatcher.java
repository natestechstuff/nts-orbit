package com.natestechstuff.jarvis;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * v2.3: runs one parsed tool call. Pure Java: the phone side is behind {@link Actions}
 * (PhoneTools on the device, a fake in unit tests).
 */
public final class ToolDispatcher {
    private ToolDispatcher() {}

    /** What the phone can do. Each method returns null on success or a short error. */
    public interface Actions {
        /** e.g. "12:41 PM, Wednesday, October 7, 2026" */
        String timeDate();
        /** {level 0-100, charging 0/1}, or null if unknown */
        int[] battery();
        String setTimer(int seconds, String label);
        String setAlarm(int hour, int minute, String label);
        /** Opens the best match; returns the app's real label, or null if nothing matched. */
        String openApp(String name);
        String flashlight(boolean on);
    }

    public enum Kind { LOCAL, ASK_TAB, ERROR }

    public static final class Outcome {
        public final Kind kind;
        /** The call as it was (or as it was rewritten, e.g. unknown tool → ask_tab). */
        public final ToolCalls.Call call;
        /** For LOCAL/ERROR: the JSON fed back to the model inside <tool_response>. */
        public final String response;
        /** Short line to say if the model's follow-up is unusable. */
        public final String speech;
        /** For ASK_TAB: the request to hand off (unused in this app). */
        public final String askText;
        public final String chip;

        Outcome(Kind kind, ToolCalls.Call call, String response, String speech, String askText) {
            this.kind = kind; this.call = call; this.response = response; this.speech = speech; this.askText = askText;
            this.chip = ToolCalls.chip(call);
        }
    }

    public static Outcome dispatch(ToolCalls.Call c, String userText, boolean askTabEnabled, Actions a) {
        String n = c.name == null ? "" : c.name.trim().toLowerCase(Locale.US);
        JSONObject args = c.args;
        try {
            switch (n) {
                case ToolCalls.ASK_TAB: {
                    if (!askTabEnabled) return error(c,"That needs the internet, and this assistant has no online tools. Answer from what you know, or say you can't do it.");
                    String req = args.optString("request", "").trim();
                    if (req.isEmpty()) req = userText == null ? "" : userText.trim();
                    return askTab(new ToolCalls.Call(ToolCalls.ASK_TAB, obj("request", req)), req);
                }
                case ToolCalls.TIME: {
                    String t = a.timeDate();
                    return local(c, obj("time_date", t), "It's " + t + ".");
                }
                case ToolCalls.BATTERY: {
                    int[] b = a.battery();
                    if (b == null) return error(c, "Couldn't read the battery.");
                    JSONObject r = obj("level_percent", b[0]);
                    r.put("charging", b[1] != 0);
                    return local(c, r, "Battery's at " + b[0] + " percent" + (b[1] != 0 ? " and charging." : "."));
                }
                case ToolCalls.TIMER: {
                    Integer s = intArg(args, "seconds");
                    if (s == null) {
                        Integer m = intArg(args, "minutes");
                        if (m != null) s = m * 60;
                    }
                    if (s == null || s <= 0 || s > 24 * 3600) return error(c, "Need a timer length between 1 second and 24 hours.");
                    String label = args.optString("label", "").trim();
                    ToolCalls.Call fixed = new ToolCalls.Call(c.name, args);
                    String err = a.setTimer(s, label);
                    if (err != null) return error(fixed, err);
                    return local(fixed, ok("timer set for " + ToolCalls.duration(s).replace(" s", " sec")), "Timer set for " + spoken(s) + ".");
                }
                case ToolCalls.ALARM: {
                    Integer h = intArg(args, "hour"), m = intArg(args, "minute");
                    if (m == null) m = 0;
                    if (h == null || h < 0 || h > 23 || m < 0 || m > 59) return error(c, "Need an alarm time: hour 0-23, minute 0-59.");
                    String label = args.optString("label", "").trim();
                    String err = a.setAlarm(h, m, label);
                    if (err != null) return error(c, err);
                    return local(c, ok("alarm set for " + ToolCalls.clock(h, m)), "Alarm set for " + ToolCalls.clock(h, m) + ".");
                }
                case ToolCalls.OPEN_APP: {
                    String name = args.optString("name", args.optString("app", "")).trim();
                    if (name.isEmpty()) return error(c, "Which app?");
                    String opened = a.openApp(name);
                    if (opened == null) return error(c, "No app called " + name + " on this phone.");
                    return local(c, ok("opened " + opened), "Opening " + opened + ".");
                }
                case ToolCalls.FLASHLIGHT: {
                    boolean on = boolArg(args, "on", true);
                    String err = a.flashlight(on);
                    if (err != null) return error(c, err);
                    return local(c, ok("flashlight " + (on ? "on" : "off")), "Flashlight " + (on ? "on." : "off."));
                }
                default:
                    // A tool we don't have (get_weather, send_text, …): that's what the hand-off is for (when enabled).
                    if (askTabEnabled && userText != null && !userText.trim().isEmpty()) {
                        String req = userText.trim();
                        return askTab(new ToolCalls.Call(ToolCalls.ASK_TAB, obj("request", req)), req);
                    }
                    return error(c, "There's no " + c.name + " tool.");
            }
        } catch (JSONException e) {
            return error(c, "bad arguments");
        } catch (RuntimeException e) {
            return error(c, "failed: " + e.getMessage());
        }
    }

    private static Outcome askTab(ToolCalls.Call c, String req) {
        return new Outcome(Kind.ASK_TAB, c, null, "Asking Tab.", req);
    }

    private static Outcome local(ToolCalls.Call c, JSONObject result, String speech) {
        return new Outcome(Kind.LOCAL, c, ToolCalls.dumps(result), speech, null);
    }

    private static Outcome error(ToolCalls.Call c, String why) {
        JSONObject r = new JSONObject();
        try { r.put("error", why); } catch (JSONException ignored) {}
        return new Outcome(Kind.ERROR, c, ToolCalls.dumps(r), why, null);
    }

    private static JSONObject ok(String what) throws JSONException { return obj("result", what); }

    static JSONObject obj(String k, Object v) {
        JSONObject o = new JSONObject();
        try { o.put(k, v); } catch (JSONException ignored) {}
        return o;
    }

    static String spoken(int s) {
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(h == 1 ? " hour" : " hours");
        if (m > 0) b.append(b.length() > 0 ? " " : "").append(m).append(m == 1 ? " minute" : " minutes");
        if (sec > 0) b.append(b.length() > 0 ? " " : "").append(sec).append(sec == 1 ? " second" : " seconds");
        return b.toString();
    }

    /** Integer argument, tolerating 300, 300.0, "300", "5 min". */
    static Integer intArg(JSONObject a, String key) {
        if (a == null || !a.has(key)) return null;
        Object v = a.opt(key);
        if (v instanceof Number) return (int) Math.round(((Number) v).doubleValue());
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.US);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)?").matcher(s);
            if (!m.find()) return null;
            double n = Double.parseDouble(m.group(1));
            String u = m.group(2);
            if ("seconds".equals(key) && u != null) {
                if (u.startsWith("h")) n *= 3600;
                else if (u.startsWith("m")) n *= 60;
            }
            return (int) Math.round(n);
        }
        return null;
    }

    static boolean boolArg(JSONObject a, String key, boolean def) {
        if (a == null || !a.has(key)) return def;
        Object v = a.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.US);
            if (s.equals("false") || s.equals("off") || s.equals("0") || s.equals("no")) return false;
            if (s.equals("true") || s.equals("on") || s.equals("1") || s.equals("yes")) return true;
        }
        return def;
    }

    /** Best launcher label for a spoken app name; null if nothing is close. Pure, so it's unit-tested. */
    public static int bestAppMatch(String wanted, java.util.List<String> labels) {
        String w = norm(wanted);
        if (w.isEmpty()) return -1;
        int best = -1, bestScore = 0;
        for (int i = 0; i < labels.size(); i++) {
            String l = norm(labels.get(i));
            if (l.isEmpty()) continue;
            int score;
            if (l.equals(w)) score = 100;
            else if (l.replace(" ", "").equals(w.replace(" ", ""))) score = 95;
            else if (l.startsWith(w + " ") || l.startsWith(w)) score = 80 - Math.min(20, l.length() - w.length());
            else if (w.startsWith(l + " ") || (w.contains(l) && l.length() >= 4)) score = 60;
            else if (l.contains(w) && w.length() >= 3) score = 50 - Math.min(20, l.length() - w.length());
            else score = 0;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (t.startsWith("the ")) t = t.substring(4);
        if (t.endsWith(" app")) t = t.substring(0, t.length() - 4);
        return t;
    }
}
