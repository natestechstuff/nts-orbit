package com.natestechstuff.jarvis;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** The faint 42px teal grid from natestechstuff.com's background. */
public class GridDrawable extends Drawable {
    private final Paint line = new Paint();
    private final float step;

    public GridDrawable(float stepPx) {
        this.step = stepPx;
        line.setColor(Ui.withAlpha(Ui.TEAL, 0x0d));   // rgba(85,197,177,.05)
        line.setStrokeWidth(1f);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawColor(Ui.PAPER);
        int w = getBounds().width(), h = getBounds().height();
        for (float x = 0; x < w; x += step) canvas.drawLine(x, 0, x, h, line);
        for (float y = 0; y < h; y += step) canvas.drawLine(0, y, w, y, line);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
