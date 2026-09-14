package com.tsuyu.messenger.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.util.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class CustomizationActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextView previewInText, previewOutText, fontValue, sizeLabel;
    private SeekBar sizeSeek;
    private Switch swBold, swItalic;
    private EditText inTyping, inOnline, inLastSeen, inMessage;
    private ActivityResultLauncher<Intent> fontPicker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customization);
        prefs = new Prefs(this);

        ((TextView) findViewById(R.id.headerTitle)).setText("Кастомизация");
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        previewInText = findViewById(R.id.previewInText);
        previewOutText = findViewById(R.id.previewOutText);
        fontValue = findViewById(R.id.fontValue);
        sizeLabel = findViewById(R.id.sizeLabel);
        sizeSeek = findViewById(R.id.sizeSeek);
        swBold = findViewById(R.id.swBold);
        swItalic = findViewById(R.id.swItalic);
        inTyping = findViewById(R.id.inTyping);
        inOnline = findViewById(R.id.inOnline);
        inLastSeen = findViewById(R.id.inLastSeen);
        inMessage = findViewById(R.id.inMessage);

        sizeSeek.setProgress(prefs.textSize());
        swBold.setChecked(prefs.bold());
        swItalic.setChecked(prefs.italic());
        inTyping.setText(prefs.wordTyping());
        inOnline.setText(prefs.wordOnline());
        inLastSeen.setText(prefs.wordLastSeen());
        inMessage.setText(prefs.wordMessage());
        renderFont();

        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                prefs.setTextSize(Math.max(10, p));
                refreshPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        swBold.setOnCheckedChangeListener((b, on) -> { prefs.setBold(on); refreshPreview(); });
        swItalic.setOnCheckedChangeListener((b, on) -> { prefs.setItalic(on); refreshPreview(); });

        fontPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), res -> {
                    if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                    Uri uri = res.getData().getData();
                    if (uri != null) importFont(uri);
                });

        findViewById(R.id.rowFont).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            fontPicker.launch(i);
        });
        findViewById(R.id.btnResetFont).setOnClickListener(v -> {
            prefs.setFontPath(null);
            renderFont();
            refreshPreview();
        });

        ((Button) findViewById(R.id.btnSave)).setOnClickListener(v -> save());
        refreshPreview();
    }

    private void renderFont() {
        String p = prefs.fontPath();
        fontValue.setText(p == null ? "Стандартный" : new File(p).getName());
    }

    private void refreshPreview() {
        sizeLabel.setText("Размер текста: " + prefs.textSize() + " sp");
        if (previewInText != null) Ui.applyTextStyle(previewInText, prefs);
        if (previewOutText != null) Ui.applyTextStyle(previewOutText, prefs);
    }

    private void importFont(Uri uri) {
        new Thread(() -> {
            try {
                File out = new File(getFilesDir(), "custom_font.ttf");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                android.graphics.Typeface.createFromFile(out);
                prefs.setFontPath(out.getAbsolutePath());
                runOnUiThread(() -> { renderFont(); refreshPreview(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Не удалось загрузить шрифт (.ttf/.otf)", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void save() {
        prefs.setWordTyping(text(inTyping, "печатает"));
        prefs.setWordOnline(text(inOnline, "в сети"));
        prefs.setWordLastSeen(text(inLastSeen, "была в сети"));
        prefs.setWordMessage(text(inMessage, "Сообщение"));
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String text(EditText e, String def) {
        String v = e.getText().toString().trim();
        return v.isEmpty() ? def : v;
    }
}
