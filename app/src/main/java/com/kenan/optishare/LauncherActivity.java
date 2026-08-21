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
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER); root.setPadding(44,44,44,44); root.setBackgroundColor(Color.rgb(7,17,31));
        TextView title=new TextView(this); title.setText("OptiShare Direct"); title.setTextSize(32); title.setTextColor(Color.WHITE); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub=new TextView(this); sub.setText("v0.7 • Direct phone-to-phone transfer\nNo Internet • No router • No QR data stream"); sub.setTextSize(16); sub.setTextColor(Color.rgb(148,208,232)); sub.setGravity(Gravity.CENTER); sub.setPadding(0,16,0,28); root.addView(sub);
        Button start=new Button(this); start.setText("Open OptiShare Direct"); start.setAllCaps(false); start.setOnClickListener(v->startActivity(new Intent(this,DirectActivity.class))); root.addView(start,new LinearLayout.LayoutParams(-1,-2));
        TextView note=new TextView(this); note.setText("Uses a private Wi‑Fi Direct link between the two phones.\nYour phones do not need to join a Wi‑Fi network and no Internet data is used."); note.setTextSize(14); note.setTextColor(Color.LTGRAY); note.setGravity(Gravity.CENTER); note.setPadding(0,24,0,20); root.addView(note);
        TextView credit=new TextView(this); credit.setText("Designed & developed by Kenan Alhennawi"); credit.setTextSize(13); credit.setTextColor(Color.rgb(56,189,248)); credit.setGravity(Gravity.CENTER); root.addView(credit);
        setContentView(root);
    }
}
