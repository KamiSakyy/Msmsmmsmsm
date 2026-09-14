package com.tsuyu.messenger.util;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.tsuyu.messenger.data.Prefs;

import java.io.File;

public final class Ui {

    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** Fade + slight rise, matching the web app's fadeIn animation. */
    public static void fadeIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 6));
        v.animate().alpha(1f).translationY(0).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void pop(View v) {
        v.setScaleX(0.6f); v.setScaleY(0.6f); v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
    }

    public static void tapScale(View v) {
        ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.94f, 1f).setDuration(160).start();
        ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.94f, 1f).setDuration(160).start();
    }

    /** Circular bitmap for avatars. */
    public static Bitmap circle(Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap scaled = Bitmap.createBitmap(src, (src.getWidth() - size) / 2,
                (src.getHeight() - size) / 2, size, size);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }

    /** Deterministic colour + initial placeholder, like Telegram. */
    public static void placeholderAvatar(ImageView iv, String seed, String name) {
        int[] palette = {0xFF0A84FF, 0xFF34C759, 0xFFFF9500, 0xFFFF3B30,
                0xFFAF52DE, 0xFF5AC8FA, 0xFFFFCC00};
        int color = palette[Math.abs((seed == null ? "x" : seed).hashCode()) % palette.length];
        int size = 160;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        String letter = (name == null || name.trim().isEmpty()) ? "?"
                : name.trim().substring(0, 1).toUpperCase();
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.44f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, p);
        iv.setImageBitmap(bmp);
    }

    public static Bitmap decodeB64(String b64) {
        try {
            byte[] raw = com.tsuyu.messenger.crypto.CryptoUtil.unb64(b64);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setAvatar(ImageView iv, String b64, String seed, String name) {
        Bitmap bmp = b64 == null ? null : decodeB64(b64);
        if (bmp != null) iv.setImageBitmap(circle(bmp));
        else placeholderAvatar(iv, seed, name);
    }

    /** Applies the user's font/size/style choices to a TextView. */
    public static void applyTextStyle(TextView tv, Prefs p) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.textSize());
        int style = Typeface.NORMAL;
        if (p.bold() && p.italic()) style = Typeface.BOLD_ITALIC;
        else if (p.bold()) style = Typeface.BOLD;
        else if (p.italic()) style = Typeface.ITALIC;
        Typeface base = Typeface.DEFAULT;
        String path = p.fontPath();
        if (path != null) {
            try {
                File f = new File(path);
                if (f.exists()) base = Typeface.createFromFile(f);
            } catch (Exception ignored) { }
        }
        tv.setTypeface(base, style);
    }
}
