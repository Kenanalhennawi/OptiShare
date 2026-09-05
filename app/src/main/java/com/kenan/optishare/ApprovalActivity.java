package com.kenan.optishare;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kenan.optishare.transfer.TransferService;
import com.kenan.optishare.ui.UiText;
import com.kenan.optishare.settings.LocaleSupport;
import com.kenan.optishare.settings.Appearance;

/**
 * Focused approval UI opened for security-code verification and incoming-transfer consent.
 * A back gesture is deliberately treated as a decline so no approval can be bypassed implicitly.
 */
public final class ApprovalActivity extends Activity {
    public static final String EXTRA_TITLE = "approval_title";
    public static final String EXTRA_TEXT = "approval_text";
    public static final String EXTRA_PEER_FINGERPRINT = "peer_fingerprint";

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(LocaleSupport.wrap(base)); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String text = getIntent().getStringExtra(EXTRA_TEXT);
        String peerFingerprint = getIntent().getStringExtra(EXTRA_PEER_FINGERPRINT);
        if (title == null || title.trim().isEmpty()) title = "OptiShare approval";
        if (text == null || text.trim().isEmpty()) text = "Confirm only if you expect this action.";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackground(Appearance.light(this) ? solid(Appearance.background(this), 0)
                : gradient(Color.rgb(5, 17, 38), Color.rgb(18, 48, 82), 0));

        TextView badge = text("O", 28, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(gradient(Color.rgb(28, 165, 255), Color.rgb(91, 73, 245), 24));
        root.addView(badge, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView heading = text(title, 25, Color.WHITE, true);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, dp(22), 0, 0);
        root.addView(heading, hp);

        TextView detail = text(text, 17, Color.rgb(195, 219, 237), false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(8), dp(16), dp(8), dp(20));
        root.addView(detail, new LinearLayout.LayoutParams(-1, -2));

        boolean security = title.toLowerCase(java.util.Locale.US).contains("security");
        LinearLayout note = card();
        TextView noteText = text(
                security
                        ? "Compare every digit on both phones. Confirm only when the six-digit code matches exactly. A mismatch can mean you connected to the wrong peer or the pairing was intercepted."
                        : "Review the incoming transfer before accepting. Received files are published only after their SHA-256 integrity check succeeds.",
                13, Color.rgb(158, 187, 211), false);
        note.addView(noteText);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        Button confirm = button(security ? "Codes match — confirm" : "Accept transfer",
                Color.rgb(43, 201, 139), Color.rgb(16, 126, 92));
        confirm.setOnClickListener(v -> decide(true, false));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(56));
        cp.setMargins(0, dp(22), 0, 0);
        root.addView(confirm, cp);

        if (security && peerFingerprint != null && !peerFingerprint.trim().isEmpty()) {
            Button trust = button("Trust this device & confirm",
                    Color.rgb(48, 126, 218), Color.rgb(35, 72, 164));
            trust.setOnClickListener(v -> decide(true, true));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(54));
            tp.setMargins(0, dp(10), 0, 0);
            root.addView(trust, tp);
        }

        Button decline = button(security ? "Codes do not match" : "Decline transfer",
                Color.rgb(107, 50, 66), Color.rgb(74, 31, 45));
        decline.setOnClickListener(v -> decide(false, false));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, this.dp(52));
        dp.setMargins(0, this.dp(10), 0, 0);
        root.addView(decline, dp);

        TextView privacy = text("Local connection • No cloud approval server", 11,
                Color.rgb(112, 159, 193), false);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, dp(20), 0, 0);
        root.addView(privacy);

        setContentView(root);
    }

    private void decide(boolean accept, boolean trust) {
        String action = !accept ? TransferService.ACTION_DECLINE
                : trust ? TransferService.ACTION_TRUST_ACCEPT : TransferService.ACTION_ACCEPT;
        startService(new Intent(this, TransferService.class).setAction(action));
        finish();
    }

    @SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() {
        decide(false, false);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = solid(Appearance.light(this) ? Appearance.surface(this) : Color.rgb(13, 33, 56), 18);
        bg.setStroke(dp(1), Appearance.light(this) ? Color.rgb(210, 222, 232) : Color.rgb(37, 68, 96));
        layout.setBackground(bg);
        return layout;
    }

    private Button button(String label, int top, int bottom) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(UiText.get(this,label));
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(gradient(top, bottom, 18));
        if(android.os.Build.VERSION.SDK_INT>=21)button.setElevation(dp(9));press(button);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiText.get(this,value));
        view.setTextSize(size);
        view.setTextColor(Appearance.text(this, color));
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable solid(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable gradient(int top, int bottom, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void press(android.view.View view){view.setOnTouchListener((v,e)->{if(e.getAction()==android.view.MotionEvent.ACTION_DOWN)v.animate().scaleX(.96f).scaleY(.96f).translationY(dp(3)).setDuration(75).start();else if(e.getAction()==android.view.MotionEvent.ACTION_UP||e.getAction()==android.view.MotionEvent.ACTION_CANCEL)v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(175).start();return false;});}

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
