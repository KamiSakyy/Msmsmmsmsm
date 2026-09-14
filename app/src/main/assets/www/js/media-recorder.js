/**
 * Tsuyu Messenger - Media Recording & Compression Engine
 * Dynamic Amplitude Voice Waveforms + Telegram Video Circles ("Кружочки") + Image/Video Base64 Optimizers
 */

class MediaRecorderEngine {
  constructor() {
    this.voiceStream = null;
    this.voiceRecorder = null;
    this.voiceChunks = [];
    this.voiceTimer = null;
    this.voiceSeconds = 0;
    this.voiceWaveformPeaks = [];
    this.audioContext = null;
    this.analyser = null;
    this.animFrameId = null;

    this.circleStream = null;
    this.circleRecorder = null;
    this.circleChunks = [];
    this.circleTimer = null;
    this.circleSeconds = 0;
    this.circleFacingMode = "user";
    this.circleTorchOn = false;
  }

  getSupportedMimeType(type) {
    if (type === "audio") {
      const types = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4", "audio/aac"];
      for (const t of types) {
        if (MediaRecorder.isTypeSupported(t)) return t;
      }
    } else if (type === "video") {
      const types = ["video/webm;codecs=vp8,opus", "video/webm", "video/mp4"];
      for (const t of types) {
        if (MediaRecorder.isTypeSupported(t)) return t;
      }
    }
    return "";
  }

  // --- Voice Message Recording with Realtime Dynamic Waveform ---

  async startVoiceRecording() {
    try {
      const mimeType = this.getSupportedMimeType("audio");
      this.voiceStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      this.voiceChunks = [];
      this.voiceWaveformPeaks = [];

      this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
      const source = this.audioContext.createMediaStreamSource(this.voiceStream);
      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 64;
      source.connect(this.analyser);

      const options = mimeType ? { mimeType } : undefined;
      this.voiceRecorder = new MediaRecorder(this.voiceStream, options);

      this.voiceRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) this.voiceChunks.push(e.data);
      };

      this.voiceRecorder.start(100);

      document.getElementById("inputAreaNormal").classList.add("hidden");
      document.getElementById("inputAreaVoice").classList.remove("hidden");

      this.voiceSeconds = 0;
      document.getElementById("voiceTimer").textContent = "0:00";
      if (this.voiceTimer) clearInterval(this.voiceTimer);
      this.voiceTimer = setInterval(() => {
        this.voiceSeconds++;
        const m = Math.floor(this.voiceSeconds / 60);
        const s = this.voiceSeconds % 60;
        document.getElementById("voiceTimer").textContent = `${m}:${s < 10 ? "0" : ""}${s}`;
      }, 1000);

      this.trackWaveformAmplitudes();
    } catch (e) {
      console.error("Voice recording error:", e);
      alert("Не удалось запустить запись аудио: доступ к микрофону запрещен.");
    }
  }

  trackWaveformAmplitudes() {
    const dataArray = new Uint8Array(this.analyser.frequencyBinCount);
    const sample = () => {
      if (!this.voiceRecorder || this.voiceRecorder.state !== "recording") return;
      this.analyser.getByteFrequencyData(dataArray);
      let sum = 0;
      for (let i = 0; i < dataArray.length; i++) {
        sum += dataArray[i];
      }
      const avg = sum / dataArray.length;
      const normalized = Math.max(0.15, Math.min(1.0, avg / 128));
      this.voiceWaveformPeaks.push(parseFloat(normalized.toFixed(2)));
      this.animFrameId = requestAnimationFrame(sample);
    };
    this.animFrameId = requestAnimationFrame(sample);
  }

  cancelVoiceRecording() {
    if (this.voiceRecorder && this.voiceRecorder.state !== "inactive") {
      this.voiceRecorder.stop();
    }
    this.cleanupVoice();
    document.getElementById("inputAreaVoice").classList.add("hidden");
    document.getElementById("inputAreaNormal").classList.remove("hidden");
  }

  async stopAndSendVoiceRecording(onComplete) {
    if (!this.voiceRecorder || this.voiceRecorder.state === "inactive") return;

    this.voiceRecorder.onstop = async () => {
      const mime = this.voiceRecorder.mimeType || "audio/webm";
      const blob = new Blob(this.voiceChunks, { type: mime });
      const duration = this.voiceSeconds || 1;
      const sampledWaveform = this.compressWaveformPeaks(this.voiceWaveformPeaks, 32);

      const base64 = await this.blobToBase64(blob);
      this.cleanupVoice();
      document.getElementById("inputAreaVoice").classList.add("hidden");
      document.getElementById("inputAreaNormal").classList.remove("hidden");

      if (onComplete) {
        onComplete({
          type: "voice",
          name: `voice_${Date.now()}.mgs`,
          mime,
          duration,
          waveform: sampledWaveform,
          dataBase64: base64
        });
      }
    };

    this.voiceRecorder.stop();
  }

  compressWaveformPeaks(peaks, targetCount) {
    if (!peaks || peaks.length === 0) {
      return Array(targetCount).fill(0.3);
    }
    if (peaks.length <= targetCount) {
      const res = [...peaks];
      while (res.length < targetCount) res.push(0.2);
      return res;
    }
    const step = peaks.length / targetCount;
    const res = [];
    for (let i = 0; i < targetCount; i++) {
      const idx = Math.floor(i * step);
      res.push(peaks[idx] || 0.3);
    }
    return res;
  }

  cleanupVoice() {
    if (this.voiceTimer) clearInterval(this.voiceTimer);
    if (this.animFrameId) cancelAnimationFrame(this.animFrameId);
    if (this.voiceStream) {
      this.voiceStream.getTracks().forEach((t) => t.stop());
      this.voiceStream = null;
    }
    if (this.audioContext && this.audioContext.state !== "closed") {
      this.audioContext.close().catch(() => {});
    }
    this.voiceRecorder = null;
    this.voiceChunks = [];
  }

  // --- Telegram Round Video Circle ("Кружочек") Recording ---

  async startCircleRecording() {
    try {
      this.circleFacingMode = "user";
      this.circleTorchOn = false;

      try {
        this.circleStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: this.circleFacingMode, width: { ideal: 480 }, height: { ideal: 480 } },
          audio: true
        });
      } catch (errFallback) {
        this.circleStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
      }

      this.circleChunks = [];
      const videoElem = document.getElementById("circleVideoPreview");
      videoElem.srcObject = this.circleStream;
      videoElem.style.transform = this.circleFacingMode === "user" ? "scaleX(-1)" : "scaleX(1)";
      await videoElem.play().catch(() => {});

      document.getElementById("circleModal").classList.remove("hidden");

      const mimeType = this.getSupportedMimeType("video");
      const recOptions = mimeType ? { mimeType } : undefined;
      this.circleRecorder = new MediaRecorder(this.circleStream, recOptions);

      this.circleRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) this.circleChunks.push(e.data);
      };

      this.circleRecorder.start(100);

      this.circleSeconds = 0;
      document.getElementById("circleTimer").textContent = "0:00";
      if (this.circleTimer) clearInterval(this.circleTimer);
      this.circleTimer = setInterval(() => {
        this.circleSeconds++;
        const m = Math.floor(this.circleSeconds / 60);
        const s = this.circleSeconds % 60;
        document.getElementById("circleTimer").textContent = `${m}:${s < 10 ? "0" : ""}${s}`;
        if (this.circleSeconds >= 60) {
          this.stopAndSendCircleRecording();
        }
      }, 1000);
    } catch (e) {
      console.error("Circle recording error:", e);
      alert("Не удалось запустить кружочек: проверьте доступ к камере/микрофону.");
      this.cleanupCircle();
    }
  }

  async toggleCircleCamera() {
    if (!this.circleStream) return;
    this.circleFacingMode = this.circleFacingMode === "user" ? "environment" : "user";
    this.circleStream.getTracks().forEach((t) => t.stop());

    try {
      this.circleStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: this.circleFacingMode, width: { ideal: 480 }, height: { ideal: 480 } },
        audio: true
      });
      const videoElem = document.getElementById("circleVideoPreview");
      videoElem.srcObject = this.circleStream;
      videoElem.style.transform = this.circleFacingMode === "user" ? "scaleX(-1)" : "scaleX(1)";
      await videoElem.play().catch(() => {});
    } catch (e) {
      console.error("Camera flip error:", e);
    }
  }

  async toggleCircleTorch() {
    if (!this.circleStream) return;
    const track = this.circleStream.getVideoTracks()[0];
    if (track && track.applyConstraints) {
      try {
        this.circleTorchOn = !this.circleTorchOn;
        await track.applyConstraints({
          advanced: [{ torch: this.circleTorchOn }]
        });
      } catch (e) {
        console.warn("Torch not supported on this device/camera.");
      }
    }
  }

  cancelCircleRecording() {
    if (this.circleRecorder && this.circleRecorder.state !== "inactive") {
      this.circleRecorder.stop();
    }
    this.cleanupCircle();
  }

  async stopAndSendCircleRecording(onComplete) {
    if (!this.circleRecorder || this.circleRecorder.state === "inactive") return;

    this.circleRecorder.onstop = async () => {
      const mime = this.circleRecorder.mimeType || "video/webm";
      const blob = new Blob(this.circleChunks, { type: mime });
      const duration = this.circleSeconds || 1;

      const base64 = await this.blobToBase64(blob);
      this.cleanupCircle();

      if (onComplete) {
        onComplete({
          type: "circle",
          name: `circle_${Date.now()}.mkru`,
          mime,
          duration,
          dataBase64: base64
        });
      }
    };

    this.circleRecorder.stop();
  }

  cleanupCircle() {
    if (this.circleTimer) clearInterval(this.circleTimer);
    if (this.circleStream) {
      this.circleStream.getTracks().forEach((t) => t.stop());
      this.circleStream = null;
    }
    this.circleRecorder = null;
    this.circleChunks = [];
    document.getElementById("circleModal").classList.add("hidden");
  }

  // --- Image Compression & Resizing to Base64 ---

  async compressImageFile(file, maxDimension = 1280, quality = 0.82) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = (e) => {
        const img = new Image();
        img.onload = () => {
          let width = img.width;
          let height = img.height;

          if (width > maxDimension || height > maxDimension) {
            if (width > height) {
              height = Math.round((height * maxDimension) / width);
              width = maxDimension;
            } else {
              width = Math.round((width * maxDimension) / height);
              height = maxDimension;
            }
          }

          const canvas = document.createElement("canvas");
          canvas.width = width;
          canvas.height = height;
          const ctx = canvas.getContext("2d");
          ctx.drawImage(img, 0, 0, width, height);

          // Return compressed Base64 Data URL
          const dataUrl = canvas.toDataURL("image/jpeg", quality);
          resolve({
            width,
            height,
            mime: "image/jpeg",
            name: file.name || `photo_${Date.now()}.jpg`,
            dataBase64: dataUrl
          });
        };
        img.onerror = reject;
        img.src = e.target.result;
      };
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
  }

  // --- Video First-Frame Thumbnail Generator ---

  async extractVideoThumbnail(fileOrBlob) {
    return new Promise((resolve) => {
      const video = document.createElement("video");
      video.preload = "metadata";
      video.muted = true;
      video.playsInline = true;

      const url = URL.createObjectURL(fileOrBlob);
      video.src = url;

      video.onloadeddata = () => {
        video.currentTime = Math.min(1.0, video.duration / 2);
      };

      video.onseeked = () => {
        const canvas = document.createElement("canvas");
        canvas.width = Math.min(video.videoWidth, 480);
        canvas.height = Math.min(video.videoHeight, 360);
        const ctx = canvas.getContext("2d");
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const thumbBase64 = canvas.toDataURL("image/jpeg", 0.65);
        URL.revokeObjectURL(url);
        resolve({
          thumbBase64,
          duration: Math.round(video.duration) || 0,
          width: video.videoWidth,
          height: video.videoHeight
        });
      };

      video.onerror = () => {
        URL.revokeObjectURL(url);
        resolve({ thumbBase64: "", duration: 0 });
      };
    });
  }

  blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }
}

window.mediaRecorderEngine = new MediaRecorderEngine();
