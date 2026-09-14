package com.tsuyu.messenger.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.TsuyuApp;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.ui.CallActivity;

import java.util.HashMap;
import java.util.Map;

/**
 * Call signalling over RTDB: calls/{uid} holds the current offer/answer/ICE.
 * States: ringing -> accepted / declined / ended
 */
public final class CallSignaling {

    public static final String RINGING = "ringing";
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String ENDED = "ended";

    private static boolean listening;

    private CallSignaling() {}

    public static DatabaseReference ref(Context ctx, String uid) {
        return Repo.get(ctx).db().getReference("calls").child(uid);
    }

    /** Places a call: writes the offer into the callee's node. */
    public static void placeCall(Context ctx, String from, String to,
                                 boolean video, String sdpOffer, String callId) {
        Map<String, Object> m = new HashMap<>();
        m.put("callId", callId);
        m.put("from", from);
        m.put("video", video);
        m.put("state", RINGING);
        m.put("offer", sdpOffer);
        m.put("ts", ServerValue.TIMESTAMP);
        ref(ctx, to).setValue(m);
    }

    public static void answer(Context ctx, String callerUid, String sdpAnswer) {
        Map<String, Object> m = new HashMap<>();
        m.put("state", ACCEPTED);
        m.put("answer", sdpAnswer);
        ref(ctx, callerUid).updateChildren(m);
    }

    public static void setState(Context ctx, String uid, String state) {
        ref(ctx, uid).child("state").setValue(state);
    }

    public static void clear(Context ctx, String uid) {
        ref(ctx, uid).removeValue();
    }

    public static void addIce(Context ctx, String uid, String candidate) {
        ref(ctx, uid).child("ice").push().setValue(candidate);
    }

    /** Watches our own call node and raises a full-screen incoming-call UI. */
    public static void listen(Context ctx, String me) {
        if (listening) return;
        listening = true;
        ref(ctx, me).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Object state = s.child("state").getValue();
                if (!RINGING.equals(state)) return;
                Object from = s.child("from").getValue();
                if (from == null) return;
                boolean video = Boolean.TRUE.equals(s.child("video").getValue());
                showIncoming(ctx, String.valueOf(from), video);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private static void showIncoming(Context ctx, String fromUid, boolean video) {
        Intent i = new Intent(ctx, CallActivity.class);
        i.putExtra("peerUid", fromUid);
        i.putExtra("video", video);
        i.putExtra("outgoing", false);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent full = PendingIntent.getActivity(ctx, 99, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(ctx, TsuyuApp.CH_CALLS)
                .setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(video ? "Входящий видеозвонок" : "Входящий звонок")
                .setContentText("Tsuyu")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(full, true)
                .setAutoCancel(true)
                .setOngoing(true)
                .build();

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(777, n);
        ctx.startActivity(i);
    }
}
