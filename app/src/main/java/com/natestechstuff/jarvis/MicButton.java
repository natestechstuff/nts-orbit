package com.natestechstuff.jarvis;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Big round mic button. LISTENING: pulsing teal rings. SPEAKING: lime waveform. */
public class MicButton extends View {
    public static final int IDLE = 0, LISTENING = 1, WAITING = 2, SPEAKING = 3;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final ValueAnimator clock;
    private int state = IDLE;
    private float t;
    private float level;   // mic loudness 0..1 while listening

    public MicButton(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Ui.TEAL);
        icon.setColor(Ui.PAPER);
        icon.setStyle(Paint.Style.FILL);
        wave.setColor(Ui.PAPER);
        wave.setStrokeCap(Paint.Cap.ROUND);
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1600);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        setContentDescription("Talk");
        setClickable(true);
        setFocusable(true);
    }

    public void setState(int s) { state = s; invalidate(); }
    public int getState() { return state; }
    public void setLevel(float l) { level = Math.max(0f, Math.min(1f, l)); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (!Dev.ON || Dev.animations(getContext())) clock.start(); }   // dev: animations off
    @Override protected void onDetachedFromWindow() { clock.cancel(); super.onDetachedFromWindow(); }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy);
        float r = maxR * 0.62f;

        if (state == LISTENING) {
            // two pulsing rings, offset by half a cycle, growing with mic level
            for (int k = 0; k < 2; k++) {
                float p = (t + k * 0.5f) % 1f;
                float rr = r + (maxR - r) * p * (0.75f + 0.25f * level);
                ring.setStrokeWidth(Ui.dp(getContext(), 2.5f) * (1f - p) + 1f);
                ring.setAlpha((int) (220 * (1f - p)));
                canvas.drawCircle(cx, cy, rr, ring);
            }
        } else if (state == WAITING) {
            ring.setStrokeWidth(Ui.dp(getContext(), 2));
            ring.setAlpha(200);
            ring.setColor(Ui.BLUE);
            rect.set(cx - r - 10, cy - r - 10, cx + r + 10, cy + r + 10);
            canvas.drawArc(rect, t * 360f, 80f, false, ring);
            ring.setColor(Ui.TEAL);
        }

        int bg = state == LISTENING ? Ui.TEAL : state == SPEAKING ? Ui.LIME : state == WAITING ? Ui.BLUE : Ui.INK;
        fill.setColor(bg);
        canvas.drawCircle(cx, cy, r * (state == LISTENING ? 1f + 0.06f * level : 1f), fill);

        if (state == SPEAKING) {
            // lime button with a moving waveform inside
            int n = 7;
            float span = r * 1.1f, step = span / (n - 1);
            wave.setStrokeWidth(r * 0.12f);
            for (int i = 0; i < n; i++) {
                double a = t * Math.PI * 2 * 2 + i * 0.9;
                float hgt = r * (0.18f + 0.55f * (float) Math.abs(Math.sin(a) * Math.cos(a * 0.5 + i)));
                float x = cx - span / 2f + i * step;
                canvas.drawLine(x, cy - hgt / 2f, x, cy + hgt / 2f, wave);
            }
        } else {
            drawMic(canvas, cx, cy, r * 0.5f);
        }
    }

    private void drawMic(Canvas c, float cx, float cy, float s) {
        // capsule
        float w = s * 0.55f, h = s * 1.0f;
        rect.set(cx - w / 2f, cy - h * 0.75f, cx + w / 2f, cy + h * 0.25f);
        c.drawRoundRect(rect, w / 2f, w / 2f, icon);
        // stand
        Paint st = new Paint(icon);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(s * 0.12f);
        st.setStrokeCap(Paint.Cap.ROUND);
        rect.set(cx - s * 0.5f, cy - h * 0.45f, cx + s * 0.5f, cy + h * 0.45f);
        c.drawArc(rect, 20, 140, false, st);
        c.drawLine(cx, cy + h * 0.45f, cx, cy + h * 0.68f, st);
        c.drawLine(cx - s * 0.28f, cy + h * 0.68f, cx + s * 0.28f, cy + h * 0.68f, st);
    }
}
