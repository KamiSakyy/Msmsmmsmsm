package com.tsuyu.messenger.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.IdentityStore;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.media.MediaCodecUtil;
import com.tsuyu.messenger.util.Ui;

public class AuthActivity extends AppCompatActivity {

    private boolean registerMode = false;
    private String avatarB64;

    private EditText inName, inUsername, inBio, inEmail, inPassword;
    private FrameLayout avatarWrap;
    private ImageView avatar;
    private Button btnPrimary, btnSwitch;
    private TextView error;
    private ProgressBar progress;

    private ActivityResultLauncher<PickVisualMediaRequest> picker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        inName = findViewById(R.id.inName);
        inUsername = findViewById(R.id.inUsername);
        inBio = findViewById(R.id.inBio);
        inEmail = findViewById(R.id.inEmail);
        inPassword = findViewById(R.id.inPassword);
        avatarWrap = findViewById(R.id.avatarWrap);
        avatar = findViewById(R.id.avatar);
        btnPrimary = findViewById(R.id.btnPrimary);
        btnSwitch = findViewById(R.id.btnSwitch);
        error = findViewById(R.id.error);
        progress = findViewById(R.id.progress);

        picker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) loadAvatar(uri);
        });

        avatarWrap.setOnClickListener(v -> picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));

        btnPrimary.setOnClickListener(v -> { Ui.tapScale(v); submit(); });
        btnSwitch.setOnClickListener(v -> toggleMode());

        Ui.fadeIn(findViewById(R.id.title));
    }

    private void toggleMode() {
        registerMode = !registerMode;
        int vis = registerMode ? View.VISIBLE : View.GONE;
        avatarWrap.setVisibility(vis);
        inName.setVisibility(vis);
        inUsername.setVisibility(vis);
        inBio.setVisibility(vis);
        btnPrimary.setText(registerMode ? "Создать аккаунт" : "Войти");
        btnSwitch.setText(registerMode ? "У меня уже есть аккаунт" : "Создать аккаунт");
        error.setVisibility(View.GONE);
        if (registerMode) {
            Ui.fadeIn(avatarWrap);
            Ui.fadeIn(inUsername);
        }
    }

    private void loadAvatar(Uri uri) {
        new Thread(() -> {
            try {
                MediaCodecUtil.Encoded e = MediaCodecUtil.encodeImage(this, uri, 320, 80);
                avatarB64 = e.base64;
                Bitmap bmp = Ui.decodeB64(avatarB64);
                runOnUiThread(() -> {
                    avatar.setImageBitmap(Ui.circle(bmp));
                    Ui.pop(avatar);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> showError("Не удалось загрузить фото"));
            }
        }).start();
    }

    private void showError(String msg) {
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
        Ui.fadeIn(error);
        loading(false);
    }

    private void loading(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        btnPrimary.setEnabled(!on);
        btnSwitch.setEnabled(!on);
    }

    private void submit() {
        String email = inEmail.getText().toString().trim();
        String pass = inPassword.getText().toString();
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(pass)) {
            showError("Введите почту и пароль");
            return;
        }
        if (pass.length() < 6) { showError("Пароль минимум 6 символов"); return; }

        error.setVisibility(View.GONE);
        loading(true);

        if (!registerMode) {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                    .addOnSuccessListener(r -> onAuthed())
                    .addOnFailureListener(e -> showError(human(e.getMessage())));
            return;
        }

        String name = inName.getText().toString().trim();
        String username = inUsername.getText().toString().trim()
                .replace("@", "").toLowerCase();
        String bio = inBio.getText().toString().trim();

        if (TextUtils.isEmpty(name)) { showError("Введите имя"); return; }
        if (!username.matches("[a-z0-9_]{3,24}")) {
            showError("Юз: 3-24 символа, латиница, цифры и _");
            return;
        }

        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(res -> {
                    String uid = res.getUser().getUid();
                    IdentityStore.get(this).persist();
                    Repo repo = Repo.get(this);
                    repo.claimUsername(username, uid, (ok, err) -> {
                        if (!ok) {
                            res.getUser().delete();
                            FirebaseAuth.getInstance().signOut();
                            showError(err);
                            return;
                        }
                        repo.publishProfile(uid, username, name, bio, avatarB64, this::onAuthed);
                    });
                })
                .addOnFailureListener(e -> showError(human(e.getMessage())));
    }

    private String human(String raw) {
        if (raw == null) return "Ошибка входа";
        String r = raw.toLowerCase();
        if (r.contains("password is invalid") || r.contains("credential is incorrect"))
            return "Неверный пароль";
        if (r.contains("no user record")) return "Аккаунт не найден";
        if (r.contains("email address is already")) return "Почта уже используется";
        if (r.contains("badly formatted")) return "Некорректная почта";
        if (r.contains("network")) return "Нет соединения";
        return raw;
    }

    private void onAuthed() {
        String uid = Repo.get(this).uid();
        if (uid != null) {
            new Prefs(this).setUid(uid);
            // make sure our current key bundle is published for this device
            Repo.get(this).publishProfile(uid, null, null, null, null, null);
        }
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
