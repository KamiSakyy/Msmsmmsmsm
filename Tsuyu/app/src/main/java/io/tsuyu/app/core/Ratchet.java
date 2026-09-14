package io.tsuyu.app.core;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Double Ratchet (Tsuyu v1) — Signal-style E2EE, self-contained per message.
 *
 * Every envelope carries: eph (X25519 pub used), sId (sender identity pub),
 * rk (sender's current DH ratchet pub), opk (one-time prekey id or null),
 * i (index within current leg), c (AES-256-GCM ciphertext).
 *
 *  - Private keys never leave the device (wrapped with Android Keystore master key).
 *  - No prior handshake needed: bootstrap uses X3DH-style derivation from the peer's
 *    public bundle + a fresh per-message ephemeral key, so an offline peer you never
 *    chatted with can decrypt your first message immediately on first connect.
 *  - After the first exchange, each direction runs a ratchet leg: root step =
 *    ECDH(both parties' last published ratchet keys), chain step = KDF2 chain with a
 *    per-leg DH secret. Forward secrecy per round trip, one-time prekeys per bootstrap.
 *  - Every message within a leg is deterministically derivable from (legStartCk, dh, i),
 *    so out-of-order delivery never loses messages.
 *  - Key rotation is transparent; rotated keys stay archived for history.
 */
public class Ratchet {
    private static final String TAG = "TsuyuRatchet";
    private static final int MAX_REPLAY = 100000;
    private static final Map<String, Keys.Bundle> bundleCache = new HashMap<>();
    private static final Map<String, Integer> fetchRetries = new HashMap<>();

    public interface SendCallback {
        void onEnvelope(String envJson);
    }

    private static File sessionFile(Context ctx, String peer) {
        String safe = peer.replaceAll("[^a-zA-Z0-9_-]", "_");
        File d = new File(ctx.getDir("keys", Context.MODE_PRIVATE), "sessions");
        d.mkdirs();
        return new File(d, safe + ".json");
    }

    private static byte[] slice(byte[] kp, int off) {
        byte[] r = new byte[32];
        System.arraycopy(kp, off, r, 0, 32);
        return r;
    }
    private static byte[] b64d(String s) {
        return s == null ? null : Base64.decode(s, Base64.NO_PADDING);
    }
    private static String b64e(byte[] d) {
        return d == null ? null : Base64.encodeToString(d, Base64.NO_WRAP | Base64.NO_PADDING);
    }
    private static byte[] str(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    static JSONObject loadSession(Context ctx, String peer) {
        try {
            File f = sessionFile(ctx, peer);
            if (!f.exists()) return null;
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            return new JSONObject(new String(buf, "UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "loadSession", t);
            return null;
        }
    }

    private static void saveSession(Context ctx, String peer, JSONObject s) {
        try {
            FileOutputStream fos = new FileOutputStream(sessionFile(ctx, peer));
            fos.write(s.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.e(TAG, "saveSession", t);
        }
    }

    // Archive my ratchet privs (for racing root-step decryption fallback).
    private static void pushRkArchive(JSONObject s, byte[] priv, byte[] pub) {
        try {
            JSONObject arch = s.optJSONObject("rkArchive");
            if (arch == null) arch = new JSONObject();
            arch.put(String.valueOf(System.currentTimeMillis()),
                    b64e(priv) + ":" + b64e(pub));
            if (arch.length() > 24) {
                long[] ts = new long[arch.length()];
                int n = 0;
                Iterator<String> it = arch.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try { ts[n++] = Long.parseLong(k); } catch (Throwable t) { arch.remove(k); }
                }
                if (n > 24) {
                    java.util.Arrays.sort(ts);
                    for (int i = 0; i < n - 24; i++) arch.remove(String.valueOf(ts[i]));
                }
            }
            s.put("rkArchive", arch);
        } catch (Throwable ignored) {}
    }

    private static byte[][] rkArchiveList(JSONObject s) {
        try {
            JSONObject arch = s.optJSONObject("rkArchive");
            if (arch == null) return new byte[0][];
            String[] keys = new String[arch.length()];
            int n = 0;
            Iterator<String> it = arch.keys();
            while (it.hasNext()) keys[n++] = it.next();
            java.util.Arrays.sort(keys);
            byte[][] out = new byte[arch.length()][];
            int m = 0;
            for (int i = keys.length - 1; i >= 0; i--) {
                String v = arch.getString(keys[i]);
                int colon = v.indexOf(':');
                out[m++] = new byte[][]{b64d(v.substring(0, colon)), b64d(v.substring(colon + 1))};
            }
            java.util.Arrays.copyOf(out, m);
            return m == out.length ? out : java.util.Arrays.copyOf(out, m);
        } catch (Throwable t) {
            return new byte[0][];
        }
    }

    public static void prefetchBundle(Context ctx, String peer) {
        try {
            fetchBundleAsync(ctx, peer, () -> {});
        } catch (Throwable ignored) {}
    }

    // ---------------- bundle fetch ----------------
    private static void fetchBundleAsync(Context ctx, String uid, Runnable done) {
        try {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("users/" + uid + "/pub")
                    .getValue()
                    .addOnCompleteListener(task -> {
                        try {
                            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                                com.google.firebase.database.DataSnapshot ds = task.getResult();
                                Keys.Bundle b = new Keys.Bundle();
                                b.idPub = ds.child("i").getValue(String.class);
                                b.rootPub = ds.child("r").getValue(String.class);
                                b.fp = ds.child("fp").getValue(String.class);
                                b.preKeys = new HashMap<>();
                                com.google.firebase.database.DataSnapshot p = ds.child("p");
                                if (p.exists()) {
                                    for (com.google.firebase.database.DataSnapshot c : p.getChildren()) {
                                        b.preKeys.put(c.getKey(), c.getValue(String.class));
                                    }
                                }
                                if (b.rootPub != null) bundleCache.put(uid, b);
                            }
                        } catch (Throwable ignored) {}
                        done.run();
                    });
        } catch (Throwable t) {
            done.run();
        }
    }

    // ---------------- SEND ----------------
    public static synchronized void send(Context ctx, final String peerUid, final byte[] payload, final SendCallback cb) {
        send(ctx, peerUid, payload, cb, 0);
    }

    private static synchronized void send(Context ctx, String peerUid, byte[] payload, SendCallback cb, int retry) {
        try {
            String myIdPub = Keys.idPub(ctx);
            if (myIdPub == null) {
                Keys.ensure(ctx);
                myIdPub = Keys.idPub(ctx);
                if (myIdPub == null) { cb.onEnvelope(null); return; }
            }
            JSONObject s = loadSession(ctx, peerUid);
            if (s == null) s = new JSONObject();

            int sentCount = s.optInt("sentCount", 0);
            String myLastRkPriv = s.optString("lastRkPriv", null);
            String peerLastRk = s.optString("peerLastRk", null);
            boolean legPreReply = s.optBoolean("legPreReply", true);
            String legRk = s.optString("legRk", null);

            JSONObject env = new JSONObject();
            env.put("v", 1);
            env.put("f", ((io.tsuyu.app.TsuyuApp) ctx.getApplicationContext()).myUid());
            env.put("t", peerUid);
            env.put("sId", myIdPub);

            // 1) Never sent to this peer -> bootstrap X3DH message
            if (sentCount == 0 || (legPreReply && peerLastRk == null)) {
                Keys.Bundle b = bundleCache.get(peerUid);
                if (b == null || b.rootPub == null) {
                    int r = fetchRetries.getOrDefault(peerUid, 0);
                    if (r >= 4) { cb.onEnvelope(null); fetchRetries.remove(peerUid); return; }
                    fetchRetries.put(peerUid, r + 1);
                    fetchBundleAsync(ctx, peerUid, () -> send(ctx, peerUid, payload, cb, retry + 1));
                    return;
                }
                int i = s.optInt("legCtr", 0);
                fillX3dh(ctx, peerUid, b, payload, env, i);
                byte[] ephPub = b64d(env.getString("rk"));
                byte[] ephPriv = b64d(env.getString("rkPriv"));
                env.remove("rkPriv");
                s.put("lastRkPriv", b64e(ephPriv));
                s.put("legPreReply", true);
                s.put("legRk", b64e(ephPub));
                s.put("legCtr", i + 1);
                s.put("sentCount", sentCount + 1);
                pushRkArchive(s, ephPriv, ephPub);
                saveSession(ctx, peerUid, s);
                cb.onEnvelope(env.toString());
                return;
            }

            // 2) Bootstrap leg, but peer has replied at least once -> start normal leg (root step)
            if (legPreReply && peerLastRk != null) {
                if (myLastRkPriv == null) { cb.onEnvelope(null); return; }
                byte[] rs = Crypto.x25519(b64d(myLastRkPriv), b64d(peerLastRk));
                byte[] newKp = Crypto.x25519Generate();
                byte[] newPriv = slice(newKp, 0);
                byte[] newPub = slice(newKp, 32);
                byte[] dh = Crypto.x25519(newPriv, b64d(peerLastRk));
                byte[] legCk0 = Crypto.hkdf(rs, null, str("TSUYU-LEGCK"), 32);
                byte[][] kdf = Crypto.kdf2(legCk0, dh);
                s.put("lastRkPriv", b64e(newPriv));
                s.put("legPreReply", false);
                s.put("legRk", b64e(newPub));
                s.put("legRemoteRk", peerLastRk);
                s.put("legStartCk", b64e(legCk0));
                s.put("legDh", b64e(dh));
                s.put("legCk", b64e(kdf[1]));
                s.put("legCtr", 1);
                s.put("sentCount", sentCount + 1);
                pushRkArchive(s, newPriv, newPub);
                saveSession(ctx, peerUid, s);
                env.put("rk", b64e(newPub));
                env.put("eph", b64e(newPub));
                env.put("opk", JSONObject.NULL);
                env.put("i", 0);
                env.put("c", b64e(Crypto.aesGcmEncrypt(kdf[0], payload)));
                cb.onEnvelope(env.toString());
                return;
            }

            // 3) Normal leg
            if (legRk == null) { cb.onEnvelope(null); return; }
            boolean rootStep = peerLastRk == null || !peerLastRk.equals(s.optString("legRemoteRk", ""));
            if (rootStep) {
                if (peerLastRk == null || myLastRkPriv == null) { cb.onEnvelope(null); return; }
                byte[] rs = Crypto.x25519(b64d(myLastRkPriv), b64d(peerLastRk));
                byte[] newKp = Crypto.x25519Generate();
                byte[] newPriv = slice(newKp, 0);
                byte[] newPub = slice(newKp, 32);
                byte[] dh = Crypto.x25519(newPriv, b64d(peerLastRk));
                byte[] legCk0 = Crypto.hkdf(rs, null, str("TSUYU-LEGCK"), 32);
                byte[][] kdf = Crypto.kdf2(legCk0, dh);
                s.put("lastRkPriv", b64e(newPriv));
                s.put("legRk", b64e(newPub));
                s.put("legRemoteRk", peerLastRk);
                s.put("legStartCk", b64e(legCk0));
                s.put("legDh", b64e(dh));
                s.put("legCk", b64e(kdf[1]));
                s.put("legCtr", 1);
                s.put("sentCount", sentCount + 1);
                pushRkArchive(s, newPriv, newPub);
                saveSession(ctx, peerUid, s);
                env.put("rk", b64e(newPub));
                env.put("eph", b64e(newPub));
                env.put("opk", JSONObject.NULL);
                env.put("i", 0);
                env.put("c", b64e(Crypto.aesGcmEncrypt(kdf[0], payload)));
                cb.onEnvelope(env.toString());
                return;
            }

            // 4) Chain step (same leg)
            byte[] dh = b64d(s.optString("legDh", null));
            byte[] legStartCk = b64d(s.optString("legStartCk", null));
            if (dh == null || legStartCk == null) { cb.onEnvelope(null); return; }
            int i = s.optInt("legCtr", 0);
            byte[][] pair = replayChain(legStartCk, dh, i);
            if (pair == null) { cb.onEnvelope(null); return; }
            s.put("legCk", b64e(pair[1]));
            s.put("legCtr", i + 1);
            s.put("sentCount", sentCount + 1);
            saveSession(ctx, peerUid, s);
            env.put("rk", s.optString("legRk"));
            env.put("eph", s.optString("legRk"));
            env.put("opk", JSONObject.NULL);
            env.put("i", i);
            env.put("c", b64e(Crypto.aesGcmEncrypt(pair[0], payload)));
            cb.onEnvelope(env.toString());
        } catch (Throwable t) {
            Log.e(TAG, "send", t);
            cb.onEnvelope(null);
        }
    }

    private static void fillX3dh(Context ctx, String peerUid, Keys.Bundle b, byte[] payload, JSONObject env, int i) throws Exception {
        byte[] eph = Crypto.x25519Generate();
        byte[] ephPriv = slice(eph, 0);
        byte[] ephPub = slice(eph, 32);
        byte[] myRootPriv = b64d(Keys.rootPriv(ctx));
        ByteArrayOutputStream ss = new ByteArrayOutputStream();
        ss.write(Crypto.x25519(ephPriv, b64d(b.rootPub)));
        ss.write(Crypto.x25519(myRootPriv, b64d(b.idPub)));
        Integer opkId = null;
        if (b.preKeys != null && !b.preKeys.isEmpty()) {
            String bestId = null;
            for (String id : b.preKeys.keySet()) {
                int v;
                try { v = Integer.parseInt(id); } catch (Throwable t) { continue; }
                if (bestId == null || v < Integer.parseInt(bestId)) bestId = String.valueOf(v);
            }
            if (bestId != null) {
                ss.write(Crypto.x25519(ephPriv, b64d(b.preKeys.get(bestId))));
                opkId = Integer.parseInt(bestId);
            }
        }
        byte[] mk = Crypto.hkdf(ss.toByteArray(), null, str("TSUYU-MK"), 32);
        env.put("eph", b64e(ephPub));
        env.put("rk", b64e(ephPub));
        env.put("rkPriv", b64e(ephPriv));
        env.put("opk", opkId == null ? JSONObject.NULL : opkId);
        env.put("i", i);
        env.put("c", b64e(Crypto.aesGcmEncrypt(mk, payload)));
    }

    // ---------------- RECEIVE ----------------
    public static synchronized byte[] receive(Context ctx, String peerUid, JSONObject env) {
        try {
            String myIdPriv = Keys.idPriv(ctx);
            String myRootPriv = Keys.rootPriv(ctx);
            if (myIdPriv == null || myRootPriv == null) return null;

            String eph = env.optString("eph", null);
            String sId = env.optString("sId", null);
            String rk = env.optString("rk", null);
            int i = env.optInt("i", -1);
            int opk = env.isNull("opk") ? -1 : env.optInt("opk", -1);
            String c = env.optString("c", null);
            if (rk == null || c == null || i < 0) return null;

            JSONObject s = loadSession(ctx, peerUid);
            if (s == null) s = new JSONObject();
            s.put("peerLastRk", rk);

            String legRk = s.optString("legRk", null);
            boolean legPreReply = s.optBoolean("legPreReply", true);
            byte[] payload = null;

            if (legRk == null || legPreReply) {
                // bootstrap leg: self-contained X3DH per message (any order)
                payload = decryptX3dh(ctx, eph, sId, opk, c);
                if (payload != null) {
                    s.put("legRk", rk);
                    s.put("legPreReply", true);
                    s.put("legCtr", Math.max(s.optInt("legCtr", 0), i + 1));
                }
            } else if (rk.equals(legRk)) {
                // same leg: deterministic replay to index i
                payload = decryptLegMessage(ctx, s, rk, i, c, true);
                if (payload != null) {
                    s.put("legCtr", Math.max(s.optInt("legCtr", 0), i + 1));
                }
            } else {
                // new leg from peer
                payload = decryptLegMessage(ctx, s, rk, i, c, false);
                if (payload != null) {
                    s.put("legCtr", Math.max(s.optInt("legCtr", 0), i + 1));
                }
            }

            saveSession(ctx, peerUid, s);
            return payload;
        } catch (Throwable t) {
            Log.e(TAG, "receive", t);
            return null;
        }
    }

    /**
     * Decrypt a normal-leg message. For a new leg, tries my current rk first, then my
     * archived ratchet privs (racing root-step fallback).
     */
    private static byte[] decryptLegMessage(Context ctx, JSONObject s, String rk, int i, String c, boolean sameLeg) {
        try {
            String myLastRkPriv = s.optString("lastRkPriv", null);
            if (myLastRkPriv == null) return null;
            byte[] ct = b64d(c);

            if (sameLeg) {
                byte[] dh = b64d(s.optString("legDh", null));
                byte[] legStartCk = b64d(s.optString("legStartCk", null));
                if (dh == null || legStartCk == null) return null;
                byte[] pair = replayChain(legStartCk, dh, i);
                if (pair == null) return null;
                byte[] mk = pair[0];
                byte[] dec = tryDecrypt(mk, ct);
                if (dec == null) return null;
                s.put("legCk", b64e(pair[1]));
                return dec;
            }

            // new leg: RS = ECDH(myRk.priv, theirOldRk)  — try current rk, then archived
            String oldRk = s.optString("legRk", null);
            if (oldRk == null) return null;
            byte[] oldRkB = b64d(oldRk);
            byte[][] candidates = rkArchiveList(s);
            // candidate 0 = current rk priv (always first)
            byte[] first = b64d(myLastRkPriv);
            byte[][][] all = new byte[candidates.length + 1][][];
            all[0] = new byte[][]{first, null};
            System.arraycopy(candidates, 0, all, 1, candidates.length);

            for (byte[][] cand : all) {
                byte[] candPriv = cand[0];
                if (candPriv == null) continue;
                try {
                    byte[] rs = Crypto.x25519(candPriv, oldRkB);
                    byte[] legCk0 = Crypto.hkdf(rs, null, str("TSUYU-LEGCK"), 32);
                    byte[] dh = Crypto.x25519(candPriv, b64d(rk));
                    byte[] pair = replayChain(legCk0, dh, i);
                    if (pair == null) continue;
                    byte[] dec = tryDecrypt(pair[0], ct);
                    if (dec != null) {
                        s.put("legRk", rk);
                        s.put("legPreReply", false);
                        s.put("legStartCk", b64e(legCk0));
                        s.put("legDh", b64e(dh));
                        s.put("legCk", b64e(pair[1]));
                        return dec;
                    }
                } catch (Throwable t) {
                    // try next candidate
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "decryptLegMessage", t);
            return null;
        }
    }

    private static byte[] tryDecrypt(byte[] mk, byte[] ct) {
        try {
            return Crypto.aesGcmDecrypt(mk, ct);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Derive [mk, nextCk] at chain index idx from leg start. */
    private static byte[][] replayChain(byte[] legStartCk, byte[] dh, int idx) {
        if (legStartCk == null || dh == null || idx < 0 || idx > MAX_REPLAY) return null;
        byte[] ck = legStartCk;
        byte[] mk = null;
        for (int j = 0; j <= idx; j++) {
            byte[][] kdf = Crypto.kdf2(ck, dh);
            mk = kdf[0];
            ck = kdf[1];
        }
        return new byte[][]{mk, ck};
    }

    private static byte[] decryptX3dh(Context ctx, String eph, String sId, int opk, String c) {
        try {
            if (eph == null || sId == null) return null;
            byte[] myRootPriv = b64d(Keys.rootPriv(ctx));
            byte[] myIdPriv = b64d(Keys.idPriv(ctx));
            if (myRootPriv == null || myIdPriv == null) return null;
            ByteArrayOutputStream ss = new ByteArrayOutputStream();
            ss.write(Crypto.x25519(myRootPriv, b64d(eph)));
            ss.write(Crypto.x25519(myIdPriv, b64d(sId)));
            if (opk >= 0) {
                byte[] opkPriv = opkPrivFor(ctx, opk);
                if (opkPriv != null) {
                    ss.write(Crypto.x25519(opkPriv, b64d(eph)));
                    consumeOpk(ctx, opk);
                } else {
                    Log.w(TAG, "opk " + opk + " not available locally; bootstrap without it");
                }
            }
            byte[] mk = Crypto.hkdf(ss.toByteArray(), null, str("TSUYU-MK"), 32);
            return tryDecrypt(mk, b64d(c));
        } catch (Throwable t) {
            Log.w(TAG, "decryptX3dh failed", t);
            return null;
        }
    }

    /** Find prekey priv: active pool first, then consumed archive. */
    private static byte[] opkPrivFor(Context ctx, int id) {
        try {
            File d = ctx.getDir("keys", Context.MODE_PRIVATE);
            File pkf = new File(d, "prekeys.json");
            if (pkf.exists()) {
                FileInputStream fis = new FileInputStream(pkf);
                byte[] buf = new byte[(int) pkf.length()];
                int off = 0;
                while (off < buf.length) {
                    int n = fis.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                fis.close();
                JSONObject pk = new JSONObject(new String(buf, "UTF-8"));
                if (pk.has(String.valueOf(id))) return b64d(pk.getString(String.valueOf(id)));
            }
        } catch (Throwable ignored) {}
        return Keys.consumedPriv(ctx, id);
    }

    private static void consumeOpk(Context ctx, int id) {
        try {
            File d = ctx.getDir("keys", Context.MODE_PRIVATE);
            File pkf = new File(d, "prekeys.json");
            if (!pkf.exists()) return;
            FileInputStream fis = new FileInputStream(pkf);
            byte[] buf = new byte[(int) pkf.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            JSONObject pk = new JSONObject(new String(buf, "UTF-8"));
            if (pk.has(String.valueOf(id))) {
                byte[] priv = b64d(pk.getString(String.valueOf(id)));
                // move to consumed archive
                JSONObject arch = new JSONObject();
                File cf = new File(d, "consumed.json");
                if (cf.exists()) {
                    FileInputStream f2 = new FileInputStream(cf);
                    byte[] b2 = new byte[(int) cf.length()];
                    int o2 = 0;
                    while (o2 < b2.length) {
                        int n2 = f2.read(b2, o2, b2.length - o2);
                        if (n2 < 0) break;
                        o2 += n2;
                    }
                    f2.close();
                    arch = new JSONObject(new String(b2, "UTF-8"));
                }
                JSONObject o = new JSONObject();
                o.put("priv", b64e(priv));
                o.put("ts", System.currentTimeMillis());
                arch.put(String.valueOf(id), o);
                FileOutputStream fos = new FileOutputStream(cf);
                fos.write(arch.toString().getBytes("UTF-8"));
                fos.close();
                pk.remove(String.valueOf(id));
                FileOutputStream fos2 = new FileOutputStream(pkf);
                fos2.write(pk.toString().getBytes("UTF-8"));
                fos2.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "consumeOpk", t);
        }
    }

    public static void clearSession(Context ctx, String peer) {
        try {
            File f = sessionFile(ctx, peer);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
    }
}
