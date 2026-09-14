package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.media.AudioPlayer;
import com.tsuyu.messenger.media.WaveformView;
import com.tsuyu.messenger.util.Fmt;
import com.tsuyu.messenger.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    public interface Callbacks {
        void onMenu(Models.Message m, View anchor);
        void onDoubleTapHeart(Models.Message m);
        void onOpenMedia(Models.Message m, int index);
        void onReplyClick(String messageId);
    }

    private final Context ctx;
    private final List<Models.Message> items = new ArrayList<>();
    private final Callbacks cb;
    private final Prefs prefs;
    private final Map<String, String> avatarsByUid = new HashMap<>();
    private final Map<String, String> namesByUid = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public MessageAdapter(Context ctx, Callbacks cb) {
        this.ctx = ctx;
        this.cb = cb;
        this.prefs = new Prefs(ctx);
        setHasStableIds(true);
    }

    public void setPeerInfo(String uid, String name, String avatar) {
        namesByUid.put(uid, name);
        if (avatar != null) avatarsByUid.put(uid, avatar);
        notifyDataSetChanged();
    }

    public List<Models.Message> items() { return items; }

    public void submit(List<Models.Message> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) {
        String id = items.get(position).id;
        return id == null ? position : id.hashCode();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Models.Message m = items.get(pos);
        boolean out = m.outgoing;

        // ---- date separator ----
        boolean showDate = pos == 0 || !Fmt.sameDayTs(items.get(pos - 1).ts, m.ts);
        h.dateSeparator.setVisibility(showDate ? View.VISIBLE : View.GONE);
        if (showDate) h.dateSeparator.setText(Fmt.daySeparator(m.ts));

        // ---- alignment ----
        h.rowWrap.setGravity(out ? Gravity.END : Gravity.START);
        h.bubbleColumn.setGravity(out ? Gravity.END : Gravity.START);
        h.rowWrap.removeView(h.btnMsgMenu);
        h.rowWrap.addView(h.btnMsgMenu, out ? 0 : h.rowWrap.getChildCount());
        h.bubble.setBackgroundResource(out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);

        boolean bare = m.type.equals(Models.T_CIRCLE)
                || (m.attachments.size() > 0 && !hasText(m)
                    && (isVisual(m.attachments.get(0).type)));
        h.bubble.setBackgroundResource(bare ? 0
                : (out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in));
        int padH = bare ? 0 : Ui.dp(ctx, 12);
        int padV = bare ? 0 : Ui.dp(ctx, 8);
        h.bubble.setPadding(padH, padV, padH, padV);

        // ---- reset ----
        h.mediaWrap.setVisibility(View.GONE);
        h.circleWrap.setVisibility(View.GONE);
        h.voiceWrap.setVisibility(View.GONE);
        h.musicWrap.setVisibility(View.GONE);
        h.messageText.setVisibility(View.GONE);
        h.replyQuote.setVisibility(View.GONE);
        h.forwarded.setVisibility(View.GONE);
        h.heartsRow.setVisibility(View.GONE);
        h.editedMark.setVisibility(m.edited ? View.VISIBLE : View.GONE);

        // ---- forwarded / reply ----
        if (m.forwardedFrom != null && !m.forwardedFrom.isEmpty()) {
            h.forwarded.setVisibility(View.VISIBLE);
            h.forwarded.setText("Переслано от " + m.forwardedFrom);
        }
        if (m.replyTo != null) {
            h.replyQuote.setVisibility(View.VISIBLE);
            h.replyQuoteName.setText(m.replyName == null ? "Сообщение" : m.replyName);
            h.replyQuoteText.setText(m.replyPreview == null ? "" : m.replyPreview);
            h.replyQuote.setOnClickListener(v -> cb.onReplyClick(m.replyTo));
        }

        // ---- body ----
        if (m.failed) {
            h.messageText.setVisibility(View.VISIBLE);
            h.messageText.setText("🔒 Не удалось расшифровать");
            h.messageText.setTextColor(Color.parseColor("#FF9500"));
        } else if (m.deleted) {
            h.messageText.setVisibility(View.VISIBLE);
            h.messageText.setText("Сообщение удалено");
            h.messageText.setTextColor(Color.parseColor("#888888"));
        } else {
            bindContent(h, m);
        }

        // ---- time & read ----
        h.messageTime.setText(Fmt.time(m.ts));
        if (out) {
            h.readMark.setVisibility(View.VISIBLE);
            h.readMark.setImageResource(m.read ? R.drawable.ic_check_double : R.drawable.ic_check);
            h.readMark.setColorFilter(Color.parseColor(m.read ? "#FFFFFF" : "#CCDDEEFF"));
        } else {
            h.readMark.setVisibility(View.GONE);
        }

        // ---- hearts ----
        if (!m.hearts.isEmpty()) {
            h.heartsRow.setVisibility(View.VISIBLE);
            h.heartsRow.setLayoutParams(pillParams(out));
            renderHearts(h.heartAvatars, m.hearts);
            Ui.pop(h.heartsRow);
        }

        // ---- interactions ----
        h.btnMsgMenu.setOnClickListener(v -> cb.onMenu(m, v));
        attachDoubleTap(h.bubble, m);
    }

    private LinearLayout.LayoutParams pillParams(boolean out) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = -Ui.dp(ctx, 6);
        lp.gravity = out ? Gravity.END : Gravity.START;
        return lp;
    }

    private boolean hasText(Models.Message m) {
        return m.text != null && !m.text.trim().isEmpty();
    }

    private boolean isVisual(String t) {
        return Models.T_PHOTO.equals(t) || Models.T_VIDEO.equals(t);
    }

    private void bindContent(VH h, Models.Message m) {
        List<Models.Attachment> atts = m.attachments;

        if (!atts.isEmpty()) {
            Models.Attachment first = atts.get(0);
            switch (first.type) {
                case Models.T_CIRCLE:
                    bindCircle(h, m, first);
                    break;
                case Models.T_VOICE:
                    bindVoice(h, m, first);
                    break;
                case Models.T_AUDIO:
                    bindMusic(h, m, first);
                    break;
                default:
                    bindGrid(h, m, atts);
            }
        }

        if (hasText(m)) {
            h.messageText.setVisibility(View.VISIBLE);
            h.messageText.setText(m.text);
            h.messageText.setTextColor(Color.WHITE);
            Ui.applyTextStyle(h.messageText, prefs);
        }
    }

    // ---------- photo / video collage ----------

    private void bindGrid(VH h, Models.Message m, List<Models.Attachment> atts) {
        h.mediaWrap.setVisibility(View.VISIBLE);
        int maxW = Ui.dp(ctx, 250);
        int span = atts.size() == 1 ? 1 : 2;

        MediaGridAdapter ga = new MediaGridAdapter(ctx, atts, maxW, span,
                idx -> cb.onOpenMedia(m, idx));
        h.mediaGrid.setLayoutManager(new GridLayoutManager(ctx, span));
        if (atts.size() == 3) {
            ((GridLayoutManager) h.mediaGrid.getLayoutManager()).setSpanSizeLookup(
                    new GridLayoutManager.SpanSizeLookup() {
                        @Override public int getSpanSize(int position) {
                            return position == 0 ? 2 : 1;
                        }
                    });
        }
        h.mediaGrid.setAdapter(ga);
        ViewGroup.LayoutParams lp = h.mediaGrid.getLayoutParams();
        lp.width = maxW;
        h.mediaGrid.setLayoutParams(lp);
    }

    // ---------- circle video ----------

    private void bindCircle(VH h, Models.Message m, Models.Attachment a) {
        h.circleWrap.setVisibility(View.VISIBLE);
        h.circleVideo.removeAllViews();
        ImageView preview = new ImageView(ctx);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Bitmap thumb = a.thumb != null ? Ui.decodeB64(a.thumb) : null;
        if (thumb != null) preview.setImageBitmap(thumb);
        else preview.setBackgroundColor(Color.parseColor("#111111"));
        h.circleVideo.addView(preview);
        h.circleVideo.setOnClickListener(v -> cb.onOpenMedia(m, 0));
    }

    // ---------- voice ----------

    private void bindVoice(VH h, Models.Message m, Models.Attachment a) {
        h.voiceWrap.setVisibility(View.VISIBLE);
        h.voiceWave.setFlat(false);
        h.voiceWave.setBars(a.waveform);
        h.voiceWave.setColors(Color.WHITE, Color.parseColor("#4DFFFFFF"));
        h.voiceTime.setText(Fmt.duration(a.durationMs));

        float curSpd = AudioPlayer.get().getSpeed();
        if (h.voiceSpeed != null) {
            h.voiceSpeed.setText(curSpd == 1.0f ? "1X" : (curSpd == 1.5f ? "1.5X" : "2X"));
            h.voiceSpeed.setOnClickListener(v -> {
                Ui.tapScale(v);
                float s = AudioPlayer.get().getSpeed();
                float nextSpd;
                if (s <= 1.05f) nextSpd = 1.5f;
                else if (s <= 1.55f) nextSpd = 2.0f;
                else nextSpd = 1.0f;
                AudioPlayer.get().setSpeed(nextSpd);
                h.voiceSpeed.setText(nextSpd == 1.0f ? "1X" : (nextSpd == 1.5f ? "1.5X" : "2X"));
            });
        }

        String key = "v_" + m.id;
        boolean playing = AudioPlayer.get().isPlaying(key);
        h.voicePlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        AudioPlayer.Callback pc = new AudioPlayer.Callback() {
            @Override public void onProgress(float f, long posMs) {
                ui.post(() -> {
                    h.voiceWave.setProgress(f);
                    h.voiceTime.setText(Fmt.duration(posMs) + " / " + Fmt.duration(a.durationMs));
                });
            }
            @Override public void onStateChanged(boolean p) {
                ui.post(() -> h.voicePlay.setImageResource(
                        p ? R.drawable.ic_pause : R.drawable.ic_play));
            }
        };
        h.voicePlay.setOnClickListener(v -> {
            Ui.tapScale(v);
            AudioPlayer.get().toggle(ctx, key, a.data, pc);
        });
        h.voiceWave.setSeekListener(f -> {
            if (AudioPlayer.get().isPlaying(key)) AudioPlayer.get().seek(f);
        });
    }

    // ---------- music ----------

    private void bindMusic(VH h, Models.Message m, Models.Attachment a) {
        h.musicWrap.setVisibility(View.VISIBLE);
        h.musicTitle.setText(a.fileName == null ? "Аудио" : a.fileName);
        h.musicArtist.setText(a.artist == null ? "Неизвестный исполнитель" : a.artist);
        h.musicWave.setFlat(true);   // music = always a straight bar
        h.musicWave.setBars(new int[40]);
        h.musicWave.setColors(Color.WHITE, Color.parseColor("#3A3A3C"));
        h.musicTime.setText(Fmt.duration(a.durationMs));

        String key = "a_" + m.id;
        h.musicPlay.setImageResource(AudioPlayer.get().isPlaying(key)
                ? R.drawable.ic_pause : R.drawable.ic_play);

        AudioPlayer.Callback pc = new AudioPlayer.Callback() {
            @Override public void onProgress(float f, long posMs) {
                ui.post(() -> {
                    h.musicWave.setProgress(f);
                    h.musicTime.setText(Fmt.duration(posMs) + " / " + Fmt.duration(a.durationMs));
                });
            }
            @Override public void onStateChanged(boolean p) {
                ui.post(() -> h.musicPlay.setImageResource(
                        p ? R.drawable.ic_pause : R.drawable.ic_play));
            }
        };
        h.musicPlay.setOnClickListener(v -> {
            Ui.tapScale(v);
            AudioPlayer.get().toggle(ctx, key, a.data, pc);
        });
        h.musicWave.setSeekListener(f -> {
            if (AudioPlayer.get().isPlaying(key)) AudioPlayer.get().seek(f);
        });
    }

    // ---------- hearts ----------

    private void renderHearts(FrameLayout container, List<String> uids) {
        container.removeAllViews();
        int size = Ui.dp(ctx, 18);
        int overlap = Ui.dp(ctx, 11);
        for (int i = 0; i < Math.min(uids.size(), 3); i++) {
            String uid = uids.get(i);
            ImageView iv = new ImageView(ctx);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.leftMargin = i * overlap;   // cute overlapping stack, like Telegram
            iv.setLayoutParams(lp);
            Ui.setAvatar(iv, avatarsByUid.get(uid), uid, namesByUid.get(uid));
            container.addView(iv);
        }
    }

    // ---------- double tap ----------

    private void attachDoubleTap(View v, Models.Message m) {
        final long[] lastTap = {0};
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - lastTap[0] < 300) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    cb.onDoubleTapHeart(m);
                    Ui.tapScale(view);
                    lastTap[0] = 0;
                } else {
                    lastTap[0] = now;
                }
            }
            return false;
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView dateSeparator, messageText, messageTime, editedMark, forwarded;
        TextView replyQuoteName, replyQuoteText, voiceTime, voiceSpeed, musicTitle, musicArtist, musicTime;
        LinearLayout rowWrap, bubbleColumn, bubble, replyQuote, voiceWrap, musicWrap, heartsRow;
        FrameLayout mediaWrap, circleWrap, heartAvatars;
        CircleVideoView circleVideo;
        RecyclerView mediaGrid;
        ImageView btnMsgMenu, readMark, voicePlay, musicPlay;
        WaveformView voiceWave, musicWave;

        VH(@NonNull View v) {
            super(v);
            dateSeparator = v.findViewById(R.id.dateSeparator);
            rowWrap = v.findViewById(R.id.rowWrap);
            bubbleColumn = v.findViewById(R.id.bubbleColumn);
            bubble = v.findViewById(R.id.bubble);
            forwarded = v.findViewById(R.id.forwarded);
            replyQuote = v.findViewById(R.id.replyQuote);
            replyQuoteName = v.findViewById(R.id.replyQuoteName);
            replyQuoteText = v.findViewById(R.id.replyQuoteText);
            mediaWrap = v.findViewById(R.id.mediaWrap);
            mediaGrid = v.findViewById(R.id.mediaGrid);
            circleWrap = v.findViewById(R.id.circleWrap);
            circleVideo = v.findViewById(R.id.circleVideo);
            voiceWrap = v.findViewById(R.id.voiceWrap);
            voicePlay = v.findViewById(R.id.voicePlay);
            voiceWave = v.findViewById(R.id.voiceWave);
            voiceTime = v.findViewById(R.id.voiceTime);
            voiceSpeed = v.findViewById(R.id.voiceSpeed);
            musicWrap = v.findViewById(R.id.musicWrap);
            musicPlay = v.findViewById(R.id.musicPlay);
            musicWave = v.findViewById(R.id.musicWave);
            musicTitle = v.findViewById(R.id.musicTitle);
            musicArtist = v.findViewById(R.id.musicArtist);
            musicTime = v.findViewById(R.id.musicTime);
            messageText = v.findViewById(R.id.messageText);
            messageTime = v.findViewById(R.id.messageTime);
            editedMark = v.findViewById(R.id.editedMark);
            readMark = v.findViewById(R.id.readMark);
            heartsRow = v.findViewById(R.id.heartsRow);
            heartAvatars = v.findViewById(R.id.heartAvatars);
            btnMsgMenu = v.findViewById(R.id.btnMsgMenu);
        }
    }
}
