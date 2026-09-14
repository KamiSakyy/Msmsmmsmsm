package io.tsuyu.app.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.tsuyu.app.R;
import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.Ratchet;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.notif.Notifier;

/**
 * Foreground service: keeps RTDB listeners alive in the background and posts
 * instant rich notifications (with reply) for new messages and incoming calls.
 */
public class BgService extends Service {
    private static final String TAG = "TsuyuBg";
    private static BgService inst;
    private static final List<ChatListener> listeners = new ArrayList<>();
    public static final java.util.Map<String, String> previewCache = new java.util.HashMap<>();
    public static final java.util.Map<String, String> thumbCache = new java.util.HashMap<>();
    private static final Set<String> activeChats = new HashSet<>();
    private final Map<String, ValueEventListener> peerListeners = new HashMap<>();
    private final Map<String, ChildEventListener> chatListeners = new HashMap<>();
    private DatabaseReference myChatsRef;

    public interface ChatListener {
        void onEvent(String chatId, int kind, String msgKey);
    }

    public static synchronized void addListener(ChatListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }
    public static synchronized void removeListener(ChatListener l) {
        listeners.remove(l);
    }
    public static synchronized void setActiveChat(String chatId) {
        if (chatId == null) activeChats.remove(chatId);
        else activeChats.add(chatId);
    }
    public static synchronized boolean isActive(String chatId) {
        return activeChats.contains(chatId);
    }
    public static BgService inst() { return inst; }

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, BgService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "start", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        try {
            Notifier.postService(this, "Tsuyu работает в фоне");
            startForeground(Notifier.ID_SERVICE,
                    Notification.from(this)
                            .setChannel(Notifier.CH_SVC)
                            .setContentTitle("Tsuyu")
                            .setContentText("Tsuyu работает в фоне")
                            .setSmallIcon(R.drawable.ic_launcher_fg)
                            .build(),
                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground", t);
        }
        setup();
    }

    private void setup() {
        try {
            String me = Fb.myUid();
            if (me == null) return;
            ensurePresence();
            // my chats index
            myChatsRef = FirebaseDatabase.getInstance().getReference("mychats/" + me);
            myChatsRef.addOnChildEventListener(new ChildEventListener() {
                @Override public void onChildAdded(DataSnapshot ds, String prev) { attachChat(ds.getKey()); }
                @Override public void onChildChanged(DataSnapshot ds, String prev) {}
                @Override public void onChildRemoved(DataSnapshot ds) { detachChat(ds.getKey()); }
                @Override public void onChildMoved(DataSnapshot ds, String prev) {}
                @Override public void onCancelled(DatabaseError e) {}
            });
            // incoming calls
            FirebaseDatabase.getInstance().getReference("calls").addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot ds) {
                    try {
                        String me = Fb.myUid();
                        if (me == null || !ds.exists()) return;
                        for (DataSnapshot c : ds.getChildren()) {
                            String to = c.child("t").getValue(String.class);
                            String st = c.child("st").getValue(String.class);
                            if (me.equals(to) && "ringing".equals(st)) {
                                onIncomingCall(c.getKey(), c);
                            } else if (me.equals(c.child("f").getValue(String.class)) && "active".equals(st)) {
                                fireEvent(c.getKey(), 4, null); // outgoing connected
                            } else if ((me.equals(to) || me.equals(c.child("f").getValue(String.class)))
                                    && ("ended".equals(st) || "declined".equals(st))) {
                                fireEvent(c.getKey(), 5, st);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
        } catch (Throwable t) {
            Log.e(TAG, "setup", t);
        }
    }

    private void ensurePresence() {
        try {
            String me = Fb.myUid();
            if (me == null) return;
            MeowUser meU = Fb.userCache.get(me);
            if (meU != null && meU.ghost) return; // ghost: no presence
            DatabaseReference db = FirebaseDatabase.getInstance().getReference("users/" + me);
            db.child("online").onDisconnect().setValue(false);
            db.child("typingIn").onDisconnect().setValue(null);
            db.child("lastSeen").onDisconnect().setValue(System.currentTimeMillis());
            if (meU == null || !meU.online) {
                db.updateChildren(new JSONObject().put("online", true).put("lastSeen", System.currentTimeMillis()).toMap());
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensurePresence", t);
        }
    }

    private void attachChat(String chatId) {
        if (chatId == null || chatListeners.containsKey(chatId)) return;
        String me = Fb.myUid();
        if (me == null) return;
        String peer = Fb.otherOf(chatId, me);
        try {
            FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs")
                    .addChildEventListener(new ChildEventListener() {
                        @Override public void onChildAdded(DataSnapshot ds, String prev) { onMsg(ds, chatId, peer); }
                        @Override public void onChildChanged(DataSnapshot ds, String prev) { onMsg(ds, chatId, peer); }
                        @Override public void onChildRemoved(DataSnapshot ds, String prev) { fireEvent(chatId, 2, ds.getKey()); }
                        @Override public void onChildMoved(DataSnapshot ds, String prev) {}
                        @Override public void onCancelled(DatabaseError e) {}
                    });
            chatListeners.put(chatId, null);
            // peer presence
            FirebaseDatabase.getInstance().getReference("users/" + peer).addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot ds) {
                    MeowUser u = Fb.parseUser(ds);
                    if (u != null) {
                        u.uid = peer;
                        Fb.userCache.put(peer, u);
                        fireEvent(chatId, 1, null);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
            peerListeners.put(chatId, null);
            // prefetch peer bundle for crypto
            Ratchet.prefetchBundle(this, peer);
        } catch (Throwable t) {
            Log.e(TAG, "attachChat", t);
        }
    }

    private void detachChat(String chatId) {
        try {
            // (listeners removed with the DB ref on service destroy; keep it simple)
        } catch (Throwable ignored) {}
    }

    private void onMsg(DataSnapshot ds, String chatId, String peer) {
        try {
            if (ds == null || !ds.exists()) return;
            String me = Fb.myUid();
            if (me == null) return;
            String from = ds.child("f").getValue(String.class);
            Long delTs = ds.child("del").getValue(Long.class);
            if (delTs != null && delTs > 0) {
                fireEvent(chatId, 2, ds.getKey());
                return;
            }
            Long ts = ds.child("t").getValue(Long.class);
            if (ts == null) return;
            boolean fromMe = me.equals(from);
            if (!fromMe) {
                JSONObject env = null;
                Object eo = ds.child("e").getValue();
                if (eo instanceof Map) env = new JSONObject((Map<String, Object>) eo);
                if (env != null) {
                    byte[] payload = Ratchet.receive(this, peer, env);
                    if (payload != null) {
                        JSONObject p = new JSONObject(new String(payload, "UTF-8"));
                        try {
                            previewCache.put(ds.getKey(), previewOf(p, ds));
                        } catch (Throwable ignored) {}
                        // mark delivered for the SENDER (green double-check on their side)
                        try {
                            FirebaseDatabase.getInstance()
                                    .getReference("chats/" + chatId + "/msgs/" + ds.getKey() + "/d/" + from)
                                    .setValue(System.currentTimeMillis());
                        } catch (Throwable ignored) {}
                        // notify
                        if (!isActive(chatId)) {
                            MeowUser peerU = Fb.userCache.get(peer);
                            String preview = previewOf(p, ds);
                            if (peerU != null && Boolean.TRUE.equals(peerU.notifyOn) || peerU == null) {
                                Notifier.postMessage(this, chatId, peerU, preview, ds.getKey().hashCode());
                            }
                        }
                    }
                }
            }
            fireEvent(chatId, 0, ds.getKey());
        } catch (Throwable t) {
            Log.e(TAG, "onMsg", t);
        }
    }

    private String previewOf(JSONObject p, DataSnapshot ds) {
        try {
            String tx = p.optString("tx", null);
            if (tx != null && !tx.isEmpty()) return tx;
            String ty = ds.child("ty").getValue(String.class);
            if (ty == null) ty = "media";
            switch (ty) {
                case "photo": return "📷 Фото";
                case "video": return "🎬 Видео";
                case "voice": return "🎤 Голосовое сообщение";
                case "circle": return "📹 Кружочек";
                case "music": return "🎵 " + p.optString("name", "Музыка");
                case "collage": return "🖼 Коллаж";
                case "call": return "📞 Звонок";
                default: return "📎 Файл";
            }
        } catch (Throwable t) {
            return "Новое сообщение";
        }
    }

    private void onIncomingCall(String callId, DataSnapshot c) {
        try {
            String me = Fb.myUid();
            String from = c.child("f").getValue(String.class);
            if (from == null || from.equals(me)) return;
            MeowUser peer = Fb.userCache.get(from);
            if (peer == null) {
                Fb.fetchUser(from, map -> {
                    try {
                        MeowUser u = Fb.parseUserRaw(map);
                        if (u != null) { u.uid = from; Fb.userCache.put(from, u); }
                        Notifier.postCall(BgService.this, callId, u,
                                "video".equals(c.child("ty").getValue(String.class)), "in");
                        fireEvent(callId, 3, null);
                    } catch (Throwable t) {}
                });
            } else {
                Notifier.postCall(this, callId, peer,
                        "video".equals(c.child("ty").getValue(String.class)), "in");
            }
            try {
                io.tsuyu.app.call.CallService.start(this, callId, from, false);
            } catch (Throwable ignored) {}
            fireEvent(callId, 3, null);
        } catch (Throwable t) {
            Log.e(TAG, "onIncomingCall", t);
        }
    }

    private static synchronized void fireEvent(String chatId, int kind, String key) {
        for (ChatListener l : new ArrayList<>(listeners)) {
            try {
                l.onEvent(chatId, kind, key);
            } catch (Throwable ignored) {}
        }
    }

    public static void writeMyChat(String chatId) {
        try {
            String me = Fb.myUid();
            if (me == null) return;
            FirebaseDatabase.getInstance().getReference("mychats/" + me + "/" + chatId)
                    .setValue(true);
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        inst = null;
        try {
            String me = Fb.myUid();
            if (me != null) {
                Fb.setPresence(this, false);
            }
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
