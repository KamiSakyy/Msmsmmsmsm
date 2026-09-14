package com.tsuyu.messenger;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import androidx.multidex.MultiDex;

import com.google.firebase.database.FirebaseDatabase;
import com.tsuyu.messenger.data.Prefs;

public class TsuyuApp extends Application {

    public static final String CH_MESSAGES = "tsuyu_messages";
    public static final String CH_CALLS = "tsuyu_calls";
    public static final String CH_SERVICE = "tsuyu_service";

    private static TsuyuApp instance;

    public static TsuyuApp get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        MultiDex.install(this);
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception ignored) { }
        createChannels();
    }

    public void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);

        nm.deleteNotificationChannel(CH_MESSAGES);
        NotificationChannel msg = new NotificationChannel(
                CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("Мгновенные уведомления о новых сообщениях");
        msg.enableVibration(true);
        msg.setVibrationPattern(new long[]{0, 60, 50, 60});
        msg.enableLights(true);

        Prefs p = new Prefs(this);
        if (p.notificationsEnabled()) {
            Uri sound = p.notificationSoundUri();
            if (sound != null) {
                msg.setSound(sound, new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build());
            }
        } else {
            msg.setSound(null, null);
        }
        nm.createNotificationChannel(msg);

        NotificationChannel calls = new NotificationChannel(
                CH_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Входящие аудио и видео звонки");
        calls.enableVibration(true);
        calls.setVibrationPattern(new long[]{0, 700, 600, 700, 600});
        nm.createNotificationChannel(calls);

        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, "Фоновая служба", NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);
    }
}
