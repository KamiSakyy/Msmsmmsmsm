package io.tsuyu.app.core;

import android.content.Context;
import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * On-device private key management.
 * Private keys are wrapped with an AES-256-GCM key from the Android Keystore
 * (hardware-backed where available) and never leave the device.
 * Public bundles (identity pub, root pub, one-time prekeys) are published to RTDB.
 */
public class Keys {
    private static final String TAG = "TsuyuKeys";
    public static final int PREKEY_COUNT = 5;

    public static class Bundle {
        public String idPub;   // b64
        public String rootPub; // b64
        public Map<String, String> preKeys; // id -> b64 pub
        public String fp;      // fingerprint hex
    }

    private static byte[] wrapKey(Context ctx) {
        try {
            android.security.keystore.KeyStoreParameterSpec spec = new android.security.keystore.KeyStoreParameterSpec.Builder("tsuyu_master",
                    KeyStorePurposes())
                    .setDigestStrength(256)
                    .build();
            KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            kg.init(256, spec);
            SecretKey sk = kg.generateKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.WRAP_MODE, sk);
            return c.wrap(new SecretKeySpec(Crypto.rand(32), "AES"));
        } catch (Throwable t) {
            Log.e(TAG, "wrapKey", t);
            return null;
        }
    }

    private static int[] KeyStorePurposes() {
        return new int[]{Cipher.ENCRYPT_MODE, Cipher.DECRYPT_MODE};
    }

    private static SecretKey unwrap(Context ctx, byte[] wrapped) {
        try {
            android.security.keystore.KeyStoreParameterSpec spec = new android.security.keystore.KeyStoreParameterSpec.Builder("tsuyu_master",
                    KeyStorePurposes()).setDigestStrength(256).build();
            KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            kg.init(256, spec);
            SecretKey sk = kg.generateKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.UNWRAP_MODE, sk);
            return (SecretKey) c.unwrap(wrapped, "AES", Cipher.SECRET_KEY);
        } catch (Throwable t) {
            Log.e(TAG, "unwrap", t);
            return null;
        }
    }

    private static File dir(Context ctx) {
        File d = ctx.getDir("keys", Context.MODE_PRIVATE);
        return d;
    }

    private static String encFile(File f, SecretKey key, JSONObject obj) {
        try {
            byte[] plain = obj.toString().getBytes("UTF-8");
            byte[] ct = Crypto.aesGcmEncrypt(key.getEncoded(), plain);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(ct);
            fos.close();
            return Crypto.b64(ct);
        } catch (Throwable t) {
            Log.e(TAG, "encFile", t);
            return null;
        }
    }

    private static JSONObject decFile(File f, SecretKey key) {
        try {
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
            byte[] plain = Crypto.aesGcmDecrypt(key.getEncoded(), buf);
            return new JSONObject(new String(plain, "UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "decFile", t);
            return null;
        }
    }

    /** Ensure identity + root keys exist (generate if not). */
    public static synchronized void ensure(Context ctx) {
        try {
            File idFile = new File(dir(ctx), "identity.json");
            SecretKey wrap = unwrap(ctx, readAll(new File(dir(ctx), "masterwrap")));
            if (idFile.exists() && wrap != null) {
                JSONObject id = decFile(idFile, wrap);
                if (id != null && id.has("idPriv") && id.has("rootPriv")) return;
            }
            // generate
            byte[] idKp = Crypto.x25519Generate();
            byte[] rootKp = Crypto.x25519Generate();
            JSONObject id = new JSONObject();
            id.put("idPriv", Crypto.b64(slice(idKp, 0)));
            id.put("idPub", Crypto.b64(slice(idKp, 32)));
            id.put("rootPriv", Crypto.b64(slice(rootKp, 0)));
            id.put("rootPub", Crypto.b64(slice(rootKp, 32)));
            id.put("ts", System.currentTimeMillis());
            byte[] w2 = wrapKey(ctx);
            if (w2 == null) throw new RuntimeException("no keystore");
            File wf = new File(dir(ctx), "masterwrap");
            FileOutputStream fos = new FileOutputStream(wf);
            fos.write(w2);
            fos.close();
            SecretKey wrap2 = unwrap(ctx, w2);
            encFile(idFile, wrap2, id);
            initPrekeys(ctx);
            publish(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "ensure", t);
        }
    }

    private static byte[] slice(byte[] kp, int off) {
        byte[] r = new byte[32];
        System.arraycopy(kp, off, r, 0, 32);
        return r;
    }

    private static byte[] readAll(File f) {
        if (!f.exists()) return null;
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            return buf;
        } catch (Throwable t) { return null; }
    }

    private static JSONObject loadIdentity(Context ctx) {
        try {
            SecretKey wrap = unwrap(ctx, readAll(new File(dir(ctx), "masterwrap")));
            if (wrap == null) return null;
            return decFile(new File(dir(ctx), "identity.json"), wrap);
        } catch (Throwable t) { return null; }
    }

    private static void saveIdentity(Context ctx, JSONObject id) {
        try {
            SecretKey wrap = unwrap(ctx, readAll(new File(dir(ctx), "masterwrap")));
            encFile(new File(dir(ctx), "identity.json"), wrap, id);
        } catch (Throwable t) {
            Log.e(TAG, "saveIdentity", t);
        }
    }

    // ---------- prekeys ----------
    private static JSONObject loadJson(Context ctx, String name) {
        try {
            File f = new File(dir(ctx), name);
            if (!f.exists()) return new JSONObject();
            return new JSONObject(new String(readAll(f), "UTF-8"));
        } catch (Throwable t) { return new JSONObject(); }
    }

    private static void saveJson(Context ctx, String name, JSONObject obj) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(dir(ctx), name));
            fos.write(obj.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.e(TAG, "saveJson", t);
        }
    }

    private static void initPrekeys(Context ctx) {
        JSONObject pk = loadJson(ctx, "prekeys.json");
        if (pk.length() >= PREKEY_COUNT) return;
        int id = 0;
        try { id = pk.has("nextId") ? pk.getInt("nextId") : 0; } catch (Throwable ignored) {}
        while (pk.length() < PREKEY_COUNT && pk.length() < 50) {
            byte[] kp = Crypto.x25519Generate();
            pk.put(String.valueOf(id), Crypto.b64(slice(kp, 0))); // priv
            JSONObject o = new JSONObject();
            o.put("p", Crypto.b64(slice(kp, 32))); // pub
            pk.put("pub" + id, o.optString("x", ""));
            // simpler: separate pub map
            savePub(ctx, id, slice(kp, 32));
            id++;
        }
        pk.put("nextId", id);
        saveJson(ctx, "prekeys.json", pk);
    }

    private static void savePub(Context ctx, int id, byte[] pub) {
        try {
            JSONObject pubs = loadJson(ctx, "prekeypubs.json");
            JSONObject o = new JSONObject();
            o.put("p", Crypto.b64(pub));
            pubs.put(String.valueOf(id), o);
            saveJson(ctx, "prekeypubs.json", pubs);
        } catch (Throwable ignored) {}
    }

    /** Take next unused prekey: returns [priv, pub] or null. Consumes it. */
    public static synchronized byte[][] takePrekey(Context ctx) {
        try {
            JSONObject pk = loadJson(ctx, "prekeys.json");
            for (int i = 0; i < 100; i++) {
                String k = String.valueOf(i);
                if (pk.has(k)) {
                    byte[] priv = Crypto.unb64(pk.getString(k));
                    JSONObject pubs = loadJson(ctx, "prekeypubs.json");
                    byte[] pub = Crypto.unb64(pubs.optJSONObject(k).getString("p"));
                    pk.remove(k);
                    // archive consumed (needed to decrypt future messages referencing it)
                    JSONObject arch = loadJson(ctx, "consumed.json");
                    JSONObject o = new JSONObject();
                    o.put("priv", Crypto.b64(priv));
                    o.put("pub", Crypto.b64(pub));
                    o.put("ts", System.currentTimeMillis());
                    arch.put(k, o);
                    trimArchive(arch, 500);
                    saveJson(ctx, "consumed.json", arch);
                    pk.put("nextId", Math.max(pk.optInt("nextId", 0), i + 1));
                    saveJson(ctx, "prekeys.json", pk);
                    initPrekeys(ctx);
                    return new byte[][]{priv, pub};
                }
            }
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "takePrekey", t);
            return null;
        }
    }

    public static byte[] consumedPriv(Context ctx, int id) {
        try {
            JSONObject arch = loadJson(ctx, "consumed.json");
            if (arch.has(String.valueOf(id))) {
                return Crypto.unb64(arch.getJSONObject(String.valueOf(id)).getString("priv"));
            }
            return null;
        } catch (Throwable t) { return null; }
    }

    private static void trimArchive(JSONObject arch, int max) {
        if (arch.length() <= max) return;
        long[] ts = new long[arch.length()];
        int n = 0;
        Iterator<String> it = arch.keys();
        while (it.hasNext()) {
            try {
                String k = it.next();
                ts[n++] = arch.optJSONObject(k).optLong("ts", 0);
            } catch (Throwable ignored) {}
        }
        java.util.Arrays.sort(ts);
        long cutoff = ts[Math.max(0, ts.length - max)];
        it = arch.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (arch.optJSONObject(k).optLong("ts", 0) < cutoff) arch.remove(k);
        }
    }

    // ---------- identity accessors ----------
    public static String idPriv(Context ctx) {
        JSONObject id = loadIdentity(ctx);
        return id == null ? null : id.optString("idPriv", null);
    }
    public static String idPub(Context ctx) {
        JSONObject id = loadIdentity(ctx);
        return id == null ? null : id.optString("idPub", null);
    }
    public static String rootPriv(Context ctx) {
        JSONObject id = loadIdentity(ctx);
        return id == null ? null : id.optString("rootPriv", null);
    }
    public static String rootPub(Context ctx) {
        JSONObject id = loadIdentity(ctx);
        return id == null ? null : id.optString("rootPub", null);
    }

    public static String fingerprint(Context ctx) {
        String pub = idPub(ctx);
        if (pub == null) return "--------";
        return Crypto.sha256Hex(Crypto.unb64(pub));
    }

    // ---------- publish ----------
    public static synchronized void publish(Context ctx) {
        try {
            JSONObject id = loadIdentity(ctx);
            if (id == null) return;
            String uid = ((io.tsuyu.app.TsuyuApp) ctx.getApplicationContext()).myUid();
            if (uid == null) return;
            JSONObject pk = loadJson(ctx, "prekeys.json");
            JSONObject pubs = loadJson(ctx, "prekeypubs.json");
            JSONObject p = new JSONObject();
            Iterator<String> it = pk.keys();
            while (it.hasNext()) {
                String k = it.hasNext() ? it.next() : null;
                if (k == null) break;
                if (k.equals("nextId")) continue;
                JSONObject po = pubs.optJSONObject(k);
                if (po != null) p.put(k, po.getString("p"));
            }
            JSONObject pub = new JSONObject();
            pub.put("i", id.getString("idPub"));
            pub.put("r", id.getString("rootPub"));
            pub.put("p", p);
            pub.put("fp", Crypto.sha256Hex(Crypto.unb64(id.getString("idPub"))));
            DatabaseReference db = FirebaseDatabase.getInstance().getReference("users/" + uid + "/pub");
            db.setValue(pub).addOnFailureListener(e -> Log.e(TAG, "publish", e));
        } catch (Throwable t) {
            Log.e(TAG, "publish", t);
        }
    }

    // ---------- rotation ----------
    /** Rotate identity + root keys. Old keys are archived for history decryption. */
    public static synchronized void rotate(Context ctx) {
        try {
            JSONObject old = loadIdentity(ctx);
            if (old == null) { ensure(ctx); old = loadIdentity(ctx); if (old == null) return; }
            // archive old
            JSONObject arch = loadJson(ctx, "oldkeys.json");
            arch.put(String.valueOf(System.currentTimeMillis()), old);
            saveJson(ctx, "oldkeys.json", arch);
            // new
            byte[] idKp = Crypto.x25519Generate();
            byte[] rootKp = Crypto.x25519Generate();
            JSONObject id = new JSONObject();
            id.put("idPriv", Crypto.b64(slice(idKp, 0)));
            id.put("idPub", Crypto.b64(slice(idKp, 32)));
            id.put("rootPriv", Crypto.b64(slice(rootKp, 0)));
            id.put("rootPub", Crypto.b64(slice(rootKp, 32)));
            id.put("ts", System.currentTimeMillis());
            saveIdentity(ctx, id);
            initPrekeys(ctx);
            publish(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "rotate", t);
        }
    }

    // ---------- remote bundle ----------
    public static Bundle fetchBundle(final Context ctx, String uid, final Runnable done) {
        try {
            DatabaseReference db = FirebaseDatabase.getInstance().getReference("users/" + uid + "/pub");
            db.setValue(db.getValue()); // no-op
            db.getValue().addOnCompleteListener(task -> {
                try {
                    if (task.isSuccessful() && task.getResult() != null) {
                        DataSnapshot ds = task.getResult();
                        Bundle b = new Bundle();
                        b.idPub = ds.child("i").getValue(String.class);
                        b.rootPub = ds.child("r").getValue(String.class);
                        b.fp = ds.child("fp").getValue(String.class);
                        b.preKeys = new HashMap<>();
                        DataSnapshot p = ds.child("p");
                        if (p.exists()) {
                            for (DataSnapshot child : p.getChildren()) {
                                b.preKeys.put(child.getKey(), child.getValue(String.class));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                if (done != null) done.run();
            });
            return null;
        } catch (Throwable t) {
            if (done != null) done.run();
            return null;
        }
    }

    // ---------- export / import (new phone) ----------
    /** Export all private key material, encrypted with a passphrase. */
    public static byte[] exportAll(Context ctx, String passphrase) {
        try {
            JSONObject id = loadIdentity(ctx);
            JSONObject pk = loadJson(ctx, "prekeys.json");
            JSONObject pubs = loadJson(ctx, "prekeypubs.json");
            JSONObject consumed = loadJson(ctx, "consumed.json");
            JSONObject old = loadJson(ctx, "oldkeys.json");
            JSONObject out = new JSONObject();
            out.put("v", 1);
            out.put("id", id);
            out.put("prekeys", pk);
            out.put("prekeypubs", pubs);
            out.put("consumed", consumed);
            out.put("oldkeys", old);
            // include sessions
            File sd = new File(dir(ctx), "sessions");
            JSONObject sessions = new JSONObject();
            if (sd.exists() && sd.listFiles() != null) {
                for (File f : sd.listFiles()) {
                    if (f.getName().endsWith(".json")) {
                        byte[] data = readAll(f);
                        if (data != null) sessions.put(f.getName().replace(".json", ""), Crypto.b64(data));
                    }
                }
            }
            out.put("sessions", sessions);
            byte[] salt = Crypto.rand(16);
            byte[] key = Crypto.pbkdf2(passphrase.getBytes("UTF-8"), salt, 310000, 32);
            byte[] ct = Crypto.aesGcmEncrypt(key, out.toString().getBytes("UTF-8"));
            byte[] outBytes = new byte[16 + ct.length];
            System.arraycopy(salt, 0, outBytes, 0, 16);
            System.arraycopy(ct, 0, outBytes, 16, ct.length);
            return outBytes;
        } catch (Throwable t) {
            Log.e(TAG, "exportAll", t);
            return null;
        }
    }

    /** Import key material. Returns true on success. */
    public static synchronized boolean importAll(Context ctx, byte[] file, String passphrase) {
        try {
            if (file.length < 16 + 29) return false;
            byte[] salt = new byte[16];
            System.arraycopy(file, 0, salt, 0, 16);
            byte[] ct = new byte[file.length - 16];
            System.arraycopy(file, 16, ct, 0, ct.length);
            byte[] key = Crypto.pbkdf2(passphrase.getBytes("UTF-8"), salt, 310000, 32);
            byte[] plain = Crypto.aesGcmDecrypt(key, ct);
            JSONObject in = new JSONObject(new String(plain, "UTF-8"));
            JSONObject id = in.optJSONObject("id");
            if (id != null) {
                JSONObject keepOld = loadIdentity(ctx);
                if (keepOld != null) {
                    JSONObject arch = loadJson(ctx, "oldkeys.json");
                    arch.put(String.valueOf(System.currentTimeMillis()), keepOld);
                    saveJson(ctx, "oldkeys.json", arch);
                }
                saveIdentity(ctx, id);
            }
            JSONObject pk = in.optJSONObject("prekeys");
            if (pk != null) saveJson(ctx, "prekeys.json", pk);
            JSONObject pubs = in.optJSONObject("prekeypubs");
            if (pubs != null) saveJson(ctx, "prekeypubs.json", pubs);
            JSONObject consumed = in.optJSONObject("consumed");
            if (consumed != null) saveJson(ctx, "consumed.json", consumed);
            JSONObject old = in.optJSONObject("oldkeys");
            if (old != null) {
                JSONObject arch = loadJson(ctx, "oldkeys.json");
                Iterator<String> it = old.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    arch.put(k, old.opt(k));
                }
                saveJson(ctx, "oldkeys.json", arch);
            }
            JSONObject sessions = in.optJSONObject("sessions");
            if (sessions != null) {
                File sd = new File(dir(ctx), "sessions");
                sd.mkdirs();
                Iterator<String> it = sessions.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    File f = new File(sd, k + ".json");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(Crypto.unb64(sessions.getString(k)));
                    fos.close();
                }
            }
            publish(ctx);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "importAll", t);
            return false;
        }
    }
}
