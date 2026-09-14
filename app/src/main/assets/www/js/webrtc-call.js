/**
 * Tsuyu Messenger - WebRTC P2P Audio & Video Calling Engine
 * Realtime Database Signaling + STUN Fallback + Stream Controls
 */

class WebRTCCallEngine {
  constructor() {
    this.peerConnection = null;
    this.localStream = null;
    this.remoteStream = null;
    this.activeCall = null; // { callId, peerUid, isCaller, type: 'audio'|'video', status }
    this.callTimer = null;
    this.callDurationSec = 0;
    this.ringtoneAudio = null;
    this.isMuted = false;
    this.isVideoOn = false;
    this.currentCamera = "user"; // 'user' or 'environment'

    this.iceServers = [
      { urls: "stun:stun.l.google.com:19302" },
      { urls: "stun:stun1.l.google.com:19302" },
      { urls: "stun:stun2.l.google.com:19302" }
    ];

    this.initIncomingCallListener();
  }

  // --- Start Outgoing Call ---

  async startCall(peerUid, peerName, peerPhoto, isVideo = false) {
    if (this.activeCall) {
      alert("У вас уже есть активный вызов");
      return;
    }

    const myUid = window.firebaseApp.uid;
    const callId = `call_${myUid}_${peerUid}_${Date.now()}`;
    this.activeCall = {
      callId,
      peerUid,
      peerName: peerName || "Собеседник",
      peerPhoto: peerPhoto || "",
      isCaller: true,
      type: isVideo ? "video" : "audio",
      status: "calling"
    };

    this.isVideoOn = isVideo;
    this.showCallUI(this.activeCall);
    this.updateCallStatus("Вызов...");
    this.playRingtone(true);

    // Notify Android Foreground Service of call
    if (window.AndroidBridge && window.AndroidBridge.startCallForeground) {
      window.AndroidBridge.startCallForeground(peerName, isVideo);
    }

    try {
      this.localStream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: isVideo ? { facingMode: this.currentCamera, width: { ideal: 640 }, height: { ideal: 480 } } : false
      });

      this.setupLocalVideoPreview();
      this.createPeerConnection();

      this.localStream.getTracks().forEach((track) => {
        this.peerConnection.addTrack(track, this.localStream);
      });

      const offer = await this.peerConnection.createOffer();
      await this.peerConnection.setLocalDescription(offer);

      const callData = {
        callId,
        callerId: myUid,
        calleeId: peerUid,
        callerName: window.currentUser ? window.currentUser.name : "Tsuyu User",
        callerPhoto: window.currentUser ? (window.currentUser.photo || "") : "",
        type: isVideo ? "video" : "audio",
        offer: { type: offer.type, sdp: offer.sdp },
        status: "calling",
        createdAt: Date.now()
      };

      await window.firebaseApp.dbSet(`calls/${peerUid}/incoming`, callData);
      await window.firebaseApp.dbSet(`calls_active/${callId}`, callData);

      this.listenCallSignaling(callId, peerUid);
    } catch (e) {
      console.error("Failed to start call:", e);
      alert("Не удалось получить доступ к микрофону/камере");
      this.endCall();
    }
  }

  // --- Handle Incoming Call Notification & Acceptance ---

  initIncomingCallListener() {
    const myUid = window.firebaseApp ? window.firebaseApp.uid : localStorage.getItem("tsuyu_uid");
    if (!myUid) return;

    window.firebaseApp.dbListen(`calls/${myUid}/incoming`, (path, data) => {
      if (!data || !data.callId) {
        this.hideIncomingBanner();
        return;
      }

      if (this.activeCall && this.activeCall.callId === data.callId) {
        return;
      }

      if (data.status === "calling" && (Date.now() - data.createdAt < 45000)) {
        this.showIncomingBanner(data);
      }
    });
  }

  showIncomingBanner(callData) {
    this.playRingtone(true);
    let banner = document.getElementById("incomingCallBanner");
    if (!banner) {
      banner = document.createElement("div");
      banner.id = "incomingCallBanner";
      banner.className = "incoming-call-banner";
      document.body.appendChild(banner);
    }

    banner.innerHTML = `
      <img class="incoming-call-avatar" src="${callData.callerPhoto || 'data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'46\' height=\'46\' fill=\'%23555\'><rect width=\'46\' height=\'46\' rx=\'23\'/></svg>'}" alt="">
      <div class="incoming-call-info">
        <div class="incoming-call-name">${callData.callerName || 'Собеседник'}</div>
        <div class="incoming-call-sub">${callData.type === 'video' ? 'Входящий видеозвонок...' : 'Входящий аудиозвонок...'}</div>
      </div>
      <div class="incoming-call-actions">
        <button class="incoming-call-btn incoming-call-decline" onclick="window.webrtcCall.declineIncomingCall('${callData.callId}', '${callData.callerId}')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
        <button class="incoming-call-btn incoming-call-accept" onclick="window.webrtcCall.acceptIncomingCall('${callData.callId}', '${callData.callerId}', '${callData.callerName}', '${callData.callerPhoto}', '${callData.type}')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/></svg>
        </button>
      </div>
    `;
    banner.classList.remove("hidden");

    if (window.AndroidBridge && window.AndroidBridge.showIncomingCallNotification) {
      window.AndroidBridge.showIncomingCallNotification(callData.callerName, callData.type === 'video');
    }
  }

  hideIncomingBanner() {
    this.stopRingtone();
    const banner = document.getElementById("incomingCallBanner");
    if (banner) banner.classList.add("hidden");
  }

  async acceptIncomingCall(callId, callerId, callerName, callerPhoto, callType) {
    this.hideIncomingBanner();
    const isVideo = callType === "video";
    this.activeCall = {
      callId,
      peerUid: callerId,
      peerName: callerName || "Собеседник",
      peerPhoto: callerPhoto || "",
      isCaller: false,
      type: callType,
      status: "connecting"
    };

    this.isVideoOn = isVideo;
    this.showCallUI(this.activeCall);
    this.updateCallStatus("Соединение...");

    if (window.AndroidBridge && window.AndroidBridge.startCallForeground) {
      window.AndroidBridge.startCallForeground(callerName, isVideo);
    }

    try {
      this.localStream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: isVideo ? { facingMode: this.currentCamera, width: { ideal: 640 }, height: { ideal: 480 } } : false
      });

      this.setupLocalVideoPreview();
      this.createPeerConnection();

      this.localStream.getTracks().forEach((track) => {
        this.peerConnection.addTrack(track, this.localStream);
      });

      const callData = await window.firebaseApp.dbGet(`calls_active/${callId}`);
      if (!callData || !callData.offer) {
        alert("Звонок уже завершен");
        this.endCall();
        return;
      }

      await this.peerConnection.setRemoteDescription(new RTCSessionDescription(callData.offer));
      const answer = await this.peerConnection.createAnswer();
      await this.peerConnection.setLocalDescription(answer);

      await window.firebaseApp.dbUpdate(`calls_active/${callId}`, {
        answer: { type: answer.type, sdp: answer.sdp },
        status: "accepted"
      });

      await window.firebaseApp.dbDelete(`calls/${window.firebaseApp.uid}/incoming`);

      this.listenCallSignaling(callId, callerId);
    } catch (e) {
      console.error("Failed to accept call:", e);
      this.endCall();
    }
  }

  async declineIncomingCall(callId, callerId) {
    this.hideIncomingBanner();
    await window.firebaseApp.dbUpdate(`calls_active/${callId}`, { status: "declined" });
    await window.firebaseApp.dbDelete(`calls/${window.firebaseApp.uid}/incoming`);
  }

  // --- PeerConnection Setup ---

  createPeerConnection() {
    this.peerConnection = new RTCPeerConnection({ iceServers: this.iceServers });

    this.peerConnection.onicecandidate = (event) => {
      if (event.candidate && this.activeCall) {
        const side = this.activeCall.isCaller ? "caller_ice" : "callee_ice";
        window.firebaseApp.dbPush(`calls_active/${this.activeCall.callId}/${side}`, {
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex
        });
      }
    };

    this.peerConnection.ontrack = (event) => {
      this.remoteStream = event.streams[0];
      const remoteVideo = document.getElementById("callRemoteVideo");
      if (remoteVideo) {
        remoteVideo.srcObject = this.remoteStream;
        remoteVideo.play().catch(() => {});
      }
    };

    this.peerConnection.onconnectionstatechange = () => {
      if (!this.peerConnection) return;
      const state = this.peerConnection.connectionState;
      if (state === "connected") {
        this.stopRingtone();
        this.updateCallStatus("00:00");
        this.startCallTimer();
      } else if (state === "disconnected" || state === "failed" || state === "closed") {
        this.endCall();
      }
    };
  }

  listenCallSignaling(callId, peerUid) {
    window.firebaseApp.dbListen(`calls_active/${callId}`, async (path, data) => {
      if (!data) return;

      if (data.status === "declined" || data.status === "ended") {
        this.updateCallStatus(data.status === "declined" ? "Отклонено" : "Завершено");
        setTimeout(() => this.endCall(), 1200);
        return;
      }

      // If we are caller and received answer
      if (this.activeCall && this.activeCall.isCaller && data.answer && this.peerConnection) {
        if (!this.peerConnection.currentRemoteDescription) {
          await this.peerConnection.setRemoteDescription(new RTCSessionDescription(data.answer));
        }
      }

      // Process ICE candidates
      const remoteIceField = this.activeCall.isCaller ? "callee_ice" : "caller_ice";
      if (data[remoteIceField] && this.peerConnection) {
        const iceList = Object.values(data[remoteIceField]);
        for (const ice of iceList) {
          try {
            await this.peerConnection.addIceCandidate(new RTCIceCandidate(ice));
          } catch (e) {}
        }
      }
    });
  }

  // --- Stream & Hardware Controls ---

  toggleMute() {
    if (!this.localStream) return;
    this.isMuted = !this.isMuted;
    this.localStream.getAudioTracks().forEach((track) => {
      track.enabled = !this.isMuted;
    });
    const muteBtn = document.getElementById("callBtnMute");
    if (muteBtn) muteBtn.classList.toggle("active", this.isMuted);
  }

  async toggleVideo() {
    if (!this.activeCall) return;
    this.isVideoOn = !this.isVideoOn;
    const videoBtn = document.getElementById("callBtnVideo");
    if (videoBtn) videoBtn.classList.toggle("active", this.isVideoOn);

    if (this.isVideoOn) {
      try {
        const videoStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: this.currentCamera, width: { ideal: 640 }, height: { ideal: 480 } }
        });
        const videoTrack = videoStream.getVideoTracks()[0];
        this.localStream.addTrack(videoTrack);
        if (this.peerConnection) {
          this.peerConnection.addTrack(videoTrack, this.localStream);
        }
        this.setupLocalVideoPreview();
      } catch (e) {
        console.error("Failed to enable video:", e);
      }
    } else {
      this.localStream.getVideoTracks().forEach((track) => {
        track.stop();
        this.localStream.removeTrack(track);
      });
      const localVid = document.getElementById("callLocalVideo");
      if (localVid) localVid.srcObject = null;
    }
  }

  async flipCamera() {
    this.currentCamera = this.currentCamera === "user" ? "environment" : "user";
    if (this.isVideoOn && this.localStream) {
      this.localStream.getVideoTracks().forEach((t) => t.stop());
      const newStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: this.currentCamera, width: { ideal: 640 }, height: { ideal: 480 } }
      });
      const newTrack = newStream.getVideoTracks()[0];
      const sender = this.peerConnection.getSenders().find((s) => s.track && s.track.kind === "video");
      if (sender) {
        sender.replaceTrack(newTrack);
      }
      this.localStream.removeTrack(this.localStream.getVideoTracks()[0]);
      this.localStream.addTrack(newTrack);
      this.setupLocalVideoPreview();
    }
  }

  setupLocalVideoPreview() {
    const localVid = document.getElementById("callLocalVideo");
    if (localVid && this.localStream && this.localStream.getVideoTracks().length > 0) {
      localVid.srcObject = this.localStream;
      localVid.classList.remove("hidden");
      localVid.play().catch(() => {});
    }
  }

  // --- Call UI & Timers ---

  showCallUI(call) {
    let modal = document.getElementById("callModal");
    if (!modal) {
      modal = document.createElement("div");
      modal.id = "callModal";
      modal.className = "call-modal";
      document.body.appendChild(modal);
    }

    modal.innerHTML = `
      <div class="call-video-container" id="callVideoContainer">
        <video class="call-remote-video" id="callRemoteVideo" autoplay playsinline></video>
        <video class="call-local-video-pip hidden" id="callLocalVideo" autoplay muted playsinline></video>
      </div>

      <div class="call-header">
        <div class="call-avatar-wrap" id="callAvatarWrap">
          <div class="call-pulse-ring"></div>
          <div class="call-pulse-ring"></div>
          <div class="call-pulse-ring"></div>
          <img class="call-avatar" src="${call.peerPhoto || 'data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'110\' height=\'110\' fill=\'%23555\'><rect width=\'110\' height=\'110\' rx=\'55\'/></svg>'}" alt="">
        </div>
        <div class="call-user-name">${call.peerName}</div>
        <div class="call-status" id="callStatusText">Вызов...</div>
      </div>

      <div class="call-controls">
        <button class="call-btn call-btn-mute" id="callBtnMute" onclick="window.webrtcCall.toggleMute()" title="Микрофон">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
        </button>

        <button class="call-btn call-btn-video ${this.isVideoOn ? 'active' : ''}" id="callBtnVideo" onclick="window.webrtcCall.toggleVideo()" title="Видео">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>
        </button>

        <button class="call-btn call-btn-flip" id="callBtnFlip" onclick="window.webrtcCall.flipCamera()" title="Переключить камеру">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 10c0-4.418-3.582-8-8-8s-8 3.582-8 8"/><path d="M4 14c0 4.418 3.582 8 8 8s8-3.582 8-8"/><polyline points="1 7 4 10 7 7"/><polyline points="23 17 20 14 17 17"/></svg>
        </button>

        <button class="call-btn call-btn-end" onclick="window.webrtcCall.endCall()" title="Завершить">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>
    `;

    modal.classList.remove("hidden");
  }

  updateCallStatus(text) {
    const el = document.getElementById("callStatusText");
    if (el) el.textContent = text;
  }

  startCallTimer() {
    this.callDurationSec = 0;
    if (this.callTimer) clearInterval(this.callTimer);
    this.callTimer = setInterval(() => {
      this.callDurationSec++;
      const m = Math.floor(this.callDurationSec / 60);
      const s = this.callDurationSec % 60;
      const timeStr = `${m}:${s < 10 ? '0' : ''}${s}`;
      this.updateCallStatus(timeStr);
    }, 1000);
  }

  // --- Ringtones & Audio Feedback ---

  playRingtone(isIncoming = false) {
    this.stopRingtone();
    try {
      this.ringtoneAudio = new Audio("sounds/ringtone.wav");
      this.ringtoneAudio.loop = true;
      this.ringtoneAudio.play().catch(() => {});
    } catch (e) {}
  }

  stopRingtone() {
    if (this.ringtoneAudio) {
      try {
        this.ringtoneAudio.pause();
        this.ringtoneAudio.currentTime = 0;
      } catch (e) {}
      this.ringtoneAudio = null;
    }
  }

  // --- End Call & Cleanup ---

  async endCall() {
    this.stopRingtone();
    if (this.callTimer) {
      clearInterval(this.callTimer);
      this.callTimer = null;
    }

    if (this.activeCall) {
      try {
        await window.firebaseApp.dbUpdate(`calls_active/${this.activeCall.callId}`, { status: "ended" });
        await window.firebaseApp.dbDelete(`calls/${this.activeCall.peerUid}/incoming`);
        await window.firebaseApp.dbDelete(`calls/${window.firebaseApp.uid}/incoming`);
        window.firebaseApp.dbUnlisten(`calls_active/${this.activeCall.callId}`);
      } catch (e) {}
    }

    if (this.localStream) {
      this.localStream.getTracks().forEach((track) => track.stop());
      this.localStream = null;
    }

    if (this.peerConnection) {
      this.peerConnection.close();
      this.peerConnection = null;
    }

    const modal = document.getElementById("callModal");
    if (modal) modal.classList.add("hidden");

    this.hideIncomingBanner();
    this.activeCall = null;
    this.isMuted = false;
    this.isVideoOn = false;

    if (window.AndroidBridge && window.AndroidBridge.stopCallForeground) {
      window.AndroidBridge.stopCallForeground();
    }
  }
}

window.webrtcCall = new WebRTCCallEngine();
