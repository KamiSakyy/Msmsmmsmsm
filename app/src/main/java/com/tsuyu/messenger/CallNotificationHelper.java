package com.tsuyu.messenger;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class CallNotificationHelper {

    public static final int CALL_NOTIFICATION_ID = 8888;

    public static void showIncomingCallNotification(Context context, String callerName, boolean isVideo) {
        Intent fullScreenIntent = new Intent(context, MainActivity.class);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, TsuyuNotificationService.CHANNEL_CALLS_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName != null ? callerName : "Tsuyu")
            .setContentText(isVideo ? "Входящий видеозвонок..." : "Входящий аудиозвонок...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(CALL_NOTIFICATION_ID, builder.build());
        }
    }

    public static void cancelCallNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(CALL_NOTIFICATION_ID);
        }
    }
}
