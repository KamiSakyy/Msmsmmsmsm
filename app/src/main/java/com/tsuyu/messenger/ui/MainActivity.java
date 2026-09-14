package com.tsuyu.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
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
import com.tsuyu.messenger.service.TsuyuService;
import com.tsuyu.messenger.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private Repo repo;
    private Prefs prefs;
    private String me;

    private DrawerLayout drawerLayout;
    private RecyclerView list;
    private EditText searchInput;
    private ImageView btnClearSearch, btnGhost, btnProfile, btnDrawerMenu;
    private LinearLayout emptyState;

    private TextView tabAll, tabDirect, tabUnread;
    private int currentTab = 0; // 0=all, 1=direct, 2=unread

    // Drawer Views
    private ImageView drawerAvatar, drawerAvatarEdit;
    private TextView drawerName, drawerUsername, drawerOnlineStatus, drawerBio, drawerCacheText;
    private Switch drawerSwitchStealth, drawerSwitchSound;

    private DialogAdapter dialogAdapter;
    private SearchAdapter searchAdapter;

    private final Map<String, Models.Dialog> dialogs = new LinkedHashMap<>();
    private final Map<String, ValueEventListener> presenceListeners = new HashMap<>();
    private final Map<String, DatabaseReference> presenceRefs = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

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

        drawerLayout = findViewById(R.id.drawerLayout);
        btnDrawerMenu = findViewById(R.id.btnDrawerMenu);
        list = findViewById(R.id.dialogsList);
        searchInput = findViewById(R.id.searchInput);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        btnGhost = findViewById(R.id.btnGhost);
        btnProfile = findViewById(R.id.btnProfile);
        emptyState = findViewById(R.id.emptyState);

        tabAll = findViewById(R.id.tabAll);
        tabDirect = findViewById(R.id.tabDirect);
        tabUnread = findViewById(R.id.tabUnread);

        setupDrawer();
        setupTabs();

        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        dialogAdapter = new DialogAdapter(this, new ArrayList<>(), this::openChat);
        searchAdapter = new SearchAdapter(this, new ArrayList<>(), this::openChatWithUser);
        list.setAdapter(dialogAdapter);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnProfile.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));

        if (btnDrawerMenu != null) {
            btnDrawerMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }

        updateGhostIcon();
        btnGhost.setOnClickListener(v -> {
            prefs.setGhost(!prefs.ghost());
            updateGhostIcon();
            if (drawerSwitchStealth != null) drawerSwitchStealth.setChecked(prefs.ghost());
            Ui.tapScale(btnGhost);
            repo.goOnline();
        });

        btnClearSearch.setOnClickListener(v -> searchInput.setText(""));
        setupSearch();

        loadMyProfile();
        attachChats();
        attachTyping();

        TsuyuService.start(this);
    }

    private void setupTabs() {
        tabAll.setOnClickListener(v -> setTab(0));
        tabDirect.setOnClickListener(v -> setTab(1));
        tabUnread.setOnClickListener(v -> setTab(2));
    }

    private void setTab(int tab) {
        currentTab = tab;
        tabAll.setBackgroundResource(tab == 0 ? R.drawable.bg_chip_active : R.drawable.bg_chip);
        tabAll.setTextColor(ContextCompat.getColor(this, tab == 0 ? R.color.white : R.color.text_secondary));

        tabDirect.setBackgroundResource(tab == 1 ? R.drawable.bg_chip_active : R.drawable.bg_chip);
        tabDirect.setTextColor(ContextCompat.getColor(this, tab == 1 ? R.color.white : R.color.text_secondary));

        tabUnread.setBackgroundResource(tab == 2 ? R.drawable.bg_chip_active : R.drawable.bg_chip);
        tabUnread.setTextColor(ContextCompat.getColor(this, tab == 2 ? R.color.white : R.color.text_secondary));

        publish();
    }

    private void setupDrawer() {
        drawerAvatar = findViewById(R.id.drawerAvatar);
        drawerAvatarEdit = findViewById(R.id.drawerAvatarEdit);
        drawerName = findViewById(R.id.drawerName);
        drawerUsername = findViewById(R.id.drawerUsername);
        drawerOnlineStatus = findViewById(R.id.drawerOnlineStatus);
        drawerBio = findViewById(R.id.drawerBio);
        drawerCacheText = findViewById(R.id.drawerCacheText);
        drawerSwitchStealth = findViewById(R.id.drawerSwitchStealth);
        drawerSwitchSound = findViewById(R.id.drawerSwitchSound);

        if (drawerSwitchStealth != null) {
            drawerSwitchStealth.setChecked(prefs.ghost());
            drawerSwitchStealth.setOnCheckedChangeListener((b, checked) -> {
                prefs.setGhost(checked);
                updateGhostIcon();
                repo.goOnline();
            });
        }

        if (drawerSwitchSound != null) {
            drawerSwitchSound.setChecked(prefs.notificationsEnabled());
            drawerSwitchSound.setOnCheckedChangeListener((b, checked) -> {
                prefs.setNotificationsEnabled(checked);
                TsuyuApp.get().createChannels();
            });
        }

        findViewById(R.id.drawerRowStealth).setOnClickListener(v -> {
            if (drawerSwitchStealth != null) drawerSwitchStealth.toggle();
        });
        findViewById(R.id.drawerRowSound).setOnClickListener(v -> {
            if (drawerSwitchSound != null) drawerSwitchSound.toggle();
        });

        findViewById(R.id.drawerRowKeys).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, KeysActivity.class));
        });

        findViewById(R.id.drawerRowProfile).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, ProfileActivity.class));
        });

        findViewById(R.id.drawerRowCustomization).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, CustomizationActivity.class));
        });

        findViewById(R.id.drawerRowPrivacy).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, PrivacyActivity.class));
        });

        if (drawerAvatarEdit != null) {
            drawerAvatarEdit.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                startActivity(new Intent(this, ProfileActivity.class));
            });
        }

        if (drawerBio != null) {
            drawerBio.setOnClickListener(v -> showQuickEditBioDialog());
        }

        findViewById(R.id.drawerRowCache).setOnClickListener(v -> clearCachePrompt());
        findViewById(R.id.drawerRowLogout).setOnClickListener(v -> logoutPrompt());

        calcDrawerCache();
    }

    private void calcDrawerCache() {
        new Thread(() -> {
            long size = getDirSize(getCacheDir());
            if (getExternalCacheDir() != null) size += getDirSize(getExternalCacheDir());
            double mb = size / (1024.0 * 1024.0);
            String text = String.format(Locale.US, "Очистить кэш (%.1f MB)", mb);
            runOnUiThread(() -> {
                if (drawerCacheText != null) drawerCacheText.setText(text);
            });
        }).start();
    }

    private long getDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) total += getDirSize(f);
                else total += f.length();
            }
        }
        return total;
    }

    private void showQuickEditBioDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        EditText editName = new EditText(this);
        editName.setHint("Имя");
        editName.setTextColor(0xFFFFFFFF);
        editName.setHintTextColor(0xFF888888);
        editName.setBackgroundResource(R.drawable.bg_input);
        editName.setPadding(36, 32, 36, 32);
        if (drawerName != null && drawerName.getText() != null) {
            editName.setText(drawerName.getText().toString());
        }
        layout.addView(editName);

        android.view.View spacer = new android.view.View(this);
        spacer.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 24));
        layout.addView(spacer);

        EditText editBio = new EditText(this);
        editBio.setHint("Описание (статус)");
        editBio.setTextColor(0xFFFFFFFF);
        editBio.setHintTextColor(0xFF888888);
        editBio.setBackgroundResource(R.drawable.bg_input);
        editBio.setPadding(36, 32, 36, 32);
        if (drawerBio != null && drawerBio.getText() != null) {
            String currentBio = drawerBio.getText().toString();
            if (!currentBio.startsWith("Нажмите")) {
                editBio.setText(currentBio);
            }
        }
        layout.addView(editBio);

        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Редактирование профиля")
                .setView(layout)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String newName = editName.getText().toString().trim();
                    String newBio = editBio.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        repo.updateProfileField("name", newName);
                        if (drawerName != null) drawerName.setText(newName);
                    }
                    repo.updateProfileField("bio", newBio);
                    if (drawerBio != null) {
                        drawerBio.setText(newBio.isEmpty() ? "Нажмите, чтобы изменить описание..." : newBio);
                    }
                    Toast.makeText(this, "Профиль обновлен", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void clearCachePrompt() {
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Очистить кэш?")
                .setMessage("Будут удалены временные файлы и превью сообщений.")
                .setPositiveButton("Очистить", (d, w) -> new Thread(() -> {
                    deleteDir(getCacheDir());
                    if (getExternalCacheDir() != null) deleteDir(getExternalCacheDir());
                    runOnUiThread(() -> {
                        calcDrawerCache();
                        Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show();
                    });
                }).start())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
    }

    private void logoutPrompt() {
        new AlertDialog.Builder(this, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Выйти из аккаунта?")
                .setMessage("Приватные ключи останутся на устройстве.")
                .setPositiveButton("Выйти", (d, w) -> {
                    repo.goOffline();
                    FirebaseAuth.getInstance().signOut();
                    Intent i = new Intent(this, AuthActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void updateGhostIcon() {
        boolean g = prefs.ghost();
        btnGhost.setColorFilter(ContextCompat.getColor(this,
                g ? R.color.accent : R.color.text_secondary));
        btnGhost.setBackgroundResource(R.drawable.bg_circle_btn);
    }

    private void loadMyProfile() {
        repo.userRef(me).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) return;
                Models.User u = Repo.parseUser(s);
                Ui.setAvatar(btnProfile, u.avatar, u.uid, u.name);
                if (drawerAvatar != null) Ui.setAvatar(drawerAvatar, u.avatar, u.uid, u.name);
                if (drawerName != null) drawerName.setText(u.name);
                if (drawerUsername != null) drawerUsername.setText("@" + (u.username != null ? u.username : "user"));
                if (drawerBio != null && u.bio != null && !u.bio.isEmpty()) {
                    drawerBio.setText(u.bio);
                }
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
        ui.postDelayed(new Runnable() {
            @Override public void run() { publish(); ui.postDelayed(this, 2000); }
        }, 2000);
    }

    private void publish() {
        if (searching) return;
        List<Models.Dialog> out = new ArrayList<>();
        for (Models.Dialog d : dialogs.values()) {
            if (d.peer == null) continue;
            if (currentTab == 2 && d.unread == 0) continue; // unread tab filter
            out.add(d);
        }
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
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        repo.goOnline();
        calcDrawerCache();
        dialogAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!prefs.ghost()) repo.goOffline();
    }
}
