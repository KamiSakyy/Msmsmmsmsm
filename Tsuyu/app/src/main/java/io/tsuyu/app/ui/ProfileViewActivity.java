package io.tsuyu.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import io.tsuyu.app.R;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.Keys;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.util.Ui;

public class ProfileViewActivity extends AppCompatActivity {
    private String uid;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_view);
        uid = getIntent().getStringExtra("uid");
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        Fb.fetchUser(uid, map -> {
            MeowUser u = Fb.parseUserRaw(map);
            if (u != null) {
                u.uid = uid;
                Fb.userCache.put(uid, u);
                runOnUiThread(() -> render(u));
            }
        });
    }

    private void render(MeowUser u) {
        try {
            boolean isMe = u.uid.equals(Fb.myUid());
            TextView name = findViewById(R.id.tvName);
            TextView username = findViewById(R.id.tvUsername);
            TextView status = findViewById(R.id.tvStatus);
            TextView email = findViewById(R.id.tvEmail);
            TextView bio = findViewById(R.id.tvBio);
            TextView fp = findViewById(R.id.tvFingerprint);
            ImageView iv = findViewById(R.id.ivAvatar);
            TextView letter = findViewById(R.id.tvLetter);
            View bgView = ((android.view.ViewGroup) findViewById(R.id.ivAvatar).getParent()).getChildAt(0);
            name.setText(u.displayName());
            username.setText(u.username != null ? "@" + u.username : "");
            String onlineText = Ui.st("online", getString(R.string.online));
            String last = Ui.st("lastSeen", "был(а)");
            if (u.online) status.setText(onlineText);
            else if (u.lastSeen != null) status.setText(Ui.lastSeenText(u, onlineText, last));
            else status.setText("");
            email.setText(isMe ? (u.email != null ? u.email : "—") : "—");
            boolean hideBio = u.privacyBio != null && u.privacyBio.equals("nobody");
            bio.setText(hideBio || u.bio == null || u.bio.isEmpty() ? "—" : u.bio);
            boolean hidePhoto = u.privacyPhoto != null && u.privacyPhoto.equals("nobody");
            Ui.setAvatar(iv, bgView, letter, hidePhoto ? null : u);
            if (isMe) {
                fp.setText(Keys.fingerprint(this));
            } else {
                fp.setText(u.fp != null ? u.fp : "—");
            }
        } catch (Throwable ignored) {}
    }
}
