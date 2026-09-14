package com.tsuyu.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Prefs;
import com.tsuyu.messenger.data.Repo;
import com.tsuyu.messenger.service.TsuyuService;
import com.tsuyu.messenger.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private Repo repo;
    private Prefs prefs;
    private String me;

    private RecyclerView list;
    private EditText searchInput;
    private ImageView btnClearSearch, btnGhost, btnProfile;
    private LinearLayout emptyState;

    private DialogAdapter dialogAdapter;
    private SearchAdapter searchAdapter;

    private final Map<String, Models.Dialog> dialogs = new LinkedHashMap<>();
    private final Map<String, ValueEventListener> presenceListeners = new HashMap<>();
    private final Map<String, DatabaseReference> presenceRefs = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ChildEventListener chatsListener;
    private DatabaseReference chatsRef;
    private ValueEventListener typingListener;
    private DatabaseReference typingRef;

    private boolean searching = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repo = Repo.get(this);
        prefs = new Prefs(this);
        me = repo.uid();
        if (me == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }

        list = findViewById(R.id.dialogsList);
        searchInput = findViewById(R.id.searchInput);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        btnGhost = findViewById(R.id.btnGhost);
        btnProfile = findViewById(R.id.btnProfile);
        emptyState = findViewById(R.id.emptyState);

        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        dialogAdapter = new DialogAdapter(this, new ArrayList<>(), this::openChat);
        searchAdapter = new SearchAdapter(this, new ArrayList<>(), this::openChatWithUser);
        list.setAdapter(dialogAdapter);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnProfile.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));

        updateGhostIcon();
        btnGhost.setOnClickListener(v -> {
            prefs.setGhost(!prefs.ghost());
            updateGhostIcon();
            Ui.tapScale(btnGhost);
            repo.goOnline();
        });

        btnClearSearch.setOnClickListener(v -> searchInput.setText(""));
        setupSearch();

        loadMyAvatar();
        attachChats();
        attachTyping();

        TsuyuService.start(this);
    }

    private void updateGhostIcon() {
        boolean g = prefs.ghost();
        btnGhost.setColorFilter(ContextCompat.getColor(this,
                g ? R.color.accent : R.color.text_secondary));
        btnGhost.setBackgroundResource(R.drawable.bg_circle_btn);
    }

    private void loadMyAvatar() {
        repo.userRef(me).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Models.User u = Repo.parseUser(s);
                Ui.setAvatar(btnProfile, u.avatar, u.uid, u.name);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    // ----------------- search -----------------

    private Runnable searchTask;

    private void setupSearch() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim();
                btnClearSearch.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                if (searchTask != null) ui.removeCallbacks(searchTask);
                if (q.isEmpty()) {
                    searching = false;
                    list.setAdapter(dialogAdapter);
                    Ui.fadeIn(list);
                    refreshEmpty();
                    return;
                }
                // realtime search, debounced 180ms
                searchTask = () -> repo.searchByUsername(q, users -> runOnUiThread(() -> {
                    searching = true;
                    if (list.getAdapter() != searchAdapter) {
                        list.setAdapter(searchAdapter);
                        Ui.fadeIn(list);
                    }
                    searchAdapter.submit(users);
                    emptyState.setVisibility(View.GONE);
                }));
                ui.postDelayed(searchTask, 180);
            }
        });
    }

    // ----------------- dialogs -----------------

    private void attachChats() {
        chatsRef = repo.db().getReference("chats");
        chatsListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String prev) { handle(s); }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String prev) { handle(s); }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                String peer = peerOf(s.getKey());
                if (peer != null) { dialogs.remove(peer); publish(); }
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String prev) { }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        // Only chats that contain our uid are relevant; RTDB has no "contains" query,
        // so we track our own index of conversations instead.
        repo.db().getReference("userChats").child(me)
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(@NonNull DataSnapshot s, String p) { watchChat(s.getKey()); }
                    @Override public void onChildChanged(@NonNull DataSnapshot s, String p) { watchChat(s.getKey()); }
                    @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                        dialogs.remove(s.getKey());
                        publish();
                    }
                    @Override public void onChildMoved(@NonNull DataSnapshot s, String p) { }
                    @Override public void onCancelled(@NonNull DatabaseError e) { }
                });
    }

    private final Map<String, ChildEventListener> chatWatchers = new HashMap<>();

    private void watchChat(String peerUid) {
        if (peerUid == null || chatWatchers.containsKey(peerUid)) return;
        ensureDialog(peerUid);
        ChildEventListener l = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String p) { onMsg(peerUid, s); }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) { onMsg(peerUid, s); }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) { reloadLast(peerUid); }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) { }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        chatWatchers.put(peerUid, l);
        repo.chatRef(me, peerUid).orderByChild("ts").limitToLast(1).addChildEventListener(l);
    }

    private void onMsg(String peerUid, DataSnapshot s) {
        new Thread(() -> {
            Models.Message m = repo.decodeMessage(s, me);
            runOnUiThread(() -> {
                Models.Dialog d = ensureDialog(peerUid);
                if (d.last == null || m.ts >= d.last.ts) d.last = m;
                publish();
            });
        }).start();
    }

    private void reloadLast(String peerUid) {
        repo.chatQuery(me, peerUid, 1).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                Models.Dialog d = ensureDialog(peerUid);
                d.last = null;
                for (DataSnapshot c : snapshot.getChildren()) d.last = repo.decodeMessage(c, me);
                publish();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        });
    }

    private Models.Dialog ensureDialog(String peerUid) {
        Models.Dialog d = dialogs.get(peerUid);
        if (d != null) return d;
        d = new Models.Dialog();
        d.peerUid = peerUid;
        dialogs.put(peerUid, d);

        repo.userRef(peerUid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Models.Dialog dd = dialogs.get(peerUid);
                if (dd == null) return;
                dd.peer = Repo.parseUser(s);
                publish();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });

        watchPresence(peerUid);
        return d;
    }

    private void watchPresence(String peerUid) {
        if (presenceListeners.containsKey(peerUid)) return;
        DatabaseReference ref = repo.presenceRef(peerUid);
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Models.Dialog d = dialogs.get(peerUid);
                if (d == null || d.peer == null) return;
                Object on = s.child("online").getValue();
                Object ls = s.child("lastSeen").getValue();
                d.peer.online = Boolean.TRUE.equals(on);
                d.peer.lastSeen = ls instanceof Number ? ((Number) ls).longValue() : 0;
                d.peer.ghost = Boolean.TRUE.equals(s.child("ghost").getValue());
                publish();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        ref.addValueEventListener(l);
        presenceListeners.put(peerUid, l);
        presenceRefs.put(peerUid, ref);
    }

    private void attachTyping() {
        typingRef = repo.typingRef(me);
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                long now = System.currentTimeMillis();
                for (Models.Dialog d : dialogs.values()) d.typing = false;
                for (DataSnapshot c : s.getChildren()) {
                    Object v = c.getValue();
                    long ts = v instanceof Number ? ((Number) v).longValue() : 0;
                    Models.Dialog d = dialogs.get(c.getKey());
                    if (d != null && now - ts < 4000) d.typing = true;
                }
                publish();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        typingRef.addValueEventListener(typingListener);
        // typing flags expire on their own; re-render periodically
        ui.postDelayed(new Runnable() {
            @Override public void run() { publish(); ui.postDelayed(this, 2000); }
        }, 2000);
    }

    private String peerOf(String chatKey) {
        if (chatKey == null || !chatKey.contains("_")) return null;
        String[] parts = chatKey.split("_");
        if (parts.length != 2) return null;
        if (parts[0].equals(me)) return parts[1];
        if (parts[1].equals(me)) return parts[0];
        return null;
    }

    private void handle(DataSnapshot s) { /* handled through userChats index */ }

    private void publish() {
        if (searching) return;
        List<Models.Dialog> out = new ArrayList<>();
        for (Models.Dialog d : dialogs.values()) if (d.peer != null) out.add(d);
        out.sort((a, b) -> Long.compare(b.last == null ? 0 : b.last.ts,
                a.last == null ? 0 : a.last.ts));
        dialogAdapter.submit(out);
        refreshEmpty();
    }

    private void refreshEmpty() {
        boolean empty = !searching && dialogAdapter.getItemCount() == 0;
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void openChat(Models.Dialog d) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("peerUid", d.peerUid);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void openChatWithUser(Models.User u) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("peerUid", u.uid);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    @Override
    protected void onResume() {
        super.onResume();
        repo.goOnline();
        dialogAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!prefs.ghost()) repo.goOffline();
    }
}
