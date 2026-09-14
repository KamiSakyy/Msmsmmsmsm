package io.tsuyu.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.regex.Pattern;

import io.tsuyu.app.R;
import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.Keys;
import io.tsuyu.app.media.ImageUtil;
import io.tsuyu.app.service.BgService;
import io.tsuyu.app.util.Ui;

public class RegisterActivity extends AppCompatActivity {
    private static final int PICK_AVATAR = 501;
    private Uri avatarUri;
    private byte[] avatarJpeg;
    private EditText etFirst, etLast, etUsername, etBio, etEmail, etPass;
    private Button btnRegister;
    private TextView tvError, tvLogin;
    private ProgressBar progress;
    private ImageView ivAvatar;
    private TextView tvLetter;
    private String chosenUsername;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        etFirst = findViewById(R.id.etFirst);
        etLast = findViewById(R.id.etLast);
        etUsername = findViewById(R.id.etUsername);
        etBio = findViewById(R.id.etBio);
        etEmail = findViewById(R.id.etEmail);
        etPass = findViewById(R.id.etPass);
        btnRegister = findViewById(R.id.btnRegister);
        tvError = findViewById(R.id.tvError);
        tvLogin = findViewById(R.id.tvLogin);
        progress = findViewById(R.id.progress);
        ivAvatar = findViewById(R.id.ivAvatarPreview);
        tvLetter = findViewById(R.id.tvAvatarLetter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.avatarPick).setOnClickListener(v -> pickAvatar());
        tvLogin.setOnClickListener(v -> finish());
        btnRegister.setOnClickListener(v -> doRegister());
    }

    private void pickAvatar() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Выбрать фото"), PICK_AVATAR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AVATAR && resultCode == RESULT_OK && data != null && data.getData() != null) {
            avatarUri = data.getData();
            try {
                ImageUtil.ImgResult r = ImageUtil.process(this, avatarUri, 512, 85);
                if (r != null) {
                    avatarJpeg = r.jpeg;
                    Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(avatarJpeg, 0, avatarJpeg.length);
                    ivAvatar.setImageBitmap(bm);
                    ivAvatar.setVisibility(View.VISIBLE);
                    tvLetter.setVisibility(View.GONE);
                }
            } catch (Throwable t) {
                Ui.toast(this, "Не удалось загрузить фото");
            }
        }
    }

    private void doRegister() {
        String first = etFirst.getText().toString().trim();
        String last = etLast.getText().toString().trim();
        String username = etUsername.getText().toString().trim().toLowerCase().replace("@", "");
        String bio = etBio.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String pass = etPass.getText().toString();

        if (first.isEmpty()) { showError("Введите имя"); return; }
        if (!username.matches("[a-z0-9._]{3,20}")) {
            showError("Юз: 3-20 символов, латиница, цифры, . _");
            return;
        }
        if (TextUtils.isEmpty(email) || !email.contains("@")) { showError("Введите корректный email"); return; }
        if (pass.length() < 6) { showError("Пароль минимум 6 символов"); return; }

        tvError.setVisibility(View.GONE);
        // unique username check
        Fb.usernameTaken(this, username, taken -> {
            runOnUiThread(() -> {
                if (taken) {
                    tvError.setText("Этот @ занят. Выберите другой.");
                    tvError.setVisibility(View.VISIBLE);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                btnRegister.setEnabled(false);
                chosenUsername = username;
                createAccount(first, last, username, bio, email, pass);
            });
        });
    }

    private void createAccount(final String first, final String last, final String username,
                               final String bio, String email, String pass) {
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                .addOnCompleteListener(task -> {
                    progress.setVisibility(View.GONE);
                    btnRegister.setEnabled(true);
                    if (!task.isSuccessful()) {
                        tvError.setText(task.getException() == null ? "Ошибка регистрации" : task.getException().getMessage());
                        tvError.setVisibility(View.VISIBLE);
                        return;
                    }
                    try {
                        Keys.ensure(this);
                        String uid = Fb.myUid();
                        String name = (first + " " + last).trim();
                        // write profile
                        String avatarB64 = avatarJpeg == null ? null
                                : android.util.Base64.encodeToString(avatarJpeg, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                        Fb.saveMyProfile(this, first, last, username, bio, avatarB64, null,
                                null, null, null, null, null, null);
                        // search index
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("search/" + username)
                                .updateChildren(new JSONObject()
                                        .put("uid", uid)
                                        .put("u", username)
                                        .toMap());
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("users/" + uid)
                                .updateChildren(new JSONObject().put("created", System.currentTimeMillis()).toMap());
                        BgService.start(this);
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    } catch (Throwable t) {
                        Ui.toast(this, "Ошибка сохранения профиля");
                    }
                });
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }
}
