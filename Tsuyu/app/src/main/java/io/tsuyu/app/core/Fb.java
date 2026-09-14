package io.tsuyu.app.core;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import io.tsuyu.app.model.MeowUser;

/** Firebase helpers: profile read/write (encrypted), chat ids, presence. */
public class Fb {
    private static final String TAG = "TsuyuFb";
    public static final Map<String, MeowUser> userCache = new HashMap<>();
    private static MeowUser me;

    public static FirebaseUser user() {
        try { return FirebaseAuth.getInstance().getCurrentUser(); } catch (Throwable t) { return null; }
    }

    public static String myUid() {
        FirebaseUser u = user();
        return u == null ? null : u.getUid();
    }

    public static String chatId(String a, String b) {
        if (a == null || b == null) return null;
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    public static com.google.firebase.database.FirebaseDatabase fb() {
        return com.google.firebase.database.FirebaseDatabase.getInstance();
    }

    public static String myChatId(String a, String b) {
        return chatId(a, b);
    }

    public static String otherOf(String chatId) {
        String me = myUid();
        String[] ids = chatId.split("_");
        if (ids.length == 2) {
            return ids[0].equals(me) ? ids[1] : ids[0];
        }
        return null;
    }

    public static String otherOf(String chatId, String meUid) {
        String[] p = chatId.split("_");
        return p.length == 2 && p[0].equals(meUid) ? p[1] : p[0];
    }

    // ---------------- profile ----------------
    /** Load user profile (decrypting encrypted fields) from a DataSnapshot. */
    public static MeowUser parseUser(DataSnapshot ds) {
        if (ds == null || !ds.exists()) return null;
        MeowUser u = new MeowUser();
        u.uid = ds.getKey();
        u.username = ds.child("u").getValue(String.class);
        u.online = ds.child("online").getValue(Boolean.class) != null && ds.child("online").getValue(Boolean.class);
        u.lastSeen = ds.child("lastSeen").getValue(Long.class);
        u.typingIn = ds.child("typingIn").getValue(String.class);
        u.typingUntil = ds.child("typingUntil").getValue(Long.class);
        u.ghost = ds.child("ghost").getValue(Boolean.class) != null && ds.child("ghost").getValue(Boolean.class);
        JSONObject enc = null;
        try {
            Object eo = ds.child("enc").getValue();
            if (eo instanceof Map) {
                enc = new JSONObject((Map<String, Object>) eo);
            }
        } catch (Throwable ignored) {}
        if (enc != null) {
            u.firstName = ProfileCipher.dec(enc.optString("f", null));
            u.lastName = ProfileCipher.dec(enc.optString("l", null));
            u.name = ProfileCipher.dec(enc.optString("n", null));
            u.bio = ProfileCipher.dec(enc.optString("b", null));
            u.avatarB64 = ProfileCipher.dec(enc.optString("a", null));
            u.custom = enc.optString("c", null); // encrypted customization JSON
        }
        u.privacyWrite = ds.child("privacy").child("write").getValue(String.class);
        u.privacyLastSeen = ds.child("privacy").child("lastSeen").getValue(String.class);
        u.privacyPhoto = ds.child("privacy").child("photo").getValue(String.class);
        u.privacyBio = ds.child("privacy").child("bio").getValue(String.class);
        u.notifyOn = ds.child("notify").child("on").getValue(Boolean.class);
        u.notifyOn = u.notifyOn == null ? Boolean.TRUE : u.notifyOn;
        u.notifySoundB64 = ds.child("notify").child("s").getValue(String.class);
        u.fp = ds.child("pub").child("fp").getValue(String.class);
        return u;
    }

    public static MeowUser parseUserRaw(Map<String, Object> map) {
        return parseUserShim(map);
    }

    public static MeowUser parseUserShim(Map<String, Object> map) {
        MeowUser u = new MeowUser();
        Object uid = map.get("uid");
        u.uid = uid == null ? null : String.valueOf(uid);
        u.username = asStr(map.get("u"));
        u.online = asBool(map.get("online"));
        u.lastSeen = asLong(map.get("lastSeen"));
        u.typingIn = asStr(map.get("typingIn"));
        u.typingUntil = asLong(map.get("typingUntil"));
        u.ghost = asBool(map.get("ghost"));
        Object enc = map.get("enc");
        if (enc instanceof Map) {
            try {
                JSONObject jo = new JSONObject((Map<String, Object>) enc);
                u.firstName = ProfileCipher.dec(jo.optString("f", null));
                u.lastName = ProfileCipher.dec(jo.optString("l", null));
                u.name = ProfileCipher.dec(jo.optString("n", null));
                u.bio = ProfileCipher.dec(jo.optString("b", null));
                u.avatarB64 = ProfileCipher.dec(jo.optString("a", null));
                u.custom = jo.optString("c", null);
            } catch (Throwable ignored) {}
        }
        Map<String, Object> priv = asMap(map.get("privacy"));
        if (priv != null) {
            u.privacyWrite = asStr(priv.get("write"));
            u.privacyLastSeen = asStr(priv.get("lastSeen"));
            u.privacyPhoto = asStr(priv.get("photo"));
            u.privacyBio = asStr(priv.get("bio"));
        }
        Map<String, Object> notif = asMap(map.get("notify"));
        if (notif != null) {
            u.notifyOn = asBool(notif.get("on"));
            u.notifyOn = u.notifyOn == null ? Boolean.TRUE : u.notifyOn;
            u.notifySoundB64 = asStr(notif.get("s"));
        }
        Map<String, Object> pub = asMap(map.get("pub"));
        if (pub != null) u.fp = asStr(pub.get("fp"));
        return u;
    }

    /** JSONObject -> Map (org.json has no toMap). */
    public static java.util.Map<String, Object> toMap(org.json.JSONObject j) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        if (j == null) return m;
        java.util.Iterator<String> it = j.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                Object v = j.get(k);
                m.put(k, jsonToObj(v));
            } catch (Throwable ignored) {}
        }
        return m;
    }

    private static Object jsonToObj(Object v) {
        if (v instanceof org.json.JSONObject) return toMap((org.json.JSONObject) v);
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v;
            java.util.List<Object> l = new java.util.ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                try { l.add(jsonToObj(a.get(i))); } catch (Throwable ignored) {}
            }
            return l;
        }
        return v;
    }

    public static String asStr(Object o) { return o == null ? null : String.valueOf(o); }
    public static Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(o));
    }
    public static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Throwable t) { return null; }
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    /** Persist my profile fields (encrypted). null = do not change. */
    public static void saveMyProfile(Context ctx, String firstName, String lastName, String username,
                                     String bio, String avatarB64, String customEnc,
                                     String privacyWrite, String privacyLastSeen,
                                     String privacyPhoto, String privacyBio,
                                     Boolean notifyOn, String notifySoundB64) {
        try {
            String uid = myUid();
            if (uid == null) return;
            JSONObject enc = new JSONObject();
            if (firstName != null) enc.put("f", ProfileCipher.enc(firstName));
            if (lastName != null) enc.put("l", ProfileCipher.enc(lastName));
            if (username != null) {
                enc.put("n", ProfileCipher.enc((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)));
            }
            if (bio != null) enc.put("b", ProfileCipher.enc(bio));
            if (avatarB64 != null) enc.put("a", ProfileCipher.enc(avatarB64));
            if (customEnc != null) enc.put("c", customEnc);
            JSONObject patch = new JSONObject();
            if (!enc.isEmpty()) patch.put("enc", enc);
            if (username != null) patch.put("u", username);
            if (privacyWrite != null || privacyLastSeen != null || privacyPhoto != null || privacyBio != null) {
                JSONObject p = new JSONObject();
                if (privacyWrite != null) p.put("write", privacyWrite);
                if (privacyLastSeen != null) p.put("lastSeen", privacyLastSeen);
                if (privacyPhoto != null) p.put("photo", privacyPhoto);
                if (privacyBio != null) p.put("bio", privacyBio);
                patch.put("privacy", p);
            }
            if (notifyOn != null || notifySoundB64 != null) {
                JSONObject n = new JSONObject();
                if (notifyOn != null) n.put("on", notifyOn);
                if (notifySoundB64 != null) n.put("s", notifySoundB64);
                patch.put("notify", n);
            }
            DatabaseReference db = FirebaseDatabase.getInstance().getReference("users/" + uid);
            db.updateChildren(toMap(patch)).addOnFailureListener(e -> Log.e(TAG, "saveMyProfile", e));
        } catch (Throwable t) {
            Log.e(TAG, "saveMyProfile", t);
        }
    }

    public static void publishBundle(Context ctx) {
        try {
            io.tsuyu.app.core.Keys.publish(ctx);
        } catch (Throwable ignored) {}
    }

    public static void setPresence(Context ctx, boolean online) {
        try {
            String uid = myUid();
            if (uid == null) return;
            JSONObject patch = new JSONObject();
            patch.put("online", online);
            if (online) {
                patch.put("lastSeen", System.currentTimeMillis());
            } else {
                patch.put("lastSeen", System.currentTimeMillis());
                patch.put("typingIn", null);
            }
            DatabaseReference db = FirebaseDatabase.getInstance().getReference("users/" + uid);
            db.updateChildren(toMap(patch)).addOnFailureListener(e -> Log.e(TAG, "setPresence", e));
            if (online) {
                db.getValue().addOnCompleteListener(task -> {
                    try {
                        db.getRef("online").onDisconnect().setValue(false)
                                .addOnFailureListener(e -> Log.e(TAG, "onDisconnect", e));
                    } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "setPresence", t);
        }
    }

    public static void setTyping(Context ctx, String chatId, boolean typing) {
        try {
            String uid = myUid();
            if (uid == null) return;
            JSONObject patch = new JSONObject();
            if (typing) {
                patch.put("typingIn", chatId);
                patch.put("typingUntil", System.currentTimeMillis() + 6000);
            } else {
                patch.put("typingIn", null);
                patch.put("typingUntil", null);
            }
            FirebaseDatabase.getInstance().getReference("users/" + uid)
                    .updateChildren(toMap(patch));
        } catch (Throwable t) {
            Log.e(TAG, "setTyping", t);
        }
    }

    /** Username availability check. */
    public static void usernameTaken(Context ctx, String username, final BooleanCallback cb) {
        try {
            String lower = username.toLowerCase();
            FirebaseDatabase.getInstance().getReference("search/" + lower)
                    .child("uid").getValue()
                    .addOnCompleteListener(task -> {
                        try {
                            if (task.isSuccessful() && task.getResult() != null) {
                                String uid = task.getResult().getValue(String.class);
                                cb.done(uid != null && !uid.equals(myUid()));
                            } else cb.done(false);
                        } catch (Throwable t) { cb.done(false); }
                    });
        } catch (Throwable t) {
            cb.done(false);
        }
    }

    public interface BooleanCallback { void done(boolean v); }

    public interface ValueCallback { void done(Map<String, Object> v); }

    public static void fetchUser(String uid, final ValueCallback cb) {
        try {
            FirebaseDatabase.getInstance().getReference("users/" + uid).getValue()
                    .addOnCompleteListener(task -> {
                        try {
                            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                                Map<String, Object> map = task.getResult().getValue(Map.class);
                                cb.done(map);
                            } else cb.done(null);
                        } catch (Throwable t) { cb.done(null); }
                    });
        } catch (Throwable t) {
            cb.done(null);
        }
    }

    public static void writeMyChatMeta(String chatId, long ts, String type, String by, String thumbB64) {
        try {
            JSONObject m = new JSONObject();
            m.put("ts", ts);
            m.put("ty", type);
            m.put("by", by);
            if (thumbB64 != null) m.put("thumb", thumbB64);
            FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/last")
                    .setValue(m).addOnFailureListener(e -> Log.e(TAG, "writeMyChatMeta", e));
        } catch (Throwable t) {
            Log.e(TAG, "writeMyChatMeta", t);
        }
    }
}
