package com.natestechstuff.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Settings → Premium: enter a key, see the tier, open Pro settings. Keys are checked offline
 * ({@link OrbitKey}); nothing is sent anywhere.
 */
public class PremiumActivity extends Activity {
    /**
     * TODO(baller-model): where Baller members download the Baller model. Leave empty until the
     * model is approved for distribution; the section then shows "coming soon".
     */
    static final String BALLER_MODEL_URL = "";

    /** What Pro unlocks, shown on this screen. Keep in sync with {@link ProMenuActivity#PAGES}. */
    static final String[] PRO_FEATURES = {
            "Model & inference: sampling, system prompt, context, threads, seed, stop words, GGUF details",
            "Thought process: show / force thinking and its limits",
            "Server: timeout, streaming and sampling options",
            "Chat: live tokens/sec, export and import your history",
            "Tools: switch each phone action on or off",
            "Voice & wake: wake phrase, sensitivity, speech, mic test",
            "Look: themes, accent color, font size, animations",
            "Benchmark: speed test your phone or server",
    };

    private LinearLayout col;
    private EditText input;
    private TextView msg;

    @Override
    protected void attachBaseContext(Context base) { super.attachBaseContext(Dev.ON ? Dev.wrap(base) : base); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Dev.ON) Dev.applyTheme(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GridDrawable(Ui.dp(this, 42)));
        root.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        col.setPadding(pad, pad, pad, Ui.dp(this, 40));
        sv.addView(col);
        root.addView(sv);
        setContentView(root);
        build();
    }

    /** (Re)draws the screen for the current state. Public for the screenshot test. */
    public void build() {
        col.removeAllViews();
        boolean pro = Pro.isPro(this);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.chip(this, "← back", Ui.MUTED);
        back.setOnClickListener(x -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.rightMargin = Ui.dp(this, 10);
        head.addView(back, blp);
        TextView title = text("Premium", 28, Ui.INK);
        title.setTypeface(Ui.groteskBold(this));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.chip(this, pro ? "★ " + Pro.tierLabel(this).toLowerCase(Locale.US) : "free", pro ? Ui.LIME : Ui.MUTED));
        col.addView(head);

        if (pro) buildUnlocked(); else buildEnterKey();

        LinearLayout f = section("pro unlocks", Ui.TEAL);
        for (String s : PRO_FEATURES) {
            TextView t = text("• " + s, 13, pro ? Ui.INK : Ui.MUTED);
            t.setPadding(0, Ui.dp(this, 6), 0, 0);
            f.addView(t);
        }
        TextView tiers = mono(f, "Keys come with Crew 2, Crew 3 and Baller in the NTS Orbit Discord, or with a Pro purchase. Every paid tier unlocks all of Pro. Keys are checked on this phone, offline.");
        tiers.setPadding(0, Ui.dp(this, 12), 0, 0);
    }

    private void buildEnterKey() {
        LinearLayout k = section("enter key", Ui.LIME);
        TextView how = text("Paste the key from your Discord DM (it starts with ORBIT-). Spaces and dashes don't matter.", 13, Ui.MUTED);
        how.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        k.addView(how);
        input = new EditText(this);
        input.setHint("ORBIT-XXXXXX-XXXXXX-…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTypeface(Ui.mono(this));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.withAlpha(Ui.MUTED, 0x99));
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        input.setBackground(Ui.box(Ui.PAPER, Ui.LINE, 1, 12, this));
        k.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView paste = Ui.chip(this, "paste", Ui.MUTED);
        paste.setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData cd = cm == null ? null : cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).coerceToText(this) != null)
                input.setText(cd.getItemAt(0).coerceToText(this).toString().trim());
        });
        row.addView(paste);
        TextView unlock = Ui.chip(this, "unlock", Ui.LIME);
        unlock.setOnClickListener(x -> tryUnlock(input.getText().toString()));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.leftMargin = Ui.dp(this, 8);
        row.addView(unlock, ulp);
        k.addView(row);
        msg = text("", 13, Ui.CORAL);
        msg.setPadding(0, Ui.dp(this, 10), 0, 0);
        k.addView(msg);
    }

    /** Checks the key and, if it's good, saves it and redraws. Returns the error (null = unlocked). */
    public String tryUnlock(String text) {
        OrbitKey.Result r = Pro.unlock(this, text);
        if (!r.ok()) {
            if (msg != null) msg.setText(r.error);
            return r.error;
        }
        Toast.makeText(this, "Unlocked " + r.key.tier.label + " ★", Toast.LENGTH_SHORT).show();
        recreate();   // picks up Pro theme / font size
        return null;
    }

    private void buildUnlocked() {
        OrbitKey.Parsed p = Pro.key(this);
        LinearLayout s = section("your key", Ui.LIME);
        TextView t = text(p.tier.label + " ★", 22, Ui.INK);
        t.setTypeface(Ui.groteskBold(this));
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        s.addView(t);
        mono(s, "key " + p.keyId + " · issued " + day(p.issuedDay) + (p.expiresDay == 0 ? " · never expires" : " · valid to " + day(p.expiresDay)));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView open = Ui.chip(this, "pro settings →", Ui.TEAL);
        open.setOnClickListener(x -> Dev.open(this));
        row.addView(open);
        TextView rm = Ui.chip(this, "remove key", Ui.CORAL);
        rm.setOnClickListener(x -> new AlertDialog.Builder(this)
                .setMessage("Remove the key from this phone? Pro turns off until you enter it again.")
                .setPositiveButton("remove", (d, w) -> { Pro.remove(this); recreate(); })
                .setNegativeButton("cancel", null).show());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.leftMargin = Ui.dp(this, 8);
        row.addView(rm, rlp);
        s.addView(row);

        if (p.tier == OrbitKey.Tier.BALLER) {
            LinearLayout bm = section("baller model", Ui.CORAL);
            TextView d = text(BALLER_MODEL_URL.isEmpty()
                    ? "The Baller model is coming soon. When it's ready, the download shows up right here (and in #baller-builds)."
                    : "Your Baller model is ready to download. After it's downloaded: Settings → on-device model → pick the .gguf.", 13, Ui.INK);
            d.setPadding(0, Ui.dp(this, 6), 0, 0);
            bm.addView(d);
            if (!BALLER_MODEL_URL.isEmpty()) {
                TextView dl = Ui.chip(this, "download baller model ↓", Ui.CORAL);
                dl.setOnClickListener(x -> startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(BALLER_MODEL_URL))));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                dlp.topMargin = Ui.dp(this, 10);
                bm.addView(dl, dlp);
            }
        }
    }

    static String day(int d) {
        SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date((d + OrbitKey.EPOCH_DAY) * 86_400_000L));
    }

    // ------------------------------------------------------------------ building blocks

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.grotesk(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView mono(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.mono(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        parent.addView(t);
        return t;
    }

    private LinearLayout section(String name, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.box(Ui.CREAM, Ui.LINE, 1, 16, this));
        card.addView(Ui.chip(this, name, color), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        col.addView(card, lp);
        return card;
    }
}
