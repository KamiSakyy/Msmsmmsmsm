package com.tsuyu.messenger.ui;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.TsuyuApp;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.data.Repo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextView soundValue;
    private ActivityResultLauncher<Intent> soundPicker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        ((TextView) findViewById(R.id.headerTitle)).setText("Настройки");
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        soundValue = findViewById(R.id.soundValue);

        Switch swNotif = findViewById(R.id.swNotif);
        Switch swVibrate = findViewById(R.id.swVibrate);
        Switch swGhost = findViewById(R.id.swGhost);

        swNotif.setChecked(prefs.notificationsEnabled());
        swVibrate.setChecked(prefs.vibrate());
        swGhost.setChecked(prefs.ghost());

        swNotif.setOnCheckedChangeListener((b, on) -> {
            prefs.setNotificationsEnabled(on);
            TsuyuApp.get().createChannels();
        });
        swVibrate.setOnCheckedChangeListener((b, on) -> prefs.setVibrate(on));
        swGhost.setOnCheckedChangeListener((b, on) -> {
            prefs.setGhost(on);
            Repo.get(this).goOnline();
        });

        soundPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), res -> {
                    if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                    Uri uri = res.getData().getData();
                    if (uri != null) importSound(uri);
                });

        findViewById(R.id.rowSound).setOnClickListener(v -> chooseSound());
        findViewById(R.id.btnTestSound).setOnClickListener(v -> testSound());

        findViewById(R.id.rowCustomization).setOnClickListener(v ->
                startActivity(new Intent(this, CustomizationActivity.class)));
        findViewById(R.id.rowPrivacy).setOnClickListener(v ->
                startActivity(new Intent(this, PrivacyActivity.class)));
        findViewById(R.id.rowKeys).setOnClickListener(v ->
                startActivity(new Intent(this, KeysActivity.class)));

        findViewById(R.id.rowLogout).setOnClickListener(v -> logout());

        renderSound();
    }

    private void renderSound() {
        String s = prefs.notificationSoundRaw();
        if (s == null || "builtin".equals(s)) soundValue.setText("Стандартный Tsuyu");
        else soundValue.setText("Свой звук");
    }

    private void chooseSound() {
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Звук уведомления")
                .setItems(new String[]{"Стандартный Tsuyu", "Выбрать свой mp3"},
                        (d, which) -> {
                            if (which == 0) {
                                prefs.setNotificationSound("builtin");
                                TsuyuApp.get().createChannels();
                                renderSound();
                            } else {
                                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                i.setType("audio/*");
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                soundPicker.launch(i);
                            }
                        })
                .show();
    }

    /** Copies the chosen mp3 into app storage so the URI stays valid. */
    private void importSound(Uri uri) {
        new Thread(() -> {
            try {
                File out = new File(getFilesDir(), "notify_custom.mp3");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        total += n;
                        if (total > 3 * 1024 * 1024) break; // 1s clip is tiny; cap anyway
                    }
                }
                prefs.setNotificationSound(Uri.fromFile(out).toString());
                runOnUiThread(() -> {
                    TsuyuApp.get().createChannels();
                    renderSound();
                    Toast.makeText(this, "Звук установлен", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось загрузить звук",
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void testSound() {
        try {
            MediaPlayer mp;
            String custom = prefs.notificationSoundRaw();
            if (custom != null && !"builtin".equals(custom)) {
                mp = new MediaPlayer();
                mp.setDataSource(this, Uri.parse(custom));
                mp.prepare();
            } else {
                mp = MediaPlayer.create(this, R.raw.notify);
            }
            if (mp == null) return;
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.start();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show();
        }
    }

    private void logout() {
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Выйти из аккаунта?")
                .setMessage("Приватные ключи останутся на устройстве. "
                        + "Скачайте их, если планируете войти на другом телефоне.")
                .setPositiveButton("Выйти", (d, w) -> {
                    Repo.get(this).goOffline();
                    FirebaseAuth.getInstance().signOut();
                    Intent i = new Intent(this, AuthActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
