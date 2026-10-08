package com.natestechstuff.jarvis;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** natestechstuff.com design tokens: colors, fonts, shapes. */
public final class Ui {
    public static final int PAPER = Color.parseColor("#111916");
    public static final int CREAM = Color.parseColor("#1a2520");
    public static final int PANEL = Color.parseColor("#22352e");   // the site's .signal-box
    public static final int LINE = Color.parseColor("#34443c");
    public static final int SHADOW = Color.parseColor("#29372f");
    public static final int INK = Color.parseColor("#e3ece6");
    public static final int MUTED = Color.parseColor("#a1b0a8");
    public static final int LIME = Color.parseColor("#c9ec72");
    public static final int TEAL = Color.parseColor("#55c5b1");
    public static final int CORAL = Color.parseColor("#f48b70");
    public static final int BLUE = Color.parseColor("#83aaff");
    public static final int MARK_BG = Color.parseColor("#e7f0ff");
    public static final int MARK_N = Color.parseColor("#2563eb");

    private static Typeface grotesk, groteskBold, groteskMedium, mono, monoMedium;

    private Ui() {}

    private static Typeface variable(Context c, int weight) {
        try {
            return new Typeface.Builder(c.getAssets(), "fonts/SpaceGrotesk.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return Typeface.create(Typeface.SANS_SERIF, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private static Typeface asset(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static Typeface grotesk(Context c) {
        if (grotesk == null) grotesk = variable(c, 400);
        return grotesk;
    }

    public static Typeface groteskMedium(Context c) {
        if (groteskMedium == null) groteskMedium = variable(c, 500);
        return groteskMedium;
    }

    public static Typeface groteskBold(Context c) {
        if (groteskBold == null) groteskBold = variable(c, 700);
        return groteskBold;
    }

    public static Typeface mono(Context c) {
        if (mono == null) mono = asset(c, "fonts/DMMono-Regular.ttf", Typeface.MONOSPACE);
        return mono;
    }

    public static Typeface monoMedium(Context c) {
        if (monoMedium == null) monoMedium = asset(c, "fonts/DMMono-Medium.ttf", Typeface.MONOSPACE);
        return monoMedium;
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable box(int fill, int stroke, float strokeDp, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable box(int fill, int stroke, float strokeDp, float[] radiiDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        float[] r = new float[8];
        for (int i = 0; i < 4; i++) { r[2 * i] = dp(c, radiiDp[i]); r[2 * i + 1] = dp(c, radiiDp[i]); }
        g.setCornerRadii(r);
        return g;
    }

    /** A mono uppercase "chip" label like the site's tags. */
    public static TextView chip(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        styleChip(t, color);
        return t;
    }

    public static void styleChip(TextView t, int color) {
        Context c = t.getContext();
        t.setTypeface(monoMedium(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setTextColor(color);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        t.setBackground(box(withAlpha(color, 0x1f), withAlpha(color, 0x66), 1, 999, c));
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }
}
