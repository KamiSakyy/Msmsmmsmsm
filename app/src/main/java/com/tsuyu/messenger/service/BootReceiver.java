package com.tsuyu.messenger.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.firebase.auth.FirebaseAuth;

/** Restarts the listener service after reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() != null) TsuyuService.start(ctx);
        } catch (Exception ignored) { }
    }
}
