package com.natestechstuff.jarvis;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** App-wide TextToSpeech with the chosen voice + speed settings. */
public final class Speaker {
    public interface Done { void run(); }
    public interface StateListener { void onSpeaking(boolean speaking); }

    private static Speaker instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;
    private final List<Runnable> pending = new ArrayList<>();
    private StateListener stateListener;

    public static synchronized Speaker get(Context c) {
        if (instance == null) instance = new Speaker(c.getApplicationContext());
        return instance;
    }

    private Speaker(Context c) {
        app = c;
        tts = new TextToSpeech(app, status -> main.post(() -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                applySettings();
                for (Runnable r : pending) r.run();
            }
            pending.clear();
        }));
    }

    public void setStateListener(StateListener l) { stateListener = l; }

    public boolean isReady() { return ready; }

    public void applySettings() {
        if (!ready) return;
        Prefs p = new Prefs(app);
        tts.setSpeechRate(p.speechRate());
        tts.setPitch(p.pitch());
        String name = p.voiceName();
        boolean set = false;
        if (!name.isEmpty()) {
            try {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) for (Voice v : vs) {
                    if (v.getName().equals(name)) { tts.setVoice(v); set = true; break; }
                }
            } catch (Exception ignored) {}
        }
        if (!set) tts.setLanguage(Locale.US);
    }

    /** English voices installed on the phone, best quality first. */
    public List<Voice> voices() {
        List<Voice> out = new ArrayList<>();
        if (!ready) return out;
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs != null) for (Voice v : vs) {
                if (v.getLocale() != null && "en".equals(v.getLocale().getLanguage()) && !v.isNetworkConnectionRequired()) out.add(v);
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> b.getQuality() - a.getQuality());
        return out;
    }

    private final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
    private Done onDrained;
    private boolean speaking;

    /** Speak now, interrupting anything else. `done` runs when finished. */
    public void speak(String text, Done done) {
        queue.clear();
        enqueue(text, done, true);
    }

    /** Speak after whatever is already being said (replies often come in 2-3 chunks). */
    public void enqueue(String text, Done done) {
        enqueue(text, done, false);
    }

    private void enqueue(String text, Done done, boolean flush) {
        Runnable r = () -> {
            onDrained = done;
            if (flush && speaking) tts.stop();
            queue.addLast(text);
            if (!speaking || flush) next();
        };
        if (ready) r.run(); else pending.add(r);
    }

    public boolean isSpeaking() { return speaking; }

    /** Run `done` once everything queued has been spoken (now, if nothing is queued). */
    public void whenDrained(Done done) {
        Runnable r = () -> {
            if (!speaking && queue.isEmpty()) { if (done != null) done.run(); }
            else onDrained = done;
        };
        if (ready) r.run(); else pending.add(r);
    }

    /** Queue a sentence without touching the drained callback (used while a reply is streaming). */
    public void enqueueQuiet(String text) {
        Runnable r = () -> {
            queue.addLast(text);
            if (!speaking) next();
        };
        if (ready) r.run(); else pending.add(r);
    }

    private void next() {
        String text = queue.pollFirst();
        if (text == null) {
            boolean was = speaking;
            speaking = false;
            if (was && stateListener != null) stateListener.onSpeaking(false);
            Done d = onDrained;
            onDrained = null;
            if (d != null) d.run();
            return;
        }
        if (!speaking) {
            speaking = true;
            if (stateListener != null) stateListener.onSpeaking(true);
        }
        String id = UUID.randomUUID().toString();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) {}
            @Override public void onDone(String u) { main.post(Speaker.this::next); }
            @Override public void onError(String u) { main.post(Speaker.this::next); }
            @Override public void onStop(String u, boolean interrupted) { }
        });
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id);
    }

    public void stop() {
        queue.clear();
        onDrained = null;
        if (ready) tts.stop();
        if (speaking) {
            speaking = false;
            if (stateListener != null) stateListener.onSpeaking(false);
        }
    }
}
