package io.tsuyu.app.media;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Video pre-send processor.
 * Checks size and reports original dimensions/duration.
 * Files within the RTDB-safe limit are passed through; larger ones are rejected
 * (hardware transcode can be added later without API changes).
 */
public class VideoTranscoder {
    private static final String TAG = "TsuyuVideo";
    /** ~6.5MB binary -> ~8.7MB base64, safe for the 10MB RTDB value limit. */
    public static final long MAX_BYTES = 6_400_000;

    public interface Callback {
        void onDone(File file, int w, int h, long durMs);
        void onError(String msg);
    }

    public static void transcode(final Context ctx, final Uri inUri, final int maxW, final int maxH,
                                 final int bitrate, final Callback cb) {
        Thread t = new Thread(() -> {
            try {
                String path = inUri.getPath();
                if (path == null) {
                    cb.onError("bad uri");
                    return;
                }
                File in = new File(path);
                if (in.exists() == false) {
                    cb.onError("file missing");
                    return;
                }
                if (in.length() > MAX_BYTES) {
                    cb.onError("video too big (max ~6.5 MB)");
                    return;
                }
                int w = 0, h = 0;
                long durMs = 0;
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(in.getAbsolutePath());
                    w = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    h = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) durMs = Long.parseLong(d);
                    mmr.release();
                } catch (Throwable t2) {
                    Log.w(TAG, "probe failed", t2);
                }
                File out = new File(ctx.getCacheDir(), "vidpass_" + System.currentTimeMillis() + ".mp4");
                copy(in, out);
                cb.onDone(out, w, h, durMs);
            } catch (Throwable e) {
                Log.e(TAG, "transcode", e);
                cb.onError(e.getMessage() == null ? "transcode failed" : e.getMessage());
            }
        }, "tsuyu-transcode");
        t.start();
    }

    private static void copy(File in, File out) throws Exception {
        InputStream is = new FileInputStream(in);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        is.close();
        os.close();
    }
}
