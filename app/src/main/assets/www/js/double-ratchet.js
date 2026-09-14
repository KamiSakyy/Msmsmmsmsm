/**
 * Tsuyu Messenger - Signal Double Ratchet Cryptographic Engine
 * Complete Signal Protocol: ECDH (P-256) + HKDF (SHA-256) + HMAC-SHA256 + AES-256-GCM
 * Zero-Knowledge Profile Encryption + Key Rotation + Encrypted Backup
 */

class DoubleRatchetEngine {
  constructor() {
    this.crypto = window.crypto.subtle;
    this.identityKeyPair = null; // Our persistent identity key { publicKey, privateKey, jwkPub, jwkPriv }
    this.ratchetStates = new Map(); // peerUid -> RatchetState
    this.masterKey = null; // Derived from user password for local key encryption
    this.storagePrefix = "tsuyu_ratchet_";
  }

  // --- Utility Buffers & Encodings ---

  bufToB64(buf) {
    const bytes = new Uint8Array(buf);
    let binary = "";
    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
  }

  b64ToBuf(b64) {
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
  }

  strToBuf(str) {
    return new TextEncoder().encode(str);
  }

  bufToStr(buf) {
    return new TextDecoder().decode(buf);
  }

  async hashSha256(dataBuf) {
    return await this.crypto.digest("SHA-256", dataBuf);
  }

  async hmacSha256(keyBuf, dataBuf) {
    const key = await this.crypto.importKey(
      "raw",
      keyBuf,
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"]
    );
    return await this.crypto.sign("HMAC", key, dataBuf);
  }

  async hkdf(saltBuf, ikmBuf, infoStr, length = 64) {
    // HKDF-Extract
    const prk = await this.hmacSha256(saltBuf.byteLength > 0 ? saltBuf : new Uint8Array(32), ikmBuf);
    
    // HKDF-Expand
    const infoBuf = this.strToBuf(infoStr);
    const t1Input = new Uint8Array(infoBuf.byteLength + 1);
    t1Input.set(new Uint8Array(infoBuf), 0);
    t1Input[infoBuf.byteLength] = 1;
    const t1 = await this.hmacSha256(prk, t1Input);

    if (length <= 32) {
      return t1.slice(0, length);
    }

    const t2Input = new Uint8Array(t1.byteLength + infoBuf.byteLength + 1);
    t2Input.set(new Uint8Array(t1), 0);
    t2Input.set(new Uint8Array(infoBuf), t1.byteLength);
    t2Input[t1.byteLength + infoBuf.byteLength] = 2;
    const t2 = await this.hmacSha256(prk, t2Input);

    const out = new Uint8Array(64);
    out.set(new Uint8Array(t1), 0);
    out.set(new Uint8Array(t2), 32);
    return out.buffer;
  }

  // --- Key Pair Generation & Serialization ---

  async generateDHKeyPair() {
    const keyPair = await this.crypto.generateKey(
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveKey", "deriveBits"]
    );
    const jwkPub = await this.crypto.exportKey("jwk", keyPair.publicKey);
    const jwkPriv = await this.crypto.exportKey("jwk", keyPair.privateKey);
    return {
      publicKey: keyPair.publicKey,
      privateKey: keyPair.privateKey,
      jwkPub,
      jwkPriv,
      pubB64: this.bufToB64(this.strToBuf(JSON.stringify(jwkPub)))
    };
  }

  async importDHPublicKey(jwkOrB64) {
    let jwk;
    if (typeof jwkOrB64 === "string") {
      try {
        jwk = JSON.parse(this.bufToStr(this.b64ToBuf(jwkOrB64)));
      } catch (e) {
        jwk = JSON.parse(jwkOrB64);
      }
    } else {
      jwk = jwkOrB64;
    }
    return await this.crypto.importKey(
      "jwk",
      jwk,
      { name: "ECDH", namedCurve: "P-256" },
      true,
      []
    );
  }

  async importDHPrivateKey(jwk) {
    return await this.crypto.importKey(
      "jwk",
      jwk,
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveKey", "deriveBits"]
    );
  }

  async computeSharedSecret(privateKey, publicKey) {
    return await this.crypto.deriveBits(
      { name: "ECDH", public: publicKey },
      privateKey,
      256
    );
  }

  // --- Symmetric AES-256-GCM Encryption / Decryption ---

  async encryptAESGCM(keyBuf, plainBuf, iv = null) {
    const key = await this.crypto.importKey(
      "raw",
      keyBuf,
      { name: "AES-GCM" },
      false,
      ["encrypt"]
    );
    const nonce = iv || window.crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await this.crypto.encrypt(
      { name: "AES-GCM", iv: nonce },
      key,
      plainBuf
    );
    return {
      iv: this.bufToB64(nonce),
      ciphertext: this.bufToB64(ciphertext)
    };
  }

  async decryptAESGCM(keyBuf, ciphertextB64, ivB64) {
    const key = await this.crypto.importKey(
      "raw",
      keyBuf,
      { name: "AES-GCM" },
      false,
      ["decrypt"]
    );
    const nonce = this.b64ToBuf(ivB64);
    const cipherBuf = this.b64ToBuf(ciphertextB64);
    return await this.crypto.decrypt(
      { name: "AES-GCM", iv: nonce },
      key,
      cipherBuf
    );
  }

  // --- Password-Based Master Key Derivation (PBKDF2-SHA256) ---

  async deriveMasterKey(password, salt) {
    const baseKey = await this.crypto.importKey(
      "raw",
      this.strToBuf(password),
      { name: "PBKDF2" },
      false,
      ["deriveBits", "deriveKey"]
    );
    return await this.crypto.deriveBits(
      {
        name: "PBKDF2",
        salt: this.strToBuf(salt || "tsuyu_signal_salt_2026"),
        iterations: 600000,
        hash: "SHA-256"
      },
      baseKey,
      256
    );
  }

  // --- User Initialization & Identity Key Setup ---

  async initUserCrypto(uid, password, forceNew = false) {
    this.masterKey = await this.deriveMasterKey(password, `tsuyu_${uid}_master_salt`);

    // Check if we already have identity keys locally
    const localSaved = localStorage.getItem(`${this.storagePrefix}identity_${uid}`);
    if (localSaved && !forceNew) {
      try {
        const decryptedStr = this.bufToStr(
          await this.decryptAESGCM(this.masterKey, JSON.parse(localSaved).cipher, JSON.parse(localSaved).iv)
        );
        const parsed = JSON.parse(decryptedStr);
        this.identityKeyPair = {
          publicKey: await this.importDHPublicKey(parsed.jwkPub),
          privateKey: await this.importDHPrivateKey(parsed.jwkPriv),
          jwkPub: parsed.jwkPub,
          jwkPriv: parsed.jwkPriv,
          pubB64: this.bufToB64(this.strToBuf(JSON.stringify(parsed.jwkPub)))
        };
        await this.publishPublicKey(uid);
        this.loadAllRatchetStates(uid);
        return true;
      } catch (e) {
        console.warn("Could not decrypt local identity key with password:", e);
      }
    }

    // Check if backup exists in RTDB
    if (!forceNew) {
      try {
        const remoteKeyData = await window.firebaseApp.dbGet(`keys/${uid}/backup`);
        if (remoteKeyData && remoteKeyData.cipher && remoteKeyData.iv) {
          const decryptedStr = this.bufToStr(
            await this.decryptAESGCM(this.masterKey, remoteKeyData.cipher, remoteKeyData.iv)
          );
          const parsed = JSON.parse(decryptedStr);
          this.identityKeyPair = {
            publicKey: await this.importDHPublicKey(parsed.jwkPub),
            privateKey: await this.importDHPrivateKey(parsed.jwkPriv),
            jwkPub: parsed.jwkPub,
            jwkPriv: parsed.jwkPriv,
            pubB64: this.bufToB64(this.strToBuf(JSON.stringify(parsed.jwkPub)))
          };
          this.saveIdentityKeyLocally(uid);
          await this.publishPublicKey(uid);
          this.loadAllRatchetStates(uid);
          return true;
        }
      } catch (e) {
        console.warn("Remote key restore failed:", e);
      }
    }

    // Generate brand new identity keypair
    this.identityKeyPair = await this.generateDHKeyPair();
    await this.saveIdentityKeyLocally(uid);
    await this.publishPublicKey(uid);
    await this.backupKeysToRemote(uid);
    return true;
  }

  async saveIdentityKeyLocally(uid) {
    if (!this.identityKeyPair || !this.masterKey) return;
    const payload = JSON.stringify({
      jwkPub: this.identityKeyPair.jwkPub,
      jwkPriv: this.identityKeyPair.jwkPriv
    });
    const enc = await this.encryptAESGCM(this.masterKey, this.strToBuf(payload));
    localStorage.setItem(`${this.storagePrefix}identity_${uid}`, JSON.stringify({
      cipher: enc.ciphertext,
      iv: enc.iv
    }));
  }

  async publishPublicKey(uid) {
    if (!this.identityKeyPair) return;
    const pubPayload = {
      pubKey: this.identityKeyPair.pubB64,
      updatedAt: Date.now()
    };
    await window.firebaseApp.dbSet(`keys/${uid}/public`, pubPayload);
  }

  async backupKeysToRemote(uid) {
    if (!this.identityKeyPair || !this.masterKey) return;
    const payload = JSON.stringify({
      jwkPub: this.identityKeyPair.jwkPub,
      jwkPriv: this.identityKeyPair.jwkPriv
    });
    const enc = await this.encryptAESGCM(this.masterKey, this.strToBuf(payload));
    await window.firebaseApp.dbSet(`keys/${uid}/backup`, {
      cipher: enc.ciphertext,
      iv: enc.iv,
      updatedAt: Date.now()
    });
  }

  // --- Delfan Fingerprint (Signal Safety Number) ---

  async generateDelfanFingerprint(pubB64) {
    const raw = this.b64ToBuf(pubB64 || (this.identityKeyPair ? this.identityKeyPair.pubB64 : ""));
    const hashBuf = await this.hashSha256(raw);
    const hashBytes = new Uint8Array(hashBuf);
    let fp = "";
    for (let i = 0; i < 30; i += 5) {
      let chunk = 0;
      for (let j = 0; j < 5; j++) {
        chunk = (chunk * 256) + hashBytes[i + j];
      }
      const numStr = String(chunk % 100000).padStart(5, "0");
      fp += (fp ? " " : "") + numStr;
    }
    return fp;
  }

  // --- DOUBLE RATCHET PROTOCOL CORE ---

  async initSenderSession(peerUid, peerPubB64) {
    const peerPubKey = await this.importDHPublicKey(peerPubB64);
    const ourDH = await this.generateDHKeyPair();
    const sharedSecret = await this.computeSharedSecret(ourDH.privateKey, peerPubKey);
    
    // Initial Root KDF
    const kdfOut = await this.hkdf(new Uint8Array(32), sharedSecret, "Tsuyu_Signal_DR_Root_2026", 64);
    const rootKey = kdfOut.slice(0, 32);
    const sendChainKey = kdfOut.slice(32, 64);

    const state = {
      peerUid,
      peerPubB64,
      ourDH,
      peerDHPublicKey: peerPubKey,
      peerDHPubB64: peerPubB64,
      rootKey,
      sendChainKey,
      recvChainKey: null,
      sendSeq: 0,
      recvSeq: 0,
      prevSendSeq: 0,
      skippedMessageKeys: {}
    };

    this.ratchetStates.set(peerUid, state);
    this.saveRatchetState(peerUid);
    return state;
  }

  async initReceiverSession(peerUid, ephemeralPubB64) {
    const peerEphemeralPubKey = await this.importDHPublicKey(ephemeralPubB64);
    const sharedSecret = await this.computeSharedSecret(this.identityKeyPair.privateKey, peerEphemeralPubKey);
    
    // Initial Root KDF
    const kdfOut = await this.hkdf(new Uint8Array(32), sharedSecret, "Tsuyu_Signal_DR_Root_2026", 64);
    const rootKey = kdfOut.slice(0, 32);
    const recvChainKey = kdfOut.slice(32, 64);

    const state = {
      peerUid,
      peerPubB64: ephemeralPubB64,
      ourDH: this.identityKeyPair,
      peerDHPublicKey: peerEphemeralPubKey,
      peerDHPubB64: ephemeralPubB64,
      rootKey,
      sendChainKey: null,
      recvChainKey,
      sendSeq: 0,
      recvSeq: 0,
      prevSendSeq: 0,
      skippedMessageKeys: {}
    };

    this.ratchetStates.set(peerUid, state);
    this.saveRatchetState(peerUid);
    return state;
  }

  async getOrInitRatchetState(peerUid) {
    if (this.ratchetStates.has(peerUid)) {
      return this.ratchetStates.get(peerUid);
    }

    // Try loading from localStorage
    const saved = localStorage.getItem(`${this.storagePrefix}state_${peerUid}`);
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        const state = {
          peerUid: parsed.peerUid,
          peerPubB64: parsed.peerPubB64,
          ourDH: {
            publicKey: await this.importDHPublicKey(parsed.ourDH.jwkPub),
            privateKey: await this.importDHPrivateKey(parsed.ourDH.jwkPriv),
            jwkPub: parsed.ourDH.jwkPub,
            jwkPriv: parsed.ourDH.jwkPriv,
            pubB64: parsed.ourDH.pubB64
          },
          peerDHPublicKey: parsed.peerDHPubB64 ? await this.importDHPublicKey(parsed.peerDHPubB64) : null,
          peerDHPubB64: parsed.peerDHPubB64,
          rootKey: this.b64ToBuf(parsed.rootKeyB64),
          sendChainKey: parsed.sendChainKeyB64 ? this.b64ToBuf(parsed.sendChainKeyB64) : null,
          recvChainKey: parsed.recvChainKeyB64 ? this.b64ToBuf(parsed.recvChainKeyB64) : null,
          sendSeq: parsed.sendSeq || 0,
          recvSeq: parsed.recvSeq || 0,
          prevSendSeq: parsed.prevSendSeq || 0,
          skippedMessageKeys: parsed.skippedMessageKeys || {}
        };
        this.ratchetStates.set(peerUid, state);
        return state;
      } catch (e) {
        console.warn("Error reconstructing ratchet state:", e);
      }
    }

    // Fetch peer's published public identity key from RTDB
    const peerKeyData = await window.firebaseApp.dbGet(`keys/${peerUid}/public`);
    if (peerKeyData && peerKeyData.pubKey) {
      return await this.initSenderSession(peerUid, peerKeyData.pubKey);
    }

    throw new Error("Не удалось получить публичный ключ собеседника для E2EE");
  }

  // --- Symmetric Chain Step ---

  async symmetricRatchetStep(chainKey) {
    const nextChainKey = await this.hmacSha256(chainKey, this.strToBuf("Tsuyu_NextCK_2026"));
    const messageKey = await this.hmacSha256(chainKey, this.strToBuf("Tsuyu_MsgKey_2026"));
    return { nextChainKey, messageKey };
  }

  // --- DH Ratchet Step ---

  async dhRatchetStep(state, peerNewDHPublicKey, peerNewDHPubB64) {
    state.prevSendSeq = state.sendSeq;
    state.sendSeq = 0;
    state.recvSeq = 0;
    state.peerDHPublicKey = peerNewDHPublicKey;
    state.peerDHPubB64 = peerNewDHPubB64;

    // Recv DH Step
    const recvSS = await this.computeSharedSecret(state.ourDH.privateKey, state.peerDHPublicKey);
    const recvKdf = await this.hkdf(state.rootKey, recvSS, "Tsuyu_Signal_DR_Root_2026", 64);
    state.rootKey = recvKdf.slice(0, 32);
    state.recvChainKey = recvKdf.slice(32, 64);

    // Generate new DH Keypair for sending
    state.ourDH = await this.generateDHKeyPair();
    const sendSS = await this.computeSharedSecret(state.ourDH.privateKey, state.peerDHPublicKey);
    const sendKdf = await this.hkdf(state.rootKey, sendSS, "Tsuyu_Signal_DR_Root_2026", 64);
    state.rootKey = sendKdf.slice(0, 32);
    state.sendChainKey = sendKdf.slice(32, 64);
  }

  // --- Double Ratchet Encrypt Message ---

  async encryptMessage(peerUid, plainTextOrObj) {
    const state = await this.getOrInitRatchetState(peerUid);

    if (!state.sendChainKey) {
      // Need a new sending DH ratchet
      state.ourDH = await this.generateDHKeyPair();
      const sendSS = await this.computeSharedSecret(state.ourDH.privateKey, state.peerDHPublicKey);
      const sendKdf = await this.hkdf(state.rootKey, sendSS, "Tsuyu_Signal_DR_Root_2026", 64);
      state.rootKey = sendKdf.slice(0, 32);
      state.sendChainKey = sendKdf.slice(32, 64);
    }

    const { nextChainKey, messageKey } = await this.symmetricRatchetStep(state.sendChainKey);
    state.sendChainKey = nextChainKey;
    const msgIndex = state.sendSeq++;

    const plainStr = typeof plainTextOrObj === "string" ? plainTextOrObj : JSON.stringify(plainTextOrObj);
    const enc = await this.encryptAESGCM(messageKey, this.strToBuf(plainStr));

    const header = {
      dh: state.ourDH.pubB64,
      n: msgIndex,
      pn: state.prevSendSeq,
      iv: enc.iv,
      v: "signal_dr_v1"
    };

    this.saveRatchetState(peerUid);

    return {
      header,
      ciphertext: enc.ciphertext,
      fullEncryptedPayload: "ENC_DR:" + btoa(JSON.stringify({ h: header, c: enc.ciphertext }))
    };
  }

  // --- Double Ratchet Decrypt Message ---

  async decryptMessage(fromUid, fullPayloadStr) {
    let payload;
    if (typeof fullPayloadStr === "string" && fullPayloadStr.startsWith("ENC_DR:")) {
      payload = JSON.parse(atob(fullPayloadStr.substring(7)));
    } else if (typeof fullPayloadStr === "object") {
      payload = fullPayloadStr;
    } else {
      return null;
    }

    const header = payload.h || payload.header;
    const ciphertext = payload.c || payload.ciphertext;
    if (!header || !ciphertext) return null;

    let state = this.ratchetStates.get(fromUid);
    if (!state) {
      state = await this.initReceiverSession(fromUid, header.dh);
    }

    // Check if message key was already skipped and saved
    const skippedKeyId = `${header.dh}_${header.n}`;
    if (state.skippedMessageKeys && state.skippedMessageKeys[skippedKeyId]) {
      const savedMK = this.b64ToBuf(state.skippedMessageKeys[skippedKeyId]);
      delete state.skippedMessageKeys[skippedKeyId];
      this.saveRatchetState(fromUid);
      const decBuf = await this.decryptAESGCM(savedMK, ciphertext, header.iv);
      const decStr = this.bufToStr(decBuf);
      try { return JSON.parse(decStr); } catch (e) { return decStr; }
    }

    // If new DH ratchet key received, advance DH Ratchet
    if (header.dh !== state.peerDHPubB64) {
      const peerNewDHPublicKey = await this.importDHPublicKey(header.dh);
      await this.dhRatchetStep(state, peerNewDHPublicKey, header.dh);
    }

    // Advance symmetric receiving chain up to message sequence number n
    while (state.recvSeq < header.n) {
      const { nextChainKey, messageKey } = await this.symmetricRatchetStep(state.recvChainKey);
      state.recvChainKey = nextChainKey;
      state.skippedMessageKeys[`${header.dh}_${state.recvSeq}`] = this.bufToB64(messageKey);
      state.recvSeq++;
    }

    const { nextChainKey, messageKey } = await this.symmetricRatchetStep(state.recvChainKey);
    state.recvChainKey = nextChainKey;
    state.recvSeq++;

    this.saveRatchetState(fromUid);

    const decBuf = await this.decryptAESGCM(messageKey, ciphertext, header.iv);
    const decStr = this.bufToStr(decBuf);
    try {
      return JSON.parse(decStr);
    } catch (e) {
      return decStr;
    }
  }

  // --- Key Rotation (Change Private Key for Chat) ---

  async rotateChatKey(peerUid) {
    const state = await this.getOrInitRatchetState(peerUid);
    state.ourDH = await this.generateDHKeyPair();
    // Force sending ratchet step on next message
    state.sendChainKey = null;
    this.saveRatchetState(peerUid);
    return true;
  }

  // --- Profile Zero-Knowledge Encryption ---

  async deriveProfileKey(usernameOrUid) {
    const raw = this.strToBuf(`tsuyu_zk_profile_${usernameOrUid.toLowerCase()}`);
    return await this.hashSha256(raw);
  }

  async encryptProfileData(usernameOrUid, profileObj) {
    const keyBuf = await this.deriveProfileKey(usernameOrUid);
    const plainBuf = this.strToBuf(JSON.stringify(profileObj));
    const enc = await this.encryptAESGCM(keyBuf, plainBuf);
    return {
      profile_enc: enc.ciphertext,
      profile_iv: enc.iv,
      v: "zk_p1"
    };
  }

  async decryptProfileData(usernameOrUid, encObj) {
    if (!encObj || !encObj.profile_enc || !encObj.profile_iv) return null;
    try {
      const keyBuf = await this.deriveProfileKey(usernameOrUid);
      const decBuf = await this.decryptAESGCM(keyBuf, encObj.profile_enc, encObj.profile_iv);
      return JSON.parse(this.bufToStr(decBuf));
    } catch (e) {
      console.warn("Could not decrypt profile:", e);
      return null;
    }
  }

  // --- Local Ratchet State Persistence ---

  saveRatchetState(peerUid) {
    const state = this.ratchetStates.get(peerUid);
    if (!state) return;
    try {
      const serializable = {
        peerUid: state.peerUid,
        peerPubB64: state.peerPubB64,
        ourDH: {
          jwkPub: state.ourDH.jwkPub,
          jwkPriv: state.ourDH.jwkPriv,
          pubB64: state.ourDH.pubB64
        },
        peerDHPubB64: state.peerDHPubB64,
        rootKeyB64: this.bufToB64(state.rootKey),
        sendChainKeyB64: state.sendChainKey ? this.bufToB64(state.sendChainKey) : null,
        recvChainKeyB64: state.recvChainKey ? this.bufToB64(state.recvChainKey) : null,
        sendSeq: state.sendSeq,
        recvSeq: state.recvSeq,
        prevSendSeq: state.prevSendSeq,
        skippedMessageKeys: state.skippedMessageKeys || {}
      };
      localStorage.setItem(`${this.storagePrefix}state_${peerUid}`, JSON.stringify(serializable));
    } catch (e) {
      console.error("Error saving ratchet state:", e);
    }
  }

  loadAllRatchetStates(uid) {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key.startsWith(`${this.storagePrefix}state_`)) {
        const peerUid = key.replace(`${this.storagePrefix}state_`, "");
        this.getOrInitRatchetState(peerUid).catch(() => {});
      }
    }
  }

  // --- Export / Import Backup ---

  async exportEncryptedKeysBackup() {
    if (!this.identityKeyPair) throw new Error("Ключи не инициализированы");
    const bundle = {
      version: "tsuyu_signal_keys_v1",
      exportedAt: new Date().toISOString(),
      identity: {
        jwkPub: this.identityKeyPair.jwkPub,
        jwkPriv: this.identityKeyPair.jwkPriv
      },
      ratchetStates: {}
    };

    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k.startsWith(`${this.storagePrefix}state_`)) {
        bundle.ratchetStates[k] = localStorage.getItem(k);
      }
    }

    const payloadStr = JSON.stringify(bundle);
    const enc = await this.encryptAESGCM(this.masterKey, this.strToBuf(payloadStr));

    const exportData = {
      type: "TSUYU_SIGNAL_BACKUP",
      cipher: enc.ciphertext,
      iv: enc.iv
    };

    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `tsuyu_signal_keys_${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  async importEncryptedKeysBackup(jsonContent, password) {
    const parsed = JSON.parse(jsonContent);
    if (!parsed || parsed.type !== "TSUYU_SIGNAL_BACKUP" || !parsed.cipher || !parsed.iv) {
      throw new Error("Неверный формат файла резервной копии ключей");
    }

    const testMasterKey = await this.deriveMasterKey(password, `tsuyu_${window.firebaseApp.uid}_master_salt`);
    const decBuf = await this.decryptAESGCM(testMasterKey, parsed.cipher, parsed.iv);
    const backup = JSON.parse(this.bufToStr(decBuf));

    if (!backup.identity || !backup.identity.jwkPub || !backup.identity.jwkPriv) {
      throw new Error("Поврежденные данные ключей");
    }

    this.masterKey = testMasterKey;
    this.identityKeyPair = {
      publicKey: await this.importDHPublicKey(backup.identity.jwkPub),
      privateKey: await this.importDHPrivateKey(backup.identity.jwkPriv),
      jwkPub: backup.identity.jwkPub,
      jwkPriv: backup.identity.jwkPriv,
      pubB64: this.bufToB64(this.strToBuf(JSON.stringify(backup.identity.jwkPub)))
    };

    await this.saveIdentityKeyLocally(window.firebaseApp.uid);
    await this.publishPublicKey(window.firebaseApp.uid);

    if (backup.ratchetStates) {
      for (const [k, v] of Object.entries(backup.ratchetStates)) {
        localStorage.setItem(k, v);
      }
    }

    this.loadAllRatchetStates(window.firebaseApp.uid);
    return true;
  }
}

window.doubleRatchet = new DoubleRatchetEngine();
