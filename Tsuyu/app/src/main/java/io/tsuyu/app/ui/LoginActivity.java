package io.tsuyu.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import io.tsuyu.app.R;
import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.core.Keys;
import io.tsuyu.app.notif.Notifier;
import io.tsuyu.app.service.BgService;
import io.tsuyu.app.util.Ui;

public class LoginActivity extends AppCompatActivity {
    private EditText etEmail, etPass;
    private Button btnLogin;
    private TextView tvError, tvRegister;
    private ProgressBar progress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        etEmail = findViewById(R.id.etEmail);
        etPass = findViewById(R.id.etPass);
        btnLogin = findViewById(R.id.btnLogin);
        tvError = findViewById(R.id.tvError);
        tvRegister = findViewById(R.id.tvRegister);
        progress = findViewById(R.id.progress);

        btnLogin.setOnClickListener(v -> doLogin());
        tvRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        etPass.setOnEditorActionListener((tv, actionId, event) -> {
            doLogin();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) goMain();
    }

    private void goMain() {
        try {
            Keys.ensure(this);
            BgService.start(this);
        } catch (Throwable ignored) {}
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void doLogin() {
        String email = etEmail.getText().toString().trim();
        String pass = etPass.getText().toString();
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(pass)) {
            showError("Введите email и пароль");
            return;
        }
        tvError.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        btnLogin.setEnabled(false);
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                .addOnCompleteListener(task -> {
                    progress.setVisibility(View.GONE);
                    btnLogin.setEnabled(true);
                    if (task.isSuccessful()) {
                        goMain();
                    } else {
                        showError("Неверный email или пароль");
                    }
                });
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        // keep on login
    }
}
