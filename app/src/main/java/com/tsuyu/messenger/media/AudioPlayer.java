package com.tsuyu.messenger.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.tsuyu.messenger.crypto.CryptoUtil;

import java.io.File;
import java.io.FileOutputStream;

/** Single global player so only one voice/music clip plays at a time. */
public class AudioPlayer {

    public interface Callback {
        void onProgress(float fraction, long positionMs);
        void onStateChanged(boolean playing);
    }

    private static AudioPlayer instance;

    private MediaPlayer player;
    private String currentKey;
    private Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float currentSpeed = 1.0f;

    public static synchronized AudioPlayer get() {
        if (instance == null) instance = new AudioPlayer();
        return instance;
    }

    public String currentKey() { return currentKey; }

    public float getSpeed() { return currentSpeed; }

    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (player != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                boolean playing = player.isPlaying();
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
                if (!playing) player.pause();
            } catch (Exception ignored) { }
        }
    }

    public boolean isPlaying(String key) {
        return key != null && key.equals(currentKey) && player != null && player.isPlaying();
    }

    /** Plays base64 audio, caching it to a temp file. Toggles if the same key is tapped. */
    public void toggle(Context ctx, String key, String base64, Callback cb) {
        if (key.equals(currentKey) && player != null) {
            if (player.isPlaying()) {
                player.pause();
                if (callback != null) callback.onStateChanged(false);
            } else {
                player.start();
                callback = cb;
                if (callback != null) callback.onStateChanged(true);
                handler.post(ticker);
            }
            return;
        }
        stop();
        try {
            File f = new File(ctx.getCacheDir(), "aud_" + Math.abs(key.hashCode()) + ".m4a");
            if (!f.exists() || f.length() == 0) {
                byte[] data = CryptoUtil.unb64(base64);
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
            }
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && currentSpeed != 1.0f) {
                try {
                    player.setPlaybackParams(player.getPlaybackParams().setSpeed(currentSpeed));
                } catch (Exception ignored) { }
            }
            player.start();
            currentKey = key;
            callback = cb;
            if (callback != null) callback.onStateChanged(true);
            player.setOnCompletionListener(mp -> {
                if (callback != null) {
                    callback.onProgress(0f, 0);
                    callback.onStateChanged(false);
                }
                stop();
            });
            handler.post(ticker);
        } catch (Exception e) {
            stop();
        }
    }

    public void seek(float fraction) {
        if (player == null) return;
        try { player.seekTo((int) (player.getDuration() * fraction)); } catch (Exception ignored) { }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            try {
                if (player.isPlaying() && callback != null) {
                    int dur = Math.max(1, player.getDuration());
                    callback.onProgress(player.getCurrentPosition() / (float) dur,
                            player.getCurrentPosition());
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 60);
        }
    };

    public void stop() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            try { player.reset(); player.release(); } catch (Exception ignored) { }
        }
        player = null;
        currentKey = null;
        callback = null;
    }
}
