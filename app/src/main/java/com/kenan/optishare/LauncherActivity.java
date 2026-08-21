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
        sub.setText("Optical Modem v0.4.1\nCrash-safe launcher");
        sub.setTextSize(16);
        sub.setTextColor(Color.rgb(148, 208, 232));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 16, 0, 24);
        root.addView(sub);

        Button start = new Button(this);
        start.setText("Open OptiShare");
        start.setAllCaps(false);
        start.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, MainActivity.class));
            } catch (Throwable t) {
                sub.setText("Could not open engine:\n" + t.getClass().getSimpleName() + ": " + t.getMessage());
                sub.setTextColor(Color.rgb(255, 150, 150));
            }
        });
        root.addView(start, new LinearLayout.LayoutParams(-1, -2));

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
