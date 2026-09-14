package io.tsuyu.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import io.tsuyu.app.R;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.model.Msg;
import io.tsuyu.app.util.Ui;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = "TsuyuAdapter";
    private final ChatActivity act;
    private final List<Object> rows;
    private final Map<String, View> voiceViews = new HashMap<>();
    private MediaPlayerManager mp = new MediaPlayerManager();

    ChatAdapter(ChatActivity a, List<Object> r) {
        this.act = a;
        this.rows = r;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View root;
        android.widget.LinearLayout innerRoot;
        LinearLayout bubble;
        LinearLayout replyQuote;
        TextView tvReplyName, tvReplyText;
        TextView tvText;
        FrameLayout mediaContainer;
        TextView tvMsgTime, tvEdited;
        ImageView ivStatus;
        TextView btnMore;
        LinearLayout reactionsRow;
        ViewHolder(View v) {
            super(v);
            root = v;
            innerRoot = v.findViewById(R.id.root);
            bubble = v.findViewById(R.id.bubble);
            replyQuote = v.findViewById(R.id.replyQuote);
            tvReplyName = v.findViewById(R.id.tvReplyName);
            tvReplyText = v.findViewById(R.id.tvReplyText);
            tvText = v.findViewById(R.id.tvText);
            mediaContainer = v.findViewById(R.id.mediaContainer);
            tvMsgTime = v.findViewById(R.id.tvMsgTime);
            tvEdited = v.findViewById(R.id.tvEdited);
            ivStatus = v.findViewById(R.id.ivStatus);
            btnMore = v.findViewById(R.id.btnMore);
            reactionsRow = v.findViewById(R.id.reactionsRow);
        }
    }

    static class DateHolder extends RecyclerView.ViewHolder {
        TextView tvDate;
        DateHolder(View v) { super(v); tvDate = v.findViewById(R.id.tvDate); }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 1) {
            return new DateHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_date_sep, parent, false));
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_msg, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof Long ? 1 : 0;
    }

    @Override
    public int getItemCount() {
        return rows == null ? 0 : rows.size();
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof ViewHolder) {
            ViewHolder h = (ViewHolder) holder;
            try {
                // stop players bound to this view
                for (Map.Entry<String, View> e : voiceViews.entrySet()) {
                    if (e.getValue() == h.root) {
                        mp.stopFor(h.root);
                        voiceViews.remove(e.getKey());
                    }
                }
                h.mediaContainer.removeAllViews();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (row instanceof Long) {
            ((DateHolder) holder).tvDate.setText(Ui.dateLabel((Long) row));
            return;
        }
        Msg m = (Msg) row;
        ViewHolder h = (ViewHolder) holder;
        try {
            boolean mine = m.from.equals(Fb.myUid());
            h.root.animate().cancel();
            h.root.setAlpha(1f);
            // bubble background
            h.bubble.setBackgroundResource(mine ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
            if (h.innerRoot != null) h.innerRoot.setGravity(mine ? Gravity.END : Gravity.START);
            h.bubble.setMaxWidth(act.dp((int) (act.getResources().getDisplayMetrics().widthPixels * 0.78)));
            // hidden by me / deleted
            if (m.hiddenByMe || m.deletedForAll > 0) {
                h.root.setAlpha(0.45f);
            }
            // reply quote
            if (m.payload != null && m.payload.has("replyName")) {
                h.replyQuote.setVisibility(View.VISIBLE);
                h.tvReplyName.setText(m.payload.getString("replyName"));
                h.tvReplyText.setText(m.payload.optString("replyText", ""));
            } else {
                h.replyQuote.setVisibility(View.GONE);
            }
            // forward label
            if (m.fwdFrom != null) {
                h.tvText.setText("Переслано от " + act.peerName(m.fwdFrom) + "\n" + (m.text() != null ? m.text() : ""));
            }
            // media / text
            h.mediaContainer.removeAllViews();
            boolean isMedia = "photo".equals(m.type) || "video".equals(m.type) || "voice".equals(m.type)
                    || "music".equals(m.type) || "circle".equals(m.type) || "collage".equals(m.type);
            if (isMedia) {
                h.tvText.setVisibility(View.GONE);
                renderMedia(h, m);
            } else if ("call".equals(m.type)) {
                h.tvText.setVisibility(View.VISIBLE);
                h.tvText.setText("📞 Звонок");
                Ui.applyFont(h.tvText, 14f);
            } else {
                String tx = m.text();
                if (tx == null) {
                    if (m.decryptState == 2) tx = "🔒 Расшифровка...";
                    else if (m.decryptState == 3) tx = "🔒 Сообщение (нельзя расшифровать)";
                    else tx = "";
                }
                h.tvText.setVisibility(View.VISIBLE);
                h.tvText.setText(tx);
                Ui.applyFont(h.tvText, Ui.textSizeSp());
            }
            // time + status
            h.tvMsgTime.setText(Ui.timeHM(m.ts));
            h.tvEdited.setVisibility(m.editedTs > 0 ? View.VISIBLE : View.GONE);
            if (mine) {
                h.ivStatus.setVisibility(View.VISIBLE);
                if (m.readByMe > 0) {
                    h.ivStatus.setImageResource(R.drawable.ic_check_double);
                    h.ivStatus.setColorFilter(0xFF0A84FF);
                } else if (m.deliveredToMe > 0) {
                    h.ivStatus.setImageResource(R.drawable.ic_check_double);
                    h.ivStatus.setColorFilter(0xFF34C759);
                } else {
                    h.ivStatus.setImageResource(R.drawable.ic_check);
                    h.ivStatus.setColorFilter(0xFF8E8E93);
                }
            } else {
                h.ivStatus.setVisibility(View.GONE);
            }
            // reactions
            renderReactions(h, m);
            // more button
            h.btnMore.setOnClickListener(v -> act.onMsgMore(m, v));
            // double tap = heart
            if (isMedia == false && "call".equals(m.type) == false) {
                final Msg fm = m;
                h.bubble.setOnClickListener(null);
                h.root.setOnClickListener(null);
                GestureDetector gd = new GestureDetector(h.root.getContext(), new GestureDetector.SimpleOnDoubleTapListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        act.toggleReaction(fm);
                        h.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        return true;
                    }
                });
                h.bubble.setOnTouchListener((v, event) -> {
                    gd.onTouchEvent(event);
                    return false;
                });
            } else {
                h.bubble.setOnTouchListener(null);
            }
            // animate in
            h.root.setTranslationY(8);
            h.root.setAlpha(0);
            h.root.animate().translationY(0).alpha(1).setDuration(160).start();
        } catch (Throwable t) {
            Log.e(TAG, "bind", t);
        }
    }

    private void renderReactions(ViewHolder h, Msg m) {
        try {
            h.reactionsRow.removeAllViews();
            if (m.reactions == null || m.reactions.length() == 0) {
                h.reactionsRow.setVisibility(View.GONE);
                return;
            }
            h.reactionsRow.setVisibility(View.VISIBLE);
            TextView heart = new TextView(act);
            heart.setText("❤️");
            heart.setTextSize(13);
            h.reactionsRow.addView(heart);
            TextView count = new TextView(act);
            count.setText(" " + m.reactions.length());
            count.setTextColor(0xFFAAAAAA);
            count.setTextSize(11);
            h.reactionsRow.addView(count);
            // small avatars of reactors (max 2, overlapping)
            int n = 0;
            java.util.Iterator<String> it = m.reactions.keys();
            FrameLayout avWrap = null;
            while (it.hasNext() && n < 2) {
                String uid = it.next();
                io.tsuyu.app.model.MeowUser u = Fb.userCache.get(uid);
                int size = act.dp(18);
                FrameLayout fl = new FrameLayout(act);
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(size, size);
                flp.leftMargin = n == 0 ? act.dp(5) : -act.dp(7);
                fl.setLayoutParams(flp);
                View bg = new View(act);
                bg.setBackgroundResource(R.drawable.bg_avatar_default);
                fl.addView(bg, new FrameLayout.LayoutParams(size, size));
                TextView letter = new TextView(act);
                letter.setText(u == null ? "?" : u.letter());
                letter.setTextColor(0xFFFFFFFF);
                letter.setTextSize(9);
                letter.setGravity(Gravity.CENTER);
                letter.setTypeface(null, android.graphics.Typeface.BOLD);
                fl.addView(letter, new FrameLayout.LayoutParams(size, size));
                if (u != null && u.avatarB64 != null) {
                    try {
                        byte[] d = Base64.decode(u.avatarB64, Base64.DEFAULT);
                        Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                        if (bm != null) {
                            ImageView iv = new ImageView(act);
                            iv.setImageBitmap(bm);
                            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            GradientDrawable gd = new GradientDrawable();
                            gd.setShape(GradientDrawable.OVAL);
                            iv.setBackground(gd);
                            fl.addView(iv, new FrameLayout.LayoutParams(size, size));
                        }
                    } catch (Throwable ignored) {}
                }
                h.reactionsRow.addView(fl);
                n++;
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- media rendering ----------------
    private void renderMedia(ViewHolder h, Msg m) {
        try {
            Context ctx = h.root.getContext();
            int maxW = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.72);
            switch (m.type) {
                case "photo": {
                    byte[] data = decodeMedia(m);
                    if (data == null) return;
                    ImageView iv = new ImageView(ctx);
                    Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bm == null) return;
                    int w = bm.getWidth(), hh = bm.getHeight();
                    int dw = Math.min(maxW, w);
                    int dh = (int) (dw * (double) hh / Math.max(1, w));
                    if (dh > maxW) {
                        dh = maxW;
                        dw = (int) (dh * (double) w / Math.max(1, hh));
                    }
                    iv.setImageBitmap(bm);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setLayoutParams(new FrameLayout.LayoutParams(dw, Math.min(dh, act.dp(320))));
                    roundCorners(iv, act.dp(12));
                    // time badge over photo (TG style)
                    FrameLayout wrap = h.mediaContainer;
                    wrap.addView(iv);
                    TextView time = timeBadge(m.ts, mine(m));
                    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    tlp.gravity = Gravity.BOTTOM | Gravity.END;
                    tlp.bottomMargin = act.dp(6);
                    tlp.rightMargin = act.dp(8);
                    wrap.addView(time, tlp);
                    iv.setOnClickListener(v -> openViewer(m, data));
                    break;
                }
                case "video": {
                    byte[] data = decodeMedia(m);
                    if (data == null) return;
                    int w = m.w(), hh = m.h();
                    int dw = Math.min(maxW, act.dp(300));
                    int dh = hh > 0 ? (int) (dw * (double) hh / Math.max(1, w)) : act.dp(180);
                    dh = Math.min(dh, act.dp(300));
                    FrameLayout wrap = h.mediaContainer;
                    wrap.setLayoutParams(new FrameLayout.LayoutParams(dw, dh));
                    // poster (first frame from cache or black)
                    View bg = new View(ctx);
                    bg.setBackgroundColor(0xFF0A0A0A);
                    wrap.addView(bg, new FrameLayout.LayoutParams(dw, dh));
                    File cached = cachedVideoFile(m);
                    if (cached != null) {
                        Bitmap frame = Ui.firstVideoFrameLocal(cached, dw);
                        if (frame != null) {
                            ImageView poster = new ImageView(ctx);
                            poster.setImageBitmap(frame);
                            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            wrap.addView(poster, new FrameLayout.LayoutParams(dw, dh));
                            frame.recycle();
                        }
                    }
                    // play button
                    View play = new FrameLayout(ctx);
                    int ps = act.dp(56);
                    FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ps, ps);
                    plp.gravity = Gravity.CENTER;
                    play.setLayoutParams(plp);
                    play.setBackgroundResource(R.drawable.bg_play_overlay);
                    ImageView pi = new ImageView(ctx);
                    pi.setImageResource(R.drawable.ic_play_white);
                    pi.setColorFilter(0xFFFFFFFF);
                    pi.setScaleType(ImageView.ScaleType.CENTER);
                    play.addView(pi, new FrameLayout.LayoutParams(ps, ps));
                    wrap.addView(play);
                    // duration
                    TextView dur = timeBadgeText(Ui.durText(m.dur()));
                    FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    dlp.gravity = Gravity.BOTTOM | Gravity.START;
                    dlp.bottomMargin = act.dp(6);
                    dlp.leftMargin = act.dp(6);
                    wrap.addView(dur, dlp);
                    wrap.setOnClickListener(v -> openVideo(m, data));
                    break;
                }
                case "circle": {
                    byte[] data = decodeMedia(m);
                    if (data == null) return;
                    int size = act.dp(190);
                    FrameLayout wrap = h.mediaContainer;
                    wrap.setLayoutParams(new FrameLayout.LayoutParams(size, size));
                    View bg = new View(ctx);
                    bg.setBackgroundColor(0xFF111111);
                    GradientDrawable oval = new GradientDrawable();
                    oval.setShape(GradientDrawable.OVAL);
                    bg.setBackground(oval);
                    wrap.addView(bg, new FrameLayout.LayoutParams(size, size));
                    File cached = cachedVideoFile(m);
                    if (cached != null) {
                        Bitmap frame = Ui.firstVideoFrameLocal(cached, size);
                        if (frame != null) {
                            Bitmap square = Bitmap.createBitmap(frame,
                                    Math.max(0, frame.getWidth() / 2 - frame.getHeight() / 2),
                                    frame.getHeight() / 2, frame.getHeight(), frame.getHeight());
                            ImageView poster = new ImageView(ctx);
                            poster.setImageBitmap(square);
                            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            oval2(poster);
                            wrap.addView(poster, new FrameLayout.LayoutParams(size, size));
                            square.recycle();
                            frame.recycle();
                        }
                    }
                    View ring = new View(ctx);
                    ring.setBackgroundResource(R.drawable.bg_circle_ring);
                    wrap.addView(ring, new FrameLayout.LayoutParams(size, size));
                    View play = new FrameLayout(ctx);
                    int ps = act.dp(52);
                    FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ps, ps);
                    plp.gravity = Gravity.CENTER;
                    play.setLayoutParams(plp);
                    play.setBackgroundResource(R.drawable.bg_play_overlay);
                    ImageView pi = new ImageView(ctx);
                    pi.setImageResource(R.drawable.ic_play_white);
                    pi.setColorFilter(0xFFFFFFFF);
                    play.addView(pi, new FrameLayout.LayoutParams(ps, ps));
                    wrap.addView(play);
                    TextView time = timeBadge(m.ts, true);
                    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    tlp.gravity = Gravity.BOTTOM | Gravity.END;
                    tlp.bottomMargin = act.dp(14);
                    tlp.rightMargin = act.dp(14);
                    wrap.addView(time, tlp);
                    wrap.setOnClickListener(v -> openVideo(m, data));
                    break;
                }
                case "voice": {
                    buildVoice(h, m);
                    break;
                }
                case "music": {
                    buildMusic(h, m);
                    break;
                }
                case "collage": {
                    buildCollage(h, m, maxW);
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "renderMedia", t);
        }
    }

    private boolean mine(Msg m) {
        return m.from.equals(Fb.myUid());
    }

    private void oval2(ImageView iv) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        iv.setBackground(g);
        iv.setClipToOutline(true);
    }

    private void roundCorners(ImageView iv, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        iv.setBackground(g);
        iv.setClipToOutline(true);
    }

    private TextView timeBadge(long ts, boolean out) {
        TextView t = new TextView(act);
        t.setText(Ui.timeHM(ts));
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(10);
        t.setBackgroundResource(R.drawable.bg_media_time);
        t.setPadding(act.dp(6), act.dp(2), act.dp(6), act.dp(2));
        return t;
    }

    private TextView timeBadgeText(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(11);
        t.setBackgroundResource(R.drawable.bg_media_time);
        t.setPadding(act.dp(7), act.dp(2), act.dp(7), act.dp(2));
        return t;
    }

    private byte[] decodeMedia(Msg m) {
        try {
            if (m.payload == null || m.payload.optString("m", null) == null) return null;
            String hash = io.tsuyu.app.core.Crypto.sha256Hex(m.payload.getString("m").getBytes("UTF-8"));
            File f = Ui.mediaFile(act, m.type, hash);
            if (f.exists() && f.length() > 0) return Ui.readFile(f);
            byte[] data = Base64.decode(m.payload.getString("m"), Base64.DEFAULT);
            Ui.writeMedia(act, m.type, hash, data);
            return data;
        } catch (Throwable t) {
            return null;
        }
    }

    private File cachedVideoFile(Msg m) {
        try {
            String b64 = m.payload == null ? null : m.payload.optString("m", null);
            if (b64 == null) return null;
            String hash = io.tsuyu.app.core.Crypto.sha256Hex(b64.getBytes("UTF-8"));
            return Ui.mediaFile(act, m.type, hash);
        } catch (Throwable t) {
            return null;
        }
    }

    private void openViewer(Msg m, byte[] data) {
        try {
            String b64 = m.payload.getString("m");
            String hash = io.tsuyu.app.core.Crypto.sha256Hex(b64.getBytes("UTF-8"));
            File f = Ui.mediaFile(act, "photo", hash);
            if (f.exists() == false) {
                f = Ui.writeMedia(act, "photo", hash, data);
            }
            if (f == null) return;
            android.content.Intent i = new android.content.Intent(act, ImageViewerActivity.class);
            i.putExtra("path", f.getAbsolutePath());
            i.putExtra("info", (m.from.equals(Fb.myUid()) ? "Вы" : act.peerName(m.from)) + " • " + Ui.timeHM(m.ts));
            act.startActivity(i);
        } catch (Throwable t) {
            Ui.toast(act, "Ошибка");
        }
    }

    private void openVideo(Msg m, byte[] data) {
        try {
            String b64 = m.payload.getString("m");
            String hash = io.tsuyu.app.core.Crypto.sha256Hex(b64.getBytes("UTF-8"));
            File f = Ui.mediaFile(act, m.type, hash);
            if (f.exists() == false) {
                f = Ui.writeMedia(act, m.type, hash, data);
            }
            if (f == null) return;
            android.content.Intent i = new android.content.Intent(act, VideoPlayerActivity.class);
            i.putExtra("path", f.getAbsolutePath());
            i.putExtra("circle", "circle".equals(m.type));
            act.startActivity(i);
        } catch (Throwable t) {
            Ui.toast(act, "Ошибка");
        }
    }

    // ---------------- voice ----------------
    private void buildVoice(ViewHolder h, Msg m) {
        try {
            Context ctx = h.root.getContext();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new FrameLayout.LayoutParams(act.dp(230), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(0, act.dp(6), 0, act.dp(6));

            FrameLayout playBtn = new FrameLayout(ctx);
            int bs = act.dp(40);
            playBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
            playBtn.setBackgroundResource(R.drawable.bg_circle_btn);
            ImageView pi = new ImageView(ctx);
            pi.setImageResource(R.drawable.ic_play);
            pi.setColorFilter(0xFF000000);
            playBtn.addView(pi, new FrameLayout.LayoutParams(bs, bs));
            row.addView(playBtn);

            // waveform
            LinearLayout wave = new LinearLayout(ctx);
            wave.setOrientation(LinearLayout.HORIZONTAL);
            wave.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, act.dp(26), 1);
            wlp.leftMargin = act.dp(10);
            wave.setLayoutParams(wlp);
            double[] w = m.wave();
            if (w == null || w.length == 0) {
                w = new double[28];
                java.util.Random rnd = new java.util.Random(m.ts);
                for (int i = 0; i < w.length; i++) w[i] = 0.25 + rnd.nextDouble() * 0.6;
            }
            for (int i = 0; i < w.length; i++) {
                View bar = new View(ctx);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(2, act.dp(4 + (int) (w[i] * 20)));
                blp.rightMargin = 2;
                bar.setLayoutParams(blp);
                bar.setBackgroundResource(R.drawable.bg_wave_inactive);
                wave.addView(bar);
            }
            row.addView(wave);

            TextView dur = new TextView(ctx);
            dur.setText(Ui.durText(m.dur()));
            dur.setTextColor(0xFFAAAAAA);
            dur.setTextSize(10);
            LinearLayout.LayoutParams durLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            durLp.leftMargin = act.dp(8);
            dur.setLayoutParams(durLp);
            row.addView(dur);

            h.mediaContainer.addView(row);
            voiceViews.put(m.key, row);
            playBtn.setOnClickListener(v -> toggleVoice(m, row, playBtn, wave, pi));
        } catch (Throwable t) {
            Log.e(TAG, "buildVoice", t);
        }
    }

    private void toggleVoice(Msg m, LinearLayout row, FrameLayout playBtn, LinearLayout wave, ImageView pi) {
        try {
            if (mp.isPlayingFor(row)) {
                mp.stopFor(row);
                pi.setImageResource(R.drawable.ic_play);
                return;
            }
            byte[] data = decodeMedia(m);
            if (data == null) { Ui.toast(act, "Нет данных"); return; }
            File f = Ui.writeMedia(act, "voice_tmp", m.key, data);
            if (f == null) return;
            mp.play(act, f, row, new MediaPlayerManager.Listener() {
                @Override public void onProgress(float frac) {
                    try {
                        int n = wave.getChildCount();
                        int active = (int) (frac * n);
                        for (int i = 0; i < n; i++) {
                            View bar = wave.getChildAt(i);
                            bar.setBackgroundResource(i <= active ? R.drawable.bg_wave_active : R.drawable.bg_wave_inactive);
                        }
                    } catch (Throwable ignored) {}
                }
                @Override public void onDone() {
                    try {
                        pi.setImageResource(R.drawable.ic_play);
                    } catch (Throwable ignored) {}
                }
            });
            pi.setImageResource(R.drawable.ic_pause);
        } catch (Throwable t) {
            Log.e(TAG, "toggleVoice", t);
        }
    }

    // ---------------- music ----------------
    private void buildMusic(ViewHolder h, Msg m) {
        try {
            Context ctx = h.root.getContext();
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.bg_music_card);
            card.setLayoutParams(new FrameLayout.LayoutParams(act.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(10));

            FrameLayout playBtn = new FrameLayout(ctx);
            int bs = act.dp(44);
            playBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
            playBtn.setBackgroundResource(R.drawable.bg_call_btn_mute);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF0A84FF);
            playBtn.setBackground(g);
            ImageView pi = new ImageView(ctx);
            pi.setImageResource(R.drawable.ic_play_white);
            pi.setColorFilter(0xFFFFFFFF);
            playBtn.addView(pi, new FrameLayout.LayoutParams(bs, bs));
            card.addView(playBtn);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            ilp.leftMargin = act.dp(10);
            info.setLayoutParams(ilp);
            TextView title = new TextView(ctx);
            title.setText(m.name() != null ? m.name() : "Музыка");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(13);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setSingleLine(true);
            info.addView(title);
            TextView artist = new TextView(ctx);
            artist.setText("Tsuyu • аудио");
            artist.setTextColor(0xFF8E8E93);
            artist.setTextSize(11);
            info.addView(artist);
            // straight progress (TG style)
            FrameLayout track = new FrameLayout(ctx);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, act.dp(3));
            tlp.topMargin = act.dp(6);
            track.setLayoutParams(tlp);
            track.setBackgroundResource(R.drawable.bg_progress_track);
            View bar = new View(ctx);
            bar.setBackgroundColor(0xFF0A84FF);
            bar.setLayoutParams(new FrameLayout.LayoutParams(0, act.dp(3)));
            track.addView(bar);
            info.addView(track);
            TextView tm = new TextView(ctx);
            tm.setText("0:00");
            tm.setTextColor(0xFF666666);
            tm.setTextSize(10);
            info.addView(tm);

            card.addView(info);
            h.mediaContainer.addView(card);
            playBtn.setOnClickListener(v -> toggleMusic(m, bar, pi, tm, track));
        } catch (Throwable t) {
            Log.e(TAG, "buildMusic", t);
        }
    }

    private void toggleMusic(Msg m, View bar, ImageView pi, TextView tm, FrameLayout track) {
        try {
            if (mp.isPlayingFor(bar)) {
                mp.stopFor(bar);
                pi.setImageResource(R.drawable.ic_play_white);
                return;
            }
            byte[] data = decodeMedia(m);
            if (data == null) { Ui.toast(act, "Нет данных"); return; }
            String ext = m.mediaType() == null ? "m4a" : m.mediaType();
            File f = Ui.writeMedia(act, "music_tmp", m.key, data);
            if (f == null) return;
            mp.play(act, f, bar, new MediaPlayerManager.Listener() {
                @Override public void onProgress(float frac) {
                    try {
                        int tw = track.getWidth();
                        bar.getLayoutParams().width = (int) (tw * frac);
                        bar.requestLayout();
                    } catch (Throwable ignored) {}
                }
                @Override public void onDone() {
                    try {
                        pi.setImageResource(R.drawable.ic_play_white);
                    } catch (Throwable ignored) {}
                }
            });
            pi.setImageResource(R.drawable.ic_pause);
        } catch (Throwable t) {
            Log.e(TAG, "toggleMusic", t);
        }
    }

    // ---------------- collage ----------------
    private void buildCollage(ViewHolder h, Msg m, int maxW) {
        try {
            Context ctx = h.root.getContext();
            JSONArray items = m.collage();
            if (items == null || items.length() == 0) return;
            int n = items.length();
            GridLayout grid = new GridLayout(ctx);
            grid.setColumnCount(2);
            int cell = Math.min(maxW / 2, act.dp(170));
            grid.setLayoutParams(new FrameLayout.LayoutParams(n == 1 ? cell : cell * 2 + 2, cell * 2 + 2));
            grid.setPadding(1, 1, 1, 1);
            for (int i = 0; i < n; i++) {
                JSONObject it = items.getJSONObject(i);
                ImageView iv = new ImageView(ctx);
                byte[] d = Base64.decode(it.getString("m"), Base64.DEFAULT);
                Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                iv.setImageBitmap(bm);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams(
                        GridLayout.spec(0), GridLayout.spec(i % 2));
                glp.width = cell;
                glp.height = cell;
                glp.setMargins(1, 1, 1, 1);
                iv.setLayoutParams(glp);
                roundCorners(iv, act.dp(4));
                final int idx = i;
                iv.setOnClickListener(v -> {
                    String b64 = null;
                    try { b64 = it.getString("m"); } catch (Throwable ignored) {}
                    if (b64 == null) return;
                    String hash = io.tsuyu.app.core.Crypto.sha256Hex(b64.getBytes("UTF-8"));
                    File f = Ui.mediaFile(act, "photo", hash);
                    if (f.exists() == false) f = Ui.writeMedia(act, "photo", hash, d);
                    if (f == null) return;
                    android.content.Intent ii = new android.content.Intent(act, ImageViewerActivity.class);
                    ii.putExtra("path", f.getAbsolutePath());
                    ii.putExtra("info", "Коллаж " + (idx + 1) + "/" + n + " • " + Ui.timeHM(m.ts));
                    act.startActivity(ii);
                });
                grid.addView(iv);
            }
            FrameLayout wrap = new FrameLayout(ctx);
            wrap.addView(grid);
            TextView time = timeBadge(m.ts, mine(m));
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.BOTTOM | Gravity.END;
            tlp.bottomMargin = act.dp(6);
            tlp.rightMargin = act.dp(8);
            wrap.addView(time, tlp);
            h.mediaContainer.addView(wrap);
        } catch (Throwable t) {
            Log.e(TAG, "buildCollage", t);
        }
    }
}
