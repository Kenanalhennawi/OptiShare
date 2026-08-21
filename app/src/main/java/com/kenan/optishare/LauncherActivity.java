package com.kenan.optishare;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.rgb(7, 17, 31));

        TextView title = new TextView(this);
        title.setText("OptiShare");
        title.setTextSize(32);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("VLC Lab v0.5\nFirst prove optical decoding, then scale speed");
        sub.setTextSize(16);
        sub.setTextColor(Color.rgb(148, 208, 232));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 16, 0, 24);
        root.addView(sub);

        Button vlc = new Button(this);
        vlc.setText("Open VLC Lab v0.5");
        vlc.setAllCaps(false);
        vlc.setOnClickListener(v -> startActivity(new Intent(this, VlcActivity.class)));
        root.addView(vlc, new LinearLayout.LayoutParams(-1, -2));

        Button legacy = new Button(this);
        legacy.setText("Open legacy v0.4 engine");
        legacy.setAllCaps(false);
        legacy.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 16, 0, 0);
        root.addView(legacy, lp);

        TextView credit = new TextView(this);
        credit.setText("Designed & developed by Kenan Alhennawi");
        credit.setTextSize(13);
        credit.setTextColor(Color.rgb(56, 189, 248));
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, 28, 0, 0);
        root.addView(credit);

        setContentView(root);
    }
}
