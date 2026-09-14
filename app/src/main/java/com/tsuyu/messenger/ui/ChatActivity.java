package com.tsuyu.messenger.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.media.AudioPlayer;
import com.tsuyu.messenger.media.MediaCodecUtil;
import com.tsuyu.messenger.media.VoiceRecorder;
import com.tsuyu.messenger.media.WaveformView;
import com.tsuyu.messenger.util.Fmt;
import com.tsuyu.messenger.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity implements MessageAdapter.Callbacks {

    private Repo repo;
    private Prefs prefs;
    private String me, peerUid;
    private Models.User peer;

    private RecyclerView list;
    private MessageAdapter adapter;
    private EditText input;
    private ImageView btnSend, btnCircle, headerAvatar, scrollFab;
    private TextView headerName, headerStatus, typingText;
    private LinearLayout typingBar, replyBar, recordingBar, inputRow;
    private TextView replyTitle, replyText, recTimer, recCancel;
    private WaveformView recWave;
    private RecyclerView attachStrip;

    private final Map<String, Models.Message> messages = new LinkedHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String replyToId, replyToName, replyToPreview;
    private String editingId;

    private final List<PendingAttachment> pending = new ArrayList<>();
    private AttachAdapter attachAdapter;

    private final VoiceRecorder recorder = new VoiceRecorder();
    private final List<Integer> liveWave = new ArrayList<>();

    private ActivityResultLauncher<Intent> mediaPicker, audioPicker;
    private ActivityResultLauncher<Intent> circleRecorder;

    static class PendingAttachment {
        String type;
        MediaCodecUtil.Encoded data;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (prefs.secureScreen()) {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_chat);

        repo = Repo.get(this);
        me = repo.uid();
        peerUid = getIntent().getStringExtra("peerUid");
        if (me == null || peerUid == null) { finish(); return; }

        bindViews();
        setupList();
        setupInput();
        setupPickers();

        loadPeer();
        watchPresence();
        watchTyping();
        watchMessages();
    }

    private void bindViews() {
        list = findViewById(R.id.messagesList);
        input = findViewById(R.id.messageInput);
        btnSend = findViewById(R.id.btnSend);
        btnCircle = findViewById(R.id.btnCircle);
        headerAvatar = findViewById(R.id.headerAvatar);
        headerName = findViewById(R.id.headerName);
        headerStatus = findViewById(R.id.headerStatus);
        typingBar = findViewById(R.id.typingBar);
        typingText = findViewById(R.id.typingText);
        replyBar = findViewById(R.id.replyBar);
        replyTitle = findViewById(R.id.replyTitle);
        replyText = findViewById(R.id.replyText);
        recordingBar = findViewById(R.id.recordingBar);
        recTimer = findViewById(R.id.recTimer);
        recCancel = findViewById(R.id.recCancel);
        recWave = findViewById(R.id.recWave);
        inputRow = findViewById(R.id.inputRow);
        attachStrip = findViewById(R.id.attachStrip);
        scrollFab = findViewById(R.id.scrollFab);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.replyClose).setOnClickListener(v -> clearReply());
        findViewById(R.id.btnMenu).setOnClickListener(this::showChatMenu);
        findViewById(R.id.headerInfo).setOnClickListener(v -> openPeerProfile());
        headerAvatar.setOnClickListener(v -> openPeerProfile());

        findViewById(R.id.btnCall).setOnClickListener(v -> startCall(false));
        findViewById(R.id.btnVideoCall).setOnClickListener(v -> startCall(true));

        findViewById(R.id.btnAttach).setOnClickListener(v -> showAttachSheet());
        scrollFab.setOnClickListener(v -> scrollToBottom(true));

        attachAdapter = new AttachAdapter();
        attachStrip.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        attachStrip.setAdapter(attachAdapter);
    }

    private void setupList() {
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        adapter = new MessageAdapter(this, this);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                boolean atBottom = !rv.canScrollVertically(1);
                scrollFab.setVisibility(atBottom ? View.GONE : View.VISIBLE);
            }
        });

        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback swipeCallback =
                new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0,
                        androidx.recyclerview.widget.ItemTouchHelper.LEFT | androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
                    @Override
                    public boolean onMove(@NonNull RecyclerView recyclerView,
                                          @NonNull RecyclerView.ViewHolder viewHolder,
                                          @NonNull RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                        int pos = viewHolder.getAdapterPosition();
                        if (pos >= 0 && pos < adapter.items().size()) {
                            Models.Message m = adapter.items().get(pos);
                            if (m != null && !m.deleted) {
                                viewHolder.itemView.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                                setReply(m);
                            }
                        }
                        adapter.notifyItemChanged(pos);
                    }

                    @Override
                    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
                        return 0.25f;
                    }

                    @Override
                    public float getSwipeEscapeVelocity(float defaultValue) {
                        return defaultValue * 2;
                    }
                };
        new androidx.recyclerview.widget.ItemTouchHelper(swipeCallback).attachToRecyclerView(list);
    }

    // ------------------------------------------------------------------
    // Input & typing
    // ------------------------------------------------------------------

    private long lastTypingSent = 0;

    private void setupInput() {
        updateSendIcon();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                updateSendIcon();
                // "1 letter = 1 second" realtime typing signal
                long now = System.currentTimeMillis();
                if (e.length() > 0 && now - lastTypingSent > 900) {
                    lastTypingSent = now;
                    repo.setTyping(peerUid, true);
                    ui.removeCallbacks(stopTyping);
                    ui.postDelayed(stopTyping, 3000);
                } else if (e.length() == 0) {
                    repo.setTyping(peerUid, false);
                }
            }
        });

        btnSend.setOnClickListener(v -> {
            Ui.tapScale(v);
            if (hasContent()) send();
            else toggleVoiceRecording();
        });

        btnCircle.setOnClickListener(v -> {
            if (!ensurePermissions()) return;
            circleRecorder.launch(new Intent(this, CircleRecordActivity.class));
        });

        recCancel.setOnClickListener(v -> {
            recorder.cancel();
            showRecordingUi(false);
        });
    }

    private final Runnable stopTyping = () -> repo.setTyping(peerUid, false);

    private boolean hasContent() {
        return !input.getText().toString().trim().isEmpty() || !pending.isEmpty();
    }

    private void updateSendIcon() {
        btnSend.setImageResource(hasContent() ? R.drawable.ic_send : R.drawable.ic_mic);
    }

    // ------------------------------------------------------------------
    // Peer info
    // ------------------------------------------------------------------

    private void loadPeer() {
        repo.userRef(peerUid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                peer = Repo.parseUser(s);
                headerName.setText(peer.name);
                Ui.setAvatar(headerAvatar, peer.avatar, peer.uid, peer.name);
                adapter.setPeerInfo(peerUid, peer.name, peer.avatar);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
        repo.userRef(me).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Models.User u = Repo.parseUser(s);
                adapter.setPeerInfo(me, u.name, u.avatar);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private boolean peerOnline;
    private long peerLastSeen;
    private boolean peerHidesLastSeen;

    private void watchPresence() {
        repo.presenceRef(peerUid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Object on = s.child("online").getValue();
                Object ls = s.child("lastSeen").getValue();
                peerOnline = Boolean.TRUE.equals(on);
                peerLastSeen = ls instanceof Number ? ((Number) ls).longValue() : 0;
                peerHidesLastSeen = peer != null && "none".equals(peer.pLastSeen);
                renderStatus();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private boolean peerTyping;

    private void watchTyping() {
        repo.typingRef(me).child(peerUid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Object v = s.getValue();
                long ts = v instanceof Number ? ((Number) v).longValue() : 0;
                peerTyping = System.currentTimeMillis() - ts < 4000;
                renderStatus();
                if (peerTyping) ui.postDelayed(() -> { peerTyping = false; renderStatus(); }, 4000);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private void renderStatus() {
        if (peerTyping) {
            headerStatus.setText(prefs.wordTyping() + "…");
            headerStatus.setTextColor(ContextCompat.getColor(this, R.color.accent));
            typingBar.setVisibility(View.VISIBLE);
            typingText.setText(prefs.wordTyping() + "…");
        } else {
            headerStatus.setText(Fmt.lastSeen(this, peerOnline, peerLastSeen, peerHidesLastSeen));
            headerStatus.setTextColor(ContextCompat.getColor(this,
                    peerOnline ? R.color.green : R.color.text_secondary));
            typingBar.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // Messages stream
    // ------------------------------------------------------------------

    private void watchMessages() {
        repo.chatQuery(me, peerUid, 300).addChildEventListener(new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String p) { decode(s, true); }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) { decode(s, false); }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                messages.remove(s.getKey());
                render(false);
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) { }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private void decode(DataSnapshot s, boolean isNew) {
        if (s.child("hiddenFor").child(me).exists()) {
            messages.remove(s.getKey());
            render(false);
            return;
        }
        new Thread(() -> {
            Models.Message m = repo.decodeMessage(s, me);
            runOnUiThread(() -> {
                messages.put(m.id, m);
                render(isNew);
                if (!m.outgoing && !m.read) repo.markRead(peerUid, m.id);
            });
        }).start();
    }

    private void render(boolean scroll) {
        List<Models.Message> out = new ArrayList<>(messages.values());
        Collections.sort(out, (a, b) -> Long.compare(a.ts, b.ts));
        adapter.submit(out);
        if (scroll) scrollToBottom(false);
    }

    private void scrollToBottom(boolean smooth) {
        if (adapter.getItemCount() == 0) return;
        if (smooth) list.smoothScrollToPosition(adapter.getItemCount() - 1);
        else list.scrollToPosition(adapter.getItemCount() - 1);
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private void send() {
        if (peer == null) { toast("Профиль собеседника ещё загружается"); return; }
        String text = input.getText().toString().trim();

        if (editingId != null) {
            JSONObject p = new JSONObject();
            try {
                p.put("type", Models.T_TEXT);
                p.put("text", text);
            } catch (Exception ignored) { }
            repo.editMessage(peer, editingId, p);
            editingId = null;
            input.setText("");
            clearReply();
            return;
        }

        List<PendingAttachment> atts = new ArrayList<>(pending);
        pending.clear();
        attachAdapter.notifyDataSetChanged();
        attachStrip.setVisibility(View.GONE);
        input.setText("");
        repo.setTyping(peerUid, false);

        JSONObject payload = new JSONObject();
        try {
            payload.put("text", text);
            if (atts.isEmpty()) {
                payload.put("type", Models.T_TEXT);
            } else {
                payload.put("type", atts.size() > 1 ? Models.T_ALBUM : atts.get(0).type);
                JSONArray arr = new JSONArray();
                for (PendingAttachment pa : atts) arr.put(attachmentJson(pa));
                payload.put("att", arr);
            }
            if (replyToId != null) {
                payload.put("replyTo", replyToId);
                payload.put("replyName", replyToName);
                payload.put("replyPreview", replyToPreview);
            }
        } catch (Exception e) {
            toast("Ошибка формирования сообщения");
            return;
        }
        clearReply();

        repo.sendMessage(peer, payload, (ok, id) -> {
            if (!ok) runOnUiThread(() -> toast("Не удалось отправить"));
        });
    }

    private JSONObject attachmentJson(PendingAttachment pa) throws Exception {
        JSONObject a = new JSONObject();
        a.put("t", pa.type);
        a.put("d", pa.data.base64);
        if (pa.data.thumbBase64 != null) a.put("th", pa.data.thumbBase64);
        a.put("w", pa.data.width);
        a.put("h", pa.data.height);
        a.put("dur", pa.data.durationMs);
        if (pa.data.fileName != null) a.put("fn", pa.data.fileName);
        return a;
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    private void setupPickers() {
        mediaPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), res -> {
                    if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                    Intent data = res.getData();
                    List<Uri> uris = new ArrayList<>();
                    if (data.getClipData() != null) {
                        for (int i = 0; i < data.getClipData().getItemCount(); i++)
                            uris.add(data.getClipData().getItemAt(i).getUri());
                    } else if (data.getData() != null) {
                        uris.add(data.getData());
                    }
                    for (Uri u : uris) addMedia(u);
                });

        audioPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), res -> {
                    if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                    Uri u = res.getData().getData();
                    if (u != null) addAudio(u);
                });

        circleRecorder = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), res -> {
                    if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                    String path = res.getData().getStringExtra("path");
                    long dur = res.getData().getLongExtra("duration", 0);
                    String thumb = res.getData().getStringExtra("thumb");
                    if (path != null) sendCircle(new File(path), dur, thumb);
                });
    }

    private void openCircle() {
        if (!ensurePermissions()) return;
        circleRecorder.launch(new Intent(this, CircleRecordActivity.class));
    }

    private void showAttachSheet() {
        String[] options = {
                "📷  Фото и Галерея",
                "🎥  Видеозапись",
                "⭕  Видеосообщение (кружочек)",
                "🎵  Музыка и Аудио",
                "📄  Документ / Файл"
        };
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Вложения")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.setType("image/*");
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        mediaPicker.launch(i);
                    } else if (which == 1) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.setType("video/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        mediaPicker.launch(i);
                    } else if (which == 2) {
                        openCircle();
                    } else if (which == 3) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.setType("audio/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        audioPicker.launch(i);
                    } else {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.setType("*/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        audioPicker.launch(i);
                    }
                })
                .show();
    }

    private void addMedia(Uri uri) {
        toast("Обработка…");
        new Thread(() -> {
            try {
                String mime = getContentResolver().getType(uri);
                boolean video = mime != null && mime.startsWith("video");
                PendingAttachment pa = new PendingAttachment();
                pa.type = video ? Models.T_VIDEO : Models.T_PHOTO;
                pa.data = video ? MediaCodecUtil.encodeVideo(this, uri)
                        : MediaCodecUtil.encodeImage(this, uri, 1280, 78);
                runOnUiThread(() -> {
                    pending.add(pa);
                    attachStrip.setVisibility(View.VISIBLE);
                    attachAdapter.notifyDataSetChanged();
                    updateSendIcon();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(e.getMessage() == null ? "Ошибка файла" : e.getMessage()));
            }
        }).start();
    }

    private void addAudio(Uri uri) {
        new Thread(() -> {
            try {
                PendingAttachment pa = new PendingAttachment();
                pa.type = Models.T_AUDIO;
                pa.data = MediaCodecUtil.encodeFile(this, uri);
                runOnUiThread(() -> {
                    pending.add(pa);
                    attachStrip.setVisibility(View.VISIBLE);
                    attachAdapter.notifyDataSetChanged();
                    updateSendIcon();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Не удалось добавить аудио"));
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Voice recording
    // ------------------------------------------------------------------

    private boolean ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), 42);
            return false;
        }
        return true;
    }

    private void toggleVoiceRecording() {
        if (recorder.isRecording()) {
            VoiceRecorder.Result r = recorder.stop();
            showRecordingUi(false);
            if (r != null) sendVoice(r);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 43);
            return;
        }
        try {
            liveWave.clear();
            recorder.start(this, (level, elapsed) -> {
                liveWave.add(level);
                int[] bars = VoiceRecorder.downsample(liveWave, 40);
                recWave.setBars(bars);
                recTimer.setText(Fmt.duration(elapsed));
            });
            showRecordingUi(true);
        } catch (Exception e) {
            toast("Не удалось начать запись");
        }
    }

    private void showRecordingUi(boolean on) {
        recordingBar.setVisibility(on ? View.VISIBLE : View.GONE);
        inputRow.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) {
            recTimer.setText("0:00");
            Ui.fadeIn(recordingBar);
        }
    }

    private void sendVoice(VoiceRecorder.Result r) {
        new Thread(() -> {
            try {
                byte[] raw = readFile(r.file);
                JSONObject a = new JSONObject();
                a.put("t", Models.T_VOICE);
                a.put("d", com.tsuyu.messenger.crypto.CryptoUtil.b64(raw));
                a.put("dur", r.durationMs);
                JSONArray wf = new JSONArray();
                for (int x : r.waveform) wf.put(x);
                a.put("wf", wf);

                JSONObject payload = new JSONObject();
                payload.put("type", Models.T_VOICE);
                payload.put("text", "");
                payload.put("att", new JSONArray().put(a));
                repo.sendMessage(peer, payload, null);
                r.file.delete();
            } catch (Exception e) {
                runOnUiThread(() -> toast("Ошибка отправки голосового"));
            }
        }).start();
    }

    private void sendCircle(File f, long duration, String thumb) {
        new Thread(() -> {
            try {
                byte[] raw = readFile(f);
                JSONObject a = new JSONObject();
                a.put("t", Models.T_CIRCLE);
                a.put("d", com.tsuyu.messenger.crypto.CryptoUtil.b64(raw));
                a.put("dur", duration);
                if (thumb != null) a.put("th", thumb);

                JSONObject payload = new JSONObject();
                payload.put("type", Models.T_CIRCLE);
                payload.put("text", "");
                payload.put("att", new JSONArray().put(a));
                repo.sendMessage(peer, payload, null);
                f.delete();
            } catch (Exception e) {
                runOnUiThread(() -> toast("Ошибка отправки кружочка"));
            }
        }).start();
    }

    private byte[] readFile(File f) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // Message menu / reactions
    // ------------------------------------------------------------------

    @Override
    public void onMenu(Models.Message m, View anchor) {
        View v = LayoutInflater.from(this).inflate(R.layout.popup_message_menu, null);
        PopupWindow pw = new PopupWindow(v, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setElevation(18f);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));

        View rHeart = v.findViewById(R.id.reactHeart);
        if (rHeart != null) rHeart.setOnClickListener(x -> {
            toggleHeart(m);
            pw.dismiss();
        });
        View rUp = v.findViewById(R.id.reactThumbsUp);
        if (rUp != null) rUp.setOnClickListener(x -> {
            toggleHeart(m);
            pw.dismiss();
        });
        View rLaugh = v.findViewById(R.id.reactLaugh);
        if (rLaugh != null) rLaugh.setOnClickListener(x -> {
            toggleHeart(m);
            pw.dismiss();
        });
        View rFire = v.findViewById(R.id.reactFire);
        if (rFire != null) rFire.setOnClickListener(x -> {
            toggleHeart(m);
            pw.dismiss();
        });
        View rSad = v.findViewById(R.id.reactSad);
        if (rSad != null) rSad.setOnClickListener(x -> {
            toggleHeart(m);
            pw.dismiss();
        });
        View rSurprise = v.findViewById(R.id.reactSurprise);
        if (rSurprise != null) rSurprise.setOnClickListener(x -> {
            toggleHeart(m);
            pw.dismiss();
        });

        v.findViewById(R.id.menuReply).setOnClickListener(x -> {
            setReply(m);
            pw.dismiss();
        });

        View copyBtn = v.findViewById(R.id.menuCopy);
        if (copyBtn != null) {
            copyBtn.setVisibility(m.text != null && !m.text.isEmpty() ? View.VISIBLE : View.GONE);
            copyBtn.setOnClickListener(x -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && m.text != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text));
                    Toast.makeText(this, "Текст скопирован", Toast.LENGTH_SHORT).show();
                }
                pw.dismiss();
            });
        }

        View editBtn = v.findViewById(R.id.menuEdit);
        if (editBtn != null) {
            editBtn.setVisibility(m.outgoing && m.attachments.isEmpty() ? View.VISIBLE : View.GONE);
            editBtn.setOnClickListener(x -> {
                editingId = m.id;
                input.setText(m.text);
                input.setSelection(input.getText().length());
                pw.dismiss();
            });
        }

        View pinBtn = v.findViewById(R.id.menuPin);
        if (pinBtn != null) {
            pinBtn.setOnClickListener(x -> {
                Toast.makeText(this, "Сообщение закреплено", Toast.LENGTH_SHORT).show();
                pw.dismiss();
            });
        }

        v.findViewById(R.id.menuForward).setOnClickListener(x -> {
            forward(m);
            pw.dismiss();
        });
        v.findViewById(R.id.menuDelete).setOnClickListener(x -> {
            confirmDelete(m);
            pw.dismiss();
        });

        v.setAlpha(0f);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        pw.showAsDropDown(anchor, 0, 0, Gravity.START);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
    }

    private void toggleHeart(Models.Message m) {
        boolean on = !m.hearts.contains(me);
        repo.toggleHeart(peerUid, m.id, on);
    }

    @Override
    public void onDoubleTapHeart(Models.Message m) { toggleHeart(m); }

    @Override
    public void onOpenMedia(Models.Message m, int index) {
        Intent i = new Intent(this, MediaViewActivity.class);
        i.putExtra("messageId", m.id);
        i.putExtra("index", index);
        i.putExtra("peerUid", peerUid);
        MediaViewActivity.payload = m;
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onReplyClick(String messageId) {
        List<Models.Message> all = adapter.items();
        for (int i = 0; i < all.size(); i++) {
            if (messageId.equals(all.get(i).id)) {
                list.smoothScrollToPosition(i);
                return;
            }
        }
    }

    private void setReply(Models.Message m) {
        replyToId = m.id;
        replyToName = m.outgoing ? "Вы" : (peer == null ? "Собеседник" : peer.name);
        replyToPreview = previewOf(m);
        replyBar.setVisibility(View.VISIBLE);
        replyTitle.setText(replyToName);
        replyText.setText(replyToPreview);
        Ui.fadeIn(replyBar);
        input.requestFocus();
    }

    private String previewOf(Models.Message m) {
        if (!m.attachments.isEmpty()) {
            switch (m.attachments.get(0).type) {
                case Models.T_PHOTO: return "Фото";
                case Models.T_VIDEO: return "Видео";
                case Models.T_VOICE: return "Голосовое сообщение";
                case Models.T_CIRCLE: return "Видеосообщение";
                case Models.T_AUDIO: return "Аудиофайл";
            }
        }
        return m.text == null ? "" : m.text;
    }

    private void clearReply() {
        replyToId = null;
        replyBar.setVisibility(View.GONE);
    }

    private void confirmDelete(Models.Message m) {
        if (!m.outgoing) {
            repo.deleteMessage(peerUid, m.id, false);
            return;
        }
        final boolean[] both = {true};
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Удалить сообщение?")
                .setMultiChoiceItems(new String[]{"Удалить у обоих"}, new boolean[]{true},
                        (d, which, checked) -> both[0] = checked)
                .setPositiveButton("Удалить", (d, w) ->
                        repo.deleteMessage(peerUid, m.id, both[0]))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void forward(Models.Message m) {
        toast("Выберите чат для пересылки");
        Intent i = new Intent(this, ForwardActivity.class);
        ForwardActivity.payload = m;
        startActivity(i);
    }

    // ------------------------------------------------------------------
    // Chat menu (3 dots)
    // ------------------------------------------------------------------

    private void showChatMenu(View anchor) {
        String[] opts = {"Профиль", "Скачать переписку", "Очистить историю"};
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setItems(opts, (d, which) -> {
                    if (which == 0) openPeerProfile();
                    else if (which == 1) ExportDialog.show(this, peer, adapter.items());
                    else clearHistory();
                })
                .show();
    }

    private void clearHistory() {
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Очистить историю?")
                .setMessage("Сообщения будут удалены у вас.")
                .setPositiveButton("Очистить", (d, w) -> {
                    for (Models.Message m : new ArrayList<>(messages.values()))
                        repo.deleteMessage(peerUid, m.id, false);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void openPeerProfile() {
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", peerUid);
        startActivity(i);
    }

    private void startCall(boolean video) {
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra("peerUid", peerUid);
        i.putExtra("video", video);
        i.putExtra("outgoing", true);
        startActivity(i);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onResume() {
        super.onResume();
        repo.goOnline();
        ChatPresence.activePeer = peerUid;
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatPresence.activePeer = null;
        repo.setTyping(peerUid, false);
        AudioPlayer.get().stop();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    // ---- attachment strip adapter ----

    class AttachAdapter extends RecyclerView.Adapter<AttachAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(ChatActivity.this)
                    .inflate(R.layout.item_attach_preview, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            PendingAttachment pa = pending.get(pos);
            String src = pa.data.thumbBase64 != null ? pa.data.thumbBase64 : pa.data.base64;
            if (Models.T_AUDIO.equals(pa.type)) {
                h.thumb.setImageResource(R.drawable.ic_music);
            } else {
                android.graphics.Bitmap b = Ui.decodeB64(src);
                if (b != null) h.thumb.setImageBitmap(b);
            }
            h.remove.setOnClickListener(v -> {
                pending.remove(pos);
                notifyDataSetChanged();
                if (pending.isEmpty()) attachStrip.setVisibility(View.GONE);
                updateSendIcon();
            });
        }

        @Override public int getItemCount() { return pending.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView thumb, remove;
            VH(@NonNull View v) {
                super(v);
                thumb = v.findViewById(R.id.thumb);
                remove = v.findViewById(R.id.remove);
            }
        }
    }
}
