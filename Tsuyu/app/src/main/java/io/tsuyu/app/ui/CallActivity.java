package io.tsuyu.app.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.webrtc.SurfaceViewRenderer;

import io.tsuyu.app.R;
import io.tsuyu.app.call.CallEngine;
import io.tsuyu.app.call.CallService;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.util.Ui;

import org.json.JSONObject;

/** Fullscreen call UI: incoming / ringing / active. */
public class CallActivity extends AppCompatActivity {
    private String callId;
    private String peer;
    private boolean outgoing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceViewRenderer remoteView;
    private SurfaceViewRenderer localView;
    private long startedAt = 0;
    private TextView tvSub;
    private TextView tvTimer;
    private View answerRow, controlRow, endBtn, muteBtn, videoBtn, localBox;
    private boolean ended = false;

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (startedAt > 0) {
                long s = (System.currentTimeMillis() - startedAt) / 1000;
                tvTimer.setText(Ui.durText(s));
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_call);
        callId = getIntent().getStringExtra("callId");
        peer = getIntent().getStringExtra("peer");
        outgoing = getIntent().getBooleanExtra("out", true);
        if (callId == null) {
            finish();
            return;
        }
        tvSub = findViewById(R.id.tvCallSub);
        tvTimer = findViewById(R.id.tvCallTimer);
        answerRow = findViewById(R.id.callAnswerRow);
        controlRow = findViewById(R.id.callControlRow);
        endBtn = findViewById(R.id.btnCallEnd);
        muteBtn = findViewById(R.id.btnCallMute);
        videoBtn = findViewById(R.id.btnCallVideo);
        localBox = findViewById(R.id.localVideoBox);

        // attach webrtc renderers
        FrameLayout remoteBox = findViewById(R.id.remoteVideoBox);
        remoteView = new SurfaceViewRenderer(this);
        remoteView.setZOrderMediaOverlay(true);
        remoteView.setZOrderOnTop(false);
        remoteBox.addView(remoteView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        localView = new SurfaceViewRenderer(this);
        localView.setMirror(true);
        localBox.addView(localView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        CallEngine.setViews(localView, remoteView);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS
            }, 310);
        }

        MeowUser u = Fb.userCache.get(peer);
        TextView name = findViewById(R.id.tvCallName);
        name.setText(u == null ? peer : u.displayName());
        if (u != null && u.username != null) {
            tvSub.setText("@" + u.username);
        } else {
            tvSub.setText("");
        }
        if (u != null && u.online && outgoing) {
            // nothing special
        }
        View bgView = ((android.view.ViewGroup) findViewById(R.id.ivCallAvatar).getParent()).getChildAt(0);
        Ui.setAvatar(findViewById(R.id.ivCallAvatar), bgView, findViewById(R.id.tvCallLetter), u);

        findViewById(R.id.btnCallAccept).setOnClickListener(v -> accept());
        findViewById(R.id.btnCallDecline).setOnClickListener(v -> hangUp("declined"));
        muteBtn.setOnClickListener(v -> {
            CallEngine.toggleMute();
            muteBtn.setBackgroundResource(CallEngine.localMuted
                    ? R.drawable.bg_call_btn_mute_active : R.drawable.bg_call_btn_mute);
        });
        videoBtn.setOnClickListener(v -> {
            CallEngine.toggleVideo();
            videoBtn.setBackgroundResource(CallEngine.localVideoOff
                    ? R.drawable.bg_call_btn_mute_active : R.drawable.bg_call_btn_mute);
            localBox.setVisibility(CallEngine.localVideoOff ? View.INVISIBLE : View.VISIBLE);
        });
        endBtn.setOnClickListener(v -> hangUp("remote"));

        updatePhase("ringing");

        Fb.fb().getReference("calls/" + callId).addValueEventListener((snap, err) -> {
            try {
                if (snap == null || snap.getValue() == null) return;
                JSONObject d = snap.getValue(JSONObject.class);
                String st = d.optString("st", "ringing");
                if ("active".equals(st)) {
                    if (startedAt == 0) startedAt = d.optLong("startTs", System.currentTimeMillis());
                    updatePhase("active");
                    main.removeCallbacks(timerTick);
                    main.postDelayed(timerTick, 1000);
                } else if ("accepted".equals(st)) {
                    tvSub.setText("Соединение...");
                } else if ("ended".equals(st)) {
                    if (ended) return;
                    ended = true;
                    long dur = startedAt > 0 ? (System.currentTimeMillis() - startedAt) / 1000 : 0;
                    tvSub.setText(dur > 0 ? "Длительность: " + Ui.durText(dur) : "Звонок завершён");
                    tvTimer.setVisibility(View.GONE);
                    main.postDelayed(this::finish, 1400);
                } else if ("declined".equals(st)) {
                    if (ended) return;
                    ended = true;
                    tvSub.setText(outgoing ? "Звонок отклонён" : "");
                    main.postDelayed(this::finish, 1600);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void updatePhase(String phase) {
        try {
            if ("active".equals(phase)) {
                answerRow.setVisibility(View.GONE);
                controlRow.setVisibility(View.VISIBLE);
                tvTimer.setVisibility(View.VISIBLE);
            } else if (outgoing) {
                // ringing, I am caller: show decline (cancel) + end
                answerRow.setVisibility(View.GONE);
                controlRow.setVisibility(View.GONE);
                tvTimer.setVisibility(View.GONE);
                tvSub.setText("Идёт набор...");
            } else {
                answerRow.setVisibility(View.VISIBLE);
                controlRow.setVisibility(View.GONE);
                tvTimer.setVisibility(View.GONE);
                tvSub.setText("Входящий звонок...");
            }
        } catch (Throwable ignored) {}
    }

    private void accept() {
        try {
            CallService.startEngine();
            JSONObject d = new JSONObject().put("st", "accepted");
            Fb.fb().getReference("calls/" + callId).updateChildren(d.toMap());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void hangUp(String reason) {
        try {
            CallEngine.finishCall(reason, startedAt > 0 ? System.currentTimeMillis() - startedAt : 0);
        } catch (Throwable ignored) {}
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            main.removeCallbacks(timerTick);
            if (remoteView != null) remoteView.release();
            if (localView != null) localView.release();
        } catch (Throwable ignored) {}
    }
}
