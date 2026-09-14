package com.tsuyu.messenger.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.TsuyuApp;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.ui.ChatActivity;
import com.tsuyu.messenger.ui.ChatPresence;
import com.tsuyu.messenger.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service keeping an RTDB listener alive so messages and calls
 * arrive instantly even when the app is in the background.
 */
public class TsuyuService extends Service {

    public static final String KEY_REPLY = "tsuyu_reply";

    private Repo repo;
    private Prefs prefs;
    private String me;
    private PowerManager.WakeLock wakeLock;

    private final Map<String, ChildEventListener> watchers = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private final Map<String, List<NotifMessage>> history = new HashMap<>();
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private long startedAt;

    private static class NotifMessage {
        final String text;
        final long ts;
        NotifMessage(String text, long ts) { this.text = text; this.ts = ts; }
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, TsuyuService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        repo = Repo.get(this);
        prefs = new Prefs(this);
        startedAt = System.currentTimeMillis();
        startForeground(1, buildServiceNotification());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsuyu:sync");
        wakeLock.setReferenceCounted(false);
        try { wakeLock.acquire(); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        me = repo.uid();
        if (me == null) { stopSelf(); return START_NOT_STICKY; }
        repo.goOnline();
        attachConversations();
        CallSignaling.listen(this, me);
        return START_STICKY;
    }

    private Notification buildServiceNotification() {
        return new NotificationCompat.Builder(this, TsuyuApp.CH_SERVICE)
                .setContentTitle("Tsuyu")
                .setContentText("Сквозное шифрование активно")
                .setSmallIcon(R.drawable.ic_lock)
                .setColor(0xFF2C2C2E)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void attachConversations() {
        repo.db().getReference("userChats").child(me)
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(@NonNull DataSnapshot s, String p) {
                        watch(s.getKey());
                    }
                    @Override public void onChildChanged(@NonNull DataSnapshot s, String p) {
                        watch(s.getKey());
                    }
                    @Override public void onChildRemoved(@NonNull DataSnapshot s) { }
                    @Override public void onChildMoved(@NonNull DataSnapshot s, String p) { }
                    @Override public void onCancelled(@NonNull DatabaseError e) { }
                });
    }

    private void watch(String peerUid) {
        if (peerUid == null || watchers.containsKey(peerUid)) return;
        ChildEventListener l = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String p) {
                handleIncoming(peerUid, s);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) { }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) { }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) { }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        watchers.put(peerUid, l);
        repo.chatRef(me, peerUid).orderByChild("ts").limitToLast(6).addChildEventListener(l);
    }

    private void handleIncoming(String peerUid, DataSnapshot s) {
        String id = s.getKey();
        if (id == null || seen.contains(id)) return;
        seen.add(id);

        Object from = s.child("from").getValue();
        if (from == null || me.equals(from)) return;             // our own message
        Object tsv = s.child("ts").getValue();
        long ts = tsv instanceof Number ? ((Number) tsv).longValue() : 0;
        if (ts < startedAt - 5000) return;                        // history, not new
        if (peerUid.equals(ChatPresence.activePeer)) return;      // chat already open
        if (!prefs.notificationsEnabled()) return;

        decodeExecutor.execute(() -> {
            Models.Message m = repo.decodeMessage(s, me);
            repo.userRef(peerUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot u) {
                    Models.User peer = u.exists() ? Repo.parseUser(u) : null;
                    notifyMessage(peerUid, peer, m);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { }
            });
        });
    }

    private void notifyMessage(String peerUid, Models.User peer, Models.Message m) {
        String name = peer == null ? "Новое сообщение" : peer.name;
        String body = previewOf(m);

        synchronized (history) {
            List<NotifMessage> list = history.get(peerUid);
            if (list == null) {
                list = new ArrayList<>();
                history.put(peerUid, list);
            }
            list.add(new NotifMessage(body, m.ts));
            if (list.size() > 10) list.remove(0);
        }

        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("peerUid", peerUid);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, peerUid.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // inline quick reply
        RemoteInput remoteInput = new RemoteInput.Builder(KEY_REPLY)
                .setLabel("Ответить…").build();
        Intent replyIntent = new Intent(this, ReplyReceiver.class);
        replyIntent.putExtra("peerUid", peerUid);
        PendingIntent replyPi = PendingIntent.getBroadcast(this,
                peerUid.hashCode() + 1, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_send, "Ответить", replyPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build();

        // mark as read action
        Intent readIntent = new Intent(this, MarkReadReceiver.class);
        readIntent.putExtra("peerUid", peerUid);
        PendingIntent readPi = PendingIntent.getBroadcast(this,
                peerUid.hashCode() + 2, readIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action readAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_check, "Прочитано", readPi)
                .build();

        Bitmap avatar = peer != null && peer.avatar != null
                ? Ui.circle(Ui.decodeB64(peer.avatar)) : null;

        Person person = new Person.Builder()
                .setName(name)
                .setIcon(avatar != null ? IconCompat.createWithBitmap(avatar) : null)
                .setKey(peerUid)
                .build();

        Person mePerson = new Person.Builder()
                .setName("Вы")
                .setKey(me)
                .build();

        NotificationCompat.MessagingStyle style =
                new NotificationCompat.MessagingStyle(mePerson)
                        .setConversationTitle(name)
                        .setGroupConversation(false);

        synchronized (history) {
            List<NotifMessage> list = history.get(peerUid);
            if (list != null) {
                for (NotifMessage nmMsg : list) {
                    style.addMessage(nmMsg.text, nmMsg.ts, person);
                }
            }
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, TsuyuApp.CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_chat)
                .setColor(0xFF2C2C2E)
                .setContentTitle(name)
                .setContentText(body)
                .setStyle(style)
                .setLargeIcon(avatar)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(replyAction)
                .addAction(readAction)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDefaults(0);

        if (prefs.vibrate()) b.setVibrate(new long[]{0, 100, 80, 100});

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(peerUid.hashCode(), b.build());

        playSound();
    }

    /** Plays the built-in chime or the user's custom mp3. */
    private void playSound() {
        if (!prefs.notificationsEnabled()) return;
        try {
            MediaPlayer mp;
            String custom = prefs.notificationSoundRaw();
            if (custom != null && !"builtin".equals(custom) && !custom.isEmpty()) {
                mp = new MediaPlayer();
                mp.setDataSource(this, Uri.parse(custom));
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                mp.prepare();
            } else {
                mp = MediaPlayer.create(this, R.raw.notify);
                if (mp == null) return;
            }
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.start();
        } catch (Exception ignored) { }
    }

    private String previewOf(Models.Message m) {
        if (m.failed) return "Новое зашифрованное сообщение";
        if (!m.attachments.isEmpty()) {
            String t = m.attachments.get(0).type;
            String label;
            switch (t) {
                case Models.T_PHOTO: label = "📷 Фото"; break;
                case Models.T_VIDEO: label = "🎬 Видео"; break;
                case Models.T_VOICE: label = "🎤 Голосовое"; break;
                case Models.T_CIRCLE: label = "⭕ Видеосообщение"; break;
                case Models.T_AUDIO: label = "🎵 Аудио"; break;
                default: label = "Вложение";
            }
            if (m.text != null && !m.text.isEmpty()) return label + ": " + m.text;
            return label;
        }
        return m.text == null ? "" : m.text;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { decodeExecutor.shutdown(); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        // restart so notifications keep working
        if (repo != null && repo.uid() != null) start(getApplicationContext());
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        start(getApplicationContext());
    }
}

