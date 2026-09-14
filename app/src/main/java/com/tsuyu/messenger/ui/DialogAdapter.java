package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.util.Fmt;
import com.tsuyu.messenger.util.Ui;

import java.util.ArrayList;
import java.util.List;

public class DialogAdapter extends RecyclerView.Adapter<DialogAdapter.VH> {

    public interface OnClick { void onClick(Models.Dialog d); }

    private final Context ctx;
    private final List<Models.Dialog> items;
    private final OnClick onClick;
    private final Prefs prefs;

    public DialogAdapter(Context ctx, List<Models.Dialog> items, OnClick onClick) {
        this.ctx = ctx;
        this.items = items;
        this.onClick = onClick;
        this.prefs = new Prefs(ctx);
        setHasStableIds(true);
    }

    public void submit(List<Models.Dialog> next) {
        List<Models.Dialog> old = new ArrayList<>(items);
        DiffUtil.DiffResult res = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return old.get(o).peerUid.equals(next.get(n).peerUid);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                Models.Dialog a = old.get(o), b = next.get(n);
                if (a.typing != b.typing) return false;
                if (a.peer != null && b.peer != null) {
                    if (a.peer.online != b.peer.online) return false;
                    if (a.peer.lastSeen != b.peer.lastSeen) return false;
                    if (!eq(a.peer.name, b.peer.name)) return false;
                    if (!eq(a.peer.avatar, b.peer.avatar)) return false;
                }
                long at = a.last == null ? 0 : a.last.ts;
                long bt = b.last == null ? 0 : b.last.ts;
                if (at != bt) return false;
                String ap = a.last == null ? "" : String.valueOf(a.last.text);
                String bp = b.last == null ? "" : String.valueOf(b.last.text);
                return eq(ap, bp);
            }
        });
        items.clear();
        items.addAll(next);
        res.dispatchUpdatesTo(this);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override public long getItemId(int position) {
        return items.get(position).peerUid.hashCode();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_dialog, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.Dialog d = items.get(pos);
        Models.User u = d.peer;

        h.name.setText(u == null ? "…" : u.name);
        Ui.setAvatar(h.avatar, u == null ? null : u.avatar, d.peerUid,
                u == null ? "?" : u.name);

        boolean online = u != null && u.online;
        h.onlineDot.setBackgroundResource(online
                ? R.drawable.bg_online_dot : R.drawable.bg_offline_dot);

        // ---- preview line ----
        h.previewThumb.setVisibility(View.GONE);
        if (d.typing) {
            h.preview.setText(prefs.wordTyping() + "…");
            h.preview.setTextColor(0xFF0A84FF);
        } else if (d.last == null) {
            h.preview.setText("");
            h.preview.setTextColor(0xFF888888);
        } else {
            h.preview.setTextColor(0xFF888888);
            Models.Message m = d.last;
            String prefix = m.outgoing ? "Вы: " : "";
            if (m.failed) {
                h.preview.setText(prefix + "🔒 Зашифровано");
            } else if (m.deleted) {
                h.preview.setText(prefix + "Сообщение удалено");
            } else if (!m.attachments.isEmpty()) {
                Models.Attachment a = m.attachments.get(0);
                String label;
                switch (a.type) {
                    case Models.T_PHOTO: label = "Фото"; break;
                    case Models.T_VIDEO: label = "Видео"; break;
                    case Models.T_VOICE: label = "Голосовое сообщение"; break;
                    case Models.T_CIRCLE: label = "Видеосообщение"; break;
                    case Models.T_AUDIO: label = "Аудиофайл"; break;
                    default: label = "Вложение";
                }
                if (m.attachments.size() > 1) label += " (" + m.attachments.size() + ")";
                if (m.text != null && !m.text.isEmpty()) label += ": " + m.text;
                h.preview.setText(prefix + label);

                // blurred thumbnail like Telegram
                String thumbSrc = a.thumb != null ? a.thumb
                        : (Models.T_PHOTO.equals(a.type) ? a.data : null);
                if (thumbSrc != null) {
                    Bitmap bmp = Ui.decodeB64(thumbSrc);
                    if (bmp != null) {
                        h.previewThumb.setImageBitmap(blur(bmp));
                        h.previewThumb.setVisibility(View.VISIBLE);
                    }
                }
            } else {
                h.preview.setText(prefix + (m.text == null ? "" : m.text));
            }
        }

        h.time.setText(d.last == null ? "" : Fmt.time(d.last.ts));

        int unread = d.unread;
        if (unread > 0) {
            h.unread.setVisibility(View.VISIBLE);
            h.unread.setText(String.valueOf(unread));
        } else {
            h.unread.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> onClick.onClick(d));
    }

    /** Cheap downscale-upscale blur for dialog previews. */
    private Bitmap blur(Bitmap src) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(src, 8, 8, true);
            return Bitmap.createScaledBitmap(small, 40, 40, true);
        } catch (Exception e) {
            return src;
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView avatar, previewThumb;
        View onlineDot;
        TextView name, preview, time, unread;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            previewThumb = v.findViewById(R.id.previewThumb);
            onlineDot = v.findViewById(R.id.onlineDot);
            name = v.findViewById(R.id.name);
            preview = v.findViewById(R.id.preview);
            time = v.findViewById(R.id.time);
            unread = v.findViewById(R.id.unread);
        }
    }
}
