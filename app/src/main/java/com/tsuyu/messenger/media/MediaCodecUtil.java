package com.tsuyu.messenger.media;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.exifinterface.media.ExifInterface;

import com.tsuyu.messenger.crypto.CryptoUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Turns gallery/camera content into compact Base64 payloads suitable for RTDB.
 * RTDB caps a single value at ~10 MB, so we target well below that.
 */
public final class MediaCodecUtil {

    /** Max bytes of the raw (pre-base64) media we will embed. */
    public static final int MAX_RAW = 5 * 1024 * 1024;

    private MediaCodecUtil() {}

    public static class Encoded {
        public String base64;
        public String thumbBase64;
        public int width, height;
        public long durationMs;
        public String fileName;
        public long rawSize;
    }

    // ---------------- images ----------------

    public static Encoded encodeImage(Context ctx, Uri uri, int maxDim, int quality) throws Exception {
        ContentResolver cr = ctx.getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        int w = bounds.outWidth, h = bounds.outHeight;
        while (Math.max(w / sample, h / sample) > maxDim * 2) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IllegalStateException("Не удалось прочитать изображение");

        bmp = applyExif(cr, uri, bmp);
        bmp = scaleTo(bmp, maxDim);

        Encoded e = new Encoded();
        byte[] jpeg = compressUnder(bmp, quality, MAX_RAW);
        e.base64 = CryptoUtil.b64(jpeg);
        e.rawSize = jpeg.length;
        e.width = bmp.getWidth();
        e.height = bmp.getHeight();
        e.fileName = displayName(ctx, uri);
        return e;
    }

    private static Bitmap applyExif(ContentResolver cr, Uri uri, Bitmap bmp) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return bmp;
            ExifInterface exif = new ExifInterface(in);
            int rot = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            int deg = 0;
            if (rot == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (rot == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (rot == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg == 0) return bmp;
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        } catch (Exception e) {
            return bmp;
        }
    }

    public static Bitmap scaleTo(Bitmap bmp, int maxDim) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (Math.max(w, h) <= maxDim) return bmp;
        float ratio = maxDim / (float) Math.max(w, h);
        return Bitmap.createScaledBitmap(bmp, Math.round(w * ratio), Math.round(h * ratio), true);
    }

    /** Compresses, stepping quality down until the payload fits. */
    public static byte[] compressUnder(Bitmap bmp, int startQuality, int maxBytes) {
        int q = startQuality;
        byte[] out;
        do {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
            out = bos.toByteArray();
            q -= 12;
        } while (out.length > maxBytes && q > 25);
        return out;
    }

    public static String thumbnailB64(Bitmap bmp, int dim, int quality) {
        Bitmap small = scaleTo(bmp, dim);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        return CryptoUtil.b64(bos.toByteArray());
    }

    // ---------------- video ----------------

    public static Encoded encodeVideo(Context ctx, Uri uri) throws Exception {
        byte[] data = readAll(ctx, uri);
        if (data.length > MAX_RAW) {
            throw new IllegalStateException("Видео слишком большое (макс "
                    + (MAX_RAW / 1024 / 1024) + " МБ для RTDB)");
        }
        Encoded e = new Encoded();
        e.base64 = CryptoUtil.b64(data);
        e.rawSize = data.length;
        e.fileName = displayName(ctx, uri);

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            e.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            e.width = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            e.height = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            Bitmap frame = r.getFrameAtTime(0);
            if (frame != null) e.thumbBase64 = thumbnailB64(frame, 480, 62);
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return e;
    }

    public static Encoded encodeFile(Context ctx, Uri uri) throws Exception {
        byte[] data = readAll(ctx, uri);
        if (data.length > MAX_RAW) throw new IllegalStateException("Файл слишком большой");
        Encoded e = new Encoded();
        e.base64 = CryptoUtil.b64(data);
        e.rawSize = data.length;
        e.fileName = displayName(ctx, uri);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            e.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return e;
    }

    public static byte[] readAll(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Нет доступа к файлу");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public static String displayName(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return "file";
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
}
