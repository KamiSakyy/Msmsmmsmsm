package com.tsuyu.messenger.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.text.TextUtils;

/** All local settings: customization, privacy cache, notification sound, ghost mode. */
public class Prefs {

    private static final String NAME = "tsuyu_prefs";
    private final SharedPreferences p;

    public Prefs(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return p; }

    // ---- notifications ----
    public boolean notificationsEnabled() { return p.getBoolean("notif_on", true); }
    public void setNotificationsEnabled(boolean v) { p.edit().putBoolean("notif_on", v).apply(); }

    /** null = silent; custom uri (1s mp3) or default. */
    public Uri notificationSoundUri() {
        if (!notificationsEnabled()) return null;
        String s = p.getString("notif_sound", null);
        if (TextUtils.isEmpty(s)) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if ("builtin".equals(s)) return null; // handled by in-app player
        return Uri.parse(s);
    }

    public String notificationSoundRaw() { return p.getString("notif_sound", null); }
    public void setNotificationSound(String uri) { p.edit().putString("notif_sound", uri).apply(); }
    public boolean useBuiltinSound() { return "builtin".equals(p.getString("notif_sound", "builtin")); }

    public boolean vibrate() { return p.getBoolean("notif_vibrate", true); }
    public void setVibrate(boolean v) { p.edit().putBoolean("notif_vibrate", v).apply(); }

    // ---- customization ----
    public String fontPath() { return p.getString("font_path", null); }
    public void setFontPath(String v) { p.edit().putString("font_path", v).apply(); }

    public int textSize() { return p.getInt("text_size", 14); }
    public void setTextSize(int v) { p.edit().putInt("text_size", v).apply(); }

    public boolean bold() { return p.getBoolean("text_bold", false); }
    public void setBold(boolean v) { p.edit().putBoolean("text_bold", v).apply(); }

    public boolean italic() { return p.getBoolean("text_italic", false); }
    public void setItalic(boolean v) { p.edit().putBoolean("text_italic", v).apply(); }

    // ---- custom status wording ----
    public String wordTyping() { return p.getString("w_typing", "печатает"); }
    public void setWordTyping(String v) { p.edit().putString("w_typing", v).apply(); }

    public String wordOnline() { return p.getString("w_online", "в сети"); }
    public void setWordOnline(String v) { p.edit().putString("w_online", v).apply(); }

    public String wordLastSeen() { return p.getString("w_lastseen", "была в сети"); }
    public void setWordLastSeen(String v) { p.edit().putString("w_lastseen", v).apply(); }

    public String wordMessage() { return p.getString("w_message", "Сообщение"); }
    public void setWordMessage(String v) { p.edit().putString("w_message", v).apply(); }

    // ---- ghost mode ----
    public boolean ghost() { return p.getBoolean("ghost", false); }
    public void setGhost(boolean v) { p.edit().putBoolean("ghost", v).apply(); }

    // ---- session ----
    public String uid() { return p.getString("uid", null); }
    public void setUid(String v) { p.edit().putString("uid", v).apply(); }
}
