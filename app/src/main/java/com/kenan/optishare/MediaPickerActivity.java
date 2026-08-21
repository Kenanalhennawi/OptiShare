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
    private Set<Uri> initial = new HashSet<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) type = "image";
        ArrayList<String> raw = getIntent().getStringArrayListExtra(EXTRA_INITIAL);
        if (raw != null) for (String s : raw) initial.add(Uri.parse(s));
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
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(5,14,28), Color.rgb(9,30,52), Color.rgb(19,19,54)});
        root.setBackground(bg);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = secondary("‹");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10),0,0,0);
        labels.addView(text(title(),22,Color.WHITE,true));
        TextView count = text(initial.size()+" selected",12,Color.rgb(103,205,255),true);
        labels.addView(count);
        header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(header);

        RecyclerView recycler = new RecyclerView(this);
        recycler.setLayoutManager(new GridLayoutManager(this, 3));
        recycler.setClipToPadding(false);
        recycler.setPadding(0,dp(12),0,dp(12));
        GalleryAdapter adapter = new GalleryAdapter(initial, selected -> count.setText(selected.size()+" selected"));
        adapter.replace(new MediaRepository(this).load(type, 500, 0));
        recycler.setAdapter(adapter);
        root.addView(recycler,new LinearLayout.LayoutParams(-1,0,1));

        Button done = primary("Add selected to transfer");
        done.setOnClickListener(v -> {
            ArrayList<String> result = new ArrayList<>();
            for (Uri uri : adapter.selection()) result.add(uri.toString());
            Intent data = new Intent();
            data.putStringArrayListExtra(EXTRA_SELECTED, result);
            setResult(Activity.RESULT_OK, data);
            finish();
        });
        root.addView(done,new LinearLayout.LayoutParams(-1,dp(58)));
        setContentView(root);
    }

    private String title() {
        if ("video".equals(type)) return "Videos";
        if ("audio".equals(type)) return "Music";
        return "Photos";
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            String p = "video".equals(type) ? Manifest.permission.READ_MEDIA_VIDEO
                    : "audio".equals(type) ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_MEDIA_IMAGES;
            return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            String p = "video".equals(type) ? Manifest.permission.READ_MEDIA_VIDEO
                    : "audio".equals(type) ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_MEDIA_IMAGES;
            requestPermissions(new String[]{p}, REQ_MEDIA);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
        }
    }

    private Button primary(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT_BOLD);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(32,157,255),Color.rgb(78,75,230)});g.setCornerRadius(dp(18));b.setBackground(g);return b;}
    private Button secondary(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(22);GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(18,42,65));g.setCornerRadius(dp(16));b.setBackground(g);return b;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
