package com.kenan.optishare;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.ComponentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kenan.optishare.ui.UiText;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Scroll-safe picker showing only enabled, user-installed launcher apps. */
public final class AppPickerActivity extends ComponentActivity {
    public static final String EXTRA_PACKAGES="packages";
    private final Set<String> selected=new HashSet<>();
    private Button addButton;

    private static final class AppEntry {
        final String label,packageName;
        final Drawable icon;
        final long bytes;
        AppEntry(String label,String packageName,Drawable icon,long bytes){this.label=label;this.packageName=packageName;this.icon=icon;this.bytes=bytes;}
    }

    @Override protected void onCreate(Bundle state){super.onCreate(state);render();}

    private void render(){
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(16),dp(18),dp(12));page.setBackground(vivid(new int[]{Color.rgb(3,14,34),Color.rgb(8,46,78),Color.rgb(38,22,78)},0));

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹ Back");back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(88),dp(44)));
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);titleBox.setPaddingRelative(dp(12),0,0,0);
        titleBox.addView(text(getString(R.string.installed_apps),25,Color.WHITE,true));titleBox.addView(text(getString(R.string.user_installed_apps_only),12,Color.rgb(143,183,212),false));
        header.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));page.addView(header);

        addButton=primary(getString(R.string.select_apps));
        addButton.setEnabled(false);addButton.setAlpha(.5f);addButton.setOnClickListener(v->finishSelection());
        LinearLayout.LayoutParams addLp=new LinearLayout.LayoutParams(-1,dp(54));addLp.setMargins(0,dp(14),0,dp(10));page.addView(addButton,addLp);
        TextView hint=text(getString(R.string.app_size_includes),11,Color.rgb(142,179,207),false);hint.setPadding(dp(4),0,dp(4),dp(8));page.addView(hint);

        RecyclerView list=new RecyclerView(this);list.setLayoutManager(new LinearLayoutManager(this));list.setClipToPadding(false);list.setPadding(0,0,0,dp(16));list.setAdapter(new AppsAdapter(loadUserApps()));
        page.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
    }

    private List<AppEntry> loadUserApps(){
        PackageManager pm=getPackageManager();Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved=pm.queryIntentActivities(launcher,PackageManager.MATCH_ALL);List<AppEntry> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(ResolveInfo item:resolved){
            if(item.activityInfo==null||item.activityInfo.applicationInfo==null)continue;
            ApplicationInfo info=item.activityInfo.applicationInfo;
            if(!info.enabled||getPackageName().equals(info.packageName)||!seen.add(info.packageName))continue;
            if((info.flags&ApplicationInfo.FLAG_SYSTEM)!=0||(info.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)continue;
            result.add(new AppEntry(item.loadLabel(pm).toString(),info.packageName,info.loadIcon(pm),appBytes(info)));
        }
        result.sort(Comparator.comparing(x->x.label,String.CASE_INSENSITIVE_ORDER));return result;
    }

    private final class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.Holder>{
        private final List<AppEntry> apps;
        AppsAdapter(List<AppEntry> apps){this.apps=apps;setHasStableIds(true);}
        @Override public long getItemId(int position){return apps.get(position).packageName.hashCode();}
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type){
            LinearLayout row=new LinearLayout(parent.getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(vivid(new int[]{Color.rgb(24,65,96),Color.rgb(12,39,68),Color.rgb(35,25,76)},22));if(android.os.Build.VERSION.SDK_INT>=21)row.setElevation(dp(8));
            ImageView icon=new ImageView(parent.getContext());icon.setPadding(dp(7),dp(7),dp(7),dp(7));GradientDrawable iconHalo=round(Color.argb(48,255,255,255),28);iconHalo.setShape(GradientDrawable.OVAL);iconHalo.setStroke(dp(1),Color.argb(115,255,255,255));icon.setBackground(iconHalo);row.addView(icon,new LinearLayout.LayoutParams(dp(58),dp(58)));
            LinearLayout copy=new LinearLayout(parent.getContext());copy.setOrientation(LinearLayout.VERTICAL);copy.setPaddingRelative(dp(13),0,dp(6),0);
            TextView label=text("",15,Color.WHITE,true);TextView meta=text("",11,Color.rgb(140,178,207),false);copy.addView(label);copy.addView(meta);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            CheckBox check=new CheckBox(parent.getContext());check.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(70,194,255)));row.addView(check,new LinearLayout.LayoutParams(dp(48),dp(48)));
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(9));row.setLayoutParams(lp);return new Holder(row,icon,label,meta,check);
        }
        @Override public void onBindViewHolder(@NonNull Holder h,int position){
            AppEntry app=apps.get(position);h.icon.setImageDrawable(app.icon);h.label.setText(app.label);h.meta.setText(humanBytes(app.bytes)+"  •  "+app.packageName);
            h.check.setOnCheckedChangeListener(null);h.check.setChecked(selected.contains(app.packageName));
            View.OnClickListener toggle=v->{if(!selected.add(app.packageName))selected.remove(app.packageName);h.check.setChecked(selected.contains(app.packageName));updateAction();};
            h.root.setOnClickListener(v->{h.root.animate().scaleX(.97f).scaleY(.97f).setDuration(90).withEndAction(()->h.root.animate().scaleX(1f).scaleY(1f).setDuration(150).start()).start();toggle.onClick(v);});h.check.setOnClickListener(toggle);
        }
        @Override public int getItemCount(){return apps.size();}
        final class Holder extends RecyclerView.ViewHolder{final LinearLayout root;final ImageView icon;final TextView label,meta;final CheckBox check;Holder(LinearLayout root,ImageView icon,TextView label,TextView meta,CheckBox check){super(root);this.root=root;this.icon=icon;this.label=label;this.meta=meta;this.check=check;}}
    }

    private void updateAction(){int count=selected.size();addButton.setText(count==0?getString(R.string.select_apps):getString(R.string.add_apps_count,count));addButton.setEnabled(count>0);addButton.setAlpha(count>0?1f:.5f);}
    private void finishSelection(){setResult(RESULT_OK,new Intent().putStringArrayListExtra(EXTRA_PACKAGES,new ArrayList<>(selected)));finish();}
    private long appBytes(ApplicationInfo info){long total=fileBytes(info.sourceDir);if(info.splitSourceDirs!=null)for(String path:info.splitSourceDirs){long size=fileBytes(path);if(Long.MAX_VALUE-total<size)return Long.MAX_VALUE;total+=size;}return total;}
    private long fileBytes(String path){if(path==null)return 0L;try{return Math.max(0L,new File(path).length());}catch(Exception ignored){return 0L;}}
    private String humanBytes(long b){if(b>=1024L*1024*1024)return String.format(Locale.US,"%.2f GB",b/(1024d*1024*1024));if(b>=1024L*1024)return String.format(Locale.US,"%.1f MB",b/(1024d*1024));if(b>=1024)return String.format(Locale.US,"%.0f KB",b/1024d);return b+" B";}
    private Button button(String label){Button b=new Button(this);b.setText(UiText.get(this,label));b.setTextColor(Color.WHITE);b.setTextSize(12);b.setAllCaps(false);b.setBackground(vivid(new int[]{Color.rgb(55,211,255),Color.rgb(36,132,244),Color.rgb(126,62,232)},18));if(android.os.Build.VERSION.SDK_INT>=21)b.setElevation(dp(6));press(b);return b;}
    private Button primary(String label){Button b=button(label);b.setTextSize(14);b.setTypeface(b.getTypeface(),android.graphics.Typeface.BOLD);b.setBackground(vivid(new int[]{Color.rgb(48,204,255),Color.rgb(38,120,241),Color.rgb(105,57,224)},20));if(android.os.Build.VERSION.SDK_INT>=21)b.setElevation(dp(9));return b;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(UiText.get(this,value));t.setTextColor(color);t.setTextSize(size);if(bold)t.setTypeface(t.getTypeface(),android.graphics.Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable vivid(int[] colors,int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);g.setCornerRadius(dp(radius));g.setStroke(dp(1),Color.argb(90,255,255,255));return g;}
    private void press(View view){view.setOnTouchListener((v,e)->{if(e.getAction()==android.view.MotionEvent.ACTION_DOWN)v.animate().scaleX(.96f).scaleY(.96f).translationY(dp(3)).setDuration(75).start();else if(e.getAction()==android.view.MotionEvent.ACTION_UP||e.getAction()==android.view.MotionEvent.ACTION_CANCEL)v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(170).start();return false;});}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
