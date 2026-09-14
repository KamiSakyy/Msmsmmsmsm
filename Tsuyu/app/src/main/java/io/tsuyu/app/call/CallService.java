package io.tsuyu.app.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import io.tsuyu.app.R;
import io.tsuyu.app.core.Fb;

/** Foreground service keeping calls alive (RTDB-driven state machine). */
public class CallService extends Service {
    public static final String CH = "call_fs";
    public static String callId;
    public static String peer;
    public static boolean outgoing = true;
    public static volatile boolean engineStarted = false;

    private static String lastOfferSeen = "";
    private static String lastAnswerSeen = "";
    private static String lastIceFSeen = "";
    private static String lastIceTSeen = "";

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context ctx, String cid, String p, boolean out) {
        try {
            lastOfferSeen = "";
            lastAnswerSeen = "";
            lastIceFSeen = "";
            lastIceTSeen = "";
            Intent i = new Intent(ctx, CallService.class);
            i.putExtra("callId", cid);
            i.putExtra("peer", p);
            i.putExtra("out", out);
            Context app = ctx.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, CallService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setAppContext(this);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "Аудио-звонок", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null);
        nm.createNotificationChannel(ch);
    }

    private void postFg(String title, String text) {
        try {
            PendingIntent pi = PendingIntent.getActivity(this, 1,
                    new Intent(this, CallActivity.class).putExtra("callId", callId).putExtra("peer", peer).putExtra("out", outgoing),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(this, CH)
                    .setSmallIcon(R.drawable.ic_call)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
            startForeground(900, n);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("callId")) {
            callId = intent.getStringExtra("callId");
            peer = intent.getStringExtra("peer");
            outgoing = intent.getBooleanExtra("out", true);
        }
        postFg("Tsuyu • звонок", "Подключение...");

        // outgoing: engine immediately; incoming: wait for accept
        if (outgoing) {
            startEngine();
        }

        final String cid = callId;
        Fb.fb().getReference("calls/" + cid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                try {
                    JSONObject d = s.getValue() == null ? new JSONObject() : s.getValue(JSONObject.class);
                    handleState(d);
                    handleSignals(d);
                } catch (Throwable t) {
                    Log.e("TsuyuCall", "service loop", t);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
        return START_STICKY;
    }

    /** Called from CallActivity when callee taps Accept. */
    public static void startEngine() {
        if (engineStarted || callId == null) return;
        engineStarted = true;
        CallEngine.start(getAppCtx(), callId, peer, outgoing);
    }

    private static Context appCtx;
    public static void setAppContext(Context c) {
        appCtx = c.getApplicationContext();
    }
    public static Context getAppCtx() {
        return appCtx;
    }

    private void handleState(JSONObject d) {
        String st = d.optString("st", "ringing");
        String me = Fb.myUid();
        String from = d.optString("f", "");
        if ("ringing".equals(st)) {
            if (outgoing) postFg("Tsuyu • исходящий", "Ожидание ответа...");
            else postFg("Tsuyu • входящий", "Идёт набор...");
        } else if ("accepted".equals(st)) {
            if (outgoing) postFg("Tsuyu • звонок", "Собеседник принял...");
            else {
                // I am callee: accept tapped → start engine
                startEngine();
                postFg("Tsuyu • звонок", "Подключение...");
            }
        } else if ("active".equals(st)) {
            postFg("Tsuyu • разговор", "Идёт разговор");
        } else if ("ended".equals(st)) {
            stopWithReason(d.optString("reason", "remote"), d.optLong("durMs", 0));
        } else if ("declined".equals(st)) {
            if (outgoing) stopWithReason("declined", 0);
            else stopSelf();
        }
        lastSt = st;
    }
    private String lastSt = "ringing";

    private void handleSignals(JSONObject d) {
        // SDP
        String offer = d.optString("offer", "");
        String answer = d.optString("answer", "");
        if (outgoing == false && offer.length() > lastOfferSeen.length()) {
            lastOfferSeen = offer;
            CallEngine.onRemoteSdp(offer, "offer");
        }
        if (outgoing && answer.length() > lastAnswerSeen.length()) {
            lastAnswerSeen = answer;
            CallEngine.onRemoteSdp(answer, "answer");
        }
        // ICE: mid|line|candidate
        String iceF = d.optString("iceF", "");
        String iceT = d.optString("iceT", "");
        String mine = outgoing ? iceT : iceF;
        if (mine.length() > lastIcePeerSeen.length()) {
            lastIcePeerSeen = mine;
            try {
                String[] parts = mine.split("\\|", 3);
                if (parts.length == 3) {
                    CallEngine.onRemoteIce(parts[0], Integer.parseInt(parts[1]), parts[2]);
                }
            } catch (Throwable ignored) {}
        }
    }
    private String lastIcePeerSeen = "";

    private void stopWithReason(String reason, long ms) {
        try {
            CallEngine.finishCall(reason, ms);
        } catch (Throwable ignored) {}
        main.postDelayed(new Runnable() {
            @Override public void run() {
                stopForeground(true);
                stopSelf();
            }
        }, 800);
    }

    @Override
    public void onDestroy() {
        try {
            if (callId != null) {
                CallEngine.teardown();
            }
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
