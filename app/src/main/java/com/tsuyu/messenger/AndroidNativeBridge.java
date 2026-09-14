package com.tsuyu.messenger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

public class AndroidNativeBridge {

    private final Activity activity;
    private final WebView webView;
    private final AudioPlayerHelper audioHelper;

    public AndroidNativeBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.audioHelper = new AudioPlayerHelper(activity);
    }

    @JavascriptInterface
    public void startForegroundNotificationService(String uid) {
        Intent intent = new Intent(activity, TsuyuNotificationService.class);
        intent.setAction(TsuyuNotificationService.ACTION_START);
        intent.putExtra(TsuyuNotificationService.EXTRA_UID, uid);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }
    }

    @JavascriptInterface
    public void stopForegroundNotificationService() {
        Intent intent = new Intent(activity, TsuyuNotificationService.class);
        intent.setAction(TsuyuNotificationService.ACTION_STOP);
        activity.startService(intent);
    }

    @JavascriptInterface
    public void playNotificationSound() {
        audioHelper.playNotificationChime();
    }

    @JavascriptInterface
    public void vibrate(long ms) {
        Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms > 0 ? ms : 50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms > 0 ? ms : 50);
            }
        }
    }

    @JavascriptInterface
    public void showIncomingCallNotification(String callerName, boolean isVideo) {
        CallNotificationHelper.showIncomingCallNotification(activity, callerName, isVideo);
    }

    @JavascriptInterface
    public void startCallForeground(String callerName, boolean isVideo) {
        Intent intent = new Intent(activity, TsuyuNotificationService.class);
        intent.setAction(TsuyuNotificationService.ACTION_CALL_ACTIVE);
        intent.putExtra("callerName", callerName);
        intent.putExtra("isVideo", isVideo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }
    }

    @JavascriptInterface
    public void stopCallForeground() {
        Intent intent = new Intent(activity, TsuyuNotificationService.class);
        intent.setAction(TsuyuNotificationService.ACTION_CALL_END);
        activity.startService(intent);
    }

    @JavascriptInterface
    public void minimizeApp() {
        activity.moveTaskToBack(true);
    }

    @JavascriptInterface
    public void copyToClipboard(String text) {
        activity.runOnUiThread(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Tsuyu", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(activity, "Скопировано", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void cleanup() {
        audioHelper.release();
    }
}
