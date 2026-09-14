package com.tsuyu.messenger.crypto;

import org.json.JSONObject;

/**
 * Signal-style Double Ratchet.
 *
 * Root chain : RK, CK = HKDF(RK, DH(ours, theirs))
 * Chain step : CK' = HMAC(CK, 0x02), MK = HMAC(CK, 0x01)
 * Payload    : AES-256-GCM, header bound as AAD.
 */
public final class DoubleRatchet {

    private static final byte[] INFO_ROOT = "TsuyuRatchetRoot".getBytes();
    private static final byte[] INFO_MSG = "TsuyuMessageKeys".getBytes();
    private static final byte[] STEP_CHAIN = {0x02};
    private static final byte[] STEP_MSG = {0x01};

    private DoubleRatchet() {}

    // ---------- Session initialisation (post X3DH) ----------

    /** Initiator: knows the peer's signed pre-key as the first remote ratchet key. */
    public static RatchetState initSender(byte[] sharedSecret, byte[] peerRatchetPub) {
        RatchetState s = new RatchetState();
        byte[][] kp = CryptoUtil.generateX25519();
        s.dhsPriv = kp[0];
        s.dhsPub = kp[1];
        s.dhrPub = peerRatchetPub;
        s.rootKey = sharedSecret;
        byte[][] rk = kdfRoot(s.rootKey, CryptoUtil.dh(s.dhsPriv, s.dhrPub));
        s.rootKey = rk[0];
        s.chainSend = rk[1];
        return s;
    }

    /** Responder: its signed pre-key pair becomes the initial ratchet pair. */
    public static RatchetState initReceiver(byte[] sharedSecret, byte[] ourRatchetPriv, byte[] ourRatchetPub) {
        RatchetState s = new RatchetState();
        s.dhsPriv = ourRatchetPriv;
        s.dhsPub = ourRatchetPub;
        s.dhrPub = null;
        s.rootKey = sharedSecret;
        s.chainSend = null;
        s.chainRecv = null;
        return s;
    }

    // ---------- KDFs ----------

    /**
     * Canonical associated data. Built from a fixed field order rather than
     * JSONObject.toString(), whose key order is implementation-defined.
     */
    private static byte[] aad(String dhB64, int pn, int n) {
        try {
            return ("dh=" + dhB64 + ";pn=" + pn + ";n=" + n).getBytes("UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[][] kdfRoot(byte[] rootKey, byte[] dhOut) {
        byte[] out = CryptoUtil.hkdf(dhOut, rootKey, INFO_ROOT, 64);
        byte[] rk = new byte[32];
        byte[] ck = new byte[32];
        System.arraycopy(out, 0, rk, 0, 32);
        System.arraycopy(out, 32, ck, 0, 32);
        return new byte[][]{rk, ck};
    }

    private static byte[] chainNext(byte[] chainKey) {
        return CryptoUtil.hmacSha256(chainKey, STEP_CHAIN);
    }

    private static byte[] messageKey(byte[] chainKey) {
        byte[] seed = CryptoUtil.hmacSha256(chainKey, STEP_MSG);
        return CryptoUtil.hkdf(seed, new byte[32], INFO_MSG, 32);
    }

    // ---------- Encrypt ----------

    /**
     * Encrypts plaintext, mutating the ratchet state.
     * Returns a JSON envelope: {dh, pn, n, iv, ct}
     */
    public static JSONObject encrypt(RatchetState s, byte[] plaintext) throws Exception {
        if (s.chainSend == null) {
            throw new IllegalStateException("Sending chain not established");
        }
        byte[] mk = messageKey(s.chainSend);
        s.chainSend = chainNext(s.chainSend);

        String dhB64 = CryptoUtil.b64(s.dhsPub);
        int pn = s.prevChainLen;
        int n = s.sendCount;
        s.sendCount++;

        byte[] iv = CryptoUtil.random(12);
        byte[] aad = aad(dhB64, pn, n);
        byte[] ct = CryptoUtil.aesGcmEncrypt(mk, iv, plaintext, aad);

        JSONObject env = new JSONObject();
        env.put("dh", dhB64);
        env.put("pn", pn);
        env.put("n", n);
        env.put("iv", CryptoUtil.b64(iv));
        env.put("ct", CryptoUtil.b64(ct));
        return env;
    }

    // ---------- Decrypt ----------

    public static byte[] decrypt(RatchetState s, JSONObject env) throws Exception {
        byte[] dh = CryptoUtil.unb64(env.getString("dh"));
        int pn = env.getInt("pn");
        int n = env.getInt("n");
        byte[] iv = CryptoUtil.unb64(env.getString("iv"));
        byte[] ct = CryptoUtil.unb64(env.getString("ct"));

        byte[] aad = aad(env.getString("dh"), pn, n);

        // 1. Try a previously skipped key.
        String skipKey = env.getString("dh") + "|" + n;
        byte[] stored = s.skipped.get(skipKey);
        if (stored != null) {
            byte[] plain = CryptoUtil.aesGcmDecrypt(stored, iv, ct, aad);
            s.skipped.remove(skipKey);
            return plain;
        }

        // 2. New remote ratchet key -> DH ratchet step.
        if (s.dhrPub == null || !CryptoUtil.constantTimeEquals(dh, s.dhrPub)) {
            skipMessageKeys(s, pn);
            dhRatchet(s, dh);
        }

        // 3. Skip forward inside the current receiving chain.
        skipMessageKeys(s, n);

        if (s.chainRecv == null) throw new IllegalStateException("No receiving chain");
        byte[] mk = messageKey(s.chainRecv);
        s.chainRecv = chainNext(s.chainRecv);
        s.recvCount++;
        return CryptoUtil.aesGcmDecrypt(mk, iv, ct, aad);
    }

    private static void skipMessageKeys(RatchetState s, int until) {
        if (s.chainRecv == null) return;
        if (s.recvCount + RatchetState.MAX_SKIP < until) {
            throw new IllegalStateException("Too many skipped messages");
        }
        while (s.recvCount < until) {
            byte[] mk = messageKey(s.chainRecv);
            s.chainRecv = chainNext(s.chainRecv);
            s.skipped.put(CryptoUtil.b64(s.dhrPub) + "|" + s.recvCount, mk);
            s.recvCount++;
        }
    }

    private static void dhRatchet(RatchetState s, byte[] newRemotePub) {
        s.prevChainLen = s.sendCount;
        s.sendCount = 0;
        s.recvCount = 0;
        s.dhrPub = newRemotePub;

        byte[][] recv = kdfRoot(s.rootKey, CryptoUtil.dh(s.dhsPriv, s.dhrPub));
        s.rootKey = recv[0];
        s.chainRecv = recv[1];

        byte[][] kp = CryptoUtil.generateX25519();
        s.dhsPriv = kp[0];
        s.dhsPub = kp[1];

        byte[][] send = kdfRoot(s.rootKey, CryptoUtil.dh(s.dhsPriv, s.dhrPub));
        s.rootKey = send[0];
        s.chainSend = send[1];
    }
}
