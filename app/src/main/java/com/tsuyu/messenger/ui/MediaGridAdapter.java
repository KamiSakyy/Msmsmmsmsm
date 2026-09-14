package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.util.Fmt;
import com.tsuyu.messenger.util.Ui;

import java.util.List;

/** Telegram-style photo/video collage: hairline gaps, no outer border. */
public class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.VH> {

    public interface OnOpen { void open(int index); }

    private final Context ctx;
    private final List<Models.Attachment> items;
    private final int totalWidth;
    private final int span;
    private final OnOpen onOpen;

    public MediaGridAdapter(Context ctx, List<Models.Attachment> items,
                            int totalWidth, int span, OnOpen onOpen) {
        this.ctx = ctx;
        this.items = items;
        this.totalWidth = totalWidth;
        this.span = span;
        this.onOpen = onOpen;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_media_cell, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.Attachment a = items.get(pos);

        int gap = Ui.dp(ctx, 2);
        int cellW;
        int cellH;
        if (items.size() == 1) {
            cellW = totalWidth;
            cellH = a.height > 0 && a.width > 0
                    ? Math.min(Ui.dp(ctx, 280), (int) (totalWidth * (a.height / (float) a.width)))
                    : Ui.dp(ctx, 200);
        } else if (items.size() == 3 && pos == 0) {
            cellW = totalWidth;
            cellH = Ui.dp(ctx, 150);
        } else {
            cellW = (totalWidth - gap) / span;
            cellH = Ui.dp(ctx, 115);
        }

        ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(cellW, cellH);
        lp.width = cellW;
        lp.height = cellH;
        h.itemView.setLayoutParams(lp);

        String src = a.thumb != null ? a.thumb : a.data;
        Bitmap bmp = src == null ? null : Ui.decodeB64(src);
        if (bmp != null) h.image.setImageBitmap(bmp);

        boolean isVideo = Models.T_VIDEO.equals(a.type);
        h.playOverlay.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        if (isVideo && a.durationMs > 0) {
            h.duration.setVisibility(View.VISIBLE);
            h.duration.setText(Fmt.duration(a.durationMs));
        } else {
            h.duration.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> onOpen.open(pos));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView image;
        FrameLayout playOverlay;
        TextView duration;
        VH(@NonNull View v) {
            super(v);
            image = v.findViewById(R.id.mediaImage);
            playOverlay = v.findViewById(R.id.playOverlay);
            duration = v.findViewById(R.id.videoDuration);
        }
    }
}
