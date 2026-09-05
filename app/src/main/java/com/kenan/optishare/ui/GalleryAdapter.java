package com.kenan.optishare.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kenan.optishare.R;
import com.kenan.optishare.storage.MediaRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Media grid with previews, sizes, durations, selection state, and local audio preview. */
public final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.Holder> {
    public interface Listener { void onSelectionChanged(Set<Uri> selected, long selectedBytes); }
    private final List<MediaRepository.MediaItem> items=new ArrayList<>();
    private final Set<Uri> selected=new HashSet<>();
    private final Listener listener;
    private final String mediaType;
    private MediaPlayer player;
    private Uri playingUri;
    private ImageButton playingButton;
    private final ExecutorService artworkExecutor=Executors.newFixedThreadPool(2);

    public GalleryAdapter(String mediaType,Set<Uri> initialSelection,Listener listener){this.mediaType=mediaType==null?"image":mediaType;if(initialSelection!=null)selected.addAll(initialSelection);this.listener=listener;setHasStableIds(true);}
    public void replace(List<MediaRepository.MediaItem> data){items.clear();if(data!=null)items.addAll(data);notifyDataSetChanged();}
    public Set<Uri> selection(){return new HashSet<>(selected);}
    public long selectedBytes(){long total=0L;for(MediaRepository.MediaItem item:items)if(selected.contains(item.uri)&&item.size>0&&Long.MAX_VALUE-total>item.size)total+=item.size;return total;}
    @Override public long getItemId(int position){return items.get(position).uri.toString().hashCode();}

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){
        LinearLayout root=new LinearLayout(parent.getContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(parent,5),dp(parent,5),dp(parent,5),dp(parent,8));
        FrameLayout preview=new FrameLayout(parent.getContext());
        ImageView image=new ImageView(parent.getContext());image.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.addView(image,new FrameLayout.LayoutParams(-1,dp(parent,118)));
        TextView duration=new TextView(parent.getContext());duration.setTextColor(Color.WHITE);duration.setTextSize(10);duration.setGravity(Gravity.CENTER);duration.setPadding(dp(parent,7),dp(parent,3),dp(parent,7),dp(parent,3));duration.setBackground(pill(0xcc071522,8,parent));FrameLayout.LayoutParams durationLp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);durationLp.setMargins(0,0,dp(parent,7),dp(parent,7));preview.addView(duration,durationLp);
        TextView check=new TextView(parent.getContext());check.setText("✓");check.setTextColor(Color.WHITE);check.setTextSize(14);check.setGravity(Gravity.CENTER);check.setBackground(pill(0xff25a9ef,20,parent));FrameLayout.LayoutParams checkLp=new FrameLayout.LayoutParams(dp(parent,30),dp(parent,30),Gravity.TOP|Gravity.END);checkLp.setMargins(0,dp(parent,7),dp(parent,7),0);preview.addView(check,checkLp);
        ImageButton play=new ImageButton(parent.getContext());play.setImageResource(android.R.drawable.ic_media_play);play.setColorFilter(Color.WHITE);play.setBackground(pill(0xd92b86d1,28,parent));play.setContentDescription("Preview audio");FrameLayout.LayoutParams playLp=new FrameLayout.LayoutParams(dp(parent,52),dp(parent,52),Gravity.CENTER);preview.addView(play,playLp);
        GradientDrawable previewShape=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff234f78,0xff172a55,0xff39205f});previewShape.setCornerRadius(dp(parent,18));preview.setBackground(previewShape);preview.setClipToOutline(true);if(Build.VERSION.SDK_INT>=21)preview.setElevation(dp(parent,7));root.addView(preview,new LinearLayout.LayoutParams(-1,dp(parent,118)));
        TextView label=new TextView(parent.getContext());label.setTextColor(Color.WHITE);label.setTextSize(11);label.setTypeface(label.getTypeface(),android.graphics.Typeface.BOLD);label.setMaxLines(1);label.setPadding(dp(parent,5),dp(parent,7),dp(parent,5),0);root.addView(label,new LinearLayout.LayoutParams(-1,dp(parent,27)));
        TextView meta=new TextView(parent.getContext());meta.setTextColor(Color.rgb(154,190,217));meta.setTextSize(10);meta.setMaxLines(1);meta.setPadding(dp(parent,5),0,dp(parent,5),0);root.addView(meta,new LinearLayout.LayoutParams(-1,dp(parent,23)));
        return new Holder(root,image,label,meta,duration,check,play);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder,int position){
        MediaRepository.MediaItem item=items.get(position);
        holder.label.setText(shortName(item.name));holder.meta.setText(formatBytes(item.size)+(item.durationMs>0?"  •  "+formatDuration(item.durationMs):""));
        holder.duration.setText(formatDuration(item.durationMs));holder.duration.setVisibility(item.durationMs>0&&!"audio".equals(mediaType)?View.VISIBLE:View.GONE);
        holder.check.setVisibility(selected.contains(item.uri)?View.VISIBLE:View.GONE);holder.image.setImageDrawable(null);holder.image.setBackgroundColor(Color.rgb(15,48,76));holder.play.setVisibility("audio".equals(mediaType)?View.VISIBLE:View.GONE);
        if("audio".equals(mediaType)){holder.image.setImageResource(R.drawable.ic_os_music);holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);loadAudioArtwork(holder.image,item.uri);holder.play.setImageResource(item.uri.equals(playingUri)?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play);holder.play.setOnClickListener(v->toggleAudio(item.uri,holder.play));}
        else{holder.play.setOnClickListener(null);holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);loadVisualPreview(holder.image,item.uri,"video".equals(mediaType));}
        updateBackground(holder.root,selected.contains(item.uri));
        holder.root.setOnClickListener(v->{if(!selected.add(item.uri))selected.remove(item.uri);boolean checked=selected.contains(item.uri);updateBackground(holder.root,checked);holder.check.setVisibility(checked?View.VISIBLE:View.GONE);if(listener!=null)listener.onSelectionChanged(selection(),selectedBytes());});
    }

    private void loadVisualPreview(ImageView image,Uri uri,boolean video){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){try{Bitmap thumbnail=image.getContext().getContentResolver().loadThumbnail(uri,new Size(360,270),null);image.setImageBitmap(thumbnail);return;}catch(Exception ignored){}}
        if(video){MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(image.getContext(),uri);Bitmap frame=retriever.getFrameAtTime(0L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(frame!=null)image.setImageBitmap(frame);else image.setImageResource(R.drawable.ic_os_video);}catch(Exception ignored){image.setImageResource(R.drawable.ic_os_video);}finally{try{retriever.release();}catch(Exception ignored){}}}else image.setImageURI(uri);
    }

    private void loadAudioArtwork(ImageView image,Uri uri){String key=uri.toString();image.setTag(key);artworkExecutor.execute(()->{byte[] bytes=null;MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(image.getContext(),uri);bytes=retriever.getEmbeddedPicture();}catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}}if(bytes==null||bytes.length==0)return;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;Bitmap bitmap;try{bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}catch(Exception ignored){return;}if(bitmap==null)return;image.post(()->{if(key.equals(image.getTag())){image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.CENTER_CROP);}});});}

    private void toggleAudio(Uri uri,ImageButton button){if(uri.equals(playingUri)&&player!=null){releasePlayer();return;}releasePlayer();try{MediaPlayer next=new MediaPlayer();next.setDataSource(button.getContext(),uri);next.setOnPreparedListener(p->{p.start();button.setImageResource(android.R.drawable.ic_media_pause);});next.setOnCompletionListener(p->releasePlayer());next.setOnErrorListener((p,w,e)->{releasePlayer();return true;});playingUri=uri;playingButton=button;player=next;next.prepareAsync();}catch(Exception ignored){releasePlayer();}}
    private void releasePlayer(){MediaPlayer old=player;player=null;playingUri=null;if(playingButton!=null)playingButton.setImageResource(android.R.drawable.ic_media_play);playingButton=null;if(old!=null){try{old.stop();}catch(Exception ignored){}try{old.release();}catch(Exception ignored){}}}
    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView){releasePlayer();artworkExecutor.shutdownNow();super.onDetachedFromRecyclerView(recyclerView);}
    @Override public int getItemCount(){return items.size();}
    private static void updateBackground(LinearLayout root,boolean checked){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{checked?0xff286fa1:0xff173c61,checked?0xff174f83:0xff102b4b,checked?0xff412878:0xff271c54});g.setCornerRadius(dp(root,22));g.setStroke(dp(root,checked?3:1),checked?0xff66ddff:0xff416d94);root.setBackground(g);if(Build.VERSION.SDK_INT>=21)root.setElevation(dp(root,checked?12:7));root.animate().scaleX(checked?.965f:1f).scaleY(checked?.965f:1f).setDuration(170).start();}
    private static GradientDrawable pill(int color,int radius,View view){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(view,radius));return g;}
    private static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}
    private static String shortName(String value){if(value==null)return"media";return value.length()<=22?value:value.substring(0,19)+"…";}
    private static String formatDuration(long ms){long total=Math.max(0,ms/1000);return String.format(Locale.US,"%d:%02d",total/60,total%60);}
    private static String formatBytes(long b){if(b>=1024L*1024*1024)return String.format(Locale.US,"%.2f GB",b/(1024d*1024*1024));if(b>=1024L*1024)return String.format(Locale.US,"%.1f MB",b/(1024d*1024));if(b>=1024)return String.format(Locale.US,"%.0f KB",b/1024d);return b+" B";}
    static final class Holder extends RecyclerView.ViewHolder{final LinearLayout root;final ImageView image;final TextView label,meta,duration,check;final ImageButton play;Holder(LinearLayout root,ImageView image,TextView label,TextView meta,TextView duration,TextView check,ImageButton play){super(root);this.root=root;this.image=image;this.label=label;this.meta=meta;this.duration=duration;this.check=check;this.play=play;}}
}
