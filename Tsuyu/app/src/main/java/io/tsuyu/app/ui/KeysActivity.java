package io.tsuyu.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import io.tsuyu.app.R;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.Keys;
import io.tsuyu.app.util.Ui;

import java.io.File;

public class KeysActivity extends AppCompatActivity {
    private static final int EXPORT_FILE = 801;
    private static final int IMPORT_FILE = 802;
    private byte[] exportBytes;
    private String exportName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keys);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        TextView fp = findViewById(R.id.tvFingerprint);
        fp.setText(Keys.fingerprint(this));

        findViewById(R.id.btnRotate).setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Сменить приватный ключ?")
                    .setMessage("Будут созданы новые ключи. Старые сохранятся на устройстве для расшифровки истории. Новые сообщения расшифнутся у собеседника мгновенно.")
                    .setPositiveButton("Сменить", (d, w) -> {
                        Keys.rotate(this);
                        Fb.publishBundle(this);
                        fp.setText(Keys.fingerprint(this));
                        Ui.toast(this, "Ключи обновлены ✓");
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });

        findViewById(R.id.btnExport).setOnClickListener(v -> {
            final EditText pass = new EditText(this);
            pass.setHint("Пароль для защиты ключей");
            pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Скачать ключи")
                    .setView(pass)
                    .setMessage("Пароль необходим для импорта на новом телефоне. Забудьте пароль — ключи недоступны.")
                    .setPositiveButton("Экспортировать", (d, w) -> {
                        String p = pass.getText().toString();
                        if (p.length() < 4) {
                            Ui.toast(this, "Пароль минимум 4 символа");
                            return;
                        }
                        Ui.toast(this, "Генерирую...");
                        new Thread(() -> {
                            byte[] data = Keys.exportAll(this, p);
                            runOnUiThread(() -> {
                                if (data == null) {
                                    Ui.toast(this, "Ошибка экспорта");
                                    return;
                                }
                                exportBytes = data;
                                exportName = "tsuyu_keys_" + System.currentTimeMillis() + ".bin";
                                File f = new File(getCacheDir(), exportName);
                                try {
                                    java.io.FileOutputStream fos = new FileOutputStream(f);
                                    fos.write(data);
                                    fos.close();
                                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                    i.addCategory(Intent.CATEGORY_OPENABLE);
                                    i.setType("application/octet-stream");
                                    i.putExtra(Intent.EXTRA_TITLE, exportName);
                                    startActivityForResult(i, EXPORT_FILE);
                                } catch (Throwable t) {
                                    Ui.toast(this, "Ошибка записи");
                                }
                            });
                        }).start();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });

        findViewById(R.id.btnImport).setOnClickListener(v -> {
            final EditText pass = new EditText(this);
            pass.setHint("Пароль от файла ключей");
            pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            // ask password first, then file
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Импортировать ключи")
                    .setView(pass)
                    .setMessage("Введите пароль, затем выберите файл ключей.")
                    .setPositiveButton("Далее", (d, w) -> {
                        pendingImportPass = pass.getText().toString();
                        startActivityForResult(Intent.createChooser(i, "Файл ключей"), IMPORT_FILE);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });
    }

    private String pendingImportPass;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == EXPORT_FILE && exportBytes != null) {
                if (data.getData() == null) return;
                java.io.OutputStream os = getContentResolver().openOutputStream(data.getData());
                os.write(exportBytes);
                os.close();
                Ui.toast(this, "Ключи скачаны ✓");
            } else if (requestCode == IMPORT_FILE && data.getData() != null) {
                java.io.InputStream is = getContentResolver().openInputStream(data.getData());
                byte[] buf = new byte[1 << 20];
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                String p = pendingImportPass;
                boolean ok = Keys.importAll(this, bos.toByteArray(), p);
                if (ok) {
                    Fb.publishBundle(this);
                    Ui.toast(this, "Ключи импортированы ✓");
                    TextView fp = findViewById(R.id.tvFingerprint);
                    fp.setText(Keys.fingerprint(this));
                } else {
                    Ui.toast(this, "Неверный пароль или файл");
                }
            }
        } catch (Throwable t) {
            Ui.toast(this, "Ошибка");
        }
    }
}
