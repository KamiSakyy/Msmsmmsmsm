package com.tsuyu.messenger.data;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.OnDisconnect;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.crypto.CryptoUtil;
import com.tsuyu.messenger.crypto.IdentityStore;
import com.tsuyu.messenger.crypto.ProfileCrypto;
import com.tsuyu.messenger.crypto.SessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single access point to Firebase RTDB.
 *
 * Layout:
 *   users/{uid}                  public profile (sealed fields + key bundle)
 *   usernames/{username}         -> uid   (uniqueness index)
 *   presence/{uid}               {online,lastSeen,ghost}
 *   typing/{uid}/{peer}          timestamp
 *   chats/{chatId}/{msgId}       encrypted envelopes
 *   inbox/{uid}/{chatId}         lightweight new-message signal
 *   calls/{uid}                  call signalling
 */
public class Repo {

    private static Repo instance;

    private final Context ctx;
    private final FirebaseDatabase db;
    private final Prefs prefs;

    private Repo(Context c) {
        ctx = c.getApplicationContext();
        db = FirebaseDatabase.getInstance();
        prefs = new Prefs(ctx);
    }

    public static synchronized Repo get(Context c) {
        if (instance == null) instance = new Repo(c);
        return instance;
    }

    public FirebaseDatabase db() { return db; }

    public String uid() {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
    }

    public static String chatId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    // ------------------------------------------------------------------
    // Profile
    // ------------------------------------------------------------------

    public DatabaseReference userRef(String uid) { return db.getReference("users").child(uid); }

    /** Publishes the profile: sensitive fields sealed, key bundle in clear (public by design). */
    public void publishProfile(String uid, String username, String name, String bio,
                               String avatarB64, Runnable done) {
        IdentityStore id = IdentityStore.get(ctx);
        Map<String, Object> m = new HashMap<>();
        // Only non-null fields are written: passing null must never erase existing data.
        if (username != null) m.put("username", username.toLowerCase());
        if (name != null) m.put("name", ProfileCrypto.seal(uid, name));
        if (bio != null) m.put("bio", ProfileCrypto.seal(uid, bio));
        if (avatarB64 != null) m.put("avatar", ProfileCrypto.seal(uid, avatarB64));
        try {
            JSONObject b = id.publicBundle();
            m.put("ik", b.getString("ik"));
            m.put("ed", b.getString("ed"));
            m.put("spk", b.getString("spk"));
            m.put("spkSig", b.getString("spkSig"));
        } catch (Exception ignored) { }
        m.put("updatedAt", ServerValue.TIMESTAMP);
        userRef(uid).updateChildren(m, (e, r) -> { if (done != null) done.run(); });
    }

    public void updateProfileField(String key, String value) {
        String uid = uid();
        if (uid == null) return;
        userRef(uid).child(key).setValue(ProfileCrypto.seal(uid, value));
    }

    public void updatePrivacy(String key, String value) {
        String uid = uid();
        if (uid == null) return;
        userRef(uid).child(key).setValue(value);
    }

    /** Reserves a unique @username. */
    public void claimUsername(String username, String uid, ResultCb cb) {
        String u = username.toLowerCase();
        DatabaseReference ref = db.getReference("usernames").child(u);
        ref.runTransaction(new com.google.firebase.database.Transaction.Handler() {
            @NonNull
            @Override
            public com.google.firebase.database.Transaction.Result doTransaction(
                    @NonNull com.google.firebase.database.MutableData data) {
                Object cur = data.getValue();
                if (cur == null || uid.equals(cur)) {
                    data.setValue(uid);
                    return com.google.firebase.database.Transaction.success(data);
                }
                return com.google.firebase.database.Transaction.abort();
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot s) {
                cb.onResult(committed, committed ? null : "Юз @" + u + " уже занят");
            }
        });
    }

    public interface ResultCb { void onResult(boolean ok, String error); }

    public static Models.User parseUser(DataSnapshot s) {
        Models.User u = new Models.User();
        u.uid = s.getKey();
        u.username = str(s, "username");
        u.name = ProfileCrypto.open(u.uid, str(s, "name"));
        u.bio = ProfileCrypto.open(u.uid, str(s, "bio"));
        u.avatar = ProfileCrypto.open(u.uid, str(s, "avatar"));
        u.publicAvatar = ProfileCrypto.open(u.uid, str(s, "publicAvatar"));
        u.ik = str(s, "ik");
        u.ed = str(s, "ed");
        u.spk = str(s, "spk");
        u.spkSig = str(s, "spkSig");
        u.pWrite = def(str(s, "pWrite"), "all");
        u.pLastSeen = def(str(s, "pLastSeen"), "all");
        u.pAvatar = def(str(s, "pAvatar"), "all");
        u.pBio = def(str(s, "pBio"), "all");
        if (TextUtils.isEmpty(u.name)) u.name = u.username == null ? "Без имени" : "@" + u.username;
        return u;
    }

    private static String str(DataSnapshot s, String k) {
        Object v = s.child(k).getValue();
        return v == null ? null : String.valueOf(v);
    }

    private static String def(String v, String d) { return TextUtils.isEmpty(v) ? d : v; }

    // ------------------------------------------------------------------
    // Presence & typing
    // ------------------------------------------------------------------

    public void goOnline() {
        String uid = uid();
        if (uid == null) return;
        DatabaseReference ref = db.getReference("presence").child(uid);
        boolean ghost = prefs.ghost();
        OnDisconnect od = ref.onDisconnect();
        Map<String, Object> off = new HashMap<>();
        off.put("online", false);
        if (!ghost) off.put("lastSeen", ServerValue.TIMESTAMP);
        od.updateChildren(off);

        Map<String, Object> on = new HashMap<>();
        on.put("online", !ghost);
        on.put("ghost", ghost);
        if (!ghost) on.put("lastSeen", ServerValue.TIMESTAMP);
        ref.updateChildren(on);
    }

    public void goOffline() {
        String uid = uid();
        if (uid == null) return;
        Map<String, Object> off = new HashMap<>();
        off.put("online", false);
        if (!prefs.ghost()) off.put("lastSeen", ServerValue.TIMESTAMP);
        db.getReference("presence").child(uid).updateChildren(off);
    }

    public DatabaseReference presenceRef(String uid) {
        return db.getReference("presence").child(uid);
    }

    /** Typing is refreshed on every keystroke and expires after ~3s. */
    public void setTyping(String peer, boolean typing) {
        String uid = uid();
        if (uid == null) return;
        if (prefs.ghost() && typing) return; // Ghost mode suppresses typing indicator
        DatabaseReference ref = db.getReference("typing").child(peer).child(uid);
        if (typing) {
            ref.setValue(ServerValue.TIMESTAMP);
            ref.onDisconnect().removeValue();
        } else {
            ref.removeValue();
        }
    }

    public DatabaseReference typingRef(String uid) {
        return db.getReference("typing").child(uid);
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    public DatabaseReference chatRef(String me, String peer) {
        return db.getReference("chats").child(chatId(me, peer));
    }

    public Query chatQuery(String me, String peer, int limit) {
        return chatRef(me, peer).orderByChild("ts").limitToLast(limit);
    }

    /**
     * Encrypts and stores a message. The plaintext JSON never leaves the device.
     */
    public void sendMessage(Models.User peer, JSONObject payload, SendCb cb) {
        String me = uid();
        if (me == null || peer == null) { if (cb != null) cb.onSent(false, null); return; }
        if (peer.ik == null || peer.spk == null) {
            userRef(peer.uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        Models.User fresh = parseUser(snapshot);
                        sendMessage(fresh, payload, cb);
                    } else {
                        if (cb != null) cb.onSent(false, null);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {
                    if (cb != null) cb.onSent(false, null);
                }
            });
            return;
        }
        try {
            payload.put("ts", System.currentTimeMillis());
            String payloadStr = payload.toString();
            byte[] plain = payloadStr.getBytes("UTF-8");

            SessionManager sm = SessionManager.get(ctx);
            JSONObject forPeer = sm.encrypt(peer.uid, peer.ik, peer.spk, plain);
            String selfCopy = ProfileCrypto.seal(me, payloadStr);

            DatabaseReference ref = chatRef(me, peer.uid).push();
            String msgId = ref.getKey();
            if (msgId != null) {
                LocalMessageStore.get(ctx).putPayload(me, peer.uid, msgId, System.currentTimeMillis(), payloadStr);
            }

            Map<String, Object> m = new HashMap<>();
            m.put("from", me);
            m.put("to", peer.uid);
            m.put("ts", ServerValue.TIMESTAMP);
            m.put("env", forPeer.toString());
            m.put("selfEnv", selfCopy);
            ref.setValue(m, (e, r) -> {
                signalInbox(peer.uid, me);
                indexConversation(me, peer.uid);
                if (cb != null) cb.onSent(e == null, msgId);
            });
        } catch (Exception ex) {
            if (cb != null) cb.onSent(false, null);
        }
    }

    public interface SendCb { void onSent(boolean ok, String messageId); }

    /** Keeps both sides' conversation index in sync so dialog lists can be queried. */
    public void indexConversation(String me, String peerUid) {
        long now = System.currentTimeMillis();
        db.getReference("userChats").child(me).child(peerUid).setValue(now);
        db.getReference("userChats").child(peerUid).child(me).setValue(now);
    }

    private void signalInbox(String peerUid, String me) {
        Map<String, Object> m = new HashMap<>();
        m.put("from", me);
        m.put("ts", ServerValue.TIMESTAMP);
        db.getReference("inbox").child(peerUid).child(me).setValue(m);
    }

    /** Decrypts a stored message row into a Message model. */
    public Models.Message decodeMessage(DataSnapshot s, String me) {
        Models.Message msg = new Models.Message();
        msg.id = s.getKey();
        msg.from = str(s, "from");
        msg.to = str(s, "to");
        Object tsv = s.child("ts").getValue();
        msg.ts = tsv instanceof Number ? ((Number) tsv).longValue() : System.currentTimeMillis();
        msg.outgoing = me.equals(msg.from);
        msg.read = Boolean.TRUE.equals(s.child("read").getValue());
        msg.edited = Boolean.TRUE.equals(s.child("edited").getValue());
        msg.deleted = Boolean.TRUE.equals(s.child("deleted").getValue());

        DataSnapshot hs = s.child("hearts");
        for (DataSnapshot h : hs.getChildren()) {
            if (Boolean.TRUE.equals(h.getValue())) msg.hearts.add(h.getKey());
        }

        if (msg.deleted) { msg.type = Models.T_TEXT; msg.text = ""; return msg; }

        LocalMessageStore store = LocalMessageStore.get(ctx);
        String json = null;

        if (!msg.edited) {
            json = store.getPayload(me, msg.id);
        }

        if (json == null) {
            try {
                if (msg.outgoing) {
                    String self = str(s, "selfEnv");
                    if (self != null) json = ProfileCrypto.open(me, self);
                }
                if (json == null) {
                    String env = str(s, "env");
                    if (env != null) {
                        String peer = msg.outgoing ? msg.to : msg.from;
                        byte[] plain = SessionManager.get(ctx).decrypt(peer, new JSONObject(env));
                        json = new String(plain, "UTF-8");
                    }
                }
                // Edited messages carry a replacement envelope.
                String edited = str(s, "editEnv");
                if (edited != null) {
                    if (msg.outgoing) {
                        String o = ProfileCrypto.open(me, edited);
                        if (o != null) json = o;
                    } else {
                        byte[] p = SessionManager.get(ctx).decrypt(msg.from, new JSONObject(edited));
                        json = new String(p, "UTF-8");
                    }
                }

                if (json != null) {
                    String peer = msg.outgoing ? msg.to : msg.from;
                    store.putPayload(me, peer, msg.id, msg.ts, json);
                }
            } catch (Exception e) {
                msg.failed = true;
            }
        }

        if (json == null) {
            msg.failed = true;
            msg.text = "";
            return msg;
        }
        try {
            applyPayload(msg, new JSONObject(json));
            msg.failed = false;
        } catch (Exception e) {
            msg.failed = true;
        }
        return msg;
    }

    private void applyPayload(Models.Message msg, JSONObject p) throws Exception {
        msg.type = p.optString("type", Models.T_TEXT);
        msg.text = p.optString("text", "");
        msg.replyTo = p.has("replyTo") ? p.getString("replyTo") : null;
        msg.replyName = p.optString("replyName", null);
        msg.replyPreview = p.optString("replyPreview", null);
        msg.forwardedFrom = p.optString("fwd", null);
        JSONArray arr = p.optJSONArray("att");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                Models.Attachment at = new Models.Attachment();
                at.type = a.optString("t");
                at.data = a.optString("d", null);
                at.thumb = a.optString("th", null);
                at.width = a.optInt("w");
                at.height = a.optInt("h");
                at.durationMs = a.optLong("dur");
                at.fileName = a.optString("fn", null);
                at.artist = a.optString("ar", null);
                JSONArray w = a.optJSONArray("wf");
                if (w != null) {
                    at.waveform = new int[w.length()];
                    for (int j = 0; j < w.length(); j++) at.waveform[j] = w.getInt(j);
                }
                msg.attachments.add(at);
            }
        }
    }

    // ---- message mutations ----

    public void editMessage(Models.User peer, String msgId, JSONObject newPayload) {
        String me = uid();
        if (me == null) return;
        try {
            String payloadStr = newPayload.toString();
            JSONObject env = SessionManager.get(ctx)
                    .encrypt(peer.uid, peer.ik, peer.spk, payloadStr.getBytes("UTF-8"));
            Map<String, Object> m = new HashMap<>();
            m.put("editEnv", env.toString());
            m.put("edited", true);
            chatRef(me, peer.uid).child(msgId).updateChildren(m);
            // our own readable copy
            chatRef(me, peer.uid).child(msgId).child("selfEnv")
                    .setValue(ProfileCrypto.seal(me, payloadStr));
            LocalMessageStore.get(ctx).putPayload(me, peer.uid, msgId, System.currentTimeMillis(), payloadStr);
        } catch (Exception ignored) { }
    }

    public void deleteMessage(String peerUid, String msgId, boolean forBoth) {
        String me = uid();
        if (me == null) return;
        LocalMessageStore.get(ctx).deleteMessage(me, msgId);
        DatabaseReference ref = chatRef(me, peerUid).child(msgId);
        if (forBoth) {
            ref.removeValue();
        } else {
            ref.child("hiddenFor").child(me).setValue(true);
        }
    }

    public void toggleHeart(String peerUid, String msgId, boolean on) {
        String me = uid();
        if (me == null) return;
        chatRef(me, peerUid).child(msgId).child("hearts").child(me)
                .setValue(on ? true : null);
    }

    public void markRead(String peerUid, String msgId) {
        if (prefs.ghost() || prefs.stealthRead()) return; // ghost & stealth mode leave no read receipts
        String me = uid();
        if (me == null) return;
        chatRef(me, peerUid).child(msgId).child("read").setValue(true);
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    public void searchByUsername(String q, SearchCb cb) {
        String query = q.toLowerCase().replace("@", "");
        if (TextUtils.isEmpty(query)) { cb.onResults(new ArrayList<>()); return; }
        db.getReference("users").orderByChild("username")
                .startAt(query).endAt(query + "\uf8ff").limitToFirst(30)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<Models.User> out = new ArrayList<>();
                        String me = uid();
                        for (DataSnapshot c : snapshot.getChildren()) {
                            if (c.getKey() != null && c.getKey().equals(me)) continue;
                            out.add(parseUser(c));
                        }
                        cb.onResults(out);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        cb.onResults(new ArrayList<>());
                    }
                });
    }

    public interface SearchCb { void onResults(List<Models.User> users); }

    // ------------------------------------------------------------------
    // Key rotation
    // ------------------------------------------------------------------

    /** Rotates the private identity; peers pick it up from the next message. */
    public void rotateKeys(Runnable done) {
        String uid = uid();
        if (uid == null) return;
        SessionManager sm = SessionManager.get(ctx);
        sm.archiveCurrentSpk();
        IdentityStore.get(ctx).generate();
        sm.resetAll();
        try {
            JSONObject b = IdentityStore.get(ctx).publicBundle();
            Map<String, Object> m = new HashMap<>();
            m.put("ik", b.getString("ik"));
            m.put("ed", b.getString("ed"));
            m.put("spk", b.getString("spk"));
            m.put("spkSig", b.getString("spkSig"));
            m.put("keysRotatedAt", ServerValue.TIMESTAMP);
            userRef(uid).updateChildren(m, (e, r) -> { if (done != null) done.run(); });
        } catch (Exception e) {
            if (done != null) done.run();
        }
    }
}
