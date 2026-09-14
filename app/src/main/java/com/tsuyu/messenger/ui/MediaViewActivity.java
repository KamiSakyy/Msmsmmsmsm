package com.tsuyu.messenger.ui;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.CryptoUtil;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.util.Fmt;
import com.tsuyu.messenger.util.Ui;

import java.io.File;
import java.io.FileOutputStream;

/** Full-screen viewer: pinch-zoom photos, custom video player, download. */
public class MediaViewActivity extends AppCompatActivity {

    public static Models.Message payload;

    private VideoView videoView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Models.Attachment att;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs prefs = new Prefs(this);
        if (prefs.secureScreen()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_media_view);

        int index = getIntent().getIntExtra("index", 0);
        if (payload == null || payload.attachments.isEmpty()) { finish(); return; }
        att = payload.attachments.get(Math.min(index, payload.attachments.size() - 1));

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnDownload).setOnClickListener(v -> download());

        if (Models.T_PHOTO.equals(att.type)) showPhoto();
        else showVideo();
    }

    private void showPhoto() {
        ZoomImageView iv = findViewById(R.id.photoView);
        iv.setVisibility(View.VISIBLE);
        Bitmap bmp = Ui.decodeB64(att.data);
        if (bmp == null) { Toast.makeText(this, "Ошибка", Toast.LENGTH_SHORT).show(); finish(); return; }
        iv.setImageBitmap(bmp);
    }

    private void showVideo() {
        findViewById(R.id.videoWrap).setVisibility(View.VISIBLE);
        videoView = findViewById(R.id.videoView);
        ImageView play = findViewById(R.id.videoPlay);
        SeekBar seek = findViewById(R.id.videoSeek);
        TextView time = findViewById(R.id.videoTime);

        try {
            File f = cacheFile(Models.T_CIRCLE.equals(att.type) ? ".mp4" : ".mp4");
            videoView.setVideoURI(Uri.fromFile(f));
            videoView.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                seek.setMax(videoView.getDuration());
                videoView.start();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (videoView == null) return;
                        seek.setProgress(videoView.getCurrentPosition());
                        time.setText(Fmt.duration(videoView.getCurrentPosition()) + " / "
                                + Fmt.duration(videoView.getDuration()));
                        ui.postDelayed(this, 200);
                    }
                });
            });
            play.setOnClickListener(v -> {
                if (videoView.isPlaying()) {
                    videoView.pause();
                    play.setImageResource(R.drawable.ic_play);
                } else {
                    videoView.start();
                    play.setImageResource(R.drawable.ic_pause);
                }
            });
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) videoView.seekTo(p);
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть видео", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private File cacheFile(String ext) throws Exception {
        File f = new File(getCacheDir(), "view_" + Math.abs(att.data.hashCode()) + ext);
        if (!f.exists() || f.length() == 0) {
            byte[] data = CryptoUtil.unb64(att.data);
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
        }
        return f;
    }

    private void download() {
        new Thread(() -> {
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) dir = getCacheDir();
                String ext = Models.T_PHOTO.equals(att.type) ? ".jpg" : ".mp4";
                File out = new File(dir, "Tsuyu_" + System.currentTimeMillis() + ext);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(CryptoUtil.unb64(att.data));
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "Сохранено: " + out.getName(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка сохранения",
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoView != null) videoView.stopPlayback();
        videoView = null;
    }
}
