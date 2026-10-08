package com.natestechstuff.jarvis;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * v2.4: catches the model SAYING it did a phone action without calling a tool ("I will turn on the
 * flashlight", "Timer set for 5 minutes"). Such a reply is never shown, spoken or saved; the app
 * retries once with a nudge, then falls back to {@link ActionRouter} or ask_tab.
 *
 * Only for replies with no tool call, and never on the follow-up turn after a real tool ran (there
 * "Timer set for 5 minutes." is true).
 */
public final class FakeClaim {
    private FakeClaim() {}

    private static final String OBJ = "(?:flash ?light|torch|timer|alarm|countdown|stopwatch|reminder)";
    private static final String VERB = "(?:turn|turned|turning|switch|switched|switching|set|setting|start|started|starting|create|created|creating|activate|activated|activating|enable|enabled|enabling|schedule|scheduled|scheduling|put|putting)";
    private static final String SUBJ = "(?:\\bi(?:'ve| have|'ll| will|'m going to| am going to| am going| just| now| already)?(?: just| now| also| already| gone ahead and| go ahead and)? |\\blet me (?:go ahead and )?|\\bi'm (?:now |just )?)";

    private static final Pattern[] CLAIMS = {
            // "I will turn on the flashlight", "I've set a timer", "let me set an alarm"
            Pattern.compile(SUBJ + VERB + "\\b[^.!?\\n]{0,40}?\\b" + OBJ),
            // "Turning on the flashlight", "Sure, setting a 5 minute timer"
            Pattern.compile("(?:^|[.!?:,;\\n]\\s*|\\b(?:ok|okay|sure|alright|done|got it|you got it|no problem)\\s+)(?:turning|switching|setting|starting|creating|activating|enabling|scheduling)\\b[^.!?\\n]{0,40}?\\b" + OBJ),
            // "flashlight is on", "your timer has been set", "the alarm is now set for 7"
            Pattern.compile("\\b" + OBJ + "s? (?:is|are|has been|have been|was|'s|is now|has now been|should be|will be)(?: now| all| successfully| already)? (?:on|off|set|started|running|activated|enabled|scheduled|created|going|ticking|turned on|turned off|switched on|switched off|ready)\\b"),
            // "Timer set for 5 minutes", "Alarm set.", "Flashlight on!"
            Pattern.compile("(?:^|[.!?:,;\\n]\\s*|\\b(?:ok|okay|sure|alright|done|got it)\\s+)(?:the |your |a )?(?:\\d+[- ]\\w+ )?" + OBJ + " (?:set|started|on|off|activated|scheduled|created)(?=\\s*(?:for|at|to|$|[.!,]))"),
            // "Opening YouTube", "I'll open Spotify", "I've opened the camera", "launching ..."
            Pattern.compile("(?:^|[.!?:,;\\n]\\s*|\\b(?:ok|okay|sure|alright|done|got it)\\s+)(?:opening|launching)\\s+(?:up\\s+)?(?:the |your )?[a-z0-9]"),
            Pattern.compile(SUBJ + "(?:open|opened|opening|launch|launched|launching)\\s+(?:up\\s+)?(?:the |your )?[a-z0-9]"),
            // "I have set", "I've turned", "I've started" (no object, still a claim)
            Pattern.compile("\\bi(?:'ve| have) (?:just |now |already |successfully )?(?:set|turned|switched|started|opened|launched|created|scheduled|activated)\\b"),
    };

    // The user asked a question about something ("explain…", "what's a timer in javascript"): an answer, not an action.
    private static final Pattern INFO_QUESTION = Pattern.compile(
            "^(?:jarvis )?(?:how (?:do|does|can|would|could|to|is|are|did)|why|explain|describe|define|teach|tell me about|what (?:is|are|was|does|do) (?:a|an|the)\\b|what'?s (?:a|an)\\b|what does|write|code|can you explain|in (?:javascript|python|java))\\b");
    private static final Pattern CODE_HINT = Pattern.compile("```|\\b(?:javascript|python|java|settimeout|setinterval|function|api|code|class|method)\\b");

    /** True when this tool-less reply claims a phone action that didn't happen. */
    public static boolean isFake(String userText, String reply) {
        if (reply == null) return false;
        String r = norm(reply);
        if (r.isEmpty() || r.contains("<tool_call>")) return false;
        String u = norm(userText == null ? "" : userText);
        if (INFO_QUESTION.matcher(u).find()) return false;
        if (CODE_HINT.matcher(u).find() || CODE_HINT.matcher(r).find()) return false;
        // fake "done" replies are short; look at the opening of longer ones only
        String head = r.length() <= 260 ? r : firstSentences(r, 2);
        for (Pattern p : CLAIMS) if (p.matcher(head).find()) return true;
        return false;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.US).replace('\u2019', '\'').replace('\u2018', '\'').replaceAll("[*_#]+", "").trim();
    }

    /** The first n sentences (by . ! ? or newline), or all of s. */
    static String firstSentences(String s, int n) {
        int seen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || ((c == '.' || c == '!' || c == '?') && (i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1))))) {
                if (++seen >= n) return s.substring(0, i + 1);
            }
        }
        return s;
    }

    /** Number of complete sentences in s (for the stream filter's hold-back). */
    static int sentenceCount(String s) {
        int seen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || ((c == '.' || c == '!' || c == '?') && i + 1 < s.length() && Character.isWhitespace(s.charAt(i + 1)))) seen++;
        }
        return seen;
    }

    /** Appended to the user's message for the one retry after a fake claim. */
    public static final String NUDGE = "\n\n(Reminder: you have not done anything yet. If this needs a phone action, reply with only the <tool_call>. Never say it is done without calling the tool.)";
}
