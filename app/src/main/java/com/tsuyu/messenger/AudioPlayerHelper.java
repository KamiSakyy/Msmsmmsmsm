package com.tsuyu.messenger;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;

public class AudioPlayerHelper {

    private final Context context;
    private MediaPlayer chimePlayer;
    private MediaPlayer ringtonePlayer;

    public AudioPlayerHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void playNotificationChime() {
        try {
            if (chimePlayer != null) {
                chimePlayer.release();
            }
            chimePlayer = MediaPlayer.create(context, R.raw.notification);
            if (chimePlayer != null) {
                chimePlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    chimePlayer = null;
                });
                chimePlayer.start();
            }
        } catch (Exception ignored) {}
    }

    public synchronized void startRingtoneLoop() {
        try {
            stopRingtone();
            ringtonePlayer = MediaPlayer.create(context, R.raw.ringtone);
            if (ringtonePlayer != null) {
                ringtonePlayer.setLooping(true);
                ringtonePlayer.start();
            }
        } catch (Exception ignored) {}
    }

    public synchronized void stopRingtone() {
        if (ringtonePlayer != null) {
            try {
                if (ringtonePlayer.isPlaying()) {
                    ringtonePlayer.stop();
                }
                ringtonePlayer.release();
            } catch (Exception ignored) {}
            ringtonePlayer = null;
        }
    }

    public void release() {
        if (chimePlayer != null) {
            chimePlayer.release();
            chimePlayer = null;
        }
        stopRingtone();
    }
}
