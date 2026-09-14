package com.tsuyu.messenger.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.ProfileCrypto;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.media.MediaCodecUtil;
import com.tsuyu.messenger.util.Ui;

public class PrivacyActivity extends AppCompatActivity {

    private static final String[] KEYS = {"all", "contacts", "username", "none"};
    private static final String[] LABELS = {"Все", "Только контакты", "Только по @", "Никто"};

    private Repo repo;
    private Prefs prefs;
    private String me;
    private TextView valWrite, valLastSeen, valAvatar, valBio;
    private ImageView publicPhoto;
    private Switch swSecureScreen;
    private ActivityResultLauncher<PickVisualMediaRequest> picker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy);

        repo = Repo.get(this);
        prefs = new Prefs(this);
        me = repo.uid();
        if (me == null) { finish(); return; }

        ((TextView) findViewById(R.id.headerTitle)).setText("Конфиденциальность");
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        valWrite = findViewById(R.id.valWrite);
        valLastSeen = findViewById(R.id.valLastSeen);
        valAvatar = findViewById(R.id.valAvatar);
        valBio = findViewById(R.id.valBio);
        publicPhoto = findViewById(R.id.publicPhoto);
        swSecureScreen = findViewById(R.id.swSecureScreen);

        if (swSecureScreen != null) {
            swSecureScreen.setChecked(prefs.secureScreen());
            swSecureScreen.setOnCheckedChangeListener((b, checked) -> {
                prefs.setSecureScreen(checked);
                if (checked) {
                    getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE);
                    Toast.makeText(this, "Защита экрана включена", Toast.LENGTH_SHORT).show();
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    Toast.makeText(this, "Защита экрана выключена", Toast.LENGTH_SHORT).show();
                }
            });
        }

        findViewById(R.id.rowWrite).setOnClickListener(v ->
                choose("Кто может писать мне", "pWrite", valWrite));
        findViewById(R.id.rowLastSeen).setOnClickListener(v ->
                choose("Кто видит время захода", "pLastSeen", valLastSeen));
        findViewById(R.id.rowAvatar).setOnClickListener(v ->
                choose("Кто видит фото профиля", "pAvatar", valAvatar));
        findViewById(R.id.rowBio).setOnClickListener(v ->
                choose("Кто видит «О себе»", "pBio", valBio));

        picker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) uploadPublicPhoto(uri);
        });
        findViewById(R.id.rowPublicPhoto).setOnClickListener(v ->
                picker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        load();
    }

    private void load() {
        repo.userRef(me).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Models.User u = Repo.parseUser(s);
                valWrite.setText(labelOf(u.pWrite));
                valLastSeen.setText(labelOf(u.pLastSeen));
                valAvatar.setText(labelOf(u.pAvatar));
                valBio.setText(labelOf(u.pBio));
                Ui.setAvatar(publicPhoto, u.publicAvatar, u.uid, u.name);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private String labelOf(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return LABELS[i];
        return LABELS[0];
    }

    private void choose(String title, String field, TextView target) {
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle(title)
                .setItems(LABELS, (d, which) -> {
                    repo.updatePrivacy(field, KEYS[which]);
                    target.setText(LABELS[which]);
                })
                .show();
    }

    private void uploadPublicPhoto(Uri uri) {
        new Thread(() -> {
            try {
                MediaCodecUtil.Encoded e = MediaCodecUtil.encodeImage(this, uri, 320, 80);
                repo.userRef(me).child("publicAvatar")
                        .setValue(ProfileCrypto.seal(me, e.base64));
                runOnUiThread(() -> Toast.makeText(this, "Публичное фото обновлено",
                        Toast.LENGTH_SHORT).show());
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка",
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
