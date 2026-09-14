package com.tsuyu.messenger.service;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.RemoteInput;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Repo;

import org.json.JSONObject;

/** Sends a reply typed directly in the notification shade. */
public class ReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Bundle remote = RemoteInput.getResultsFromIntent(intent);
        if (remote == null) return;
        CharSequence text = remote.getCharSequence(TsuyuService.KEY_REPLY);
        String peerUid = intent.getStringExtra("peerUid");
        if (text == null || text.length() == 0 || peerUid == null) return;

        Repo repo = Repo.get(ctx);
        if (repo.uid() == null) return;

        repo.userRef(peerUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Models.User peer = Repo.parseUser(s);
                try {
                    JSONObject p = new JSONObject();
                    p.put("type", Models.T_TEXT);
                    p.put("text", text.toString());
                    repo.sendMessage(peer, p, null);
                } catch (Exception ignored) { }
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                if (nm != null) nm.cancel(peerUid.hashCode());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }
}
