package com.kenan.optishare;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Privacy-conscious picker limited to apps visible in the device launcher. */
public final class AppPickerActivity extends ComponentActivity {
    public static final String EXTRA_PACKAGES = "packages";
    private final Map<String, CheckBox> choices = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 20, 38));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹ Back"); back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(88), dp(44)));
        TextView title = text("Installed apps", 25, Color.WHITE, true);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        Button add = primary("Add selected apps to queue");
        add.setOnClickListener(v -> finishSelection());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(54));
        addLp.setMargins(0, dp(16), 0, dp(14)); root.addView(add, addLp);
        root.addView(text("Standard apps are sent as APK. Apps installed as split packages are bundled as APKS so every required component is preserved.", 12, Color.rgb(151, 188, 214), false));

        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL));
        apps.sort(Comparator.comparing(x -> x.loadLabel(pm).toString(), String.CASE_INSENSITIVE_ORDER));
        for (ResolveInfo resolved : apps) {
            if (resolved.activityInfo == null || resolved.activityInfo.applicationInfo == null) continue;
            ApplicationInfo info = resolved.activityInfo.applicationInfo;
            if (getPackageName().equals(info.packageName) || choices.containsKey(info.packageName)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(round(Color.rgb(12, 42, 69), 16));
            ImageView icon = new ImageView(this); icon.setImageDrawable(info.loadIcon(pm));
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout infoBox = new LinearLayout(this); infoBox.setOrientation(LinearLayout.VERTICAL); infoBox.setPadding(dp(12),0,0,0);
            infoBox.addView(text(info.loadLabel(pm).toString(), 14, Color.WHITE, true));
            infoBox.addView(text(info.packageName, 10, Color.rgb(132, 170, 199), false));
            row.addView(infoBox, new LinearLayout.LayoutParams(0, -2, 1));
            CheckBox check = new CheckBox(this); check.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(70, 194, 255)));
            row.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48))); choices.put(info.packageName, check);
            row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(10), 0, 0); root.addView(row, lp);
        }
        setContentView(scroll);
    }

    private void finishSelection() {
        ArrayList<String> selected = new ArrayList<>();
        for (Map.Entry<String, CheckBox> entry : choices.entrySet()) if (entry.getValue().isChecked()) selected.add(entry.getKey());
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(EXTRA_PACKAGES, selected));
        finish();
    }

    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setAllCaps(false);b.setBackground(round(Color.rgb(22,73,111),14));return b;}
    private Button primary(String label){Button b=button(label);b.setTextSize(14);b.setTypeface(b.getTypeface(),android.graphics.Typeface.BOLD);b.setBackground(round(Color.rgb(34,122,231),16));return b;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);if(bold)t.setTypeface(t.getTypeface(),android.graphics.Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
