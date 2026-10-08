package com.natestechstuff.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * v2.4: deterministic action-intent router. Runs on the user's words BEFORE the model on "my model".
 * When the whole sentence is plainly a phone action (flashlight, timer, alarm, open an app, time/date,
 * battery) it returns the tool call so the app can do it right away and skip the model.
 *
 * High precision on purpose: patterns are anchored to the whole sentence, so "explain how a
 * flashlight works" or "what's a timer in javascript" never match. A miss just means the model runs.
 */
public final class ActionRouter {
    private ActionRouter() {}

    /** Answers whether an app with this name is installed (strict match). */
    public interface AppLookup { boolean has(String name); }

    // optional lead-in / tail around the command
    private static final String PRE = "^(?:(?:hey|ok|okay|yo|um|uh) )?(?:jarvis )?(?:(?:can|could|would|will) you (?:please )?|please |pls |go ahead and |i need you to |i want you to )?(?:please )?";
    private static final String POST = "(?: please| pls| for me| now| right now| real quick| quick| thanks| thank you| jarvis)*$";

    private static final String NUMW = "(?:\\d{1,4}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|twenty five|thirty|forty|forty five|fifty|sixty|ninety)";
    private static final String UNIT = "(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)";
    // "5 minutes", "1 hour 30 minutes", "1 hour and 30 minutes", "an hour and a half", "half an hour", "2 and a half minutes"
    private static final String DUR = "((?:half an? hour|half hour)|" + NUMW + "(?: and a half)? " + UNIT + "(?: and a half)?(?: (?:and )?" + NUMW + " " + UNIT + ")?)";
    private static final String LABEL = "(?: (?:for|called|named|labeled|labelled) (?:the |my )?([a-z][a-z0-9 ']{1,30}))?";
    private static final String TORCH = "(?:the |my |your )?(?:flash ?light|torch)";

    private static final Pattern[] FLASH = {
            Pattern.compile(PRE + "(?:turn|switch|flip|put|cut) (on|off) " + TORCH + POST),
            Pattern.compile(PRE + "(?:turn|switch|flip|put|cut) " + TORCH + " (on|off)" + POST),
            Pattern.compile(PRE + "(?:flash ?light|torch) (on|off)" + POST),
            Pattern.compile(PRE + "(enable|disable|activate|deactivate) " + TORCH + POST),
    };
    private static final Pattern[] TIMER = {
            Pattern.compile(PRE + "(?:set|start|make|create|put on|give me|run) (?:a |an |me a |me an |up a )?" + DUR + "(?: long)? timer" + LABEL + POST),
            Pattern.compile(PRE + "(?:set|start|make|create|put on|run) (?:a |an |me a |me an |up a |the )?timer (?:for|of|to) " + DUR + LABEL + POST),
            Pattern.compile(PRE + "(?:a )?timer (?:for )?" + DUR + LABEL + POST),
            Pattern.compile(PRE + DUR + " timer" + LABEL + POST),
    };
    private static final String TIME = "(?:(noon|midnight)|(\\d{1,2})(?:(?::| )(\\d{2}))?(?: ?(am|pm|in the morning|in the evening|at night|tonight|o'?clock(?: in the morning| in the evening| at night| am| pm)?))?)";
    private static final String DAYW = "(?: (?:tomorrow|today|tonight|tomorrow morning|in the morning))?";
    private static final Pattern[] ALARM = {
            Pattern.compile(PRE + "(?:set|make|create|put|schedule) (?:an |a |my |the |me an )?alarm (?:for|at|to) " + TIME + DAYW + LABEL + POST),
            Pattern.compile(PRE + "(?:set|make|create) (?:an |a |me an )?" + TIME + " alarm" + DAYW + LABEL + POST),
            Pattern.compile(PRE + "wake me(?: up)? at " + TIME + DAYW + POST),
            Pattern.compile(PRE + "alarm (?:for|at) " + TIME + DAYW + LABEL + POST),
    };
    private static final Pattern OPEN = Pattern.compile(PRE + "(?:open|launch|pull up|bring up|fire up|open up) (?:the |my |up )?([a-z0-9][a-z0-9 .&+'!-]{1,28}?)(?: app| application)?" + POST);
    private static final Pattern[] CLOCK = {
            Pattern.compile(PRE + "(?:what(?:'s| is) the time|what time is it|what time it is|tell me the time|(?:do you know )?what time is it|time check|what(?:'s| is) the current time|current time)(?: right now| now)?" + POST),
            Pattern.compile(PRE + "(?:what(?:'s| is) (?:the |today'?s )?date(?: today)?|what(?:'s| is) today(?:'s date)?|what day is (?:it|today)(?: today)?|what(?:'s| is) the date (?:today|right now)|tell me the date|today'?s date|what(?:'s| is) the day today)" + POST),
    };
    private static final Pattern[] BATT = {
            Pattern.compile(PRE + "(?:what(?:'s| is) )?(?:my |the )?(?:phone'?s? |phone )?battery(?: level| percentage| percent| life| status| at)?" + POST),
            Pattern.compile(PRE + "how much battery (?:do i have|is left|have i got|i got|does my phone have)(?: left)?" + POST),
            Pattern.compile(PRE + "how(?:'s| is) (?:my |the )?(?:phone'?s? )?battery(?: doing| looking| level)?" + POST),
            Pattern.compile(PRE + "(?:what(?:'s| is) )?(?:my |the )?battery (?:at|level at)" + POST),
            Pattern.compile(PRE + "how (?:much )?charged is (?:my|the) phone" + POST),
            Pattern.compile(PRE + "(?:check|tell me) (?:my |the )?battery(?: level| percentage)?" + POST),
    };
    // words that make "open X" not an app launch
    private static final Pattern OPEN_STOP = Pattern.compile("^(?:(?:a|an|the|it|this|that|up|about) .*|it|this|that|door|window|file|folder|link|account|tab|new tab|box|jar|bottle|can|present|gift|mouth|eyes)$");

    /** Lowercases, normalises a.m./p.m., hyphens and punctuation the way speech recognizers write them. */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US).replace('\u2019', '\'').replace('\u2018', '\'');
        t = t.replaceAll("\\b([ap])\\.\\s?m\\.?", "$1m");
        t = t.replaceAll("(\\d)\\s*([ap])\\s?m\\b", "$1 $2m");
        t = t.replace('-', ' ');
        t = t.replaceAll("[^a-z0-9:' &+!]", " ");
        t = t.replaceAll("!", " ");
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^(?:jarvis|hey jarvis|ok jarvis|okay jarvis|yo jarvis) ", "");
        return t;
    }

    /** A confident match on the whole sentence, or null. apps may be null (then open_app never matches). */
    public static ToolCalls.Call match(String text, AppLookup apps) { return match(text, apps, true); }

    /**
     * strict=false is the post-generation fallback (the model clearly tried to act): same patterns, but
     * the command may sit inside a longer sentence ("bro it's dark, turn on the flashlight").
     */
    public static ToolCalls.Call match(String text, AppLookup apps, boolean strict) {
        String t = normalize(text);
        if (t.isEmpty() || t.length() > 120) return null;
        ToolCalls.Call c = matchWhole(t, apps);
        if (c != null || strict) return c;
        // loose: try each clause on its own
        for (String part : text.split("[,.;!?\\n]|\\b(?:and then|and|then|but|so)\\b")) {
            String p = normalize(part);
            if (p.isEmpty() || p.equals(t)) continue;
            c = matchWhole(p, apps);
            if (c != null) return c;
        }
        return null;
    }

    private static ToolCalls.Call matchWhole(String t, AppLookup apps) {
        try {
            for (Pattern p : FLASH) {
                Matcher m = p.matcher(t);
                if (m.matches()) {
                    String w = m.group(1);
                    boolean on = w.equals("on") || w.equals("enable") || w.equals("activate");
                    return new ToolCalls.Call(ToolCalls.FLASHLIGHT, new JSONObject().put("on", on));
                }
            }
            for (Pattern p : TIMER) {
                Matcher m = p.matcher(t);
                if (m.matches()) {
                    int s = seconds(m.group(1));
                    if (s <= 0 || s > 24 * 3600) return null;
                    JSONObject a = new JSONObject().put("seconds", s);
                    String label = m.groupCount() >= 2 ? m.group(2) : null;
                    if (label != null && !label.trim().isEmpty()) a.put("label", label.trim());
                    return new ToolCalls.Call(ToolCalls.TIMER, a);
                }
            }
            for (Pattern p : ALARM) {
                Matcher m = p.matcher(t);
                if (m.matches()) {
                    int[] hm = clock(m.group(1), m.group(2), m.group(3), m.group(4), t);
                    if (hm == null) return null;
                    JSONObject a = new JSONObject().put("hour", hm[0]).put("minute", hm[1]);
                    String label = m.groupCount() >= 5 ? m.group(5) : null;
                    if (label != null && !label.trim().isEmpty()) a.put("label", label.trim());
                    return new ToolCalls.Call(ToolCalls.ALARM, a);
                }
            }
            for (Pattern p : CLOCK) if (p.matcher(t).matches()) return new ToolCalls.Call(ToolCalls.TIME, new JSONObject());
            for (Pattern p : BATT) if (p.matcher(t).matches()) return new ToolCalls.Call(ToolCalls.BATTERY, new JSONObject());
            Matcher m = OPEN.matcher(t);
            if (m.matches() && apps != null) {
                String name = m.group(1).trim();
                if (!OPEN_STOP.matcher(name).find() && apps.has(name)) {
                    return new ToolCalls.Call(ToolCalls.OPEN_APP, new JSONObject().put("name", name));
                }
            }
        } catch (org.json.JSONException e) {
            return null;
        }
        return null;
    }

    /** Strict app-name check used by the router: exact (ignoring case/spaces/punctuation) label match. */
    public static boolean strictAppMatch(String wanted, java.util.List<String> labels) {
        String w = squash(wanted);
        if (w.length() < 2) return false;
        for (String l : labels) if (squash(l).equals(w)) return true;
        return false;
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    // ---------------------------------------------------------------- numbers

    static int seconds(String dur) {
        if (dur == null) return -1;
        String d = dur.trim();
        if (d.matches("half an? hour|half hour")) return 1800;
        Matcher m = Pattern.compile("(" + NUMW + ")( and a half)? (" + UNIT + ")( and a half)?(?: (?:and )?(" + NUMW + ") (" + UNIT + "))?").matcher(d);
        if (!m.matches()) return -1;
        double n = num(m.group(1));
        if (n < 0) return -1;
        if (m.group(2) != null || m.group(4) != null) n += 0.5;
        int total = (int) Math.round(n * unit(m.group(3)));
        if (m.group(5) != null) {
            double n2 = num(m.group(5));
            if (n2 < 0) return -1;
            total += (int) Math.round(n2 * unit(m.group(6)));
        }
        return total;
    }

    private static int unit(String u) {
        if (u.startsWith("s")) return 1;
        if (u.startsWith("m")) return 60;
        return 3600;
    }

    private static final String[] WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};

    static double num(String w) {
        if (w == null) return -1;
        if (w.matches("\\d+")) return Integer.parseInt(w);
        if (w.equals("a") || w.equals("an")) return 1;
        for (int i = 0; i < WORDS.length; i++) if (WORDS[i].equals(w)) return i;
        switch (w) {
            case "twenty five": return 25;
            case "thirty": return 30;
            case "forty": return 40;
            case "forty five": return 45;
            case "fifty": return 50;
            case "sixty": return 60;
            case "ninety": return 90;
            default: return -1;
        }
    }

    /** {hour 0-23, minute}, or null when it's ambiguous (e.g. "7" with no am/pm). */
    static int[] clock(String word, String hs, String ms, String ap, String whole) {
        if (word != null) return word.equals("noon") ? new int[]{12, 0} : new int[]{0, 0};
        if (hs == null) return null;
        int h = Integer.parseInt(hs), m = ms == null ? 0 : Integer.parseInt(ms);
        if (m > 59 || h > 23) return null;
        String a = ap == null ? "" : ap;
        boolean pm = a.contains("pm") || a.contains("evening") || a.contains("night");
        boolean am = a.contains("am") || a.contains("morning");
        if (!pm && !am && whole.contains("tomorrow morning")) am = true;
        if (pm || am) {
            if (h < 1 || h > 12) return null;
            if (pm && h != 12) h += 12;
            if (am && h == 12) h = 0;
            return new int[]{h, m};
        }
        if (h == 0 || h > 12) return new int[]{h, m};   // unambiguous 24-hour time
        return null;                                    // "7:30" with no am/pm: let the model ask
    }
}
