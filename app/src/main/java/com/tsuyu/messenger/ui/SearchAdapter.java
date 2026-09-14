package com.tsuyu.messenger.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.util.Ui;

import java.util.List;

public class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.VH> {

    public interface OnClick { void onClick(Models.User u); }

    private final Context ctx;
    private final List<Models.User> items;
    private final OnClick onClick;

    public SearchAdapter(Context ctx, List<Models.User> items, OnClick onClick) {
        this.ctx = ctx;
        this.items = items;
        this.onClick = onClick;
    }

    public void submit(List<Models.User> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_search_user, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.User u = items.get(pos);
        h.name.setText(u.name);
        h.username.setText("@" + (u.username == null ? "" : u.username));
        Ui.setAvatar(h.avatar, u.avatar, u.uid, u.name);
        h.itemView.setOnClickListener(v -> onClick.onClick(u));
        // staggered entry animation, like the web search results
        h.itemView.setAlpha(0f);
        h.itemView.setTranslationY(14f);
        h.itemView.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(pos, 8) * 28L).setDuration(190).start();
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView name, username;
        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.name);
            username = v.findViewById(R.id.username);
        }
    }
}
