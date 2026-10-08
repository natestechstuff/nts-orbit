package com.natestechstuff.jarvis;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The site's "signal-bars": teal bars (every 4th lime) breathing between 88% and 105%
 * height with staggered delays. In WAVE mode (while Jarvis is speaking) the bars turn
 * lime and move like an audio waveform; in IDLE they settle low.
 */
public class SignalBarsView extends View {
    public static final int BREATHE = 0, WAVE = 1, IDLE = 2;

    private static final float[] HEIGHTS = {0.42f, 0.7f, 0.55f, 0.92f, 0.6f, 0.38f, 0.8f, 0.66f, 0.5f, 0.86f, 0.45f, 0.72f, 0.58f, 0.95f, 0.4f, 0.64f};
    private final Paint teal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lime = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator clock;
    private int mode = BREATHE;
    private int bars = HEIGHTS.length;
    private float t;

    public SignalBarsView(Context c) {
        super(c);
        teal.setColor(Ui.TEAL);
        lime.setColor(Ui.LIME);
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(3600);                       // 1.8s ease, alternate => 3.6s cycle
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
    }

    public void setBars(int n) { bars = n; invalidate(); }

    public void setMode(int m) {
        mode = m;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        clock.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        clock.cancel();
        super.onDetachedFromWindow();
    }

    /** ease-in-out between .88 and 1.05, alternate, with the site's -0.6s / -1.2s delays. */
    private float breathe(int i) {
        float delay = (i % 3 == 2) ? 0.6f : (i % 3 == 0 ? 1.2f : 0f);
        float phase = ((t * 3.6f) + delay) % 3.6f;                 // seconds into the 3.6s cycle
        float x = phase < 1.8f ? phase / 1.8f : (3.6f - phase) / 1.8f;   // 0..1..0
        float eased = (float) (0.5 - 0.5 * Math.cos(Math.PI * x));
        return 0.88f + eased * 0.17f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || bars == 0) return;
        float gap = Math.max(2f, w * 0.012f);
        float bw = (w - gap * (bars - 1)) / bars;
        for (int i = 0; i < bars; i++) {
            float base = HEIGHTS[i % HEIGHTS.length];
            float scale;
            Paint p = (i % 4 == 3) ? lime : teal;     // nth-child(4n) is lime
            if (mode == WAVE) {
                double a = t * Math.PI * 2 * 6 + i * 0.75;
                scale = (float) (0.35 + 0.65 * Math.abs(Math.sin(a) * Math.cos(a * 0.37 + i)));
                p = lime;
            } else if (mode == IDLE) {
                scale = 0.25f + 0.05f * breathe(i);
                p.setAlpha(150);
            } else {
                scale = breathe(i);
            }
            float bh = h * base * scale;
            float left = i * (bw + gap);
            canvas.drawRect(left, (h - bh) / 2f, left + bw, (h + bh) / 2f, p);
            p.setAlpha(255);
        }
    }
}
