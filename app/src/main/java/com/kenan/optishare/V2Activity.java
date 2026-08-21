package com.kenan.optishare;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class V2Activity extends ComponentActivity {
    private final List<Uri> selected = new ArrayList<>();
    private LinearLayout content;
    private TextView selectionSummary;

    private final ActivityResultLauncher<Intent> picker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        if (!selected.contains(uri)) selected.add(uri);
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    if (!selected.contains(uri)) selected.add(uri);
                }
                renderSendSelection();
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showHome();
    }

    private void showHome() {
        selected.clear();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackground(gradient(Color.rgb(5,18,40), Color.rgb(17,51,92), 0));
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("O", 24, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(gradient(Color.rgb(26,154,255), Color.rgb(58,98,255), 22));
        top.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12),0,0,0);
        titleBox.addView(text("OptiShare 2", 28, Color.WHITE, true));
        titleBox.addView(text("Smart local transfer", 13, Color.rgb(158,196,225), false));
        top.addView(titleBox, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);

        TextView hero = text("Send anything.\nKeep everything organized.", 27, Color.WHITE, true);
        hero.setPadding(0,dp(28),0,dp(8));
        root.addView(hero);
        TextView sub = text("Photos, videos, music, apps and documents — multiple files in one transfer.", 14, Color.rgb(174,204,228), false);
        sub.setPadding(0,0,0,dp(20));
        root.addView(sub);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button send = bigAction("↑", "SEND", "Choose multiple files", Color.rgb(30,144,255), Color.rgb(45,89,224));
        send.setOnClickListener(v -> showSend());
        Button receive = bigAction("↓", "RECEIVE", "Open receiving mode", Color.rgb(42,202,140), Color.rgb(15,128,94));
        receive.setOnClickListener(v -> showReceive());
        actions.addView(send, new LinearLayout.LayoutParams(0,dp(156),1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,dp(156),1); rp.setMargins(dp(10),0,0,0);
        actions.addView(receive,rp);
        root.addView(actions);

        TextView catTitle = text("Browse by type", 18, Color.WHITE, true);
        catTitle.setPadding(0,dp(24),0,dp(10));
        root.addView(catTitle);

        LinearLayout grid1 = categoryRow(
                category("▣","Photos",Color.rgb(205,91,255), v -> openCategory("image/*")),
                category("▶","Videos",Color.rgb(255,84,109), v -> openCategory("video/*")),
                category("♫","Music",Color.rgb(255,172,48), v -> openCategory("audio/*")));
        root.addView(grid1);

        LinearLayout grid2 = categoryRow(
                category("A","Apps",Color.rgb(68,210,171), v -> openCategory("application/vnd.android.package-archive")),
                category("≡","Documents",Color.rgb(64,147,255), v -> openCategory("application/*")),
                category("…","Other",Color.rgb(130,145,166), v -> openCategory("*/*")));
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(-1,-2); g2.setMargins(0,dp(10),0,0);
        root.addView(grid2,g2);

        LinearLayout info = card();
        info.addView(text("Connection",15,Color.WHITE,true));
        info.addView(text("Not connected",18,Color.rgb(255,190,70),true));
        info.addView(text("Connection state will stay visible through Search → Connecting → Connected → Transfer → Resume.",12,Color.rgb(155,183,207),false));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1,-2); ip.setMargins(0,dp(22),0,0);
        root.addView(info,ip);

        TextView folder = text("Received files are organized under Download/OptiShare by type.", 12, Color.rgb(91,200,255), false);
        folder.setGravity(Gravity.CENTER);
        folder.setPadding(0,dp(18),0,0);
        root.addView(folder);

        TextView credit = text("Designed & developed by Kenan Alhennawi", 11, Color.rgb(136,165,190), false);
        credit.setGravity(Gravity.CENTER); credit.setPadding(0,dp(16),0,0); root.addView(credit);
        setContentView(scroll);
    }

    private void showSend() {
        selected.clear();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = baseScreen(scroll, "Send files", "Select as many items as you want");
        content = root;

        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[][] items = {{"Photos","image/*"},{"Videos","video/*"},{"Music","audio/*"},{"Apps","application/vnd.android.package-archive"}};
        for (String[] item: items) {
            Button b = smallButton(item[0]);
            b.setOnClickListener(v -> openCategory(item[1]));
            tabs.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));
        }
        root.addView(tabs);
        Button all = primary("Add files / documents");
        all.setOnClickListener(v -> openCategory("*/*"));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1,dp(52)); ap.setMargins(0,dp(12),0,0); root.addView(all,ap);

        selectionSummary = text("0 items selected", 16, Color.WHITE, true);
        selectionSummary.setPadding(0,dp(20),0,dp(8)); root.addView(selectionSummary);
        renderSendSelection();
        setContentView(scroll);
    }

    private void renderSendSelection() {
        if (content == null || selectionSummary == null) return;
        selectionSummary.setText(selected.size()+" item"+(selected.size()==1?"":"s")+" selected");
        View old = content.findViewWithTag("selected_card"); if (old!=null) content.removeView(old);
        LinearLayout card = card(); card.setTag("selected_card");
        if (selected.isEmpty()) {
            card.addView(text("Nothing selected yet",14,Color.rgb(160,183,202),false));
        } else {
            int show = Math.min(8, selected.size());
            for (int i=0;i<show;i++) card.addView(text("• "+selected.get(i).getLastPathSegment(),13,Color.WHITE,false));
            if (selected.size()>show) card.addView(text("+"+(selected.size()-show)+" more",12,Color.rgb(79,193,255),true));
            Button continueBtn = primary("Continue with "+selected.size()+" items");
            continueBtn.setOnClickListener(v -> showMessage("Next step", "Device discovery + batch transfer engine will use this whole selection in one session, not one file at a time."));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,dp(52)); cp.setMargins(0,dp(14),0,0); card.addView(continueBtn,cp);
        }
        content.addView(card);
    }

    private void showReceive() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = baseScreen(scroll, "Receive", "Your phone becomes visible to nearby OptiShare devices");
        LinearLayout radar = card();
        TextView circle = text("◎",72,Color.rgb(72,197,255),true); circle.setGravity(Gravity.CENTER); radar.addView(circle);
        TextView ready = text("READY TO RECEIVE",19,Color.rgb(70,229,158),true); ready.setGravity(Gravity.CENTER); radar.addView(ready);
        TextView state = text("Waiting for sender…",14,Color.WHITE,false); state.setGravity(Gravity.CENTER); radar.addView(state);
        TextView desc = text("Incoming batches will show sender name, file count, total size and category breakdown before you accept.",12,Color.rgb(158,184,205),false); desc.setGravity(Gravity.CENTER); desc.setPadding(dp(12),dp(12),dp(12),0); radar.addView(desc);
        root.addView(radar);
        setContentView(scroll);
    }

    private LinearLayout baseScreen(ScrollView scroll, String title, String subtitle) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(22),dp(20),dp(28)); root.setBackground(gradient(Color.rgb(5,18,40),Color.rgb(15,42,76),0)); scroll.addView(root);
        Button back = smallButton("← Back"); back.setOnClickListener(v -> showHome()); root.addView(back,new LinearLayout.LayoutParams(dp(96),dp(44)));
        TextView t = text(title,27,Color.WHITE,true); t.setPadding(0,dp(20),0,dp(3)); root.addView(t); root.addView(text(subtitle,13,Color.rgb(160,190,215),false));
        return root;
    }

    private void openCategory(String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        picker.launch(intent);
    }

    private Button category(String icon,String label,int color,View.OnClickListener listener) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(icon+"\n"+label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(gradient(color,darken(color),18)); b.setOnClickListener(listener); return b;
    }
    private LinearLayout categoryRow(Button a,Button b,Button c) { LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.addView(a,new LinearLayout.LayoutParams(0,dp(104),1)); LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(104),1);p2.setMargins(dp(8),0,0,0);row.addView(b,p2);LinearLayout.LayoutParams p3=new LinearLayout.LayoutParams(0,dp(104),1);p3.setMargins(dp(8),0,0,0);row.addView(c,p3);return row; }
    private Button bigAction(String icon,String title,String sub,int top,int bottom){Button b=new Button(this);b.setAllCaps(false);b.setText(icon+"\n"+title+"\n"+sub);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(top,bottom,22));return b;}
    private Button primary(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(Color.rgb(31,151,255),Color.rgb(49,91,226),16));return b;}
    private Button smallButton(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setBackground(round(Color.rgb(21,47,76),14));return b;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable g=round(Color.rgb(13,33,56),18);g.setStroke(dp(1),Color.rgb(38,69,96));l.setBackground(g);return l;}
    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable gradient(int top,int bottom,int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});g.setCornerRadius(dp(radius));return g;}
    private int darken(int color){return Color.rgb((int)(Color.red(color)*0.68),(int)(Color.green(color)*0.68),(int)(Color.blue(color)*0.68));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void showMessage(String title,String msg){new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();}
}
