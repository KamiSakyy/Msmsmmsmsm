package io.tsuyu.app.notif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;


import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import java.io.File;

import io.tsuyu.app.R;
import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.ui.ChatActivity;
import io.tsuyu.app.ui.MainActivity;
import io.tsuyu.app.ui.ReplyHandlerActivity;
import io.tsuyu.app.util.Ui;

public class Notifier {
    public static final int ID_MSG = 1000;
    public static final int ID_CALL = 2000;
    public static final int ID_SERVICE = 3000;
    public static final String CH_MSG = "tsuyu_messages";
    public static final String CH_CALL = "tsuyu_calls";
    public static final String CH_SVC = "tsuyu_service";

    public static boolean notifEnabled(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            NotificationChannel ch = nm.getNotificationChannel(CH_MSG);
            return ch == null || ch.isEnabled();
        } catch (Throwable t) { return true; }
    }

    public static void requestPermission(android.app.Activity act) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    /** Resolve notification sound URI. Returns null for default. */
    public static Uri soundUri(Context ctx) {
        try {
            SharedPreferences p = TsuyuApp.get().prefs();
            if (!p.getBoolean("sound_on", true)) return null;
            String custom = p.getString("custom_sound", null);
            if (custom != null) {
                File f = new File(ctx.getFilesDir(), "sound/" + custom);
                if (f.exists()) {
                    return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setSoundOn(Context ctx, boolean on) {
        TsuyuApp.get().prefs().edit().putBoolean("sound_on", on).apply();
    }

    public static void saveCustomSound(Context ctx, byte[] mp3) {
        try {
            File d = new File(ctx.getFilesDir(), "sound");
            d.mkdirs();
            String name = "custom_" + System.currentTimeMillis() + ".mp3";
            File f = new File(d, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(mp3);
            fos.close();
            TsuyuApp.get().prefs().edit().putString("custom_sound", name).apply();
        } catch (Throwable ignored) {}
    }

    public static boolean hasCustomSound(Context ctx) {
        String custom = TsuyuApp.get().prefs().getString("custom_sound", null);
        return custom != null && new File(ctx.getFilesDir(), "sound/" + custom).exists();
    }

    public static void postMessage(Context ctx, String chatId, MeowUser peer, String preview, long msgId) {
        try {
            if (!notifEnabled(ctx)) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent open = new Intent(ctx, ChatActivity.class);
            open.putExtra("chatId", chatId);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent piOpen = PendingIntent.getActivity(ctx, (int) (msgId & 0xffffff), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent reply = new Intent(ctx, ReplyHandlerActivity.class);
            reply.putExtra("chatId", chatId);
            PendingIntent piReply = PendingIntent.getActivity(ctx, (int) (msgId & 0xffffff) + 100000, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_MSG)
                    .setSmallIcon(R.drawable.ic_launcher_fg)
                    .setContentTitle(peer == null ? "Tsuyu" : peer.displayName())
                    .setContentText(preview == null ? "Новое сообщение" : preview)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(preview))
                    .setAutoCancel(true)
                    .setContentIntent(piOpen)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .addAction(new NotificationCompat.Action.Builder(
                            R.drawable.ic_reply,
                            "Ответить", piReply)
                            .addRemoteInput(new androidx.core.app.RemoteInput.Builder("reply_text")
                                    .setLabel("Ответить")
                                    .build())
                            .build());

            Uri sound = soundUri(ctx);
            if (sound != null) {
                b.setSound(sound, android.media.AudioAttributes.USAGE_NOTIFICATION);
                b.setDefaults(0);
            } else {
                b.setSound(null);
                b.setStyle(b.getBigTextStyle());
            }

            if (peer != null && peer.avatarB64 != null) {
                Bitmap bm = Ui.avatarBitmap(ctx, peer.avatarB64);
                if (bm != null) {
                    b.setLargeIcon(bm);
                }
            }

            NotificationManagerCompat.from(ctx).notify(ID_MSG + Math.abs(chatId.hashCode() % 1000), b.build());
        } catch (Throwable t) {
            // ignore
        }
    }

    public static void postCall(Context ctx, String callId, MeowUser peer, boolean video, String direction) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(ctx, io.tsuyu.app.call.CallActivity.class);
            open.putExtra("callId", callId);
            open.putExtra("peer", peer == null ? "" : peer.uid);
            open.putExtra("out", direction == null || direction.equals("in") ? false : true);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 4000 + Math.abs(callId.hashCode() % 1000), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_CALL)
                    .setSmallIcon(R.drawable.ic_call)
                    .setContentTitle(peer == null ? "Tsuyu" : peer.displayName())
                    .setContentText(video ? (direction == null ? "Входящий видеозвонок" : "Видеозвонок...")
                            : (direction == null ? "Входящий звонок" : "Звонок..."))
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED);

            if (peer != null && peer.avatarB64 != null) {
                Bitmap bm = Ui.avatarBitmap(ctx, peer.avatarB64);
                if (bm != null) b.setLargeIcon(bm);
            }
            if (soundUri(ctx) != null) {
                b.setSound(soundUri(ctx), android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION);
            }
            NotificationManagerCompat.from(ctx).notify(ID_CALL, b.build());
        } catch (Throwable t) {}
    }

    public static void postService(Context ctx, String text) {
        try {
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_SVC)
                    .setSmallIcon(R.drawable.ic_launcher_fg)
                    .setContentTitle("Tsuyu")
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 29) {
                b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED);
            }
            NotificationManagerCompat.from(ctx).notify(ID_SERVICE, b.build());
        } catch (Throwable t) {}
    }

    public static void cancel(Context ctx, int id) {
        try {
            NotificationManagerCompat.from(ctx).cancel(id);
        } catch (Throwable ignored) {}
    }

    public static boolean canPost(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }
}
