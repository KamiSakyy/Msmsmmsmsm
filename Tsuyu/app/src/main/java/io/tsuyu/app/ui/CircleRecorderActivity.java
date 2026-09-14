package io.tsuyu.app.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceTextureListener;
import android.view.TextureView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import io.tsuyu.app.R;
import io.tsuyu.app.util.Ui;

/** Circle (кружочек) recorder: camera preview in a circle, MediaRecorder video-only m4v. */
public class CircleRecorderActivity extends AppCompatActivity {
    private static final String TAG = "TsuyuCircle";
    private static final int LIMIT_MS = 30000;

    private TextureView textureView;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private File out;
    private volatile boolean recording;
    private long startMs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean front = true;
    private String camId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_circle_rec);
        textureView = new TextureView(this);
        // replace SurfaceView with TextureView in the preview box
        android.widget.FrameLayout box = findViewById(R.id.circlePreviewBox);
        android.view.SurfaceView sv = findViewById(R.id.previewSurface);
        int idx = box.indexOfChild(sv);
        box.removeView(sv);
        textureView.setOpaque(false);
        box.addView(textureView, idx);

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
            return;
        }
        TextureView.SurfaceTextureListener l = new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                openCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        };
        textureView.setSurfaceTextureListener(l);
    }

    private void openCamera() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            camId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                int facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    camId = id;
                    break;
                }
            }
            if (camId == null && cm.getCameraIdList().length > 0) camId = cm.getCameraIdList()[0];
            if (camId == null) return;
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    camera = c;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); camera = null; }
                @Override public void onError(CameraDevice c, int error) { c.close(); camera = null; }
            }, main);
        } catch (Throwable t) {
            Log.e(TAG, "openCamera", t);
        }
    }

    private void createSession() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            int w = Math.max(480, textureView.getWidth());
            int h = Math.max(480, textureView.getHeight());
            st.setDefaultBufferSize(w, h);
            Surface surface = new Surface(st);
            camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        android.hardware.camera2.CameraCaptureRequest.Builder b =
                                camera.createCaptureRequest(android.hardware.camera2.CameraCaptureRequest.TEMPLATE_RECORD);
                        b.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        s.setRepeatingRequest(b.build(), null, main);
                    } catch (Throwable t) {
                        Log.e(TAG, "repeat", t);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {}
            }, main);
        } catch (Throwable t) {
            Log.e(TAG, "createSession", t);
        }
    }

    private void startRec() {
        try {
            if (session == null) {
                Ui.toast(this, "Камера ещё инициализируется...");
                return;
            }
            out = new File(getCacheDir(), "circle_" + System.currentTimeMillis() + ".m4v");
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(480, 480);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(800_000);
            recorder.setOutputFile(out.getAbsolutePath());
            recorder.prepare();
            Surface recSurface = recorder.getSurface();
            // stop preview session, rebuild with recorder surface
            session.close();
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(480, 480);
            Surface previewSurface = new Surface(st);
            camera.createCaptureSession(java.util.Arrays.asList(previewSurface, recSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                android.hardware.camera2.CameraCaptureRequest.Builder b =
                                        camera.createCaptureRequest(android.hardware.camera2.CameraCaptureRequest.TEMPLATE_RECORD);
                                b.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                s.setRepeatingRequest(b.build(), null, main);
                                recorder.start();
                                recording = true;
                                startMs = SystemClock.elapsedRealtime();
                                main.postDelayed(tick, 100);
                            } catch (Throwable t) {
                                Log.e(TAG, "start", t);
                                Ui.toast(CircleRecorderActivity.this, "Ошибка записи");
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            Ui.toast(CircleRecorderActivity.this, "Ошибка камеры");
                        }
                    }, main);
        } catch (Throwable t) {
            Log.e(TAG, "startRec", t);
            Ui.toast(this, "Ошибка записи");
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
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
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(480, 480);
            Surface previewSurface = new Surface(st);
            if (camera != null) {
                camera.createCaptureSession(Collections.singletonList(previewSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try {
                                    android.hardware.camera2.CameraCaptureRequest.Builder b =
                                            camera.createCaptureRequest(android.hardware.camera2.CameraCaptureRequest.TEMPLATE_RECORD);
                                    b.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    s.setRepeatingRequest(b.build(), null, main);
                                } catch (Throwable ignored) {}
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) {}
                        }, main);
            }
        } catch (Throwable ignored) {}
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
            if (ok && camera == null) {
                textureView.getSurfaceTexture();
                if (textureView.isAvailable()) openCamera();
            } else if (ok == false) {
                Ui.toast(this, "Нет доступа к камере");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recording = false;
        try {
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            if (session != null) session.close();
            if (camera != null) camera.close();
        } catch (Throwable ignored) {}
    }
}
