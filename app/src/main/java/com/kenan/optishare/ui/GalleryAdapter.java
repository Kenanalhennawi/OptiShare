package com.kenan.optishare.ui;

import android.graphics.Color;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Size;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kenan.optishare.storage.MediaRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.Holder> {
    public interface Listener { void onSelectionChanged(Set<Uri> selected); }

    private final List<MediaRepository.MediaItem> items = new ArrayList<>();
    private final Set<Uri> selected = new HashSet<>();
    private final Listener listener;
    private final String mediaType;

    public GalleryAdapter(String mediaType, Set<Uri> initialSelection, Listener listener) {
        this.mediaType = mediaType == null ? "image" : mediaType;
        if (initialSelection != null) selected.addAll(initialSelection);
        this.listener = listener;
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

        ImageView image = new ImageView(parent.getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(image, new LinearLayout.LayoutParams(-1, dp(parent, 112)));

        TextView label = new TextView(parent.getContext());
        label.setTextColor(Color.WHITE);
        label.setTextSize(10);
        label.setMaxLines(1);
        label.setGravity(Gravity.CENTER);
        root.addView(label, new LinearLayout.LayoutParams(-1, dp(parent, 28)));
        return new Holder(root, image, label);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MediaRepository.MediaItem item = items.get(position);
        holder.label.setText(shortName(item.name));
        holder.image.setImageDrawable(null);
        if ("audio".equals(mediaType)) {
            holder.image.setBackgroundColor(Color.rgb(18, 50, 78));
            holder.image.setImageResource(android.R.drawable.ic_media_play);
            holder.image.setScaleType(ImageView.ScaleType.CENTER);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Bitmap thumbnail = holder.image.getContext().getContentResolver()
                        .loadThumbnail(item.uri, new Size(320, 240), null);
                holder.image.setImageBitmap(thumbnail);
            } catch (Exception ignored) {
                holder.image.setImageURI(item.uri);
            }
        } else {
            if ("video".equals(mediaType)) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(holder.image.getContext(), item.uri);
                    Bitmap frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) holder.image.setImageBitmap(frame);
                    else holder.image.setImageResource(android.R.drawable.ic_media_play);
                } catch (Exception ignored) {
                    holder.image.setImageResource(android.R.drawable.ic_media_play);
                } finally {
                    try { retriever.release(); } catch (Exception ignored) { }
                }
            } else holder.image.setImageURI(item.uri);
        }
        updateBackground(holder.root, selected.contains(item.uri));
        holder.root.setOnClickListener(v -> {
            if (!selected.add(item.uri)) selected.remove(item.uri);
            updateBackground(holder.root, selected.contains(item.uri));
            if (listener != null) listener.onSelectionChanged(selection());
        });
    }

    @Override public int getItemCount() { return items.size(); }

    private static void updateBackground(LinearLayout root, boolean checked) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(checked ? Color.rgb(29, 92, 145) : Color.rgb(13, 33, 56));
        g.setCornerRadius(dp(root, 14));
        g.setStroke(dp(root, checked ? 3 : 1), checked ? Color.rgb(80, 207, 255) : Color.rgb(35, 64, 90));
        root.setBackground(g);
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
        Holder(LinearLayout root, ImageView image, TextView label) {
            super(root);
            this.root = root;
            this.image = image;
            this.label = label;
        }
    }
}
