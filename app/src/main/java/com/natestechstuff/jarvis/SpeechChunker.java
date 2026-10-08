package com.natestechstuff.jarvis;

/**
 * Turns a token stream into speakable sentences as they complete, so Jarvis starts talking
 * after the first sentence instead of waiting for the whole reply. Code blocks are not read
 * out (they're on screen); a short "code's on screen" is said once instead.
 */
public final class SpeechChunker {
    private final StringBuilder buf = new StringBuilder();
    private boolean inCode, saidCode;

    public interface Sink { void say(String sentence); }

    public void push(String piece, Sink sink) {
        buf.append(piece);
        drain(sink, false);
    }

    public void finish(Sink sink) {
        drain(sink, true);
        String rest = clean(buf.toString());
        buf.setLength(0);
        if (!inCode && !rest.isEmpty()) sink.say(rest);
    }

    private void drain(Sink sink, boolean end) {
        while (true) {
            String s = buf.toString();
            int fence = s.indexOf("```");
            if (inCode) {
                if (fence < 0) {
                    // keep only a tail that might be the start of a closing fence
                    if (s.length() > 2) buf.delete(0, s.length() - 2);
                    return;
                }
                buf.delete(0, fence + 3);
                inCode = false;
                continue;
            }
            int cut = sentenceEnd(s, fence < 0 ? s.length() : fence);
            if (cut > 0) {
                String sentence = clean(s.substring(0, cut));
                buf.delete(0, cut);
                if (!sentence.isEmpty()) sink.say(sentence);
                continue;
            }
            if (fence >= 0) {
                String before = clean(s.substring(0, fence));
                if (!before.isEmpty()) sink.say(before);
                if (!saidCode) { sink.say("Code's on screen."); saidCode = true; }
                buf.delete(0, fence + 3);
                inCode = true;
                continue;
            }
            return;
        }
    }

    /** Index just past the first sentence end (". ", "! ", "? ", newline) before limit, or -1. */
    static int sentenceEnd(String s, int limit) {
        for (int i = 0; i < limit; i++) {
            char ch = s.charAt(i);
            if (ch == '\n' && i > 0) return i + 1;
            if ((ch == '.' || ch == '!' || ch == '?') && i + 1 < limit) {
                char nx = s.charAt(i + 1);
                // skip "3.14", "e.g." style dots
                if (nx == ' ' || nx == '\n') {
                    if (ch == '.' && i > 0 && Character.isDigit(s.charAt(i - 1)) && i + 2 < limit && Character.isDigit(s.charAt(i + 2))) continue;
                    if (i >= 3) return i + 1;
                }
            }
        }
        return -1;
    }

    static String clean(String s) {
        String t = SpeechText.forSpeech(s);
        t = t.replaceAll("(?m)^\\s*([-•]|\\d+[.)])\\s+", "");   // list markers
        return t.replaceAll("\\s+", " ").trim();
    }
}
