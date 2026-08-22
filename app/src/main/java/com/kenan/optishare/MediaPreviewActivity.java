package com.kenan.optishare;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.bumptech.glide.Glide;

/** Full-screen local preview for media selected in OptiShare. */
public final class MediaPreviewActivity extends Activity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_NAME = "name";

    private VideoView videoView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String raw = getIntent().getStringExtra(EXTRA_URI);
        if (raw == null || raw.trim().isEmpty()) {
            finish();
            return;
        }
        Uri uri = Uri.parse(raw);
        boolean video = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        render(uri, video, name == null ? "Preview" : name);
    }

    private void render(Uri uri, boolean video, String name) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(3, 9, 18));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹");
        back.setTextSize(22);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(name, 16, Color.WHITE, true);
        title.setMaxLines(1);
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, 0, 1);
        stageLp.setMargins(0, dp(12), 0, 0);
        root.addView(stage, stageLp);

        if (video) {
            videoView = new VideoView(this);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoView);
            videoView.setMediaController(controller);
            videoView.setVideoURI(uri);
            videoView.setOnPreparedListener(player -> {
                player.setLooping(false);
                videoView.start();
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                showFallback(stage, "This video cannot be previewed by the device decoder, but it can still be transferred.");
                return true;
            });
            stage.addView(videoView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        } else {
            ImageView image = new ImageView(this);
            image.setContentDescription("Selected image preview");
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
            Glide.with(this).load(uri).fitCenter().into(image);
        }

        TextView hint = text(video ? "Tap the video to show playback controls" : "Image preview", 11,
                Color.rgb(137, 169, 194), false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        root.addView(hint);
        setContentView(root);
    }

    private void showFallback(FrameLayout stage, String message) {
        stage.removeAllViews();
        TextView text = text(message, 14, Color.rgb(205, 218, 230), false);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(24), dp(24), dp(24));
        stage.addView(text, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    }

    @Override protected void onPause() {
        if (videoView != null && videoView.isPlaying()) videoView.pause();
        super.onPause();
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20, 42, 63));
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
