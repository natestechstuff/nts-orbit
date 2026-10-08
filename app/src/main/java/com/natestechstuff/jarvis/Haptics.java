package com.natestechstuff.jarvis;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Small buzzes: a tick on send, a double-tap when a reply arrives. */
public final class Haptics {
    private Haptics() {}

    private static void buzz(Context c, VibrationEffect e) {
        if (!new Prefs(c).haptics()) return;
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) v.vibrate(e);
    }

    public static void send(Context c) {
        buzz(c, VibrationEffect.createOneShot(25, 120));
    }

    public static void receive(Context c) {
        buzz(c, VibrationEffect.createWaveform(new long[]{0, 30, 70, 30}, new int[]{0, 140, 0, 200}, -1));
    }

    public static void listen(Context c) {
        buzz(c, VibrationEffect.createOneShot(15, 80));
    }
}
