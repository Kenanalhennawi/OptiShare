package com.kenan.optishare;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kenan.optishare.storage.MediaRepository;
import com.kenan.optishare.ui.GalleryAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Full-screen multi-select gallery used by the v3 product flow. */
public final class MediaPickerActivity extends ComponentActivity {
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_INITIAL = "initial";
    public static final String EXTRA_SELECTED = "selected";
    private static final int REQ_MEDIA = 3101;

    private String type;
    private final Set<Uri> initial = new HashSet<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) type = "image";
        ArrayList<String> raw = getIntent().getStringArrayListExtra(EXTRA_INITIAL);
        if (raw != null) {
            for (String value : raw) {
                try { initial.add(Uri.parse(value)); } catch (Exception ignored) { }
            }
        }
        if (!hasPermission()) requestPermission(); else render();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MEDIA && hasPermission()) render();
        else if (requestCode == REQ_MEDIA) finish();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(5, 14, 28), Color.rgb(9, 30, 52), Color.rgb(19, 19, 54)});
        root.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = secondary("‹");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(title(), 22, Color.WHITE, true));
        TextView count = text(initial.size() + " selected", 12, Color.rgb(103, 205, 255), true);
        labels.addView(count);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        TextView help = text("Tap to select • Hold to preview" + ("video".equals(type) ? " • ▶ opens video preview" : ""),
                11, Color.rgb(139, 171, 196), false);
        help.setPadding(0, dp(8), 0, 0);
        root.addView(help);

        RecyclerView recycler = new RecyclerView(this);
        recycler.setLayoutManager(new GridLayoutManager(this, 3));
        recycler.setClipToPadding(false);
        recycler.setPadding(0, dp(12), 0, dp(12));
        GalleryAdapter adapter = new GalleryAdapter(initial,
                selected -> count.setText(selected.size() + " selected"),
                this::openPreview);
        adapter.replace(new MediaRepository(this).load(type, 500, 0));
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(-1, 0, 1));

        Button done = primary("Add selected to transfer");
        done.setOnClickListener(v -> {
            ArrayList<String> result = new ArrayList<>();
            for (Uri uri : adapter.selection()) result.add(uri.toString());
            Intent data = new Intent();
            data.putStringArrayListExtra(EXTRA_SELECTED, result);
            setResult(Activity.RESULT_OK, data);
            finish();
        });
        root.addView(done, new LinearLayout.LayoutParams(-1, dp(58)));
        setContentView(root);
    }

    private void openPreview(MediaRepository.MediaItem item) {
        if (item == null || "audio".equals(type)) return;
        Intent intent = new Intent(this, MediaPreviewActivity.class);
        intent.putExtra(MediaPreviewActivity.EXTRA_URI, item.uri.toString());
        intent.putExtra(MediaPreviewActivity.EXTRA_NAME, item.name);
        intent.putExtra(MediaPreviewActivity.EXTRA_VIDEO, "video".equals(type));
        startActivity(intent);
    }

    private String title() {
        if ("video".equals(type)) return "Videos";
        if ("audio".equals(type)) return "Music";
        return "Photos";
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            String permission = "video".equals(type) ? Manifest.permission.READ_MEDIA_VIDEO
                    : "audio".equals(type) ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_MEDIA_IMAGES;
            return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            String permission = "video".equals(type) ? Manifest.permission.READ_MEDIA_VIDEO
                    : "audio".equals(type) ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_MEDIA_IMAGES;
            requestPermissions(new String[]{permission}, REQ_MEDIA);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
        }
    }

    private Button primary(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(32, 157, 255), Color.rgb(78, 75, 230)});
        background.setCornerRadius(dp(18));
        button.setBackground(background);
        return button;
    }

    private Button secondary(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(22);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(18, 42, 65));
        background.setCornerRadius(dp(16));
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
