package io.tsuyu.app.ui;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.File;

/** Single MediaPlayer shared across all voice/music players in chat. */
public class MediaPlayerManager {
    public interface Listener {
        void onProgress(float frac);
        void onDone();
    }

    private MediaPlayer player;
    private View bound;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player == null || listener == null) return;
            try {
                int dur = player.getDuration();
                int pos = player.getCurrentPosition();
                if (dur > 0) listener.onProgress(pos / (float) dur);
                main.postDelayed(this, 250);
            } catch (Throwable ignored) {}
        }
    };

    public boolean isPlayingFor(View v) {
        return bound == v && player != null;
    }

    public void play(Context ctx, File f, View v, Listener l) {
        stopInternal();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            final Listener lb = l;
            player.setOnCompletionListener(mp -> {
                try {
                    main.post(() -> {
                        if (listener == lb) {
                            listener.onDone();
                            stopInternal();
                        }
                    });
                } catch (Throwable ignored) {}
            });
            bound = v;
            listener = l;
            player.start();
            main.postDelayed(progressTick, 250);
        } catch (Throwable t) {
            stopInternal();
        }
    }

    public void stopFor(View v) {
        if (bound == v) stopInternal();
    }

    private void stopInternal() {
        main.removeCallbacks(progressTick);
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Throwable ignored) {}
            player = null;
        }
        bound = null;
        listener = null;
    }
}
