package com.tsuyu.messenger.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.media.MediaCodecUtil;
import com.tsuyu.messenger.util.Fmt;

import java.io.File;

/** Records a short round video message, Telegram style. */
public class CircleRecordActivity extends AppCompatActivity {

    private PreviewView previewView;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ProcessCameraProvider provider;
    private CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;

    private TextView timer;
    private ImageView btnRecord;
    private long startTime;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File outFile;

    private static final long MAX_MS = 60_000;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_circle_record);

        previewView = findViewById(R.id.preview);
        timer = findViewById(R.id.timer);
        btnRecord = findViewById(R.id.btnRecord);

        findViewById(R.id.btnCancel).setOnClickListener(v -> {
            cancel();
            finish();
        });
        findViewById(R.id.btnFlip).setOnClickListener(v -> {
            selector = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                    ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
            bindCamera();
        });
        btnRecord.setOnClickListener(v -> {
            if (recording == null) startRecording();
            else stopRecording();
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 7);
        } else {
            bindCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        boolean ok = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) bindCamera();
        else { Toast.makeText(this, "Нужен доступ к камере", Toast.LENGTH_SHORT).show(); finish(); }
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                provider.bindToLifecycle(this, selector, preview, videoCapture);
            } catch (Exception e) {
                Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("MissingPermission")
    private void startRecording() {
        if (videoCapture == null) return;
        outFile = new File(getCacheDir(), "circle_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(outFile).build();

        recording = videoCapture.getOutput()
                .prepareRecording(this, opts)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), event -> {
                    if (event instanceof VideoRecordEvent.Finalize) {
                        onFinalized();
                    }
                });

        startTime = System.currentTimeMillis();
        btnRecord.setImageResource(R.drawable.ic_check);
        ui.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (recording == null) return;
            long el = System.currentTimeMillis() - startTime;
            timer.setText(Fmt.duration(el));
            if (el >= MAX_MS) { stopRecording(); return; }
            ui.postDelayed(this, 200);
        }
    };

    private void stopRecording() {
        if (recording == null) return;
        recording.stop();
        recording = null;
        ui.removeCallbacks(tick);
    }

    private void onFinalized() {
        if (outFile == null || !outFile.exists() || outFile.length() == 0) { finish(); return; }
        long dur = System.currentTimeMillis() - startTime;
        if (dur < 700) {
            outFile.delete();
            Toast.makeText(this, "Слишком коротко", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (outFile.length() > MediaCodecUtil.MAX_RAW) {
            outFile.delete();
            Toast.makeText(this, "Кружочек слишком длинный", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String thumb = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(outFile.getAbsolutePath());
            Bitmap frame = r.getFrameAtTime(0);
            if (frame != null) thumb = MediaCodecUtil.thumbnailB64(frame, 320, 65);
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }

        Intent data = new Intent();
        data.putExtra("path", outFile.getAbsolutePath());
        data.putExtra("duration", dur);
        data.putExtra("thumb", thumb);
        setResult(RESULT_OK, data);
        finish();
    }

    private void cancel() {
        if (recording != null) { recording.close(); recording = null; }
        if (outFile != null) outFile.delete();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        if (provider != null) provider.unbindAll();
    }
}
