package com.tsuyu.messenger.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.TsuyuApp;

/** Keeps an active call alive while the app is backgrounded. */
public class CallService extends Service {

    public static void start(Context ctx, String peerName, boolean video) {
        Intent i = new Intent(ctx, CallService.class);
        i.putExtra("peerName", peerName);
        i.putExtra("video", video);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CallService.class));
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String name = intent == null ? "Tsuyu" : intent.getStringExtra("peerName");
        boolean video = intent != null && intent.getBooleanExtra("video", false);
        startForeground(2, new NotificationCompat.Builder(this, TsuyuApp.CH_CALLS)
                .setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(video ? "Видеозвонок" : "Звонок")
                .setContentText(name == null ? "Tsuyu" : name)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build());
        return START_STICKY;
    }
}
