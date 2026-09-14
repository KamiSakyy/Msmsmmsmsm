package com.tsuyu.messenger.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.ProfileCrypto;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.media.MediaCodecUtil;
import com.tsuyu.messenger.util.Fmt;
import com.tsuyu.messenger.util.Ui;

public class ProfileActivity extends AppCompatActivity {

    private Repo repo;
    private String uid, me;
    private boolean self;
    private Models.User user;

    private ImageView avatar, avatarBadge;
    private TextView name, username, status, bio;
    private EditText inName, inBio;
    private LinearLayout peerActions;
    private ActivityResultLauncher<PickVisualMediaRequest> picker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        repo = Repo.get(this);
        me = repo.uid();
        uid = getIntent().getStringExtra("uid");
        if (uid == null) uid = me;
        if (uid == null) { finish(); return; }
        self = uid.equals(me);

        ((TextView) findViewById(R.id.headerTitle)).setText(self ? "Мой профиль" : "Профиль");
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        avatar = findViewById(R.id.avatar);
        avatarBadge = findViewById(R.id.avatarBadge);
        name = findViewById(R.id.name);
        username = findViewById(R.id.username);
        status = findViewById(R.id.status);
        bio = findViewById(R.id.bio);
        inName = findViewById(R.id.inName);
        inBio = findViewById(R.id.inBio);
        peerActions = findViewById(R.id.peerActions);

        picker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), u -> {
            if (u != null) changeAvatar(u);
        });

        if (self) {
            findViewById(R.id.editSection).setVisibility(View.VISIBLE);
            avatarBadge.setVisibility(View.VISIBLE);
            avatarBadge.setOnClickListener(v -> launchPicker());
            findViewById(R.id.btnChangeAvatar).setOnClickListener(v -> launchPicker());
            ((Button) findViewById(R.id.btnSave)).setOnClickListener(v -> save());
        } else {
            peerActions.setVisibility(View.VISIBLE);
            findViewById(R.id.actionChat).setOnClickListener(v -> {
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("peerUid", uid);
                startActivity(i);
                finish();
            });
            findViewById(R.id.actionCall).setOnClickListener(v -> {
                Intent i = new Intent(this, CallActivity.class);
                i.putExtra("peerUid", uid);
                i.putExtra("video", false);
                i.putExtra("outgoing", true);
                startActivity(i);
            });
            findViewById(R.id.actionVideo).setOnClickListener(v -> {
                Intent i = new Intent(this, CallActivity.class);
                i.putExtra("peerUid", uid);
                i.putExtra("video", true);
                i.putExtra("outgoing", true);
                startActivity(i);
            });
        }

        load();
        watchPresence();
    }

    private void launchPicker() {
        picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void load() {
        repo.userRef(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                user = Repo.parseUser(s);
                render();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private void render() {
        name.setText(user.name);
        username.setText(user.username == null ? "" : "@" + user.username);

        boolean avatarVisible = self || allowed(user.pAvatar);
        String shown = avatarVisible ? user.avatar : user.publicAvatar;
        Ui.setAvatar(avatar, shown, user.uid, user.name);

        boolean bioVisible = self || allowed(user.pBio);
        bio.setText(bioVisible && user.bio != null && !user.bio.isEmpty() ? user.bio : "Нет описания");

        if (self) {
            if (inName.getText().length() == 0) inName.setText(user.name);
            if (inBio.getText().length() == 0 && user.bio != null) inBio.setText(user.bio);
        }
    }

    private boolean allowed(String policy) {
        if (policy == null || "all".equals(policy)) return true;
        if ("none".equals(policy)) return false;
        return true;
    }

    private void watchPresence() {
        repo.presenceRef(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                boolean online = Boolean.TRUE.equals(s.child("online").getValue());
                Object ls = s.child("lastSeen").getValue();
                long lastSeen = ls instanceof Number ? ((Number) ls).longValue() : 0;
                boolean hidden = user != null && "none".equals(user.pLastSeen) && !self;
                status.setText(Fmt.lastSeen(ProfileActivity.this, online, lastSeen, hidden));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private void changeAvatar(Uri uriValue) {
        new Thread(() -> {
            try {
                MediaCodecUtil.Encoded e = MediaCodecUtil.encodeImage(this, uriValue, 320, 80);
                repo.userRef(me).child("avatar").setValue(ProfileCrypto.seal(me, e.base64));
                runOnUiThread(() -> Toast.makeText(this, "Аватарка обновлена",
                        Toast.LENGTH_SHORT).show());
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка загрузки",
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void save() {
        String n = inName.getText().toString().trim();
        String b = inBio.getText().toString().trim();
        if (n.isEmpty()) { Toast.makeText(this, "Введите имя", Toast.LENGTH_SHORT).show(); return; }
        repo.updateProfileField("name", n);
        repo.updateProfileField("bio", b);
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
    }
}
