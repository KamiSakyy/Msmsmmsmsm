package com.tsuyu.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class DirectReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput != null) {
            CharSequence replyText = remoteInput.getCharSequence(TsuyuNotificationService.KEY_TEXT_REPLY);
            String peerUid = intent.getStringExtra("peerUid");

            if (replyText != null && peerUid != null) {
                // Dismiss notification or update state
                NotificationManagerCompat nm = NotificationManagerCompat.from(context);
                nm.cancelAll();

                // Send reply to RTDB in background thread
                new Thread(() -> {
                    try {
                        String chatId = "direct_" + peerUid;
                        String urlStr = "https://meow-874ce-default-rtdb.europe-west1.firebasedatabase.app/chats/" + chatId + "/messages.json";
                        URL url = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);

                        JSONObject msgObj = new JSONObject();
                        msgObj.put("text", replyText.toString());
                        msgObj.put("timestamp", System.currentTimeMillis());
                        msgObj.put("fromDirectReply", true);

                        OutputStream os = conn.getOutputStream();
                        os.write(msgObj.toString().getBytes());
                        os.flush();
                        os.close();

                        conn.getResponseCode();
                        conn.disconnect();
                    } catch (Exception ignored) {}
                }).start();
            }
        }
    }
}
