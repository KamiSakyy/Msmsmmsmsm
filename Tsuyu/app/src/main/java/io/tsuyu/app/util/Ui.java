package io.tsuyu.app.util;
import android.graphics.Bitmap;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.R;
import io.tsuyu.app.core.ProfileCipher;
import io.tsuyu.app.model.MeowUser;

public class Ui {
    private static JSONObject custom; // decrypted customization
    private static Typeface customFont;

    /** Load customization (encrypted) from user. */
    public static void loadCustom(String customEnc) {
        try {
            String json = ProfileCipher.dec(customEnc);
            custom = json == null ? null : new JSONObject(json);
            if (json != null) {
                try {
                    TsuyuApp.get().prefs().edit().putString("custom_json", json).apply();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { custom = null; }
    }

    /** Persisted customization JSON (for re-saving profile). */
    public static String currentCustomJson(android.content.Context ctx) {
        try {
            return TsuyuApp.get().prefs().getString("custom_json", null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String st(String key, String def) {
        if (custom == null) return def;
        String v = custom.optString(key, null);
        return TextUtils.isEmpty(v) ? def : v;
    }

    public static float textSizeSp() {
        if (custom == null) return 14f;
        return (float) custom.optDouble("size", 14f);
    }

    public static boolean bold() {
        return custom != null && custom.optBoolean("bold", false);
    }

    public static boolean italic() {
        return custom != null && custom.optBoolean("italic", false);
    }

    public static String fontName() {
        return custom == null ? null : custom.optString("font", null);
    }

    public static void applyFont(TextView tv, float sizeSp) {
        try {
            String fn = fontName();
            Typeface tf = null;
            if (fn != null) {
                File f = new File(TsuyuApp.get().getFilesDir(), "fonts/" + fn);
                if (f.exists()) tf = Typeface.createFromFile(f);
            }
            if (tf != null) tv.setTypeface(tf, bold() ? (italic() ? Typeface.BOLD_ITALIC : Typeface.BOLD)
                    : (italic() ? Typeface.ITALIC : Typeface.NORMAL));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        } catch (Throwable ignored) {}
    }

    // ---------- time ----------
    public static String timeHM(long ts) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return f.format(new Date(ts));
    }

    public static String dateLabel(long ts) {
        SimpleDateFormat today = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        SimpleDateFormat now = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        if (today.format(new Date(ts)).equals(now.format(new Date()))) return "Сегодня";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (today.format(new Date(ts)).equals(now.format(c.getTime()))) return "Вчера";
        SimpleDateFormat f = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
        return f.format(new Date(ts));
    }

    public static String listTime(long ts) {
        SimpleDateFormat today = new SimpleDateFormat("dd.MM", Locale.getDefault());
        if (today.format(new Date(ts)).equals(today.format(new Date()))) return timeHM(ts);
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yy", Locale.getDefault());
        return f.format(new Date(ts));
    }

    /** "была в 13:00" / "была вчера в 13:00" / "была недавно" (customizable) */
    public static String lastSeenText(MeowUser u, String customOnline, String customLast) {
        if (u.online) return customOnline;
        Long ls = u.lastSeen;
        if (ls == null) return customLast + " недавно";
        long now = System.currentTimeMillis();
        long diff = now - ls;
        java.util.Calendar c = java.util.Calendar.getInstance();
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String tm = f.format(new Date(ls));
        if (sameDay(ls, now)) return customLast + " " + tm;
        c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(ls, c.getTimeInMillis())) return customLast + " вчера " + tm;
        SimpleDateFormat fd = new SimpleDateFormat("dd.MM в HH:mm", Locale.getDefault());
        return customLast + " " + fd.format(new Date(ls));
    }

    private static boolean sameDay(long a, long b) {
        java.util.Calendar ca = java.util.Calendar.getInstance();
        ca.setTimeInMillis(a);
        java.util.Calendar cb = java.util.Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR);
    }

    public static String durText(double sec) {
        if (sec <= 0) return "0:00";
        long s = (long) sec;
        return (s / 60) + ":" + String.format(Locale.getDefault(), "%02d", s % 60);
    }

    public static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---------- avatar ----------
    public static void setAvatar(ImageView iv, View bg, TextView letter, MeowUser u) {
        try {
            boolean visible = u != null && u.avatarB64 != null && !u.avatarB64.isEmpty();
            // privacy: photo visibility enforced by caller passing null avatarB64 if hidden
            if (visible) {
                byte[] data = android.util.Base64.decode(u.avatarB64, android.util.Base64.DEFAULT);
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bm != null) {
                    iv.setImageBitmap(bm);
                    iv.setVisibility(View.VISIBLE);
                    if (bg != null) bg.setVisibility(View.GONE);
                    if (letter != null) letter.setVisibility(View.GONE);
                    return;
                }
            }
            iv.setVisibility(View.GONE);
            if (bg != null) bg.setVisibility(View.VISIBLE);
            if (letter != null) {
                letter.setVisibility(View.VISIBLE);
                letter.setText(u == null ? "?" : u.letter());
            }
        } catch (Throwable t) {
            if (letter != null) letter.setText("?");
        }
    }

    /** First frame of a local video file. */
    public static android.graphics.Bitmap firstVideoFrameLocal(File f, int maxDim) {
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(f.getAbsolutePath());
            Bitmap bm = mmr.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            mmr.release();
            if (bm == null) return null;
            if (Math.max(bm.getWidth(), bm.getHeight()) > maxDim) {
                float s = (float) maxDim / Math.max(bm.getWidth(), bm.getHeight());
                Bitmap b2 = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * s), Math.round(bm.getHeight() * s), true);
                bm.recycle();
                return b2;
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Avatar b64 for notifications (small). */
    public static android.graphics.Bitmap avatarBitmap(Context ctx, String avatarB64) {
        try {
            if (avatarB64 == null) return null;
            File f = mediaFile(ctx, "avatar", avatarB64.hashCode() + ".jpg");
            if (f.exists() && f.length() > 0) {
                return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            byte[] data = android.util.Base64.decode(avatarB64, android.util.Base64.DEFAULT);
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bm != null) {
                FileOutputStream fos = new FileOutputStream(f);
                bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos);
                fos.close();
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- media cache (b64 -> file) ----------
    public static File mediaFile(Context ctx, String prefix, String hashName) {
        File d = new File(ctx.getFilesDir(), "media/" + prefix);
        d.mkdirs();
        return new File(d, hashName);
    }

    public static File writeMedia(Context ctx, String prefix, String hash, byte[] data) {
        try {
            File f = mediaFile(ctx, prefix, hash + ".bin");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    public static byte[] readFile(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            return buf;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Save a file to Downloads (MediaStore for 29+). Returns file name or null. */
    public static String saveToDownloads(Context ctx, String name, byte[] data, String mime) {
        try {
            File f = new File(ctx.getCacheDir(), name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                android.net.Uri uri = ctx.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                         java.io.InputStream is = new java.io.FileInputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    f.delete();
                    return name;
                }
            } else {
                File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dl.exists()) dl.mkdirs();
                File out = new File(dl, name);
                FileOutputStream fos2 = new FileOutputStream(out);
                FileInputStream fis = new FileInputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) fos2.write(buf, 0, n);
                fis.close();
                fos2.close();
                f.delete();
                return name;
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    public static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    public static void toastLong(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    }
}
