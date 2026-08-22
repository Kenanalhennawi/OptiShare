package com.kenan.optishare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kenan.optishare.device.DeviceIdentity;

public final class SettingsActivity extends Activity {
    private DeviceIdentity identity;

    @Override protected void onCreate(Bundle state){super.onCreate(state);identity=new DeviceIdentity(this);render();}

    private void render(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(18),dp(20),dp(28));root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(5,14,28),Color.rgb(8,29,51),Color.rgb(18,19,54)}));scroll.addView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);Button back=button("‹");back.setTextSize(22);back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(12),0,0,0);titles.addView(text("Settings",26,Color.WHITE,true));titles.addView(text("Identity, permissions and privacy",12,Color.rgb(143,177,201),false));header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);

        TextView deviceLabel=text("This device",16,Color.WHITE,true);deviceLabel.setPadding(0,dp(22),0,dp(8));root.addView(deviceLabel);
        LinearLayout device=card();device.addView(text(identity.name(),17,Color.WHITE,true));device.addView(text("This name is shown to nearby OptiShare users.",12,Color.rgb(143,173,197),false));Button rename=primary("Rename device");rename.setOnClickListener(v->rename());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(50));rp.setMargins(0,dp(12),0,0);device.addView(rename,rp);root.addView(device);

        TextView accessLabel=text("Access",16,Color.WHITE,true);accessLabel.setPadding(0,dp(20),0,dp(8));root.addView(accessLabel);
        LinearLayout access=card();access.addView(text("Nearby & media permissions",14,Color.WHITE,true));access.addView(text("OptiShare only requests access required for discovery, selecting media, QR pairing and foreground transfer notifications.",12,Color.rgb(143,173,197),false));Button appSettings=button("Open Android app settings");appSettings.setOnClickListener(v->{Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);i.setData(Uri.parse("package:"+getPackageName()));startActivity(i);});LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48));ap.setMargins(0,dp(12),0,0);access.addView(appSettings,ap);root.addView(access);

        TextView privacyLabel=text("Privacy & security",16,Color.WHITE,true);privacyLabel.setPadding(0,dp(20),0,dp(8));root.addView(privacyLabel);
        LinearLayout privacy=card();privacy.addView(text("No account. No cloud relay.",15,Color.rgb(92,213,164),true));privacy.addView(text("Transfer content stays between the participating devices. Pairing uses an authenticated encrypted session and received files are verified before publishing.",12,Color.rgb(150,181,204),false));privacy.addView(text("Security codes must match on both phones before a transfer is approved.",12,Color.rgb(150,181,204),false));root.addView(privacy);

        TextView aboutLabel=text("About",16,Color.WHITE,true);aboutLabel.setPadding(0,dp(20),0,dp(8));root.addView(aboutLabel);
        LinearLayout about=card();about.addView(text("OptiShare "+versionName(),14,Color.WHITE,true));about.addView(text("Designed & developed by Kenan Alhennawi",12,Color.rgb(111,199,244),false));about.addView(text("Android 5.0+ • Local device-to-device transfer",11,Color.rgb(139,169,193),false));root.addView(about);
        setContentView(scroll);
    }

    private String versionName(){
        try {
            PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
            return info.versionName==null?"3.0.0":info.versionName;
        } catch(Exception ignored){
            return "3.0.0";
        }
    }

    private void rename(){final EditText input=new EditText(this);input.setText(identity.name());input.setSelectAllOnFocus(true);new AlertDialog.Builder(this).setTitle("Device name").setMessage("Use a name you will recognize on your other phone.").setView(input).setPositiveButton("Save",(d,w)->{try{identity.setName(input.getText().toString());render();}catch(Exception e){}}).setNegativeButton("Cancel",null).show();}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(15),dp(16),dp(15));GradientDrawable g=round(Color.rgb(13,30,49),18);g.setStroke(dp(1),Color.rgb(29,55,79));l.setBackground(g);return l;}
    private Button primary(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(31,157,255),Color.rgb(77,76,230)});g.setCornerRadius(dp(16));b.setBackground(g);return b;}
    private Button button(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setBackground(round(Color.rgb(24,49,73),14));return b;}
    private TextView text(String v,int s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private GradientDrawable round(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
