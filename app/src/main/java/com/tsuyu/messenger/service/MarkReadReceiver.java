package com.tsuyu.messenger.service;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.data.Repo;

import androidx.annotation.NonNull;

/** Marks conversation messages as read directly from the notification action. */
public class MarkReadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String peerUid = intent.getStringExtra("peerUid");
        if (peerUid == null) return;

        Repo repo = Repo.get(ctx);
        String me = repo.uid();
        if (me == null) return;

        repo.chatRef(me, peerUid).orderByChild("read").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (DataSnapshot s : snapshot.getChildren()) {
                            Object from = s.child("from").getValue();
                            if (peerUid.equals(from)) {
                                repo.markRead(peerUid, s.getKey());
                            }
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) { }
                });

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(peerUid.hashCode());
        }
    }
}
