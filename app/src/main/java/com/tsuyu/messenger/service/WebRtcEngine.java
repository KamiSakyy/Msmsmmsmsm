package com.tsuyu.messenger.service;

import android.content.Context;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

/** Thin P2P wrapper around WebRTC for 1:1 audio/video calls. */
public class WebRtcEngine {

    public interface Events {
        void onLocalSdp(SessionDescription sdp);
        void onIceCandidate(IceCandidate candidate);
        void onConnected();
        void onDisconnected();
        void onRemoteVideo(VideoTrack track);
    }

    private final Context ctx;
    private final Events events;
    private final boolean video;

    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private EglBase eglBase;

    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private VideoCapturer capturer;
    private SurfaceTextureHelper surfaceHelper;

    public WebRtcEngine(Context ctx, boolean video, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.video = video;
        this.events = events;
    }

    public EglBase eglBase() { return eglBase; }

    public void init() {
        eglBase = EglBase.create();

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(ctx)
                .createInitializationOptions());

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                .createIceServer());

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(ice);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

        pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) { events.onIceCandidate(c); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
            @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                if (s == PeerConnection.IceConnectionState.CONNECTED
                        || s == PeerConnection.IceConnectionState.COMPLETED) events.onConnected();
                else if (s == PeerConnection.IceConnectionState.DISCONNECTED
                        || s == PeerConnection.IceConnectionState.FAILED
                        || s == PeerConnection.IceConnectionState.CLOSED) events.onDisconnected();
            }
            @Override public void onIceConnectionReceivingChange(boolean b) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            @Override public void onAddStream(MediaStream stream) { }
            @Override public void onRemoveStream(MediaStream stream) { }
            @Override public void onDataChannel(org.webrtc.DataChannel dc) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                if (receiver.track() instanceof VideoTrack) {
                    events.onRemoteVideo((VideoTrack) receiver.track());
                }
            }
        });

        createLocalTracks();
    }

    private void createLocalTracks() {
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));

        audioSource = factory.createAudioSource(audioConstraints);
        audioTrack = factory.createAudioTrack("audio0", audioSource);
        pc.addTrack(audioTrack);

        if (!video) return;

        capturer = createCapturer();
        if (capturer == null) return;
        surfaceHelper = SurfaceTextureHelper.create("capture", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(capturer.isScreencast());
        capturer.initialize(surfaceHelper, ctx, videoSource.getCapturerObserver());
        capturer.startCapture(1280, 720, 30);
        videoTrack = factory.createVideoTrack("video0", videoSource);
        pc.addTrack(videoTrack);
    }

    private VideoCapturer createCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(ctx)
                ? new Camera2Enumerator(ctx) : new Camera1Enumerator(true);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                VideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        for (String name : enumerator.getDeviceNames()) {
            VideoCapturer c = enumerator.createCapturer(name, null);
            if (c != null) return c;
        }
        return null;
    }

    public void attachLocalView(SurfaceViewRenderer view) {
        if (videoTrack != null) videoTrack.addSink(view);
    }

    public VideoTrack localVideo() { return videoTrack; }

    public void createOffer() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                video ? "true" : "false"));
        pc.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdpObserver(), sdp);
                events.onLocalSdp(sdp);
            }
        }, c);
    }

    public void createAnswer() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                video ? "true" : "false"));
        pc.createAnswer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdpObserver(), sdp);
                events.onLocalSdp(sdp);
            }
        }, c);
    }

    public void setRemoteDescription(SessionDescription sdp) {
        if (pc != null) pc.setRemoteDescription(new SimpleSdpObserver(), sdp);
    }

    public void addIceCandidate(IceCandidate c) {
        if (pc != null) pc.addIceCandidate(c);
    }

    public void setMicEnabled(boolean on) {
        if (audioTrack != null) audioTrack.setEnabled(on);
    }

    public void setCamEnabled(boolean on) {
        if (videoTrack != null) videoTrack.setEnabled(on);
    }

    public void switchCamera() {
        if (capturer instanceof org.webrtc.CameraVideoCapturer) {
            ((org.webrtc.CameraVideoCapturer) capturer).switchCamera(null);
        }
    }

    public void release() {
        try { if (capturer != null) { capturer.stopCapture(); capturer.dispose(); } } catch (Exception ignored) { }
        try { if (surfaceHelper != null) surfaceHelper.dispose(); } catch (Exception ignored) { }
        try { if (videoSource != null) videoSource.dispose(); } catch (Exception ignored) { }
        try { if (audioSource != null) audioSource.dispose(); } catch (Exception ignored) { }
        try { if (pc != null) pc.close(); } catch (Exception ignored) { }
        try { if (factory != null) factory.dispose(); } catch (Exception ignored) { }
        try { if (eglBase != null) eglBase.release(); } catch (Exception ignored) { }
        pc = null;
        factory = null;
    }

    public static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String s) { }
        @Override public void onSetFailure(String s) { }
    }
}
