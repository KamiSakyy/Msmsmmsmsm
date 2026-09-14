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
        if (nm == null) return;

        NotificationChannel msg = nm.getNotificationChannel(CH_MESSAGES);
        if (msg == null) {
            msg = new NotificationChannel(
                    CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH);
            msg.setDescription("Уведомления о входящих сообщениях Tsuyu");
            msg.enableVibration(true);
            msg.setVibrationPattern(new long[]{0, 100, 80, 100});
            msg.enableLights(true);
            msg.setLightColor(0xFFFFFFFF);
            msg.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);

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
        }

        NotificationChannel calls = nm.getNotificationChannel(CH_CALLS);
        if (calls == null) {
            calls = new NotificationChannel(
                    CH_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH);
            calls.setDescription("Входящие аудио и видео звонки");
            calls.enableVibration(true);
            calls.setVibrationPattern(new long[]{0, 700, 600, 700, 600});
            calls.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(calls);
        }

        NotificationChannel svc = nm.getNotificationChannel(CH_SERVICE);
        if (svc == null) {
            svc = new NotificationChannel(
                    CH_SERVICE, "Фоновая синхронизация", NotificationManager.IMPORTANCE_MIN);
            svc.setDescription("Поддержание активной защищенной связи");
            svc.setShowBadge(false);
            nm.createNotificationChannel(svc);
        }
    }
}
