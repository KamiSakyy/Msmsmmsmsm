package io.tsuyu.app.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.util.Base64;

import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ImageUtil {

    public static class ImgResult {
        public byte[] jpeg;
        public int w;
        public int h;
        public String b64;
    }

    /** Decode + rotate + downscale to maxDim, JPEG quality. */
    public static ImgResult process(Context ctx, Uri uri, int maxDim, int quality) {
        try {
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap raw = BitmapFactory.decodeStream(is);
            is.close();
            if (raw == null) return null;
            // EXIF rotation
            int rotation = 0;
            try {
                java.io.InputStream eis = ctx.getContentResolver().openInputStream(uri);
                if (eis != null) {
                    ExifInterface ex = new ExifInterface(eis);
                    eis.close();
                    int orient = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    switch (orient) {
                        case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                        case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                        case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                    }
                }
            } catch (Throwable ignored) {}
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                raw = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            }
            // downscale
            int w = raw.getWidth(), h = raw.getHeight();
            if (Math.max(w, h) > maxDim) {
                float scale = (float) maxDim / Math.max(w, h);
                Bitmap scaled = Bitmap.createScaledBitmap(raw, Math.round(w * scale), Math.round(h * scale), true);
                if (scaled != raw) raw.recycle();
                raw = scaled;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            raw.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            if (raw != null) raw.recycle();
            ImgResult r = new ImgResult();
            r.jpeg = bos.toByteArray();
            r.w = raw.getWidth();
            r.h = raw.getHeight();
            r.b64 = Base64.encodeToString(r.jpeg, Base64.NO_WRAP | Base64.NO_PADDING);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tiny blurred preview for dialog list (b64). */
    public static String tinyBlur(byte[] jpeg, int size, int radius) {
        try {
            Bitmap raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (raw == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(raw, size, size, true);
            Bitmap blurred = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(blurred);
            Paint p = new Paint();
            p.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
            p.setAntiAlias(true);
            c.drawBitmap(small, 0, 0, p);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            blurred.compress(Bitmap.CompressFormat.JPEG, 50, bos);
            small.recycle();
            blurred.recycle();
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Bitmap firstVideoFrame(Context ctx, Uri uri, int maxDim) {
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(ctx, uri);
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

    public static int videoDurationMs(Uri uri, Context ctx) {
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(ctx, uri);
            String d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            mmr.release();
            return d == null ? 0 : Integer.parseInt(d);
        } catch (Throwable t) {
            return 0;
        }
    }
}
