package io.tsuyu.app.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import io.tsuyu.app.R;
import io.tsuyu.app.util.Ui;

/** Circle (кружочек) recorder: circular camera preview + MediaRecorder m4v (Camera1 API). */
public class CircleRecorderActivity extends AppCompatActivity {
    private static final String TAG = "TsuyuCircle";
    private static final int LIMIT_MS = 30000;

    private SurfaceView preview;
    private Camera camera;
    private MediaRecorder recorder;
    private File out;
    private volatile boolean recording;
    private long startMs;
    private int previewW = 720, previewH = 480;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean front = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_circle_rec);
        preview = findViewById(R.id.previewSurface);

        findViewById(R.id.btnCircleCancel).setOnClickListener(v -> finish());
        findViewById(R.id.btnCircleFlip).setOnClickListener(v -> {
            front = front == false;
            openCamera();
        });
        findViewById(R.id.btnCircleRecord).setOnClickListener(v -> {
            if (recording) stopRec();
            else startRec();
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 210);
        } else {
            openCamera();
        }
    }

    private void pickCameraIndex() {
        int n = Camera.getNumberOfCameras();
        int found = -1;
        for (int i = 0; i < n; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            int want = front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
            if (info.facing == want) {
                found = i;
                break;
            }
        }
        camIndex = found >= 0 ? found : 0;
    }

    private int camIndex = 0;

    private void releaseCamera() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
            }
        } catch (Throwable ignored) {}
        camera = null;
    }

    private void openCamera() {
        releaseCamera();
        try {
            pickCameraIndex();
            camera = Camera.open(camIndex);
            Camera.Parameters p = camera.getParameters();
            if (p.getSupportedPreviewSizes() != null) {
                int bestW = 0, bestH = 0, bestScore = Integer.MAX_VALUE;
                for (Camera.Size s : p.getSupportedPreviewSizes()) {
                    int w = s.width, h = s.height;
                    int score = Math.abs(w - 720) + Math.abs(h - 480);
                    if (score < bestScore) {
                        bestScore = score;
                        bestW = w;
                        bestH = h;
                    }
                }
                if (bestW > 0) {
                    p.setPreviewSize(bestW, bestH);
                    previewW = bestW;
                    previewH = bestH;
                }
            }
            if (p.getSupportedFocusModes() != null && p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            if (front) {
                Matrix m = new Matrix();
                m.setScale(-1, 1);
                preview.setTransformationMatrix(m);
            } else {
                preview.setTransformationMatrix(new Matrix());
            }
            camera.setPreviewDisplay(preview.getHolder());
            camera.startPreview();
        } catch (Throwable t) {
            Log.e(TAG, "openCamera", t);
            Ui.toast(this, "Камера недоступна");
        }
    }

    private void startRec() {
        try {
            if (camera == null) {
                openCamera();
            }
            if (camera == null) {
                Ui.toast(this, "Камера недоступна");
                return;
            }
            out = new File(getCacheDir(), "circle_" + System.currentTimeMillis() + ".m4v");
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            recorder.setCamera(camera);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(previewH, previewH);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(800_000);
            recorder.setOutputFile(out.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            startMs = SystemClock.elapsedRealtime();
            main.postDelayed(tick, 100);
        } catch (Throwable t) {
            Log.e(TAG, "startRec", t);
            Ui.toast(this, "Ошибка записи");
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (recording == false) return;
            long dur = SystemClock.elapsedRealtime() - startMs;
            ((TextView) findViewById(R.id.tvCircleTimer)).setText(Ui.durText(dur / 1000.0));
            if (dur >= LIMIT_MS) {
                stopRec();
            } else {
                main.postDelayed(this, 100);
            }
        }
    };

    private void stopRec() {
        recording = false;
        main.removeCallbacks(tick);
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
        } catch (Throwable ignored) {}
        // recorder released the camera — reopen for preview
        releaseCamera();
        openCamera();
        if (out != null && out.exists() && out.length() > 5000) {
            ChatActivity lastChat = ChatActivity.last();
            if (lastChat != null) {
                byte[] data = Ui.readFile(out);
                if (data != null) {
                    String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                    org.json.JSONObject p = new org.json.JSONObject();
                    try {
                        p.put("m", b64);
                        p.put("wt", "m4v");
                        p.put("dur", (SystemClock.elapsedRealtime() - startMs) / 1000.0);
                    } catch (Throwable ignored) {}
                    lastChat.sendPayload(p, "circle", null, null);
                }
            }
            out.delete();
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 210) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                openCamera();
            } else {
                Ui.toast(this, "Нет доступа к камере");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recording = false;
        main.removeCallbacks(tick);
        try {
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
        } catch (Throwable ignored) {}
        releaseCamera();
    }
}
