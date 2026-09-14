package io.tsuyu.app.call;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Capturer;
import org.webrtc.Camera2Capturer;
import org.webrtc.Camera2Enumerator;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.core.Fb;

/** WebRTC engine for 1:1 audio+video calls (RTDB signaling). */
public class CallEngine {
    private static final String TAG = "TsuyuCall";

    public static final int ST_IDLE = 0;
    public static final int ST_RINGING = 1;
    public static final int ST_ACTIVE = 2;
    public static final int ST_ENDED = 3;

    public static int state = ST_IDLE;
    public static PeerConnection peer;
    public static volatile boolean localMuted = false;
    public static volatile boolean localVideoOff = false;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final MediaConstraints SDP_CONSTRAINTS = new MediaConstraints();
    private static EglBase egl;
    private static PeerConnectionFactory factory;
    private static VideoCapturer capturer;
    private static VideoSource localVideoSource;
    private static AudioTrack localAudioTrack;
    private static VideoTrack localVideoTrack;
    private static MediaStream localStream;
    private static MediaStream remoteStream;
    private static SessionDescription pendingSignal;
    private static SurfaceViewRenderer localView;
    private static SurfaceViewRenderer remoteView;
    private static boolean audioStarted = false;

    static {
        SDP_CONSTRAINTS.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        SDP_CONSTRAINTS.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
    }

    public static synchronized void init(Context app) {
        if (factory != null) return;
        try {
            egl = EglBase.create();
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            DefaultVideoEncoderFactory enc = new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true);
            DefaultVideoDecoderFactory dec = new DefaultVideoDecoderFactory(egl.getEglBaseContext());
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(enc)
                    .setVideoDecoderFactory(dec)
                    .createPeerConnectionFactory();
            Log.i(TAG, "webrtc factory init ok");
        } catch (Throwable t) {
            Log.e(TAG, "init", t);
        }
    }

    public static void start(Context ctx, String callId, String peerUid, boolean outgoing) {
        init(ctx.getApplicationContext());
        if (factory == null) {
            Log.e(TAG, "factory null, cannot start call");
            return;
        }
        state = ST_RINGING;
        executor.execute(() -> {
            try {
                peer = createPeerConnection();
                localStream = new MediaStream("tsuyu-local");
                // audio
                AudioSource asrc = factory.createAudioSource(new MediaConstraints());
                localAudioTrack = factory.createAudioTrack("tsuyu-a0", asrc);
                localStream.addTrack(localAudioTrack);
                // video
                capturer = createCamera(TsuyuApp.get());
                localVideoSource = factory.createVideoSource(capturer);
                localVideoTrack = factory.createVideoTrack("tsuyu-v0", localVideoSource);
                localStream.addTrack(localVideoTrack);
                peer.addTrack(localAudioTrack, localStream.getLabels());
                peer.addTrack(localVideoTrack, localStream.getLabels());
                localVideoSource.start(capturer, egl.getEglBaseContext());

                if (localView != null) {
                    localView.init(TsuyuApp.get(), egl.getEglBaseContext());
                    localView.setMirror(true);
                    localView.addVideoSink(localVideoSource);
                }
                if (remoteView != null) {
                    remoteView.init(TsuyuApp.get(), egl.getEglBaseContext());
                }

                if (outgoing) {
                    createOffer();
                } else {
                    // incoming: wait for the offer
                    Log.i(TAG, "waiting for remote offer");
                }
            } catch (Throwable t) {
                Log.e(TAG, "start", t);
            }
        });
    }

    private static VideoCapturer createCamera(Context ctx) {
        try {
            if (Camera2Enumerator.isSupported()) {
                String[] cams = Camera2Enumerator.deviceNames();
                String front = null;
                String back = cams.length > 0 ? cams[0] : null;
                for (String n : cams) {
                    if (Camera2Enumerator.isFrontFacing(n)) {
                        front = n;
                        break;
                    }
                }
                String pick = front != null ? front : back;
                if (pick != null) {
                    Camera2Capturer c = new Camera2Capturer(ctx, pick, null);
                    return c;
                }
            }
            String[] c1 = Camera1Enumerator.deviceNames();
            if (c1.length > 0) {
                return new Camera1Capturer(ctx, c1[0], null);
            }
        } catch (Throwable t) {
            Log.e(TAG, "createCamera", t);
        }
        return null;
    }

    public static void setViews(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        localView = local;
        remoteView = remote;
    }

    private static PeerConnection createPeerConnection() {
        MediaConstraints pc = new MediaConstraints();
        pc.mandatory.add(new MediaConstraints.KeyValuePair("ice-gathering-timeout", "8000"));
        pc.optional.add(new MediaConstraints.KeyValuePair("bundle-policy", "max-bundle"));
        pc.optional.add(new MediaConstraints.KeyValuePair("rtcp-mux", "true"));
        PeerConnection p = new PeerConnection.Builder(TsuyuApp.get(), factory, pc)
                .setSdpObserver(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) {
                        Log.i(TAG, "sdp created " + s.type);
                    }
                    @Override public void onSetSuccess() {
                        Log.i(TAG, "sdp set ok");
                        if (pendingSignal != null) {
                            publishSignal(pendingSignal);
                            pendingSignal = null;
                        }
                        ensureRemoteViews();
                    }
                    @Override public void onCreateFailure(String e) {
                        Log.e(TAG, "sdp create fail: " + e);
                    }
                    @Override public void onSetFailure(String e) {
                        Log.e(TAG, "sdp set fail: " + e);
                    }
                })
                .setIceCandidateCallback(CallEngine::onLocalIceCandidate)
                .setIceConnectionObserver(new PeerConnection.IceConnectionObserver() {
                    @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                        Log.i(TAG, "ice state " + s);
                        if (s == PeerConnection.IceConnectionState.DISCONNECTED && peer != null) {
                            peer.restartIce();
                        }
                    }
                    @Override public void onIceConnectionReceivingChange(boolean b) {}
                    @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                        Log.i(TAG, "ice gathering " + s);
                    }
                    @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s) {}
                    @Override public void onStandardizedIceConnectionReceivingChange(boolean b) {}
                })
                .addTransceiver("audio")
                .addTransceiver("video")
                .build();
        p.setSignalingStateListener(s -> Log.i(TAG, "signaling " + s));
        return p;
    }

    private static void ensureRemoteViews() {
        if (remoteView == null || remoteStream == null) return;
        for (MediaStreamTrack t : remoteStream.getTracks()) {
            if (t instanceof VideoTrack) {
                VideoTrack vt = (VideoTrack) t;
                VideoSource vs = vt.getSource();
                if (vs != null) remoteView.addVideoSink(vs);
            }
        }
    }

    private static void createOffer() {
        if (peer == null) return;
        peer.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription s) {
                peer.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s2) {}
                    @Override public void onSetSuccess() {
                        pendingSignal = s2;
                        Log.i(TAG, "offer local, signal at ice complete");
                        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                            publishSignal(pendingSignal);
                            pendingSignal = null;
                        } else {
                            // fallback: publish after 3s even if gathering not complete
                            new Thread(() -> {
                                try { Thread.sleep(3000); } catch (Throwable ignored) {}
                                if (pendingSignal != null) {
                                    Log.w(TAG, "fallback publish (ice incomplete)");
                                    publishSignal(pendingSignal);
                                    pendingSignal = null;
                                }
                            }, "tsuyu-ice-fallback").start();
                        }
                    }
                    @Override public void onCreateFailure(String e) {}
                    @Override public void onSetFailure(String e) {}
                }, s);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {
                Log.e(TAG, "createOffer fail: " + e);
            }
            @Override public void onSetFailure(String e) {}
        }, SDP_CONSTRAINTS);
    }

    private static void createAnswer() {
        if (peer == null) return;
        peer.createAnswer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription s) {
                peer.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s2) {}
                    @Override public void onSetSuccess() {
                        publishSignal(s2);
                        markActive();
                    }
                    @Override public void onCreateFailure(String e) {}
                    @Override public void onSetFailure(String e) {}
                }, s);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {
                Log.e(TAG, "createAnswer fail: " + e);
            }
            @Override public void onSetFailure(String e) {}
        }, SDP_CONSTRAINTS);
    }

    private static void publishSignal(SessionDescription s) {
        if (CallService.callId == null || s == null) return;
        try {
            JSONObject d = new JSONObject();
            d.put(CallService.outgoing ? "offer" : "answer", s.description);
            Fb.fb().getReference("calls/" + CallService.callId).updateChildren(Fb.toMap(d));
            Log.i(TAG, "published " + s.type + " len=" + s.description.length());
        } catch (Throwable t) {
            Log.e(TAG, "publishSignal", t);
        }
    }

    public static void onRemoteSdp(String desc, String type) {
        if (desc == null || desc.isEmpty()) return;
        executor.execute(() -> {
            try {
                if (peer == null) return;
                SessionDescription sd = new SessionDescription(
                        "offer".equals(type) ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER,
                        desc);
                Log.i(TAG, "remote " + type + " received len=" + desc.length());
                peer.setRemoteDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onSetSuccess() {
                        if (peer.signalingState() == PeerConnection.SignalingState.STABLE && "offer".equals(type)) {
                            createAnswer();
                        } else if ("answer".equals(type)) {
                            state = ST_ACTIVE;
                            markActive();
                        }
                        ensureRemoteViews();
                        ensureRemoteStream();
                    }
                    @Override public void onCreateFailure(String e) {}
                    @Override public void onSetFailure(String e) {
                        Log.e(TAG, "remote set fail: " + e);
                    }
                }, sd);
            } catch (Throwable t) {
                Log.e(TAG, "onRemoteSdp", t);
            }
        });
    }

    private static void ensureRemoteStream() {
        if (peer == null || remoteStream != null) return;
        remoteStream = peer.getReceivingStream();
        Log.i(TAG, "remote stream " + (remoteStream == null ? "null" : remoteStream.getLabels().toString()));
    }

    public static void onRemoteIce(String mid, int line, String candidate) {
        if (candidate == null) return;
        executor.execute(() -> {
            try {
                if (peer == null) return;
                peer.addIceCandidate(new IceCandidate(mid, line, candidate));
            } catch (Throwable t) {
                Log.e(TAG, "onRemoteIce", t);
            }
        });
    }

    public static void onLocalIceCandidate(IceCandidate c) {
        try {
            if (CallService.callId == null || c == null) return;
            JSONObject d = new JSONObject();
            String key = CallService.outgoing ? "iceF" : "iceT";
            d.put(key, c.sdpMid() + "|" + c.sdpLine() + "|" + c.candidate());
            Fb.fb().getReference("calls/" + CallService.callId).updateChildren(Fb.toMap(d));
            Log.i(TAG, "ice published " + key + " mid=" + c.sdpMid() + " line=" + c.sdpLine());
        } catch (Throwable t) {
            Log.e(TAG, "onLocalIce", t);
        }
    }

    public static void toggleMute() {
        executor.execute(() -> {
            try {
                if (localAudioTrack == null) return;
                localMuted = !localMuted;
                localAudioTrack.setEnabled(!localMuted);
                try {
                    JSONObject d = new JSONObject();
                    d.put("muteF", localMuted);
                    Fb.fb().getReference("calls/" + CallService.callId).updateChildren(Fb.toMap(d));
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                Log.e(TAG, "toggleMute", t);
            }
        });
    }

    public static void toggleVideo() {
        executor.execute(() -> {
            try {
                if (localVideoTrack == null) return;
                localVideoOff = !localVideoOff;
                localVideoTrack.setEnabled(!localVideoOff);
            } catch (Throwable t) {
                Log.e(TAG, "toggleVideo", t);
            }
        });
    }

    public static void markActive() {
        try {
            if (CallService.callId == null) return;
            JSONObject d = new JSONObject();
            d.put("st", "active");
            d.put("startTs", System.currentTimeMillis());
            Fb.fb().getReference("calls/" + CallService.callId).updateChildren(Fb.toMap(d));
        } catch (Throwable t) {
            Log.e(TAG, "markActive", t);
        }
    }

    public static void finishCall(String reason, long ms) {
        try {
            if (CallService.callId != null) {
                JSONObject d = new JSONObject();
                d.put("st", "ended");
                d.put("endTs", System.currentTimeMillis());
                d.put("reason", reason);
                d.put("durMs", ms);
                Fb.fb().getReference("calls/" + CallService.callId).updateChildren(Fb.toMap(d));
            }
        } catch (Throwable ignored) {}
        state = ST_ENDED;
        teardown();
    }

    public static void teardown() {
        executor.execute(() -> {
            try {
                if (localVideoSource != null) localVideoSource.stop();
                if (localAudioTrack != null) localAudioTrack.dispose();
                if (localVideoTrack != null) localVideoTrack.dispose();
                if (capturer != null) capturer.dispose();
                if (peer != null) {
                    peer.close();
                    peer = null;
                }
            } catch (Throwable ignored) {}
            localStream = null;
            remoteStream = null;
            localAudioTrack = null;
            localVideoTrack = null;
            localVideoSource = null;
            capturer = null;
        });
        state = ST_IDLE;
        localMuted = false;
        localVideoOff = false;
    }
}
