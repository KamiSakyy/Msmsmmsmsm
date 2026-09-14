package io.tsuyu.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.tsuyu.app.R;
import io.tsuyu.app.call.CallService;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.Ratchet;
import io.tsuyu.app.media.ImageUtil;
import io.tsuyu.app.media.VoiceRec;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.model.Msg;
import io.tsuyu.app.service.BgService;
import io.tsuyu.app.util.Ui;

public class ChatActivity extends AppCompatActivity implements BgService.ChatListener {
    private static final String TAG = "TsuyuChat";

    public static final int PICK_PHOTO = 601;
    public static final int PICK_VIDEO = 602;
    public static final int PICK_MUSIC = 604;

    private static ChatActivity lastInst;
    public static synchronized ChatActivity last() { return lastInst; }

    private String peer, chatId;
    private MeowUser me, peerUser;
    private RecyclerView recycler;
    private ChatAdapter adapter;
    private List<Object> rows = new ArrayList<>();
    private Map<String, Msg> msgMap = new HashMap<>();
    private EditText etMessage;
    private View btnSend, btnMic, btnAttach;
    private LinearLayout typingIndicator;
    private TextView tvChatStatus;
    private View replyBar, editBar;
    private TextView tvReplyName, tvReplyText, tvEditText;
    private Msg replyTarget, editTarget;
    private VoiceRec voiceRec;
    private View recordingBar;
    private TextView tvRecTimer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastTypingSent = 0;
    private ChildEventListener msgListener;
    private ValueEventListener peerListener;
    private String lastDateShown = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        String peerExtra = getIntent().getStringExtra("peer");
        String chatExtra = getIntent().getStringExtra("chatId");
        peer = peerExtra;
        chatId = chatExtra != null ? chatExtra : Fb.chatId(Fb.myUid(), peer);
        if (peer == null || Fb.myUid() == null) {
            finish();
            return;
        }
        lastInst = this;
        BgService.writeMyChat(chatId);
        Fb.fetchUser(peer, map -> {
            peerUser = Fb.parseUserRaw(map);
            if (peerUser != null) {
                peerUser.uid = peer;
                Fb.userCache.put(peer, peerUser);
                runOnUiThread(this::refreshHeader);
            }
        });
        Fb.fetchUser(Fb.myUid(), map -> {
            me = Fb.parseUserRaw(map);
            if (me != null) {
                me.uid = Fb.myUid();
                Fb.userCache.put(me.uid, me);
                Ui.loadCustom(me.custom);
            }
        });
        Ratchet.prefetchBundle(this, peer);

        recycler = findViewById(R.id.recycler);
        adapter = new ChatAdapter(this, rows);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.stackFromEnd(true);
        recycler.setLayoutManager(lm);
        recycler.setAdapter(adapter);

        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        btnMic = findViewById(R.id.btnMic);
        btnAttach = findViewById(R.id.btnAttach);
        typingIndicator = findViewById(R.id.typingIndicator);
        tvChatStatus = findViewById(R.id.tvChatStatus);
        replyBar = findViewById(R.id.replyBar);
        editBar = findViewById(R.id.editBar);
        tvReplyName = findViewById(R.id.tvReplyName);
        tvReplyText = findViewById(R.id.tvReplyText);
        tvEditText = findViewById(R.id.tvEditText);
        recordingBar = findViewById(R.id.recordingBar);
        tvRecTimer = findViewById(R.id.tvRecTimer);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.headerAvatarWrap).setOnClickListener(v -> openProfile());
        findViewById(R.id.headerInfo).setOnClickListener(v -> openProfile());
        findViewById(R.id.btnCall).setOnClickListener(v -> startCall(false));
        findViewById(R.id.btnVideoCall).setOnClickListener(v -> startCall(true));
        findViewById(R.id.btnChatMore).setOnClickListener(v -> showChatMenu());
        findViewById(R.id.btnReplyClose).setOnClickListener(v -> setReply(null));
        findViewById(R.id.btnEditClose).setOnClickListener(v -> setEdit(null));

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                btnSend.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                btnMic.setVisibility(s.length() > 0 ? View.GONE : View.VISIBLE);
                onTypingChar();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        btnSend.setOnClickListener(v -> sendCurrent());
        btnMic.setOnClickListener(v -> startVoiceRec());
        btnAttach.setOnClickListener(v -> showAttachMenu());
        findViewById(R.id.btnRecCancel).setOnClickListener(v -> { if (voiceRec != null) voiceRec.stop(); });
        findViewById(R.id.btnRecSend).setOnClickListener(v -> { if (voiceRec != null) voiceRec.stop(); });

        loadMessages();
    }

    private void refreshHeader() {
        try {
            TextView title = findViewById(R.id.tvChatTitle);
            title.setText(peerUser == null ? "..." : peerUser.displayName());
            ImageView iv = findViewById(R.id.ivHeaderAvatar);
            TextView letter = findViewById(R.id.tvHeaderLetter);
            View bg = findViewById(R.id.headerAvatarWrap).getChildAt(0);
            Ui.setAvatar(iv, bg, letter, peerUser);
        } catch (Throwable ignored) {}
    }

    private void openProfile() {
        Intent i = new Intent(this, ProfileViewActivity.class);
        i.putExtra("uid", peer);
        startActivity(i);
    }

    private void startCall(boolean video) {
        try {
            String callId = System.currentTimeMillis() + "_c" + (int) (Math.random() * 9999);
            JSONObject call = new JSONObject();
            call.put("f", Fb.myUid());
            call.put("t", peer);
            call.put("ty", video ? "video" : "audio");
            call.put("st", "ringing");
            call.put("ts", System.currentTimeMillis());
            FirebaseDatabase.getInstance().getReference("calls/" + callId).setValue(call);
            CallService.start(this, callId, peer, true);
            Intent i = new Intent(this, io.tsuyu.app.call.CallActivity.class);
            i.putExtra("callId", callId);
            i.putExtra("peer", peer);
            i.putExtra("video", video);
            i.putExtra("incoming", false);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "startCall", t);
        }
    }

    // ---------------- load ----------------
    private void loadMessages() {
        try {
            FirebaseDatabase.getInstance().getReference("chats/" + chatId).get().addOnCompleteListener(task -> {
                try {
                    if (task.getResult() == null) return;
                    DataSnapshot ds = task.getResult();
                    if (ds.exists() == false) {
                        try {
                            JSONObject c = new JSONObject();
                            c.put("a", Fb.myUid());
                            c.put("b", peer);
                            c.put("c", System.currentTimeMillis());
                            FirebaseDatabase.getInstance().getReference("chats/" + chatId).setValue(c);
                        } catch (Throwable ignored) {}
                    }
                    DataSnapshot msgs = ds.child("msgs");
                    List<Msg> list = new ArrayList<>();
                    if (msgs.exists()) {
                        for (DataSnapshot m : msgs.getChildren()) {
                            Msg msg = parseMsg(m);
                            if (msg != null) list.add(msg);
                        }
                    }
                    list.sort((a, b) -> Long.compare(a.ts, b.ts));
                    runOnUiThread(() -> {
                        rows.clear();
                        msgMap.clear();
                        lastDateShown = "";
                        for (Msg m : list) addRow(m);
                        adapter.notifyDataSetChanged();
                        scrollToEnd();
                        markRead();
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "loadMessages", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "loadMessages", t);
        }
    }

    Msg parseMsg(DataSnapshot m) {
        try {
            Msg msg = new Msg();
            msg.key = m.getKey();
            msg.from = m.child("f").getValue(String.class);
            msg.ts = m.child("t").getValue(Long.class) == null ? 0 : m.child("t").getValue(Long.class);
            msg.type = m.child("ty").getValue(String.class);
            msg.editedTs = m.child("ed").getValue(Long.class) == null ? 0 : m.child("ed").getValue(Long.class);
            msg.replyTo = m.child("m2").child("reply").getValue(String.class);
            msg.fwdFrom = m.child("m2").child("fw").getValue(String.class);
            msg.hiddenByMe = Boolean.TRUE.equals(m.child("hb").child(Fb.myUid()).getValue(Boolean.class));
            Long rdTs = m.child("rd").child(Fb.myUid()).getValue(Long.class);
            msg.readByMe = rdTs == null ? 0 : rdTs;
            Long dTs = m.child("d").child(Fb.myUid()).getValue(Long.class);
            msg.deliveredToMe = dTs == null ? 0 : dTs;
            msg.deletedForAll = m.child("del").getValue(Long.class) == null ? 0 : m.child("del").getValue(Long.class);
            msg.reactions = new JSONObject();
            DataSnapshot r = m.child("r");
            if (r.exists()) {
                for (DataSnapshot rr : r.getChildren()) msg.reactions.put(rr.getKey(), 1);
            }
            Object mo = m.child("m2").getValue();
            if (mo instanceof Map) msg.meta = new JSONObject((Map<String, Object>) mo);
            JSONObject env = null;
            Object eo = m.child("e").getValue();
            if (eo instanceof Map) env = new JSONObject((Map<String, Object>) eo);
            if (env != null) {
                byte[] payload = Ratchet.receive(this, peer, env);
                if (payload != null) {
                    msg.payload = new JSONObject(new String(payload, "UTF-8"));
                    msg.decryptState = 1;
                } else {
                    msg.decryptState = 3;
                }
            } else {
                msg.decryptState = 3;
            }
            return msg;
        } catch (Throwable t) {
            return null;
        }
    }

    void addRow(Msg m) {
        try {
            String date = Ui.dateLabel(m.ts);
            if (date.equals(lastDateShown) == false) {
                rows.add(m.ts);
                lastDateShown = date;
            }
            rows.add(m);
            msgMap.put(m.key, m);
            if (m.replyTo != null && m.payload != null && msgMap.containsKey(m.replyTo)) {
                Msg orig = msgMap.get(m.replyTo);
                if (orig != null) m.payload.put("replyName", peerName(orig.from));
                if (orig != null) {
                    String t = orig.text();
                    if (t == null) t = typeLabel(orig.type);
                    if (t != null) m.payload.put("replyText", t.length() > 80 ? t.substring(0, 80) + "…" : t);
                }
            }
        } catch (Throwable ignored) {}
    }

    String peerName(String uid) {
        MeowUser u = uid.equals(Fb.myUid()) ? me : Fb.userCache.get(uid);
        return u == null ? "?" : u.displayName();
    }

    String typeLabel(String ty) {
        if (ty == null) return "";
        switch (ty) {
            case "photo": return "📷 Фото";
            case "video": return "🎬 Видео";
            case "voice": return "🎤 Голосовое сообщение";
            case "circle": return "📹 Кружочек";
            case "music": return "🎵 Музыка";
            case "collage": return "🖼 Коллаж";
            default: return "📎";
        }
    }

    // ---------------- realtime ----------------
    @Override
    protected void onResume() {
        super.onResume();
        BgService.addListener(this);
        BgService.setActiveChat(chatId);
        BgService.start(this);
        try {
            msgListener = new ChildEventListener() {
                @Override public void onChildAdded(DataSnapshot ds, String prev) { onMsgEvent(ds); }
                @Override public void onChildChanged(DataSnapshot ds, String prev) { onMsgEvent(ds); }
                @Override public void onChildRemoved(DataSnapshot ds, String prev) {
                    try {
                        msgMap.remove(ds.getKey());
                        for (int i = rows.size() - 1; i >= 0; i--) {
                            if (rows.get(i) instanceof Msg && ((Msg) rows.get(i)).key.equals(ds.getKey())) rows.remove(i);
                        }
                        runOnUiThread(() -> adapter.notifyDataSetChanged());
                    } catch (Throwable ignored) {}
                }
                @Override public void onChildMoved(DataSnapshot ds, String prev) {}
                @Override public void onCancelled(DatabaseError e) {}
            };
            FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs").addChildEventListener(msgListener);
            peerListener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot ds) {
                    MeowUser u = Fb.parseUser(ds);
                    if (u != null) {
                        u.uid = peer;
                        Fb.userCache.put(peer, u);
                        runOnUiThread(() -> {
                            peerUser = u;
                            refreshHeader();
                            refreshStatus();
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            };
            FirebaseDatabase.getInstance().getReference("users/" + peer).addValueEventListener(peerListener);
            markRead();
        } catch (Throwable t) {
            Log.e(TAG, "onResume", t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        BgService.removeListener(this);
        BgService.setActiveChat(null);
        Fb.setTyping(this, chatId, false);
        try {
            if (msgListener != null) {
                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs").removeEventListener(msgListener);
                msgListener = null;
            }
            if (peerListener != null) {
                FirebaseDatabase.getInstance().getReference("users/" + peer).removeEventListener(peerListener);
                peerListener = null;
            }
        } catch (Throwable ignored) {}
        if (voiceRec != null) voiceRec.stop();
    }

    private void onMsgEvent(DataSnapshot ds) {
        try {
            if (ds.exists() == false) return;
            Msg msg = parseMsg(ds);
            if (msg == null) return;
            runOnUiThread(() -> {
                Msg old = msgMap.get(msg.key);
                if (old == null) {
                    addRow(msg);
                    boolean nearBottom = isNearBottom();
                    adapter.notifyDataSetChanged();
                    if (nearBottom || msg.from.equals(Fb.myUid())) scrollToEnd();
                    if (msg.from.equals(Fb.myUid()) == false) markRead();
                } else {
                    int idx = rows.indexOf(old);
                    if (idx >= 0) {
                        rows.set(idx, msg);
                        msgMap.put(msg.key, msg);
                        adapter.notifyItemChanged(idx);
                    } else {
                        addRow(msg);
                        adapter.notifyDataSetChanged();
                    }
                }
                refreshStatus();
            });
        } catch (Throwable t) {
            Log.e(TAG, "onMsgEvent", t);
        }
    }

    @Override
    public void onEvent(String chatIdEv, int kind, String msgKey) {
        if (chatId.equals(chatIdEv) == false) return;
        if (kind == 1) {
            runOnUiThread(this::refreshStatus);
        }
    }

    private void refreshStatus() {
        try {
            String st;
            boolean typing = false;
            if (peerUser != null) {
                if (peerUser.typingIn != null && peerUser.typingIn.equals(chatId)
                        && peerUser.typingUntil != null && peerUser.typingUntil > System.currentTimeMillis()) {
                    typing = true;
                }
            }
            String typingText = Ui.st("typing", getString(R.string.typing));
            String onlineText = Ui.st("online", getString(R.string.online));
            if (typing) {
                st = typingText;
            } else if (peerUser != null && peerUser.online && peerUser.ghost == false) {
                st = onlineText;
            } else if (peerUser != null) {
                String last = Ui.st("lastSeen", "был(а)");
                boolean hidden = peerUser.privacyLastSeen != null
                        && (peerUser.privacyLastSeen.equals("nobody") || peerUser.privacyLastSeen.equals("onlyAt"));
                st = hidden ? last + " недавно" : Ui.lastSeenText(peerUser, onlineText, last);
            } else st = "";
            tvChatStatus.setText(st);
            if (typing) {
                if (typingIndicator.getVisibility() != View.VISIBLE) {
                    typingIndicator.setVisibility(View.VISIBLE);
                    animateDots();
                }
            } else {
                typingIndicator.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    private void animateDots() {
        try {
            for (int i = 1; i <= 3; i++) {
                final View dot = findViewById(i == 1 ? R.id.td1 : i == 2 ? R.id.td2 : R.id.td3);
                final Runnable r = new Runnable() {
                    @Override public void run() {
                        if (dot.getVisibility() != View.VISIBLE) return;
                        dot.animate().scaleY(1.5f).scaleX(1.3f).alpha(1f).setDuration(300)
                                .withEndAction(() -> dot.animate().scaleY(0.7f).scaleX(0.7f).alpha(0.4f)
                                        .setDuration(300).withEndAction(this).start()).start();
                    }
                };
                main.postDelayed(r, i * 200);
            }
        } catch (Throwable ignored) {}
    }

    private void markRead() {
        try {
            MeowUser my = me != null ? me : Fb.userCache.get(Fb.myUid());
            if (my != null && my.ghost) return; // ghost: no read receipts
            FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs").get()
                    .addOnCompleteListener(task -> {
                        try {
                            if (task.getResult() == null) return;
                            JSONObject patch = new JSONObject();
                            for (DataSnapshot m : task.getResult().getChildren()) {
                                String from = m.child("f").getValue(String.class);
                                if (Fb.myUid().equals(from)) continue;
                                if (m.child("rd").child(Fb.myUid()).getValue(Long.class) != null) continue;
                                long now = System.currentTimeMillis();
                                patch.put(m.getKey() + "/rd/" + Fb.myUid(), now);
                                // read receipt for the sender (blue double-check on their side)
                                if (from != null && from.equals(Fb.myUid()) == false) {
                                    patch.put(m.getKey() + "/rd/" + from, now);
                                }
                            }
                            if (patch.length() > 0) {
                                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs")
                                        .updateChildren(patch.toMap());
                            }
                        } catch (Throwable ignored) {}
                    });
        } catch (Throwable ignored) {}
    }

    // ---------------- typing ----------------
    private void onTypingChar() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTypingSent < 700) return;
        lastTypingSent = now;
        Fb.setTyping(this, chatId, true);
        main.removeCallbacks(typeTimeout);
        main.postDelayed(typeTimeout, 5500);
    }

    private final Runnable typeTimeout = () -> Fb.setTyping(this, chatId, false);

    // ---------------- send ----------------
    private void sendCurrent() {
        if (editTarget != null) {
            sendEdit();
            return;
        }
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        etMessage.setText("");
        JSONObject p = new JSONObject();
        try {
            p.put("tx", text);
            if (replyTarget != null) p.put("reply", replyTarget.key);
        } catch (Throwable ignored) {}
        sendPayload(p, "text", null, null);
        setReply(null);
    }

    private void sendEdit() {
        try {
            String text = etMessage.getText().toString().trim();
            if (text.isEmpty()) return;
            etMessage.setText("");
            JSONObject p = new JSONObject();
            p.put("tx", text);
            if (editTarget.replyTo != null) p.put("reply", editTarget.replyTo);
            Msg target = editTarget;
            setEdit(null);
            Ratchet.send(this, peer, p.toString().getBytes("UTF-8"), env -> {
                if (env == null) { Ui.toast(this, "Ошибка шифрования"); return; }
                try {
                    JSONObject patch = new JSONObject();
                    patch.put("e", new JSONObject(env));
                    patch.put("ed", System.currentTimeMillis());
                    FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs/" + target.key)
                            .updateChildren(patch.toMap());
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            Log.e(TAG, "sendEdit", t);
        }
    }

    void sendPayload(JSONObject payload, String type, String thumbB64, String name) {
        try {
            Ratchet.send(this, peer, payload.toString().getBytes("UTF-8"), env -> {
                if (env == null) {
                    Ui.toast(ChatActivity.this, "Ошибка шифрования. Попробуйте ещё раз.");
                    return;
                }
                try {
                    long ts = System.currentTimeMillis();
                    String key = ts + "_" + (int) (Math.random() * 99999);
                    JSONObject msg = new JSONObject();
                    msg.put("f", Fb.myUid());
                    msg.put("t", ts);
                    msg.put("ty", type);
                    JSONObject m2 = new JSONObject();
                    if (name != null) m2.put("name", name);
                    msg.put("m2", m2);
                    msg.put("e", new JSONObject(env));
                    FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs/" + key).setValue(msg);
                    try {
                        JSONObject lm = new JSONObject();
                        lm.put("ts", ts);
                        lm.put("ty", type);
                        lm.put("by", Fb.myUid());
                        lm.put("k", key);
                        if (thumbB64 != null) lm.put("thumb", thumbB64);
                        FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/last").setValue(lm);
                    } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    Log.e(TAG, "sendPayload", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "sendPayload", t);
        }
    }

    // ---------------- attach menu ----------------
    private void showAttachMenu() {
        try {
            android.widget.PopupWindow pw = new android.widget.PopupWindow(this);
            View v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setBackgroundResource(R.drawable.bg_menu);
            int pad = dp(6);
            v.setPadding(pad, pad, pad, pad);
            addMenuItem(v, R.drawable.ic_photo, "Фото", () -> { pw.dismiss(); pickPhotos(); });
            addMenuItem(v, R.drawable.ic_video, "Видео", () -> { pw.dismiss(); pickVideo(); });
            addMenuItem(v, R.drawable.ic_circle_rec, "Кружочек", () -> { pw.dismiss(); startCircle(); });
            addMenuItem(v, R.drawable.ic_music, "Музыка / аудио", () -> { pw.dismiss(); pickMusic(); });
            pw.setWidth(dp(220));
            pw.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            pw.showAtLocation(btnAttach, android.view.Gravity.NO_GRAVITY, dp(10), dp(50));
        } catch (Throwable t) {
            Ui.toast(this, "Ошибка");
        }
    }

    void addMenuItem(LinearLayout parent, int iconRes, String text, Runnable action) {
        View row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFAAAAAA);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.rightMargin = dp(12);
        row.addView(iv, ilp);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFDDDDDD);
        tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(view -> action.run());
        parent.addView(row);
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Выбрать фото (до 4)"), PICK_PHOTO);
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("video/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Выбрать видео"), PICK_VIDEO);
    }

    private void pickMusic() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Выбрать музыку"), PICK_MUSIC);
    }

    private void startCircle() {
        if (checkMic()) {
            Intent i = new Intent(this, CircleRecorderActivity.class);
            startActivity(i);
        }
    }

    boolean checkMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 201);
            return false;
        }
        return true;
    }

    // ---------------- voice ----------------
    private void startVoiceRec() {
        if (checkMic() == false) return;
        voiceRec = new VoiceRec(this);
        recordingBar.setVisibility(View.VISIBLE);
        etMessage.setVisibility(View.GONE);
        btnMic.setVisibility(View.GONE);
        btnSend.setVisibility(View.GONE);
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (voiceRec == null) return;
                tvRecTimer.setText(Ui.durText(voiceRec.durationMs() / 1000.0));
                h.postDelayed(this, 200);
            }
        };
        h.postDelayed(tick, 200);
        voiceRec.start((file, wave, dur) -> {
            try {
                h.removeCallbacks(tick);
            } catch (Throwable ignored) {}
            if (file == null || dur < 500) {
                recordingBar.setVisibility(View.GONE);
                etMessage.setVisibility(View.VISIBLE);
                btnMic.setVisibility(View.VISIBLE);
                return;
            }
            byte[] data = Ui.readFile(file);
            if (data == null) {
                recordingBar.setVisibility(View.GONE);
                etMessage.setVisibility(View.VISIBLE);
                btnMic.setVisibility(View.VISIBLE);
                return;
            }
            String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            JSONObject p = new JSONObject();
            try {
                p.put("m", b64);
                p.put("wt", "m4a");
                p.put("dur", dur / 1000.0);
                JSONArray w = new JSONArray();
                for (double d : wave) w.put(d);
                p.put("wave", w);
                if (replyTarget != null) p.put("reply", replyTarget.key);
            } catch (Throwable ignored) {}
            file.delete();
            setReply(null);
            recordingBar.setVisibility(View.GONE);
            etMessage.setVisibility(View.VISIBLE);
            btnMic.setVisibility(View.VISIBLE);
            sendPayload(p, "voice", null, null);
        });
    }

    // ---------------- gallery results ----------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == PICK_PHOTO) {
                List<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < Math.min(4, data.getClipData().getItemCount()); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (uris.isEmpty()) return;
                processPhotos(uris);
            } else if (requestCode == PICK_VIDEO) {
                if (data.getData() != null) processVideo(data.getData());
            } else if (requestCode == PICK_MUSIC) {
                if (data.getData() != null) processMusic(data.getData());
            }
        } catch (Throwable t) {
            Log.e(TAG, "onActivityResult", t);
        }
    }

    private void processPhotos(final List<Uri> uris) {
        new Thread(() -> {
            try {
                if (uris.size() == 1) {
                    ImageUtil.ImgResult r = ImageUtil.process(ChatActivity.this, uris.get(0), 1600, 80);
                    if (r == null) { Ui.toast(ChatActivity.this, "Ошибка обработки фото"); return; }
                    if (r.jpeg.length > 7_000_000) {
                        Ui.toast(ChatActivity.this, "Фото слишком большое");
                        return;
                    }
                    JSONObject p = new JSONObject();
                    p.put("m", r.b64);
                    p.put("wt", "jpg");
                    p.put("w", r.w);
                    p.put("h", r.h);
                    String thumb = ImageUtil.tinyBlur(r.jpeg, 48, 12);
                    sendPayload(p, "photo", thumb, null);
                } else {
                    JSONArray items = new JSONArray();
                    for (Uri u : uris) {
                        ImageUtil.ImgResult r = ImageUtil.process(ChatActivity.this, u, 900, 75);
                        if (r == null) continue;
                        JSONObject it = new JSONObject();
                        it.put("m", r.b64);
                        it.put("wt", "jpg");
                        it.put("w", r.w);
                        it.put("h", r.h);
                        items.put(it);
                    }
                    if (items.length() == 0) return;
                    long total = 0;
                    for (int i = 0; i < items.length(); i++) {
                        total += items.getJSONObject(i).getString("m").length();
                    }
                    if (total > 7_500_000) {
                        Ui.toast(ChatActivity.this, "Коллаж слишком большой");
                        return;
                    }
                    JSONObject p = new JSONObject();
                    p.put("items", items);
                    sendPayload(p, "collage", null, null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "processPhotos", t);
                Ui.toast(ChatActivity.this, "Ошибка обработки");
            }
        }).start();
    }

    private void processVideo(final Uri uri) {
        Ui.toast(this, "Обрабатываю видео...");
        new Thread(() -> {
            try {
                Uri fileUri = copyToCache(uri, "video_in");
                long size = new File(fileUri.getPath()).length();
                long durMs = ImageUtil.videoDurationMs(uri, ChatActivity.this);
                if (size <= 6_500_000) {
                    byte[] data = Ui.readFile(new File(fileUri.getPath()));
                    if (data == null) { Ui.toast(ChatActivity.this, "Ошибка чтения видео"); return; }
                    Bitmap thumb = ImageUtil.firstVideoFrame(ChatActivity.this, uri, 1280);
                    int w = 0, h = 0;
                    if (thumb != null) {
                        w = thumb.getWidth();
                        h = thumb.getHeight();
                        thumb.recycle();
                    }
                    sendVideo(data, w, h, durMs / 1000.0, "mp4");
                    new File(fileUri.getPath()).delete();
                } else {
                    int bitrate = Math.max(300_000, (int) (6_000_000L * 8 / Math.max(1, durMs / 1000) / 1.4));
                    io.tsuyu.app.media.VideoTranscoder.transcode(ChatActivity.this, fileUri, 1280, 720, bitrate,
                            new io.tsuyu.app.media.VideoTranscoder.Callback() {
                                @Override public void onDone(File f, int ww, int hh, long d) {
                                    runOnUiThread(() -> {
                                        byte[] dd = Ui.readFile(f);
                                        if (dd == null || dd.length > 7_000_000) {
                                            Ui.toast(ChatActivity.this, "Видео слишком большое");
                                            return;
                                        }
                                        sendVideo(dd, ww, hh, d / 1000.0, "mp4");
                                        f.delete();
                                    });
                                }
                                @Override public void onError(String msg) {
                                    runOnUiThread(() -> Ui.toast(ChatActivity.this, "Не удалось сжать видео"));
                                }
                            });
                }
            } catch (Throwable t) {
                Log.e(TAG, "processVideo", t);
                Ui.toast(ChatActivity.this, "Ошибка обработки видео");
            }
        }).start();
    }

    private void sendVideo(byte[] data, int w, int h, double dur, String wt) {
        try {
            String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            JSONObject p = new JSONObject();
            p.put("m", b64);
            p.put("wt", wt);
            p.put("w", w);
            p.put("h", h);
            p.put("dur", dur);
            sendPayload(p, "video", null, null);
        } catch (Throwable t) {
            Log.e(TAG, "sendVideo", t);
        }
    }

    private void processMusic(final Uri uri) {
        new Thread(() -> {
            try {
                Uri fileUri = copyToCache(uri, "music_in");
                File f = new File(fileUri.getPath());
                if (f.length() > 6_500_000) {
                    Ui.toast(ChatActivity.this, "Аудио слишком большое (макс ~6 МБ)");
                    return;
                }
                byte[] data = Ui.readFile(f);
                if (data == null) { Ui.toast(ChatActivity.this, "Ошибка чтения аудио"); return; }
                String name = queryName(uri);
                if (name == null) name = "audio";
                String wt = name.toLowerCase().endsWith(".mp3") ? "mp3"
                        : name.toLowerCase().endsWith(".wav") ? "wav"
                        : name.toLowerCase().endsWith(".ogg") ? "ogg" : "m4a";
                JSONObject p = new JSONObject();
                p.put("m", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING));
                p.put("wt", wt);
                p.put("name", name);
                p.put("dur", 0);
                sendPayload(p, "music", null, name);
                f.delete();
            } catch (Throwable t) {
                Log.e(TAG, "processMusic", t);
                Ui.toast(ChatActivity.this, "Ошибка обработки аудио");
            }
        }).start();
    }

    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx);
                c.close();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Uri copyToCache(Uri uri, String prefix) throws Exception {
        File f = new File(getCacheDir(), prefix + "_" + System.currentTimeMillis());
        java.io.InputStream is = getContentResolver().openInputStream(uri);
        java.io.FileOutputStream fos = new FileOutputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        is.close();
        fos.close();
        return Uri.fromFile(f);
    }

    // ---------------- reply / edit / forward ----------------
    void setReply(Msg m) {
        replyTarget = m;
        if (m != null) {
            tvReplyName.setText(peerName(m.from));
            String t = m.text();
            if (t == null) t = typeLabel(m.type);
            tvReplyText.setText(t != null ? t : "");
            replyBar.setVisibility(View.VISIBLE);
            etMessage.requestFocus();
        } else {
            replyBar.setVisibility(View.GONE);
        }
    }

    void setEdit(Msg m) {
        editTarget = m;
        if (m != null) {
            tvEditText.setText(m.text() != null ? m.text() : "");
            editBar.setVisibility(View.VISIBLE);
            etMessage.setText(m.text() != null ? m.text() : "");
            etMessage.setSelection(etMessage.getText().length());
            etMessage.requestFocus();
        } else {
            editBar.setVisibility(View.GONE);
        }
    }

    void forwardMsg(Msg m) {
        try {
            ForwardActivity.pending = new JSONObject()
                    .put("ty", m.type == null ? "text" : m.type)
                    .put("p", m.payload == null ? new JSONObject().put("tx", m.text() == null ? "" : m.text()) : m.payload);
        } catch (Throwable t) {
            ForwardActivity.pending = new JSONObject();
        }
        Intent i = new Intent(this, ForwardActivity.class);
        startActivity(i);
    }

    // ---------------- chat menu ----------------
    private void showChatMenu() {
        try {
            android.widget.PopupWindow pw = new android.widget.PopupWindow(this);
            View v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setBackgroundResource(R.drawable.bg_menu);
            v.setPadding(dp(6), dp(6), dp(6), dp(6));
            addMenuItem(v, R.drawable.ic_user, "Профиль", () -> { pw.dismiss(); openProfile(); });
            addMenuItem(v, R.drawable.ic_export, "Экспорт переписки", () -> {
                pw.dismiss();
                Intent i = new Intent(this, ExportChatActivity.class);
                i.putExtra("peer", peer);
                i.putExtra("chatId", chatId);
                startActivity(i);
            });
            pw.setWidth(dp(230));
            pw.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            pw.showAtLocation(findViewById(R.id.btnChatMore), android.view.Gravity.NO_GRAVITY, dp(-140), dp(46));
        } catch (Throwable ignored) {}
    }

    // ---------------- message actions (called from adapter) ----------------
    void onMsgMore(Msg m, View anchor) {
        try {
            android.widget.PopupWindow pw = new android.widget.PopupWindow(this);
            View v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setBackgroundResource(R.drawable.bg_menu);
            v.setPadding(dp(6), dp(6), dp(6), dp(6));
            final boolean mine = m.from.equals(Fb.myUid());
            addMenuItem(v, R.drawable.ic_heart_fill, "❤️  Реакция", () -> { pw.dismiss(); toggleReaction(m); });
            addMenuItem(v, R.drawable.ic_reply, "Ответить", () -> { pw.dismiss(); setReply(m); });
            addMenuItem(v, R.drawable.ic_forward, "Переслать", () -> { pw.dismiss(); forwardMsg(m); });
            if (mine && "text".equals(m.type)) {
                addMenuItem(v, R.drawable.ic_edit, "Редактировать", () -> { pw.dismiss(); setEdit(m); });
            }
            addMenuItem(v, R.drawable.ic_delete, "Удалить", () -> { pw.dismiss(); showDeleteDialog(m); });
            pw.setWidth(dp(200));
            pw.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            pw.showAsDropDown(anchor, dp(-120), 0);
        } catch (Throwable ignored) {}
    }

    void toggleReaction(Msg m) {
        try {
            String me = Fb.myUid();
            boolean has = m.reactions != null && m.reactions.has(me);
            if (has) {
                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs/" + m.key + "/r/" + me)
                        .removeValue();
            } else {
                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs/" + m.key + "/r/" + me)
                        .setValue(1);
            }
        } catch (Throwable ignored) {}
    }

    void showDeleteDialog(final Msg m) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Удалить сообщение?")
                    .setItems(new String[]{"Удалить у обоих", "Удалить у меня"}, (d, which) -> {
                        try {
                            if (which == 0) {
                                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs/" + m.key)
                                        .removeValue();
                                // refresh last meta
                                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs")
                                        .orderByChild("t").limitToLast(1).get()
                                        .addOnCompleteListener(task -> {
                                            try {
                                                if (task.getResult() == null) return;
                                                JSONObject lm = new JSONObject();
                                                lm.put("ts", 0);
                                                lm.put("ty", "none");
                                                lm.put("by", Fb.myUid());
                                                lm.put("k", "");
                                                for (DataSnapshot ms : task.getResult().getChildren()) {
                                                    lm.put("ts", ms.child("t").getValue(Long.class));
                                                    lm.put("ty", ms.child("ty").getValue(String.class));
                                                    lm.put("by", ms.child("f").getValue(String.class));
                                                    lm.put("k", ms.getKey());
                                                }
                                                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/last")
                                                        .setValue(lm);
                                            } catch (Throwable ignored) {}
                                        });
                            } else {
                                FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs/" + m.key + "/hb/" + Fb.myUid())
                                        .setValue(true);
                            }
                        } catch (Throwable ignored) {}
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    // ---------------- scroll helpers ----------------
    private boolean isNearBottom() {
        try {
            LinearLayoutManager lm = (LinearLayoutManager) recycler.getLayoutManager();
            if (lm == null) return true;
            int last = lm.findLastVisibleItemPosition();
            return last >= rows.size() - 3;
        } catch (Throwable t) {
            return true;
        }
    }

    private void scrollToEnd() {
        try {
            if (rows.isEmpty()) return;
            recycler.post(() -> recycler.scrollToPosition(rows.size() - 1));
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lastInst == this) lastInst = null;
    }
}
