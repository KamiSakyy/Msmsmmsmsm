package io.tsuyu.app.ui;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.tsuyu.app.R;
import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.Ratchet;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.notif.Notifier;
import io.tsuyu.app.service.BgService;
import io.tsuyu.app.util.Ui;

public class MainActivity extends AppCompatActivity implements BgService.ChatListener {
    private static final String TAG = "TsuyuMain";

    private RecyclerView recycler;
    private EditText etSearch;
    private View btnClearSearch, tvSearchHeader;
    private View drawer, drawerOverlay;
    private TextView tvEmpty;
    private boolean drawerOpen;

    // data
    private final Map<String, DialogInfo> dialogs = new HashMap<>();
    private final List<String> chatOrder = new ArrayList<>();
    private final List<MeowUser> searchResults = new ArrayList<>();
    private String query = "";
    private String myUid;
    private MeowUser me;
    private ValueEventListener meListener;

    private static class DialogInfo {
        String chatId;
        String peer;
        long lastTs;
        String lastType;
        String lastBy;
        String lastKey;
        String thumb;
        int unread;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        myUid = Fb.myUid();
        if (myUid == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Notifier.requestPermission(this);
        recycler = findViewById(R.id.recycler);
        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        tvSearchHeader = findViewById(R.id.tvSearchHeader);
        tvEmpty = findViewById(R.id.tvEmpty);
        drawer = findViewById(R.id.drawer);
        drawerOverlay = findViewById(R.id.drawerOverlay);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(new MainAdapter());

        findViewById(R.id.btnMenu).setOnClickListener(v -> toggleDrawer(true));
        findViewById(R.id.btnKeys).setOnClickListener(v ->
                startActivity(new Intent(this, KeysActivity.class)));
        drawerOverlay.setOnClickListener(v -> toggleDrawer(false));
        setupDrawer();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase().replace("@", "");
                btnClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                doGlobalSearch();
                notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));

        // my profile
        Fb.fetchUser(myUid, map -> {
            try {
                me = Fb.parseUserRaw(map);
                if (me != null) {
                    me.uid = myUid;
                    Fb.userCache.put(myUid, me);
                    Ui.loadCustom(me.custom);
                }
                runOnUiThread(this::refreshDrawer);
            } catch (Throwable ignored) {}
        });
        meListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot ds) {
                MeowUser u = Fb.parseUser(ds);
                if (u != null) {
                    u.uid = myUid;
                    Fb.userCache.put(myUid, u);
                    me = u;
                    Ui.loadCustom(u.custom);
                    runOnUiThread(() -> {
                        refreshDrawer();
                        notifyDataSetChanged();
                    });
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseDatabase.getInstance().getReference("users/" + myUid).addValueEventListener(meListener);
    }

    private void setupDrawer() {
        findViewById(R.id.drawerItemProfile).setOnClickListener(v -> {
            toggleDrawer(false);
            startActivity(new Intent(this, ProfileEditActivity.class));
        });
        findViewById(R.id.drawerItemSettings).setOnClickListener(v -> {
            toggleDrawer(false);
            startActivity(new Intent(this, SettingsActivity.class));
        });
        findViewById(R.id.drawerItemKeys).setOnClickListener(v -> {
            toggleDrawer(false);
            startActivity(new Intent(this, KeysActivity.class));
        });
        findViewById(R.id.drawerItemGhost).setOnClickListener(v -> toggleGhost());
        findViewById(R.id.drawerItemSound).setOnClickListener(v -> toggleSound());
        findViewById(R.id.drawerItemLogout).setOnClickListener(v -> doLogout());
    }

    private void toggleGhost() {
        try {
            boolean on = me == null || !me.ghost;
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("users/" + myUid + "/ghost")
                    .setValue(on);
            if (on) {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("users/" + myUid)
                        .updateChildren(Fb.toMap(new JSONObject().put("online", false).put("lastSeen", System.currentTimeMillis())));
            } else {
                Fb.setPresence(this, true);
            }
            Ui.toast(this, on ? "Режим призрака включён 👻" : "Режим призрака выключен");
            refreshDrawer();
        } catch (Throwable ignored) {}
    }

    private void toggleSound() {
        boolean on = !TsuyuApp.get().prefs().getBoolean("sound_on", true);
        Notifier.setSoundOn(this, on);
        try {
            Fb.saveMyProfile(this, null, null, null, null, null, null, null, null, null, null, on, null);
        } catch (Throwable ignored) {}
        refreshDrawer();
        Ui.toast(this, on ? "Звук включён" : "Звук выключен");
    }

    private void doLogout() {
        try {
            Fb.setPresence(this, false);
            FirebaseAuth.getInstance().signOut();
        } catch (Throwable ignored) {}
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void refreshDrawer() {
        try {
            MeowUser u = me;
            TextView name = findViewById(R.id.tvDrawerName);
            TextView username = findViewById(R.id.tvDrawerUsername);
            TextView status = findViewById(R.id.tvDrawerStatus);
            ImageView iv = findViewById(R.id.ivDrawerAvatar);
            TextView letter = findViewById(R.id.tvDrawerLetter);
            View bg = ((ViewGroup) findViewById(R.id.drawerAvatarWrap)).getChildAt(0);
            if (u != null) {
                name.setText(u.displayName());
                username.setText(u.username != null ? "@" + u.username : "");
                status.setText(u.bio != null && !u.bio.isEmpty() ? u.bio : "Нажмите, чтобы изменить");
                Ui.setAvatar(iv, bg, letter, u);
                ((CheckBox) findViewById(R.id.cbGhost)).setChecked(u.ghost);
                ((CheckBox) findViewById(R.id.cbSound)).setChecked(Boolean.TRUE.equals(u.notifyOn));
            }
            ((TextView) findViewById(R.id.tvSubtitle)).setText("Защищено E2EE • " + Keys_fp());
        } catch (Throwable ignored) {}
    }

    private String Keys_fp() {
        try {
            return io.tsuyu.app.core.Keys.fingerprint(this);
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------- data listeners ----------------
    @Override
    protected void onResume() {
        super.onResume();
        BgService.addListener(this);
        BgService.start(this);
        setupChatListeners();
    }

    @Override
    protected void onPause() {
        super.onPause();
        BgService.removeListener(this);
    }

    private void setupChatListeners() {
        try {
            FirebaseDatabase.getInstance().getReference("mychats/" + myUid).addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot ds) {
                    try {
                        List<String> newChats = new ArrayList<>();
                        if (ds.exists()) {
                            for (DataSnapshot c : ds.getChildren()) newChats.add(c.getKey());
                        }
                        for (String chatId : new ArrayList<>(chatOrder)) {
                            if (!newChats.contains(chatId)) dialogs.remove(chatId);
                        }
                        for (String chatId : newChats) {
                            if (!dialogs.containsKey(chatId)) {
                                DialogInfo d = new DialogInfo();
                                d.chatId = chatId;
                                d.peer = Fb.otherOf(chatId, myUid);
                                dialogs.put(chatId, d);
                                attachChatData(chatId);
                                Ratchet.prefetchBundle(MainActivity.this, d.peer);
                            }
                        }
                        rebuildOrder();
                        notifyDataSetChanged();
                    } catch (Throwable t) {
                        Log.e(TAG, "chats", t);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
        } catch (Throwable t) {
            Log.e(TAG, "setupChatListeners", t);
        }
    }

    private void attachChatData(String chatId) {
        try {
            FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/last")
                    .addValueEventListener(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot ds) {
                            try {
                                DialogInfo d = dialogs.get(chatId);
                                if (d == null) return;
                                d.lastTs = ds.child("ts").getValue(Long.class) == null ? 0
                                        : ds.child("ts").getValue(Long.class);
                                d.lastType = ds.child("ty").getValue(String.class);
                                d.lastBy = ds.child("by").getValue(String.class);
                                d.lastKey = ds.child("k").getValue(String.class);
                                d.thumb = ds.child("thumb").getValue(String.class);
                                if (d.thumb != null) BgService.thumbCache.put(d.lastKey, d.thumb);
                                rebuildOrder();
                                notifyDataSetChanged();
                            } catch (Throwable ignored) {}
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    });
            FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs")
                    .addChildEventListener(new ChildEventListener() {
                        @Override public void onChildAdded(DataSnapshot ds, String prev) { recountUnread(chatId); }
                        @Override public void onChildChanged(DataSnapshot ds, String prev) { recountUnread(chatId); }
                        @Override public void onChildRemoved(DataSnapshot ds) { recountUnread(chatId); }
                        @Override public void onChildMoved(DataSnapshot ds, String prev) {}
                        @Override public void onCancelled(DatabaseError e) {}
                    });
        } catch (Throwable t) {
            Log.e(TAG, "attachChatData", t);
        }
    }

    private void recountUnread(String chatId) {
        try {
            DialogInfo d = dialogs.get(chatId);
            if (d == null) return;
            int unread = 0;
            FirebaseDatabase.getInstance().getReference("chats/" + chatId + "/msgs").get().addOnCompleteListener(task -> {
                try {
                    if (task.isSuccessful() && task.getResult() != null) {
                        for (DataSnapshot m : task.getResult().getChildren()) {
                            String from = m.child("f").getValue(String.class);
                            if (myUid.equals(from)) continue;
                            if (m.child("del").getValue(Long.class) != null) continue;
                            if (m.child("hb").child(myUid).getValue(Boolean.class) != null) continue;
                            Boolean rd = m.child("rd").child(myUid).getValue(Boolean.class);
                            Long rdTs = m.child("rd").child(myUid).getValue(Long.class);
                            if (rdTs == null || rdTs == 0) unread++;
                        }
                        runOnUiThread(() -> {
                            d.unread = unread;
                            notifyDataSetChanged();
                        });
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void rebuildOrder() {
        chatOrder.clear();
        chatOrder.addAll(dialogs.keySet());
        Collections.sort(chatOrder, (a, b) -> {
            long ta = dialogs.get(a) == null ? 0 : dialogs.get(a).lastTs;
            long tb = dialogs.get(b) == null ? 0 : dialogs.get(b).lastTs;
            return Long.compare(tb, ta);
        });
    }

    // ---------------- search ----------------
    private void doGlobalSearch() {
        searchResults.clear();
        tvSearchHeader.setVisibility(query.length() >= 2 ? View.VISIBLE : View.GONE);
        if (query.length() < 2) {
            notifyDataSetChanged();
            return;
        }
        try {
            Query q = FirebaseDatabase.getInstance().getReference("search").orderByKey().startAt(query).limitToFirst(24);
            q.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot ds) {
                    searchResults.clear();
                    if (ds.exists()) {
                        for (DataSnapshot c : ds.getChildren()) {
                            String u = c.child("u").getValue(String.class);
                            if (u == null || !u.startsWith(query)) continue;
                            String uid = c.child("uid").getValue(String.class);
                            if (uid == null || uid.equals(myUid)) continue;
                            MeowUser cached = Fb.userCache.get(uid);
                            if (cached == null) {
                                final String uidF = uid;
                                final String nameF = u;
                                MeowUser tmp = new MeowUser();
                                tmp.uid = uidF;
                                tmp.username = nameF;
                                searchResults.add(tmp);
                                Fb.fetchUser(uidF, map -> {
                                    MeowUser full = Fb.parseUserRaw(map);
                                    if (full != null) {
                                        full.uid = uidF;
                                        Fb.userCache.put(uidF, full);
                                        runOnUiThread(() -> notifyDataSetChanged());
                                    }
                                });
                            } else {
                                searchResults.add(cached);
                            }
                        }
                    }
                    runOnUiThread(() -> notifyDataSetChanged());
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
        } catch (Throwable t) {
            Log.e(TAG, "search", t);
        }
    }

    // ---------------- adapter ----------------
    class MainAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        int TYPE_DIALOG = 0, TYPE_USER = 1;

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(viewType == TYPE_DIALOG ? R.layout.item_dialog : R.layout.item_user_result, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public int getItemViewType(int position) {
            return isUserRow(position) ? TYPE_USER : TYPE_DIALOG;
        }

        private boolean isUserRow(int position) {
            return query.length() >= 2 && position < searchResults.size();
        }

        @Override
        public int getItemCount() {
            int total = query.length() >= 2 ? searchResults.size() : 0;
            for (String chatId : chatOrder) {
                DialogInfo d = dialogs.get(chatId);
                if (d == null) continue;
                if (query.length() >= 2) {
                    MeowUser p = Fb.userCache.get(d.peer);
                    String name = p == null ? "" : p.displayName().toLowerCase();
                    String u = p == null || p.username == null ? "" : p.username.toLowerCase();
                    if (!name.contains(query) && !u.contains(query)) continue;
                }
                total++;
            }
            return total;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (isUserRow(position)) {
                MeowUser u = searchResults.get(position);
                View v = holder.itemView;
                TextView name = v.findViewById(R.id.tvName);
                TextView username = v.findViewById(R.id.tvUsername);
                TextView status = v.findViewById(R.id.tvStatus);
                ImageView iv = v.findViewById(R.id.ivAvatar);
                TextView letter = v.findViewById(R.id.tvLetter);
                View bg = ((ViewGroup) v).getChildAt(0).getChildAt(0);
                name.setText(u.displayName());
                username.setText(u.username != null ? "@" + u.username : "");
                status.setText(u.online ? "в сети" : "");
                Ui.setAvatar(iv, bg, letter, u);
                v.setOnClickListener(view -> openChat(u.uid));
                animateIn(v, position);
            } else {
                int idx = query.length() >= 2 ? position - searchResults.size() : position;
                // find idx-th visible dialog
                int count = 0;
                String chatId = null;
                for (String cid : chatOrder) {
                    DialogInfo d = dialogs.get(cid);
                    if (d == null) continue;
                    if (query.length() >= 2) {
                        MeowUser p = Fb.userCache.get(d.peer);
                        String name = p == null ? "" : p.displayName().toLowerCase();
                        String u = p == null || p.username == null ? "" : p.username.toLowerCase();
                        if (!name.contains(query) && !u.contains(query)) continue;
                    }
                    if (count == idx) { chatId = cid; break; }
                    count++;
                }
                if (chatId == null) return;
                DialogInfo d = dialogs.get(chatId);
                View v = holder.itemView;
                MeowUser peer = Fb.userCache.get(d.peer);
                TextView name = v.findViewById(R.id.tvName);
                TextView time = v.findViewById(R.id.tvTime);
                TextView preview = v.findViewById(R.id.tvPreview);
                TextView unread = v.findViewById(R.id.tvUnread);
                ImageView iv = v.findViewById(R.id.ivAvatar);
                TextView letter = v.findViewById(R.id.tvLetter);
                View bg = v.findViewById(R.id.avatarBg);
                View dot = v.findViewById(R.id.dot);
                ImageView thumb = v.findViewById(R.id.ivPreviewThumb);
                name.setText(peer == null ? "..." : peer.displayName());
                time.setText(d.lastTs > 0 ? Ui.listTime(d.lastTs) : "");
                Ui.setAvatar(iv, bg, letter, peer);
                dot.setBackgroundResource(peer != null && peer.online ? R.drawable.bg_online : R.drawable.bg_offline);
                // preview
                String prev = BgService.previewCache.get(d.lastKey);
                if (prev == null) prev = typeLabel(d.lastType);
                preview.setText(prev);
                if (d.thumb != null && d.lastType != null && (d.lastType.equals("photo") || d.lastType.equals("video") || d.lastType.equals("circle"))) {
                    byte[] tb = android.util.Base64.decode(d.thumb, android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(tb, 0, tb.length);
                    if (bm != null) {
                        thumb.setImageBitmap(bm);
                        thumb.setVisibility(View.VISIBLE);
                        thumb.setScaleX(0.6f);
                        thumb.setScaleY(0.6f);
                    }
                } else {
                    thumb.setVisibility(View.GONE);
                }
                if (d.unread > 0) {
                    unread.setText(String.valueOf(d.unread > 99 ? "99+" : d.unread));
                    unread.setVisibility(View.VISIBLE);
                    name.setTextColor(0xFFFFFFFF);
                } else {
                    unread.setVisibility(View.GONE);
                }
                v.setOnClickListener(view -> openChat(d.peer));
                animateIn(v, position);
            }
        }

        private void animateIn(View v, int position) {
            try {
                Animation a = new AlphaAnimation(0f, 1f);
                a.setDuration(180);
                a.setInterpolator(new DecelerateInterpolator());
                v.setTranslationY(12);
                v.animate().translationY(0).setDuration(180).start();
                v.startAnimation(a);
            } catch (Throwable ignored) {}
        }

        private String typeLabel(String ty) {
            if (ty == null) return "";
            switch (ty) {
                case "photo": return "📷 Фото";
                case "video": return "🎬 Видео";
                case "voice": return "🎤 Голосовое";
                case "circle": return "📹 Кружочек";
                case "music": return "🎵 Музыка";
                case "collage": return "🖼 Коллаж";
                case "call": return "📞 Звонок";
                default: return ty;
            }
        }
    }

    private void notifyDataSetChanged() {
        try {
            recycler.getAdapter().notifyDataSetChanged();
            tvEmpty.setVisibility(itemCount() == 0 ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {}
    }

    private int itemCount() {
        return recycler.getAdapter() == null ? 0 : recycler.getAdapter().getItemCount();
    }

    private void openChat(String peerUid) {
        try {
            BgService.writeMyChat(Fb.chatId(myUid, peerUid));
        } catch (Throwable ignored) {}
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("peer", peerUid);
        startActivity(i);
    }

    @Override
    public void onEvent(String chatId, int kind, String msgKey) {
        try {
            if (!isFinishing() && !isDestroyed()) {
                runOnUiThread(() -> {
                    DialogInfo d = dialogs.get(chatId);
                    if (d != null) {
                        if (kind == 2) {
                            // message removed
                        }
                        // presence/typing refresh
                        if (d.lastTs <= 0) rebuildOrder();
                        notifyDataSetChanged();
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private void toggleDrawer(boolean open) {
        if (drawerOpen == open) return;
        drawerOpen = open;
        drawer.animate().translationX(open ? 0 : -drawer.getWidth() - 40).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
        drawerOverlay.animate().alpha(open ? 1f : 0f).setDuration(240)
                .withEndAction(() -> {
                    if (!open) drawerOverlay.setVisibility(View.INVISIBLE);
                }).start();
        if (open) drawerOverlay.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (meListener != null) {
                FirebaseDatabase.getInstance().getReference("users/" + myUid).removeEventListener(meListener);
            }
        } catch (Throwable ignored) {}
    }
}
