package com.kenan.optishare;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kenan.optishare.history.TransferHistoryStore;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HistoryActivity extends Activity {
    private TransferHistoryStore store;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TransferHistoryStore(this);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(18),dp(20),dp(28));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(5,14,28),Color.rgb(8,29,51),Color.rgb(18,19,54)}));
        scroll.addView(root);

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹");back.setTextSize(22);back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(12),0,0,0);titles.addView(text("Transfer History",26,Color.WHITE,true));titles.addView(text("Recent OptiShare sessions",12,Color.rgb(143,177,201),false));header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=button("Clear");clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Clear history?").setMessage("This removes transfer history only. Received files stay on your device.").setPositiveButton("Clear",(d,w)->{store.clear();render();}).setNegativeButton("Cancel",null).show());header.addView(clear,new LinearLayout.LayoutParams(dp(76),dp(44)));root.addView(header);

        List<TransferHistoryStore.Entry> entries=store.load();
        TextView summary=text(entries.size()+" recent session"+(entries.size()==1?"":"s"),14,Color.rgb(101,204,255),true);summary.setPadding(0,dp(22),0,dp(10));root.addView(summary);
        if(entries.isEmpty()){
            LinearLayout empty=card();TextView icon=text("↕",36,Color.rgb(87,198,255),true);icon.setGravity(Gravity.CENTER);empty.addView(icon);TextView t=text("No transfer history yet",17,Color.WHITE,true);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(8),0,dp(4));empty.addView(t);TextView h=text("Completed and interrupted sessions will appear here.",12,Color.rgb(143,173,197),false);h.setGravity(Gravity.CENTER);empty.addView(h);root.addView(empty);
        } else {
            for(TransferHistoryStore.Entry e:entries)root.addView(entryCard(e));
        }
        setContentView(scroll);
    }

    private LinearLayout entryCard(TransferHistoryStore.Entry e){
        LinearLayout card=card();LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        boolean received="received".equalsIgnoreCase(e.direction);TextView icon=text(received?"↓":"↑",22,Color.WHITE,true);icon.setGravity(Gravity.CENTER);icon.setBackground(round(e.success?Color.rgb(27,131,102):Color.rgb(139,61,76),16));row.addView(icon,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(12),0,0,0);labels.addView(text((received?"Received from ":"Sent to ")+e.peer,14,Color.WHITE,true));labels.addView(text(e.fileCount+" file"+(e.fileCount==1?"":"s")+" • "+formatBytes(e.totalBytes),11,Color.rgb(143,173,198),false));labels.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(e.time))+(e.success?" • Completed":" • Interrupted"),10,e.success?Color.rgb(74,218,154):Color.rgb(255,132,143),true));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));card.addView(row);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(9));card.setLayoutParams(lp);return card;
    }

    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(15),dp(14),dp(15),dp(14));GradientDrawable g=round(Color.rgb(13,30,49),18);g.setStroke(dp(1),Color.rgb(29,55,79));l.setBackground(g);return l;}
    private Button button(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setBackground(round(Color.rgb(24,49,73),14));return b;}
    private TextView text(String v,int s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private GradientDrawable round(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private static String formatBytes(long b){if(b>=1024L*1024*1024)return String.format(Locale.US,"%.2f GB",b/(1024.0*1024*1024));if(b>=1024L*1024)return String.format(Locale.US,"%.2f MB",b/(1024.0*1024));if(b>=1024)return String.format(Locale.US,"%.1f KB",b/1024.0);return b+" B";}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
