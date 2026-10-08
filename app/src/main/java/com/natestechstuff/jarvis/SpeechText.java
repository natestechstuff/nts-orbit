package com.natestechstuff.jarvis;

/** Text cleaned up for the voice: links become "a link", markdown symbols go. */
public final class SpeechText {
    private SpeechText() {}

    public static String forSpeech(String text) {
        String t = text.replaceAll("https?://\\S+", "a link");
        t = t.replaceAll("[*_`#>]", "");
        return t.trim();
    }
}
