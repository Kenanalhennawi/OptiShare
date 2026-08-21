package com.kenan.optishare.ui;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.kenan.optishare.storage.MediaRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.Holder> {
    public interface Listener { void onSelectionChanged(Set<Uri> selected); }
    public interface PreviewListener { void onPreview(MediaRepository.MediaItem item); }

    private final List<MediaRepository.MediaItem> items = new ArrayList<>();
    private final Set<Uri> selected = new HashSet<>();
    private final Listener listener;
    private final PreviewListener previewListener;

    public GalleryAdapter(Set<Uri> initialSelection, Listener listener) {
        this(initialSelection, listener, null);
    }

    public GalleryAdapter(Set<Uri> initialSelection, Listener listener, PreviewListener previewListener) {
        if (initialSelection != null) selected.addAll(initialSelection);
        this.listener = listener;
        this.previewListener = previewListener;
        setHasStableIds(true);
    }

    public void replace(List<MediaRepository.MediaItem> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    public Set<Uri> selection() { return new HashSet<>(selected); }

    @Override public long getItemId(int position) {
        return items.get(position).uri.toString().hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(parent, 4), dp(parent, 4), dp(parent, 4), dp(parent, 4));

        FrameLayout preview = new FrameLayout(parent.getContext());
        ImageView image = new ImageView(parent.getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));

        TextView play = new TextView(parent.getContext());
        play.setText("▶");
        play.setTextColor(Color.WHITE);
        play.setTextSize(22);
        play.setGravity(Gravity.CENTER);
        play.setContentDescription("Preview video");
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(Color.argb(155, 0, 0, 0));
        playBg.setShape(GradientDrawable.OVAL);
        play.setBackground(playBg);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(parent, 44), dp(parent, 44), Gravity.CENTER);
        preview.addView(play, playLp);

        TextView duration = new TextView(parent.getContext());
        duration.setTextColor(Color.WHITE);
        duration.setTextSize(10);
        duration.setGravity(Gravity.CENTER);
        duration.setPadding(dp(parent, 6), dp(parent, 2), dp(parent, 6), dp(parent, 2));
        GradientDrawable durationBg = new GradientDrawable();
        durationBg.setColor(Color.argb(190, 0, 0, 0));
        durationBg.setCornerRadius(dp(parent, 8));
        duration.setBackground(durationBg);
        FrameLayout.LayoutParams durationLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        durationLp.setMargins(0, 0, dp(parent, 7), dp(parent, 7));
        preview.addView(duration, durationLp);

        root.addView(preview, new LinearLayout.LayoutParams(-1, dp(parent, 124)));

        TextView label = new TextView(parent.getContext());
        label.setTextColor(Color.WHITE);
        label.setTextSize(10);
        label.setMaxLines(1);
        label.setGravity(Gravity.CENTER);
        root.addView(label, new LinearLayout.LayoutParams(-1, dp(parent, 28)));
        return new Holder(root, image, label, play, duration);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MediaRepository.MediaItem item = items.get(position);
        boolean video = item.durationMs > 0L;
        holder.label.setText(shortName(item.name));
        holder.image.setContentDescription(item.name + (video ? " video" : " image"));
        holder.play.setVisibility(video ? View.VISIBLE : View.GONE);
        holder.duration.setVisibility(video ? View.VISIBLE : View.GONE);
        if (video) holder.duration.setText(formatDuration(item.durationMs));

        Glide.with(holder.image)
                .load(item.uri)
                .placeholder(new ColorDrawable(Color.rgb(20, 42, 63)))
                .error(new ColorDrawable(Color.rgb(34, 45, 58)))
                .centerCrop()
                .into(holder.image);

        updateBackground(holder.root, selected.contains(item.uri));
        holder.root.setOnClickListener(v -> {
            if (!selected.add(item.uri)) selected.remove(item.uri);
            updateBackground(holder.root, selected.contains(item.uri));
            if (listener != null) listener.onSelectionChanged(selection());
        });
        holder.root.setOnLongClickListener(v -> {
            if (previewListener != null) previewListener.onPreview(item);
            return previewListener != null;
        });
        holder.play.setOnClickListener(v -> {
            if (previewListener != null) previewListener.onPreview(item);
        });
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        Glide.with(holder.image).clear(holder.image);
        super.onViewRecycled(holder);
    }

    @Override public int getItemCount() { return items.size(); }

    private static void updateBackground(LinearLayout root, boolean checked) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(checked ? Color.rgb(28, 91, 145) : Color.rgb(13, 33, 56));
        g.setCornerRadius(dp(root, 14));
        g.setStroke(dp(root, checked ? 3 : 1), checked ? Color.rgb(80, 207, 255) : Color.rgb(35, 64, 90));
        root.setBackground(g);
    }

    private static String formatDuration(long durationMs) {
        long seconds = Math.max(0L, durationMs / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%d:%02d", minutes, secs);
    }

    private static int dp(android.view.View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static String shortName(String value) {
        if (value == null) return "media";
        return value.length() <= 18 ? value : value.substring(0, 15) + "…";
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final ImageView image;
        final TextView label;
        final TextView play;
        final TextView duration;

        Holder(LinearLayout root, ImageView image, TextView label, TextView play, TextView duration) {
            super(root);
            this.root = root;
            this.image = image;
            this.label = label;
            this.play = play;
            this.duration = duration;
        }
    }
}
