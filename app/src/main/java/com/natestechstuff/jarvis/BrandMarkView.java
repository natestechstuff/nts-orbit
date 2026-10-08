package com.natestechstuff.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** The rounded-square NTS Orbit mark: the launcher icon's own layers (N, teal orbit, lime satellite on the dark grid). */
public class BrandMarkView extends View {
    private final Drawable bg, fg;
    private final Path clip = new Path();
    private final RectF r = new RectF();

    public BrandMarkView(Context c) {
        super(c);
        bg = c.getDrawable(R.drawable.ic_launcher_bg);
        fg = c.getDrawable(R.drawable.ic_launcher_fg);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight());
        r.set(0, 0, s, s);
        clip.reset();
        clip.addRoundRect(r, s * 0.286f, s * 0.286f, Path.Direction.CW);   // 10px radius on a 35px mark
        canvas.save();
        canvas.clipPath(clip);
        // adaptive-icon layers are 108 units with the visible 72 in the middle: draw them so that 72 fills the mark
        int pad = Math.round(s * 18f / 72f);
        int full = Math.round(s);
        bg.setBounds(-pad, -pad, full + pad, full + pad);
        fg.setBounds(-pad, -pad, full + pad, full + pad);
        bg.draw(canvas);
        fg.draw(canvas);
        canvas.restore();
    }
}
