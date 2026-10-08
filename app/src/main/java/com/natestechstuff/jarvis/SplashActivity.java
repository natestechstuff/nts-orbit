package com.natestechstuff.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Loading screen: NTS Orbit mark fades up, signal bars breathe, tagline types itself out. */
public class SplashActivity extends Activity {
    static final String TAGLINE = "booting orbit… code, curiosity & too many tabs";
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean launched;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GridDrawable(Ui.dp(this, 42)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        BrandMarkView mark = new BrandMarkView(this);
        col.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92)));

        TextView title = new TextView(this);
        title.setText("NTS Orbit");
        title.setTypeface(Ui.groteskBold(this));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setTextColor(Ui.INK);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = Ui.dp(this, 22);
        col.addView(title, tp);

        TextView by = new TextView(this);
        by.setText("BY NATE'S TECH STUFF");
        by.setTypeface(Ui.monoMedium(this));
        by.setLetterSpacing(0.12f);
        by.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        by.setTextColor(Ui.MUTED);
        col.addView(by, new LinearLayout.LayoutParams(-2, -2));

        // the site's .signal-box: dark green panel with breathing bars
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 14));
        panel.setBackgroundColor(Ui.PANEL);
        LinearLayout labelRow = new LinearLayout(this);
        TextView l1 = monoLabel("SIGNAL", Ui.MUTED);
        TextView l2 = monoLabel("● LIVE", Ui.CORAL);
        labelRow.addView(l1, new LinearLayout.LayoutParams(0, -2, 1));
        labelRow.addView(l2);
        panel.addView(labelRow);
        SignalBarsView bars = new SignalBarsView(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        bp.topMargin = Ui.dp(this, 10);
        panel.addView(bars, bp);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(this, 260), -2);
        pp.topMargin = Ui.dp(this, 34);
        col.addView(panel, pp);

        TextView tag = new TextView(this);
        tag.setTypeface(Ui.mono(this));
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tag.setTextColor(Ui.TEAL);
        tag.setGravity(Gravity.CENTER);
        tag.setMinLines(2);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(this, 280), -2);
        gp.topMargin = Ui.dp(this, 20);
        col.addView(tag, gp);

        // fade-up .75s cubic-bezier(.2,.7,.2,1), staggered like the site
        PathInterpolator ease = new PathInterpolator(.2f, .7f, .2f, 1f);
        View[] seq = {mark, title, by, panel};
        for (int i = 0; i < seq.length; i++) {
            View v = seq[i];
            v.setAlpha(0f);
            v.setTranslationY(Ui.dp(this, 18));
            v.animate().alpha(1f).translationY(0).setStartDelay(120L * i).setDuration(750).setInterpolator(ease).start();
        }
        typeOut(tag, 0);

        root.setOnClickListener(v -> next());
        h.postDelayed(this::next, 2600);
        return root;
    }

    private TextView monoLabel(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.monoMedium(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setLetterSpacing(0.08f);
        t.setTextColor(color);
        return t;
    }

    private void typeOut(TextView t, int i) {
        if (i > TAGLINE.length()) return;
        t.setText(TAGLINE.substring(0, i) + (i < TAGLINE.length() ? "▌" : ""));
        h.postDelayed(() -> typeOut(t, i + 1), i == 0 ? 450 : 32);
    }

    private void next() {
        if (launched || isFinishing()) return;
        launched = true;
        startActivity(new Intent(this, MainActivity.class).putExtras(getIntent()));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
