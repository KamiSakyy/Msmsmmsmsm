package io.tsuyu.app.media;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Voice recording to M4A (AAC) with amplitude waveform sampling. */
public class VoiceRec {
    public interface StopCallback { void onStop(File file, double[] wave, long durationMs); }

    private final Context ctx;
    private MediaRecorder recorder;
    private File out;
    private volatile boolean rec = false;
    private long startMs;
    private Thread ampThread;
    private AudioRecord ampRec;
    private final List<Integer> amps = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private StopCallback cb;

    public VoiceRec(Context ctx) { this.ctx = ctx; }

    public void start(StopCallback cb) {
        try {
            this.cb = cb;
            out = new File(ctx.getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setOutputFile(out.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            rec = true;
            startMs = SystemClock.elapsedRealtime();
            ampThread = new Thread(this::sampleAmps);
            ampThread.start();
        } catch (Throwable t) {
            stop();
        }
    }

    public long durationMs() {
        return rec ? SystemClock.elapsedRealtime() - startMs : 0;
    }

    private void sampleAmps() {
        try {
            int rate = 44100;
            int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ampRec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            short[] buf = new short[minBuf];
            ampRec.startRecording();
            while (rec) {
                int n = ampRec.read(buf, 0, buf.length);
                if (n > 0) {
                    int max = 0;
                    for (int i = 0; i < n; i++) {
                        int v = Math.abs(buf[i]);
                        if (v > max) max = v;
                    }
                    amps.add(max);
                }
                Thread.sleep(60);
            }
        } catch (Throwable ignored) {} finally {
            try {
                if (ampRec != null) {
                    ampRec.stop();
                    ampRec.release();
                }
            } catch (Throwable ignored) {}
        }
    }

    public void stop() {
        if (!rec) return;
        rec = false;
        long dur = SystemClock.elapsedRealtime() - startMs;
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
        } catch (Throwable ignored) {}
        List<Integer> copy = new ArrayList<>(amps);
        main.post(() -> {
            StopCallback c = cb;
            cb = null;
            if (c != null) {
                c.onStop(out, waveFrom(copy, 32), dur);
            }
        });
    }

    public static double[] waveFrom(List<Integer> amps, int points) {
        if (amps == null || amps.isEmpty()) {
            double[] d = new double[points];
            java.util.Random r = new java.util.Random(42);
            for (int i = 0; i < points; i++) d[i] = 0.3 + r.nextDouble() * 0.4;
            return d;
        }
        double[] out = new double[points];
        int chunk = Math.max(1, amps.size() / points);
        int maxAll = 1;
        for (int a : amps) if (a > maxAll) maxAll = a;
        for (int i = 0; i < points; i++) {
            int sum = 0, n = 0;
            for (int j = 0; j < chunk && i * chunk + j < amps.size(); j++) {
                sum += amps.get(i * chunk + j);
                n++;
            }
            double v = n == 0 ? 0.2 : (sum / (double) n) / maxAll;
            out[i] = Math.max(0.12, Math.min(1.0, v * 1.2));
        }
        return out;
    }
}
