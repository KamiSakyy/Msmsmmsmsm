package io.tsuyu.app.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

import io.tsuyu.app.R;

public class VideoPlayerActivity extends AppCompatActivity {
    private ExoPlayer player;
    private boolean circle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);
        String path = getIntent().getStringExtra("path");
        circle = getIntent().getBooleanExtra("circle", false);
        if (circle) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        PlayerView pv = findViewById(R.id.playerView);
        player = new ExoPlayer.Builder(this).build();
        pv.setPlayer(player);
        if (circle) {
            pv.setUseController(false);
        }
        if (path != null) {
            MediaItem item = MediaItem.fromUri("file://" + path);
            player.setMediaItem(item);
            player.prepare();
            player.setPlayWhenReady(true);
        }
        findViewById(R.id.btnVideoClose).setOnClickListener(v -> finish());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.release();
        player = null;
    }
}
