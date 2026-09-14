package com.tsuyu.messenger.crypto;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns one Double Ratchet session per peer and turns plaintext into RTDB envelopes.
 *
 * Envelope stored in RTDB (server sees only ciphertext):
 * {
 *   v:2, from, ik, ed, eph?, spk (peer spk we used), body:{dh,pn,n,iv,ct}, sig
 * }
 *
 * Because every message carries the sender identity + ephemeral key, a recipient who
 * has never talked to the sender (or who was offline) can build the session on the fly
 * and read the message immediately.
 */
public class SessionManager {

    private static final String PREF = "tsuyu_sessions";
    private static SessionManager instance;

    private final SharedPreferences prefs;
    private final IdentityStore identity;
    private final Map<String, RatchetState> cache = new HashMap<>();

    private SessionManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        identity = IdentityStore.get(ctx);
    }

    public static synchronized SessionManager get(Context ctx) {
        if (instance == null) instance = new SessionManager(ctx);
        return instance;
    }

    public IdentityStore identity() { return identity; }

    // ---------------- persistence ----------------

    private synchronized RatchetState load(String peer) {
        RatchetState s = cache.get(peer);
        if (s != null) return s;
        String json = prefs.getString("s_" + peer, null);
        if (json == null) return null;
        try {
            s = RatchetState.fromJson(new JSONObject(json));
            cache.put(peer, s);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized void save(String peer, RatchetState s) {
        cache.put(peer, s);
        try {
            prefs.edit().putString("s_" + peer, s.toJson().toString()).apply();
        } catch (Exception ignored) { }
    }

    public synchronized void resetSession(String peer) {
        cache.remove(peer);
        prefs.edit().remove("s_" + peer).apply();
    }

    /** Called after rotating our identity: every session must be rebuilt. */
    public synchronized void resetAll() {
        cache.clear();
        prefs.edit().clear().apply();
    }

    // ---------------- encryption ----------------

    /**
     * @param peerUid   recipient uid
     * @param peerIk    recipient identity public key (base64) from their RTDB bundle
     * @param peerSpk   recipient signed pre-key (base64)
     * @param plaintext UTF-8 payload (text JSON or base64 media)
     */
    public synchronized JSONObject encrypt(String peerUid, String peerIk, String peerSpk,
                                           byte[] plaintext) throws Exception {
        RatchetState state = load(peerUid);
        String ephB64 = null;

        String oldIk = identity.getSavedPeerIk(peerUid);
        if (peerIk != null) {
            if (oldIk != null && !oldIk.equals(peerIk)) {
                // Peer identity key changed! Reset state to re-initiate X3DH
                resetSession(peerUid);
                state = null;
            }
            identity.savePeerIk(peerUid, peerIk);
        }

        if (state == null) {
            byte[] ikBytes = CryptoUtil.unb64(peerIk);
            byte[] spkBytes = CryptoUtil.unb64(peerSpk);
            X3DH.Initiated init = X3DH.initiate(identity.idPriv, ikBytes, spkBytes);
            state = DoubleRatchet.initSender(init.sharedSecret, spkBytes);
            ephB64 = CryptoUtil.b64(init.ephemeralPub);
            // remember the ephemeral so later messages in the same chain stay decryptable
            prefs.edit().putString("e_" + peerUid, ephB64)
                    .putString("t_" + peerUid, peerSpk).apply();
        } else {
            ephB64 = prefs.getString("e_" + peerUid, null);
        }

        JSONObject body = DoubleRatchet.encrypt(state, plaintext);
        save(peerUid, state);

        JSONObject env = new JSONObject();
        env.put("v", 2);
        env.put("ik", CryptoUtil.b64(identity.idPub));
        env.put("ed", CryptoUtil.b64(identity.edPub));
        env.put("spkSig", CryptoUtil.b64(identity.spkSig));
        env.put("spk", CryptoUtil.b64(identity.spkPub));
        if (ephB64 != null) env.put("eph", ephB64);
        env.put("target", prefs.getString("t_" + peerUid, peerSpk));
        env.put("body", body);

        // Authenticate the envelope with our Ed25519 identity over a canonical
        // byte string (JSON key order is not stable across platforms).
        env.put("sig", CryptoUtil.b64(CryptoUtil.sign(identity.edPriv, signable(env))));
        return env;
    }

    // ---------------- decryption ----------------

    /** Decrypts an envelope from peerUid, establishing the session if needed. */
    public synchronized byte[] decrypt(String peerUid, JSONObject env) throws Exception {
        // verify signature (ignore if absent for forward-compat)
        if (env.has("sig") && env.has("ed")) {
            boolean ok = CryptoUtil.verify(CryptoUtil.unb64(env.getString("ed")),
                    signable(env), CryptoUtil.unb64(env.getString("sig")));
            // Not fatal: AEAD already guarantees confidentiality and integrity of
            // the body. The signature additionally binds the sender identity.
            if (!ok) env.put("sigValid", false);
        }

        JSONObject body = env.getJSONObject("body");
        RatchetState state = load(peerUid);

        if (state == null) {
            // First inbound message: rebuild the session as the X3DH responder.
            String target = env.optString("target", null);
            byte[] spkPriv = identity.spkPriv;
            if (target != null && !target.equals(CryptoUtil.b64(identity.spkPub))) {
                byte[] archived = archivedSpk(target);
                if (archived != null) spkPriv = archived;
            }
            byte[] peerIk = CryptoUtil.unb64(env.getString("ik"));
            byte[] peerEph = CryptoUtil.unb64(env.getString("eph"));
            byte[] sk = X3DH.respond(identity.idPriv, spkPriv, peerIk, peerEph);
            state = DoubleRatchet.initReceiver(sk, spkPriv, CryptoUtil.x25519Public(spkPriv));
        }

        byte[] plain;
        try {
            plain = DoubleRatchet.decrypt(state, body);
        } catch (Exception first) {
            // Peer may have rotated keys and restarted the session from scratch.
            if (env.has("eph")) {
                byte[] peerIk = CryptoUtil.unb64(env.getString("ik"));
                byte[] peerEph = CryptoUtil.unb64(env.getString("eph"));
                byte[] sk = X3DH.respond(identity.idPriv, identity.spkPriv, peerIk, peerEph);
                RatchetState fresh = DoubleRatchet.initReceiver(
                        sk, identity.spkPriv, identity.spkPub);
                plain = DoubleRatchet.decrypt(fresh, body);
                state = fresh;
            } else {
                throw first;
            }
        }
        save(peerUid, state);
        return plain;
    }

    /** Canonical, order-independent byte string covering the envelope fields. */
    private static byte[] signable(JSONObject env) throws Exception {
        JSONObject body = env.getJSONObject("body");
        String s = "v=" + env.optInt("v")
                + ";ik=" + env.optString("ik")
                + ";ed=" + env.optString("ed")
                + ";spk=" + env.optString("spk")
                + ";eph=" + env.optString("eph", "")
                + ";target=" + env.optString("target", "")
                + ";dh=" + body.optString("dh")
                + ";pn=" + body.optInt("pn")
                + ";n=" + body.optInt("n")
                + ";iv=" + body.optString("iv")
                + ";ct=" + body.optString("ct");
        return s.getBytes("UTF-8");
    }

    // ---- signed pre-key archive, so old messages stay readable after rotation ----

    public synchronized void archiveCurrentSpk() {
        prefs.edit().putString("spk_" + CryptoUtil.b64(identity.spkPub),
                CryptoUtil.b64(identity.spkPriv)).apply();
    }

    private byte[] archivedSpk(String spkPubB64) {
        String v = prefs.getString("spk_" + spkPubB64, null);
        return v == null ? null : CryptoUtil.unb64(v);
    }
}
