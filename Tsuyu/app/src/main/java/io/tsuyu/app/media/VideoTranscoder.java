package io.tsuyu.app.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * H.264 + AAC video transcoder (downscale to fit RTDB 10MB limit).
 * Pipeline: MediaExtractor -> decoder(ImageReader PRIVATE_8888) -> YUV->RGB ->
 * Canvas scale -> encoder(input Surface) -> collect frames -> MediaMuxer.
 * Audio is copied (AAC passthrough). Any failure is reported via onError.
 */
public class VideoTranscoder {
    private static final String TAG = "TsuyuVideo";

    public interface Callback {
        void onDone(File file, int w, int h, long durMs);
        void onError(String msg);
    }

    private static class Sample {
        long pts;
        byte[] data;
        int track;
        Sample(long pts, int track, byte[] data) {
            this.pts = pts;
            this.track = track;
            this.data = data;
        }
    }

    public static void transcode(final Context ctx, final Uri inUri, final int maxW, final int maxH,
                                 final int bitrate, final Callback cb) {
        Thread t = new Thread(() -> {
            try {
                transcode0(ctx, inUri, maxW, maxH, bitrate, cb);
            } catch (Throwable e) {
                Log.e(TAG, "transcode", e);
                cb.onError(e.getMessage() == null ? "transcode failed" : e.getMessage());
            }
        }, "tsuyu-transcode");
        t.start();
    }

    private static int findTrack(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static void transcode0(Context ctx, Uri inUri, int maxW, int maxH, int bitrate, Callback cb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, inUri);
        int videoTrack = findTrack(ex, "video/");
        if (videoTrack < 0) throw new IllegalArgumentException("no video track");
        MediaFormat vfmt = ex.getTrackFormat(videoTrack);
        String vmime = vfmt.getString(MediaFormat.KEY_MIME);
        if (!"video/avc".equals(vmime) && !"video/hevc".equals(vmime)) {
            throw new IllegalArgumentException("unsupported codec " + vmime);
        }
        int srcW = vfmt.getInteger(MediaFormat.KEY_WIDTH);
        int srcH = vfmt.getInteger(MediaFormat.KEY_HEIGHT);
        float scale = Math.min(Math.min((float) maxW / srcW, (float) maxH / srcH), 1.0f);
        int outW = Math.max(160, (int) (srcW * scale / 2) * 2);
        int outH = Math.max(160, (int) (srcH * scale / 2) * 2);

        int audioTrack = findTrack(ex, "audio/");
        MediaFormat afmt = null;
        boolean audioCopy = false;
        if (audioTrack >= 0) {
            afmt = ex.getTrackFormat(audioTrack);
            audioCopy = "audio/mp4a-latm".equals(afmt.getString(MediaFormat.KEY_MIME));
        }
        long durationUs = 0;
        try {
            if (vfmt.containsKey(MediaFormat.KEY_DURATION)) durationUs = vfmt.getLong(MediaFormat.KEY_DURATION);
        } catch (Throwable ignored) {}

        // encoder
        MediaFormat efmt = MediaFormat.createVideoFormat("video/avc", outW, outH);
        efmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        efmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        efmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        efmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        final MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(efmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        final Canvas encoderCanvas = new Canvas(encoder.createInputSurface());
        encoder.start();

        // decoder -> ImageReader
        MediaFormat dfmt = MediaFormat.createVideoFormat(vmime, srcW, srcH);
        final MediaCodec decoder = MediaCodec.createDecoderByType(vmime);
        final ImageReader ir = ImageReader.newInstance(srcW, srcH, ImageFormat_PRIVATE(), 4);
        ir.setOnImageAvailableListener(r -> r.acquireNextImage(), null);
        decoder.configure(dfmt, ir.getSurface(), null, 0);
        ex.selectTrack(videoTrack);
        decoder.start();

        final List<Sample> frames = new ArrayList<>();
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // Main loop: feed input, pull decoded frames, encode each
        boolean inputDone = false;
        int fedFrames = 0;
        int encodedFrames = 0;
        long loopGuard = System.currentTimeMillis();

        while (System.currentTimeMillis() - loopGuard < 120000) {
            // feed input
            if (!inputDone) {
                int inIdx = decoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                    int size = inBuf == null ? -1 : ex.readSampleData(inBuf, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                        fedFrames++;
                    }
                }
            }

            int outIdx = decoder.dequeueOutputBuffer(info, inputDone ? 20000 : 30000);
            if (outIdx >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(outIdx, false);
                if (eos) break;
                if (info.size <= 0) continue;

                Image img = null;
                long t0 = System.currentTimeMillis();
                while (img == null && System.currentTimeMillis() - t0 < 3000) {
                    img = ir.acquireLatestImage();
                    if (img == null) Thread.sleep(5);
                }
                if (img == null) continue;
                Bitmap src = yuvToBitmap(img);
                img.close();
                if (src == null) continue;

                float s = Math.min((float) outW / src.getWidth(), (float) outH / src.getHeight());
                int nw = Math.max(2, (int) (src.getWidth() * s / 2) * 2);
                int nh = Math.max(2, (int) (src.getHeight() * s / 2) * 2);
                Bitmap dst = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
                Canvas dc = new Canvas(dst);
                dc.drawColor(0xFF000000);
                dc.drawBitmap(src, null, new Rect(0, 0, nw, nh), paint);
                src.recycle();

                synchronized (encoderCanvas) {
                    encoderCanvas.drawColor(0xFF000000);
                    encoderCanvas.drawBitmap(dst, 0, 0, paint);
                }
                dst.recycle();

                // drain encoder until one buffer with data
                boolean got = false;
                long t1 = System.currentTimeMillis();
                while (!got && System.currentTimeMillis() - t1 < 8000) {
                    int eIdx = encoder.dequeueOutputBuffer(info, 10000);
                    if (eIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Thread.sleep(5);
                    } else if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // format changed, continue
                    } else if (eIdx >= 0) {
                        byte[] d = readBuf(encoder, eIdx, info);
                        if (d != null && d.length > 0) frames.add(new Sample(info.presentationTimeUs, 0, d));
                        encoder.releaseOutputBuffer(eIdx, false);
                        got = true;
                    }
                }
                encodedFrames++;
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                break;
            }
        }

        // encoder EOS + drain
        try {
            encoder.signalEndOfInputStream();
        } catch (Throwable ignored) {}
        boolean encDone = false;
        long encGuard = System.currentTimeMillis();
        while (!encDone && System.currentTimeMillis() - encGuard < 15000) {
            int eIdx = encoder.dequeueOutputBuffer(info, 20000);
            if (eIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(10);
            } else if (eIdx >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (!eos) {
                    byte[] d = readBuf(encoder, eIdx, info);
                    if (d != null && d.length > 0) frames.add(new Sample(info.presentationTimeUs, 0, d));
                }
                encoder.releaseOutputBuffer(eIdx, false);
                if (eos) encDone = true;
            }
        }

        // audio copy
        List<Sample> audio = new ArrayList<>();
        if (audioTrack >= 0 && audioCopy) {
            ex.selectTrack(audioTrack);
            byte[] tmp = new byte[262144];
            while (true) {
                int size = ex.readSampleData(tmp, 0);
                if (size < 0) break;
                byte[] d = new byte[size];
                System.arraycopy(tmp, 0, d, 0, size);
                audio.add(new Sample(ex.getSampleTime(), 1, d));
                ex.advance();
            }
        }

        ex.release();
        decoder.release();
        encoder.release();
        ir.close();
        if (encodedFrames < 5) throw new IllegalArgumentException("too few frames encoded");

        // mux
        File outFile = new File(ctx.getCacheDir(), "vidout_" + System.currentTimeMillis() + ".mp4");
        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaFormat vf = MediaFormat.createVideoFormat("video/avc", outW, outH);
        Sample csd = null;
        for (Sample s : frames) {
            if (s.data.length > 4 && isCsd(s.data)) { csd = s; break; }
        }
        if (csd != null) {
            ByteBuffer bb = ByteBuffer.allocate(csd.data.length);
            bb.put(csd.data);
            vf.setByteBuffer("csd-0", bb);
        }
        int vt = muxer.addTrack(vf);
        int at = -1;
        if (!audio.isEmpty() && afmt != null) at = muxer.addTrack(afmt);
        muxer.start();

        List<Sample> all = new ArrayList<>();
        for (Sample s : frames) {
            if (s == csd) continue;
            Sample ns = new Sample(s.pts, vt, s.data);
            all.add(ns);
        }
        for (Sample s : audio) all.add(new Sample(s.pts, at, s.data));
        all.sort(Comparator.comparingLong(x -> x.pts));
        MediaCodec.BufferInfo mi = new MediaCodec.BufferInfo();
        for (Sample s : all) {
            mi.offset = 0;
            mi.size = s.data.length;
            mi.presentationTimeUs = s.pts;
            mi.flags = 0;
            try {
                muxer.writeSampleData(s.track, ByteBuffer.wrap(s.data), mi);
            } catch (Throwable t) {
                Log.w(TAG, "mux skip", t);
            }
        }
        muxer.stop();
        muxer.release();
        cb.onDone(outFile, outW, outH, durationUs / 1000);
    }

    private static int ImageFormat_PRIVATE() {
        return 0x101; // PixelFormat.PRIVATE_8888
    }

    private static boolean isCsd(byte[] d) {
        // SPS starts with 00 00 01 67 ; PPS with 00 00 01 68
        if (d.length < 5) return false;
        int n = Math.min(d.length - 1, 32);
        for (int i = 0; i < n; i++) {
            if (d[i] == 0 && d[i + 1] == 0) {
                if (i + 2 < d.length && d[i + 2] == 1) {
                    if (d[i + 3] == 0x67 || d[i + 3] == 0x68) return true;
                }
            }
        }
        return false;
    }

    private static byte[] readBuf(MediaCodec codec, int idx, MediaCodec.BufferInfo info) {
        try {
            ByteBuffer bb = codec.getOutputBuffer(idx);
            if (bb == null) return null;
            bb.position(info.offset);
            bb.limit(info.offset + info.size);
            byte[] d = new byte[info.size];
            bb.get(d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Convert PRIVATE_8888 (NV21-like) image to RGB Bitmap (approx YUV420 -> RGB). */
    private static Bitmap yuvToBitmap(Image img) {
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            Image.Plane[] planes = img.getPlanes();
            int pStride = planes[0].getPixelStride();
            int rStride = planes[0].getRowStride();
            int uvStride = planes[1].getRowStride();
            int uvPixel = Math.max(1, planes[1].getPixelStride());

            byte[] y = new byte[rStride * h];
            byte[] uv = new byte[Math.max(1, uvStride * (h / 2))];
            ByteBuffer yb = planes[0].getBuffer();
            yb.get(y);
            ByteBuffer uvb = planes[1].getBuffer();
            int n = Math.min(uv.length, uvb.remaining());
            uvb.get(uv, 0, n);

            int[] px = new int[w * h];
            for (int r = 0; r < h; r++) {
                int uvRow = (r / 2) * uvStride;
                for (int c = 0; c < w; c++) {
                    int yv = y[r * rStride + c * Math.max(1, pStride)] & 0xff;
                    int u = uv[uvRow + (c / 2) * uvPixel] & 0xff;
                    int v = uv[uvRow + (c / 2) * uvPixel + 1] & 0xff;
                    int cc = c * 2 / 2;
                    // BT.601
                    int yi = yv - 16;
                    int ui = u - 128;
                    int vi = v - 128;
                    int R = yi + (354 * vi) >> 8;
                    int G = yi - ((88 * ui) + (183 * vi)) >> 8;
                    int B = yi + (454 * ui) >> 8;
                    R = Math.max(0, Math.min(255, R));
                    G = Math.max(0, Math.min(255, G));
                    B = Math.max(0, Math.min(255, B));
                    px[r * w + c] = 0xFF000000 | (R << 16) | (G << 8) | B;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
