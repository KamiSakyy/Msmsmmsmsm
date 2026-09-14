package com.tsuyu.messenger.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.tsuyu.messenger.crypto.IdentityStore;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Warm up the identity (generates keys on first launch).
        IdentityStore.get(this);
        boolean signedIn = FirebaseAuth.getInstance().getCurrentUser() != null;
        startActivity(new Intent(this, signedIn ? MainActivity.class : AuthActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
