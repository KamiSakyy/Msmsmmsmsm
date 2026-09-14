package com.tsuyu.messenger.media;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Records AAC voice notes and samples amplitude for the Telegram-style waveform. */
public class VoiceRecorder {

    public interface Listener {
        void onAmplitude(int level, long elapsedMs);
    }

    private MediaRecorder recorder;
    private File output;
    private long startTime;
    private final List<Integer> amplitudes = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean recording;

    public boolean isRecording() { return recording; }

    public void start(Context ctx, Listener l) throws Exception {
        this.listener = l;
        amplitudes.clear();
        output = new File(ctx.getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");

        recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(ctx) : new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setOutputFile(output.getAbsolutePath());
        recorder.prepare();
        recorder.start();

        recording = true;
        startTime = System.currentTimeMillis();
        handler.post(sampler);
    }

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!recording || recorder == null) return;
            int amp = 0;
            try { amp = recorder.getMaxAmplitude(); } catch (Exception ignored) { }
            int level = Math.min(100, (int) (amp / 200f));
            amplitudes.add(level);
            if (listener != null) listener.onAmplitude(level, System.currentTimeMillis() - startTime);
            handler.postDelayed(this, 80);
        }
    };

    public static class Result {
        public File file;
        public long durationMs;
        public int[] waveform;
    }

    /** Stops and returns the recording, or null if it was too short. */
    public Result stop() {
        recording = false;
        handler.removeCallbacks(sampler);
        long dur = System.currentTimeMillis() - startTime;
        try {
            if (recorder != null) { recorder.stop(); recorder.release(); }
        } catch (Exception e) {
            if (output != null) output.delete();
            recorder = null;
            return null;
        }
        recorder = null;
        if (dur < 600 || output == null || !output.exists()) {
            if (output != null) output.delete();
            return null;
        }
        Result r = new Result();
        r.file = output;
        r.durationMs = dur;
        r.waveform = downsample(amplitudes, 48);
        return r;
    }

    public void cancel() {
        recording = false;
        handler.removeCallbacks(sampler);
        try {
            if (recorder != null) { recorder.stop(); recorder.release(); }
        } catch (Exception ignored) { }
        recorder = null;
        if (output != null) output.delete();
    }

    /** Compresses the amplitude series into N bars (3..100). */
    public static int[] downsample(List<Integer> src, int bars) {
        int[] out = new int[bars];
        if (src.isEmpty()) {
            for (int i = 0; i < bars; i++) out[i] = 12;
            return out;
        }
        for (int i = 0; i < bars; i++) {
            int from = i * src.size() / bars;
            int to = Math.max(from + 1, (i + 1) * src.size() / bars);
            int max = 0;
            for (int j = from; j < to && j < src.size(); j++) max = Math.max(max, src.get(j));
            out[i] = Math.max(6, Math.min(100, max));
        }
        return out;
    }
}
