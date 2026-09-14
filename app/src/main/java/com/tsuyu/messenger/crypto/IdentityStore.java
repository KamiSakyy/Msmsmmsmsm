package com.tsuyu.messenger.crypto;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Device-local identity. Private keys are generated on device and never uploaded.
 */
public class IdentityStore {

    private static final String PREF = "tsuyu_identity";
    private static IdentityStore instance;

    private final SharedPreferences prefs;

    public byte[] idPriv, idPub;        // X25519 long-term identity
    public byte[] edPriv, edPub;        // Ed25519 signing identity
    public byte[] spkPriv, spkPub;      // signed pre-key
    public byte[] spkSig;               // Ed25519 signature over spkPub

    private IdentityStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized IdentityStore get(Context ctx) {
        if (instance == null) instance = new IdentityStore(ctx);
        return instance;
    }

    private void load() {
        String id = prefs.getString("idPriv", null);
        if (id == null) {
            generate();
            return;
        }
        idPriv = CryptoUtil.unb64(id);
        idPub = CryptoUtil.x25519Public(idPriv);
        edPriv = CryptoUtil.unb64(prefs.getString("edPriv", null));
        edPub = CryptoUtil.ed25519Public(edPriv);
        spkPriv = CryptoUtil.unb64(prefs.getString("spkPriv", null));
        spkPub = CryptoUtil.x25519Public(spkPriv);
        spkSig = CryptoUtil.unb64(prefs.getString("spkSig", null));
    }

    /** Creates a brand new identity (also used by "change private key"). */
    public void generate() {
        byte[][] id = CryptoUtil.generateX25519();
        byte[][] ed = CryptoUtil.generateEd25519();
        byte[][] spk = CryptoUtil.generateX25519();
        idPriv = id[0];
        idPub = id[1];
        edPriv = ed[0];
        edPub = ed[1];
        spkPriv = spk[0];
        spkPub = spk[1];
        spkSig = CryptoUtil.sign(edPriv, spkPub);
        persist();
    }

    public void persist() {
        prefs.edit()
                .putString("idPriv", CryptoUtil.b64(idPriv))
                .putString("edPriv", CryptoUtil.b64(edPriv))
                .putString("spkPriv", CryptoUtil.b64(spkPriv))
                .putString("spkSig", CryptoUtil.b64(spkSig))
                .apply();
    }

    /** Public bundle published to RTDB — contains no secrets. */
    public JSONObject publicBundle() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ik", CryptoUtil.b64(idPub));
        o.put("ed", CryptoUtil.b64(edPub));
        o.put("spk", CryptoUtil.b64(spkPub));
        o.put("spkSig", CryptoUtil.b64(spkSig));
        return o;
    }

    /** Exportable backup so a new phone can decrypt old conversations. */
    public JSONObject exportKeys() throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("idPriv", CryptoUtil.b64(idPriv));
        o.put("edPriv", CryptoUtil.b64(edPriv));
        o.put("spkPriv", CryptoUtil.b64(spkPriv));
        o.put("spkSig", CryptoUtil.b64(spkSig));
        return o;
    }

    public void importKeys(JSONObject o) throws Exception {
        idPriv = CryptoUtil.unb64(o.getString("idPriv"));
        edPriv = CryptoUtil.unb64(o.getString("edPriv"));
        spkPriv = CryptoUtil.unb64(o.getString("spkPriv"));
        idPub = CryptoUtil.x25519Public(idPriv);
        edPub = CryptoUtil.ed25519Public(edPriv);
        spkPub = CryptoUtil.x25519Public(spkPriv);
        spkSig = o.has("spkSig") ? CryptoUtil.unb64(o.getString("spkSig"))
                : CryptoUtil.sign(edPriv, spkPub);
        persist();
    }

    // ---- Contact identity verification & Safety Numbers ----

    public boolean isVerified(String peerUid) {
        return prefs.getBoolean("ver_" + peerUid, false);
    }

    public void setVerified(String peerUid, boolean v) {
        prefs.edit().putBoolean("ver_" + peerUid, v).apply();
    }

    public String getSavedPeerIk(String peerUid) {
        return prefs.getString("ik_" + peerUid, null);
    }

    public void savePeerIk(String peerUid, String ik) {
        prefs.edit().putString("ik_" + peerUid, ik).apply();
    }
}
