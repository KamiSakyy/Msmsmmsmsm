package com.tsuyu.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class TsuyuNotificationService extends Service {

    public static final String ACTION_START = "com.tsuyu.messenger.START_SERVICE";
    public static final String ACTION_STOP = "com.tsuyu.messenger.STOP_SERVICE";
    public static final String ACTION_CALL_ACTIVE = "com.tsuyu.messenger.CALL_ACTIVE";
    public static final String ACTION_CALL_END = "com.tsuyu.messenger.CALL_END";
    public static final String EXTRA_UID = "extra_uid";

    public static final String CHANNEL_SERVICE_ID = "tsuyu_service_channel";
    public static final String CHANNEL_MESSAGES_ID = "tsuyu_messages_channel";
    public static final String CHANNEL_CALLS_ID = "tsuyu_calls_channel";

    public static final String KEY_TEXT_REPLY = "key_text_reply";
    private static final int SERVICE_NOTIF_ID = 9001;

    private String currentUid;
    private boolean isRunning = false;
    private Thread listenerThread;
    private AudioPlayerHelper audioHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        audioHelper = new AudioPlayerHelper(this);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Service persistent channel (silent)
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_SERVICE_ID,
                getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription(getString(R.string.channel_service_desc));
            serviceChannel.setShowBadge(false);
            nm.createNotificationChannel(serviceChannel);

            // Messages channel
            NotificationChannel msgChannel = new NotificationChannel(
                CHANNEL_MESSAGES_ID,
                getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            );
            msgChannel.setDescription(getString(R.string.channel_messages_desc));
            msgChannel.enableLights(true);
            msgChannel.setLightColor(Color.BLUE);
            msgChannel.enableVibration(true);
            nm.createNotificationChannel(msgChannel);

            // Calls channel
            NotificationChannel callChannel = new NotificationChannel(
                CHANNEL_CALLS_ID,
                getString(R.string.channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            );
            callChannel.setDescription(getString(R.string.channel_calls_desc));
            callChannel.enableLights(true);
            callChannel.setLightColor(Color.GREEN);
            callChannel.enableVibration(true);
            callChannel.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
            nm.createNotificationChannel(callChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            currentUid = intent.getStringExtra(EXTRA_UID);
            startForegroundServiceNotification();
            startRTDBListener();
        } else if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
        } else if (ACTION_CALL_ACTIVE.equals(action)) {
            String callerName = intent.getStringExtra("callerName");
            boolean isVideo = intent.getBooleanExtra("isVideo", false);
            updateCallActiveNotification(callerName, isVideo);
        } else if (ACTION_CALL_END.equals(action)) {
            startForegroundServiceNotification();
        }

        return START_STICKY;
    }

    private void startForegroundServiceNotification() {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("Tsuyu Messenger")
            .setContentText("Signal Double Ratchet E2EE подключено")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();

        startForeground(SERVICE_NOTIF_ID, notif);
    }

    private void updateCallActiveNotification(String callerName, boolean isVideo) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("Активный звонок: " + (callerName != null ? callerName : "Tsuyu"))
            .setContentText(isVideo ? "Видеозвонок..." : "Аудиозвонок...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(SERVICE_NOTIF_ID, notif);
        }
    }

    private void startRTDBListener() {
        if (isRunning || currentUid == null) return;
        isRunning = true;

        listenerThread = new Thread(() -> {
            String streamUrl = "https://meow-874ce-default-rtdb.europe-west1.firebasedatabase.app/dialogs/" + currentUid + ".json";
            while (isRunning) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(streamUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setReadTimeout(60000);

                    if (conn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        String line;
                        while (isRunning && (line = reader.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String jsonData = line.substring(6).trim();
                                if (!jsonData.equals("null") && !jsonData.isEmpty()) {
                                    handleSSEEventData(jsonData);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
        listenerThread.start();
    }

    private void handleSSEEventData(String jsonData) {
        try {
            JSONObject obj = new JSONObject(jsonData);
            if (obj.has("data")) {
                JSONObject data = obj.optJSONObject("data");
                if (data != null && data.optInt("unreadCount", 0) > 0) {
                    String peerUid = data.optString("peerUid", "Собеседник");
                    String lastText = data.optString("lastText", "Новое зашифрованное сообщение");
                    showIncomingMessageNotification(peerUid, lastText);
                }
            }
        } catch (Exception ignored) {}
    }

    public void showIncomingMessageNotification(String peerUid, String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        // Direct Reply RemoteInput Action
        RemoteInput remoteInput = new RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(getString(R.string.reply_label))
            .build();

        Intent replyIntent = new Intent(this, DirectReplyReceiver.class);
        replyIntent.setAction("com.tsuyu.messenger.DIRECT_REPLY");
        replyIntent.putExtra("peerUid", peerUid);

        PendingIntent replyPendingIntent = PendingIntent.getBroadcast(this, 101, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.MUTABLE ? PendingIntent.FLAG_MUTABLE : 0));

        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            getString(R.string.reply_action),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build();

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_MESSAGES_ID)
            .setContentTitle("Tsuyu")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) System.currentTimeMillis(), notif);
        }

        audioHelper.playNotificationChime();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        if (audioHelper != null) {
            audioHelper.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
