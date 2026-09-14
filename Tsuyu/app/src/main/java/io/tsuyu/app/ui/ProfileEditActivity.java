package io.tsuyu.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import io.tsuyu.app.R;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.ProfileCipher;
import io.tsuyu.app.media.ImageUtil;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.util.Ui;

import org.json.JSONObject;

public class ProfileEditActivity extends AppCompatActivity {
    private static final int PICK_AVATAR = 502;
    private byte[] avatarJpeg;
    private String avatarB64;
    private EditText etFirst, etLast, etUsername, etBio;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_edit);
        etFirst = findViewById(R.id.etFirst);
        etLast = findViewById(R.id.etLast);
        etUsername = findViewById(R.id.etUsername);
        etBio = findViewById(R.id.etBio);
        findViewById(R.id.etEmail).setText(Fb.user() == null ? "" : Fb.user().getEmail());

        Fb.fetchUser(Fb.myUid(), map -> {
            MeowUser u = Fb.parseUserRaw(map);
            if (u == null) return;
            runOnUiThread(() -> {
                etFirst.setText(u.firstName == null ? "" : u.firstName);
                etLast.setText(u.lastName == null ? "" : u.lastName);
                etUsername.setText(u.username == null ? "" : u.username);
                etBio.setText(u.bio == null ? "" : u.bio);
                avatarB64 = u.avatarB64;
                if (avatarB64 != null) {
                    byte[] d = android.util.Base64.decode(avatarB64, android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length);
                    if (bm != null) {
                        ImageView iv = findViewById(R.id.ivAvatar);
                        iv.setImageBitmap(bm);
                        iv.setVisibility(View.VISIBLE);
                        findViewById(R.id.tvLetter).setVisibility(View.GONE);
                    }
                }
            });
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.avatarPick).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Выбрать фото"), PICK_AVATAR);
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AVATAR && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                ImageUtil.ImgResult r = ImageUtil.process(this, data.getData(), 512, 85);
                if (r != null) {
                    avatarJpeg = r.jpeg;
                    avatarB64 = r.b64;
                    ImageView iv = findViewById(R.id.ivAvatar);
                    iv.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(avatarJpeg, 0, avatarJpeg.length));
                    iv.setVisibility(View.VISIBLE);
                    findViewById(R.id.tvLetter).setVisibility(View.GONE);
                }
            } catch (Throwable t) {
                Ui.toast(this, "Не удалось загрузить фото");
            }
        }
    }

    private void save() {
        try {
            String first = etFirst.getText().toString().trim();
            String last = etLast.getText().toString().trim();
            String username = etUsername.getText().toString().trim().toLowerCase().replace("@", "");
            String bio = etBio.getText().toString().trim();
            if (first.isEmpty()) {
                Ui.toast(this, "Введите имя");
                return;
            }
            if (username.isEmpty() || username.matches("[a-z0-9._]{3,20}") == false) {
                Ui.toast(this, "Юз: 3-20 символов латиницей");
                return;
            }
            // custom font path persistence (from settings) is kept separately
            String custom = io.tsuyu.app.util.Ui.currentCustomJson(this);
            Fb.saveMyProfile(this, first, last, username, bio, avatarB64,
                    custom == null ? null : ProfileCipher.enc(custom),
                    null, null, null, null, null, null);
            // update search index if username changed
            Fb.fetchUser(Fb.myUid(), map -> {
                MeowUser u = Fb.parseUserRaw(map);
                String oldUser = u == null ? null : u.username;
                if (u != null && oldUser != null && oldUser.equals(username) == false) {
                    try {
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("search/" + oldUser).removeValue();
                    } catch (Throwable ignored) {}
                }
                try {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                            .getReference("search/" + username)
                            .updateChildren(new JSONObject()
                                    .put("uid", Fb.myUid())
                                    .put("u", username)
                                    .toMap());
                } catch (Throwable ignored) {}
            });
            Ui.toast(this, "Сохранено ✓");
            finish();
        } catch (Throwable t) {
            Log.e("TsuyuProfile", "save", t);
            Ui.toast(this, "Ошибка сохранения");
        }
    }
}
