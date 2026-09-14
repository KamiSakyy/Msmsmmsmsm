package com.tsuyu.messenger.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.CryptoUtil;
import com.tsuyu.messenger.crypto.IdentityStore;
import com.tsuyu.messenger.crypto.SessionManager;
import com.tsuyu.messenger.data.Repo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class KeysActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> importPicker;
    private TextView safetyNumberView, fingerprintView, edFingerprintView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keys);

        ((TextView) findViewById(R.id.headerTitle)).setText("Ключи шифрования");
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        safetyNumberView = findViewById(R.id.safetyNumber);
        fingerprintView = findViewById(R.id.fingerprint);
        edFingerprintView = findViewById(R.id.edFingerprint);

        renderFingerprint();

        View boxSafety = findViewById(R.id.boxSafetyNumber);
        if (boxSafety != null) {
            boxSafety.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (safetyNumberView != null) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Safety Number", safetyNumberView.getText()));
                        Toast.makeText(this, "Код безопасности скопирован", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        importPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), res -> {
                    if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                    Uri uri = res.getData().getData();
                    if (uri != null) importKeys(uri);
                });

        findViewById(R.id.rowExport).setOnClickListener(v -> exportKeys());
        findViewById(R.id.rowImport).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            importPicker.launch(i);
        });
        findViewById(R.id.rowRotate).setOnClickListener(v -> confirmRotate());
    }

    private void renderFingerprint() {
        IdentityStore id = IdentityStore.get(this);

        // Signal 60-digit safety number derived from identity key
        String sn = CryptoUtil.computeSafetyNumber(id.idPub, id.idPub);
        if (safetyNumberView != null) {
            safetyNumberView.setText(sn);
        }

        if (fingerprintView != null) {
            fingerprintView.setText(CryptoUtil.formatFingerprint(id.idPub));
        }

        if (edFingerprintView != null) {
            edFingerprintView.setText(CryptoUtil.formatFingerprint(id.edPub));
        }
    }

    private void exportKeys() {
        try {
            JSONObject o = IdentityStore.get(this).exportKeys();
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getCacheDir();
            File out = new File(dir, "tsuyu_keys.json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(o.toString(2).getBytes("UTF-8"));
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", out);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Сохранить ключи Tsuyu"));
            Toast.makeText(this, "Храните файл в безопасном месте!",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта", Toast.LENGTH_SHORT).show();
        }
    }

    private void importKeys(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));

            IdentityStore.get(this).importKeys(o);
            SessionManager.get(this).resetAll();

            String uid = Repo.get(this).uid();
            if (uid != null) Repo.get(this).publishProfile(uid, null, null, null, null, null);

            renderFingerprint();
            Toast.makeText(this, "Ключи загружены", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Неверный файл ключей", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRotate() {
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Сменить приватный ключ?")
                .setMessage("Будет создан новый ключ. Следующее отправленное сообщение "
                        + "собеседник расшифрует автоматически.")
                .setPositiveButton("Сменить", (d, w) -> Repo.get(this).rotateKeys(() -> {
                    renderFingerprint();
                    Toast.makeText(this, "Ключ обновлён", Toast.LENGTH_SHORT).show();
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }
}
