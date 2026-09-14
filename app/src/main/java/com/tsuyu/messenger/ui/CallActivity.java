package com.tsuyu.messenger.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.service.CallSignaling;
import com.tsuyu.messenger.service.WebRtcEngine;
import com.tsuyu.messenger.util.Fmt;
import com.tsuyu.messenger.util.Ui;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.UUID;

/** 1:1 P2P call screen with RTDB signalling. */
public class CallActivity extends AppCompatActivity {

    private Repo repo;
    private String me, peerUid, callId;
    private boolean video, outgoing;

    private WebRtcEngine engine;
    private SurfaceViewRenderer localView, remoteView;
    private TextView callName, callStatus;
    private ImageView callAvatar, btnMute, btnFlip;
    private View controls, incomingControls, infoPanel;

    private MediaPlayer ringtone;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long connectedAt;
    private boolean micOn = true, ended;

    private DatabaseReference myCallRef, peerCallRef;
    private ValueEventListener myCallListener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_call);

        repo = Repo.get(this);
        me = repo.uid();
        peerUid = getIntent().getStringExtra("peerUid");
        video = getIntent().getBooleanExtra("video", false);
        outgoing = getIntent().getBooleanExtra("outgoing", true);
        callId = UUID.randomUUID().toString();
        if (me == null || peerUid == null) { finish(); return; }

        localView = findViewById(R.id.localView);
        remoteView = findViewById(R.id.remoteView);
        callName = findViewById(R.id.callName);
        callStatus = findViewById(R.id.callStatus);
        callAvatar = findViewById(R.id.callAvatar);
        btnMute = findViewById(R.id.btnMute);
        btnFlip = findViewById(R.id.btnFlipCam);
        controls = findViewById(R.id.controls);
        incomingControls = findViewById(R.id.incomingControls);
        infoPanel = findViewById(R.id.infoPanel);

        if (video) {
            btnFlip.setVisibility(View.VISIBLE);
            btnFlip.setImageResource(R.drawable.ic_switch_camera);
            btnFlip.setOnClickListener(v -> { if (engine != null) engine.switchCamera(); });
        } else {
            btnFlip.setVisibility(View.VISIBLE);
            btnFlip.setImageResource(R.drawable.ic_volume_up);
            final boolean[] speakerOn = {false};
            btnFlip.setAlpha(0.5f);
            btnFlip.setOnClickListener(v -> {
                speakerOn[0] = !speakerOn[0];
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) am.setSpeakerphoneOn(speakerOn[0]);
                btnFlip.setAlpha(speakerOn[0] ? 1.0f : 0.5f);
            });
        }

        findViewById(R.id.btnHangup).setOnClickListener(v -> hangup());
        findViewById(R.id.btnDecline).setOnClickListener(v -> hangup());
        findViewById(R.id.btnAccept).setOnClickListener(v -> accept());
        btnMute.setOnClickListener(v -> {
            micOn = !micOn;
            if (engine != null) engine.setMicEnabled(micOn);
            btnMute.setImageResource(micOn ? R.drawable.ic_mic : R.drawable.ic_mic_off);
        });

        loadPeer();

        myCallRef = CallSignaling.ref(this, me);
        peerCallRef = CallSignaling.ref(this, peerUid);

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, video
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
                    : new String[]{Manifest.permission.RECORD_AUDIO}, 11);
            return;
        }
        setup();
    }

    private boolean hasPermissions() {
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean cam = !video || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        return mic && cam;
    }

    @Override
    public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] res) {
        super.onRequestPermissionsResult(r, p, res);
        if (hasPermissions()) setup();
        else finish();
    }

    private void loadPeer() {
        repo.userRef(peerUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Models.User u = Repo.parseUser(s);
                callName.setText(u.name);
                Ui.setAvatar(callAvatar, u.avatar, u.uid, u.name);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private void setup() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.setSpeakerphoneOn(video);

        engine = new WebRtcEngine(this, video, new WebRtcEngine.Events() {
            @Override public void onLocalSdp(SessionDescription sdp) {
                if (outgoing) {
                    CallSignaling.placeCall(CallActivity.this, me, peerUid, video,
                            sdp.description, callId);
                } else {
                    CallSignaling.answer(CallActivity.this, peerUid, sdp.description);
                }
            }
            @Override public void onIceCandidate(IceCandidate c) {
                CallSignaling.addIce(CallActivity.this, peerUid,
                        c.sdpMid + "|" + c.sdpMLineIndex + "|" + c.sdp);
            }
            @Override public void onConnected() {
                ui.post(() -> {
                    stopRingtone();
                    connectedAt = System.currentTimeMillis();
                    startTimer();
                    incomingControls.setVisibility(View.GONE);
                    controls.setVisibility(View.VISIBLE);
                });
            }
            @Override public void onDisconnected() { ui.post(() -> hangup()); }
            @Override public void onRemoteVideo(VideoTrack track) {
                ui.post(() -> {
                    if (!video) return;
                    remoteView.setVisibility(View.VISIBLE);
                    infoPanel.setVisibility(View.GONE);
                    track.addSink(remoteView);
                });
            }
        });
        engine.init();

        if (video) {
            localView.init(engine.eglBase().getEglBaseContext(), null);
            localView.setMirror(true);
            localView.setZOrderMediaOverlay(true);
            localView.setVisibility(View.VISIBLE);
            remoteView.init(engine.eglBase().getEglBaseContext(), null);
            engine.attachLocalView(localView);
        }

        if (outgoing) {
            callStatus.setText("Вызов…");
            incomingControls.setVisibility(View.GONE);
            controls.setVisibility(View.VISIBLE);
            engine.createOffer();
            watchForAnswer();
        } else {
            callStatus.setText(video ? "Входящий видеозвонок" : "Входящий звонок");
            incomingControls.setVisibility(View.VISIBLE);
            controls.setVisibility(View.GONE);
            playRingtone();
        }
        watchIce();
        watchEnd();
    }

    private void accept() {
        stopRingtone();
        callStatus.setText("Соединение…");
        incomingControls.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        myCallRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Object offer = s.child("offer").getValue();
                if (offer == null) { hangup(); return; }
                engine.setRemoteDescription(new SessionDescription(
                        SessionDescription.Type.OFFER, String.valueOf(offer)));
                engine.createAnswer();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private void watchForAnswer() {
        myCallListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Object state = s.child("state").getValue();
                Object answer = s.child("answer").getValue();
                if (CallSignaling.ACCEPTED.equals(state) && answer != null) {
                    engine.setRemoteDescription(new SessionDescription(
                            SessionDescription.Type.ANSWER, String.valueOf(answer)));
                    ui.post(() -> callStatus.setText("Соединение…"));
                } else if (CallSignaling.DECLINED.equals(state)) {
                    ui.post(() -> { callStatus.setText("Отклонено"); hangup(); });
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        myCallRef.addValueEventListener(myCallListener);
    }

    private void watchIce() {
        myCallRef.child("ice").addChildEventListener(
                new com.google.firebase.database.ChildEventListener() {
                    @Override public void onChildAdded(@NonNull DataSnapshot s, String p) {
                        Object v = s.getValue();
                        if (v == null || engine == null) return;
                        String[] parts = String.valueOf(v).split("\\|", 3);
                        if (parts.length == 3) {
                            try {
                                engine.addIceCandidate(new IceCandidate(parts[0],
                                        Integer.parseInt(parts[1]), parts[2]));
                            } catch (Exception ignored) { }
                        }
                    }
                    @Override public void onChildChanged(@NonNull DataSnapshot s, String p) { }
                    @Override public void onChildRemoved(@NonNull DataSnapshot s) { }
                    @Override public void onChildMoved(@NonNull DataSnapshot s, String p) { }
                    @Override public void onCancelled(@NonNull DatabaseError e) { }
                });
    }

    private void watchEnd() {
        myCallRef.child("state").addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (CallSignaling.ENDED.equals(s.getValue())) ui.post(() -> hangup());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private void startTimer() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (ended || connectedAt == 0) return;
                callStatus.setText(Fmt.duration(System.currentTimeMillis() - connectedAt));
                ui.postDelayed(this, 1000);
            }
        });
    }

    private void playRingtone() {
        try {
            ringtone = MediaPlayer.create(this, R.raw.ringtone);
            if (ringtone == null) return;
            ringtone.setLooping(true);
            ringtone.start();
        } catch (Exception ignored) { }
    }

    private void stopRingtone() {
        try {
            if (ringtone != null) { ringtone.stop(); ringtone.release(); }
        } catch (Exception ignored) { }
        ringtone = null;
    }

    private void hangup() {
        if (ended) return;
        ended = true;
        stopRingtone();
        CallSignaling.setState(this, peerUid, CallSignaling.ENDED);
        CallSignaling.clear(this, me);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.cancel(777);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ended = true;
        stopRingtone();
        if (myCallListener != null) myCallRef.removeEventListener(myCallListener);
        if (engine != null) engine.release();
        try {
            if (localView != null) localView.release();
            if (remoteView != null) remoteView.release();
        } catch (Exception ignored) { }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.setMode(AudioManager.MODE_NORMAL);
    }
}
