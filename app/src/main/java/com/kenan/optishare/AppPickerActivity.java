package com.kenan.optishare;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Installed-app picker with limited package visibility.
 * Only launchable apps visible through the manifest <queries> declaration are listed.
 * No QUERY_ALL_PACKAGES permission is used.
 */
public final class AppPickerActivity extends Activity {
    public static final String EXTRA_SELECTED = "selected_apps";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> selectedPackages = new HashSet<>();
    private LinearLayout list;
    private TextView count;
    private Button done;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
        loadApps();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(16),dp(18),dp(18));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(5,14,28),Color.rgb(8,29,51),Color.rgb(18,19,54)}));

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=secondary("‹");back.setTextSize(22);back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(10),0,0,0);labels.addView(text("Apps",24,Color.WHITE,true));count=text("Loading installed apps…",11,Color.rgb(102,204,255),true);labels.addView(count);header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);

        TextView note=text("Select one or more launchable apps. OptiShare prepares an APK or .apks package locally before sending.",12,Color.rgb(146,178,202),false);note.setPadding(0,dp(12),0,dp(12));root.addView(note);

        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        done=primary("Add selected apps");done.setEnabled(false);done.setAlpha(.45f);done.setOnClickListener(v->prepareSelected());root.addView(done,new LinearLayout.LayoutParams(-1,dp(58)));
        setContentView(root);
    }

    private void loadApps() {
        worker.execute(() -> {
            PackageManager pm=getPackageManager();
            Intent launcher=new Intent(Intent.ACTION_MAIN);launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved=pm.queryIntentActivities(launcher,PackageManager.MATCH_DEFAULT_ONLY);
            List<AppItem> apps=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for(ResolveInfo info:resolved){
                if(info.activityInfo==null||info.activityInfo.applicationInfo==null)continue;
                ApplicationInfo app=info.activityInfo.applicationInfo;
                if(getPackageName().equals(app.packageName)||!seen.add(app.packageName))continue;
                CharSequence label=pm.getApplicationLabel(app);
                Drawable icon;
                try{icon=pm.getApplicationIcon(app);}catch(Exception e){icon=null;}
                apps.add(new AppItem(app.packageName,label==null?app.packageName:label.toString(),icon,app));
            }
            Collections.sort(apps,Comparator.comparing(a->a.label,String.CASE_INSENSITIVE_ORDER));
            runOnUiThread(()->renderApps(apps));
        });
    }

    private void renderApps(List<AppItem> apps) {
        list.removeAllViews();
        count.setText(apps.size()+" launchable apps • 0 selected");
        if(apps.isEmpty()){
            LinearLayout empty=card();empty.addView(text("No launchable apps visible",15,Color.WHITE,true));empty.addView(text("Android package visibility may limit this list. OptiShare intentionally avoids broad app-inventory permission.",12,Color.rgb(143,173,197),false));list.addView(empty);return;
        }
        for(AppItem item:apps){
            LinearLayout card=card();LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon=new ImageView(this);if(item.icon!=null)icon.setImageDrawable(item.icon);row.addView(icon,new LinearLayout.LayoutParams(dp(52),dp(52)));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(12),0,0,0);labels.addView(text(item.label,14,Color.WHITE,true));labels.addView(text(item.packageName,10,Color.rgb(139,169,193),false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            CheckBox check=new CheckBox(this);check.setOnCheckedChangeListener((b,checked)->{if(checked)selectedPackages.add(item.packageName);else selectedPackages.remove(item.packageName);updateSelection(apps.size());});row.addView(check,new LinearLayout.LayoutParams(dp(52),dp(52)));card.addView(row);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));list.addView(card,lp);
        }
    }

    private void updateSelection(int total) {
        count.setText(total+" launchable apps • "+selectedPackages.size()+" selected");
        done.setEnabled(!selectedPackages.isEmpty());done.setAlpha(selectedPackages.isEmpty()?.45f:1f);
    }

    private void prepareSelected() {
        if(selectedPackages.isEmpty())return;
        done.setEnabled(false);done.setText("Preparing app packages…");
        worker.execute(()->{
            ArrayList<String> result=new ArrayList<>();
            try{
                File dir=new File(getCacheDir(),"shared-apps");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create app package cache");
                for(String packageName:selectedPackages){
                    ApplicationInfo app=getPackageManager().getApplicationInfo(packageName,0);
                    String label=String.valueOf(getPackageManager().getApplicationLabel(app));
                    File prepared=preparePackage(dir,label,packageName,app);
                    Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",prepared);
                    result.add(uri.toString());
                }
                Intent data=new Intent();data.putStringArrayListExtra(EXTRA_SELECTED,result);
                runOnUiThread(()->{setResult(Activity.RESULT_OK,data);finish();});
            }catch(Exception e){runOnUiThread(()->{done.setEnabled(true);done.setText("Add selected apps");new android.app.AlertDialog.Builder(this).setTitle("Could not prepare app").setMessage(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).setPositiveButton("OK",null).show();});}
        });
    }

    private File preparePackage(File dir,String label,String packageName,ApplicationInfo app)throws Exception{
        String safe=safeName(label)+"-"+packageName;
        String[] splits=app.splitSourceDirs;
        if(splits==null||splits.length==0){
            File target=new File(dir,safe+".apk");copy(new File(app.sourceDir),target);return target;
        }
        File target=new File(dir,safe+".apks");
        try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target)))){
            addZip(out,new File(app.sourceDir),"base.apk");
            for(int i=0;i<splits.length;i++)addZip(out,new File(splits[i]),"split-"+(i+1)+".apk");
        }
        return target;
    }

    private static void addZip(ZipOutputStream out,File source,String name)throws Exception{
        out.putNextEntry(new ZipEntry(name));byte[] buffer=new byte[256*1024];try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(source))){int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}out.closeEntry();
    }
    private static void copy(File source,File target)throws Exception{byte[] buffer=new byte[256*1024];try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(source));BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(target))){int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}}
    private static String safeName(String value){String s=value==null?"app":value.replaceAll("[^a-zA-Z0-9._ -]","_").trim();return s.isEmpty()?"app":s;}

    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable g=round(Color.rgb(13,30,49),18);g.setStroke(dp(1),Color.rgb(29,55,79));l.setBackground(g);return l;}
    private Button primary(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(31,157,255),Color.rgb(77,76,230)});g.setCornerRadius(dp(17));b.setBackground(g);return b;}
    private Button secondary(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setBackground(round(Color.rgb(23,49,72),15));return b;}
    private TextView text(String v,int s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private GradientDrawable round(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private static final class AppItem{
        final String packageName;final String label;final Drawable icon;final ApplicationInfo info;
        AppItem(String packageName,String label,Drawable icon,ApplicationInfo info){this.packageName=packageName;this.label=label;this.icon=icon;this.info=info;}
    }
}
