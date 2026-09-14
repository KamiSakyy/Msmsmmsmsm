package io.tsuyu.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import io.tsuyu.app.R;
import io.tsuyu.app.util.Ui;

public class ImageViewerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        TextView info = findViewById(R.id.tvViewerInfo);
        info.setText(getIntent().getStringExtra("info") == null ? "" : getIntent().getStringExtra("info"));
        ZoomImageView iv = findViewById(R.id.imageView);
        if (path != null) {
            try {
                Bitmap bm = BitmapFactory.decodeFile(path);
                if (bm != null) iv.setImageBitmap(bm);
            } catch (Throwable ignored) {}
        }
        findViewById(R.id.btnViewerClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnViewerDownload).setOnClickListener(v -> {
            try {
                File f = new File(path);
                byte[] data = Ui.readFile(f);
                String name = "Tsuyu_" + f.getName();
                String saved = Ui.saveToDownloads(this, name, data, "image/jpeg");
                Ui.toast(this, saved != null ? "Сохранено в Загрузки" : "Не удалось сохранить");
            } catch (Throwable t) {
                Ui.toast(this, "Ошибка");
            }
        });
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
