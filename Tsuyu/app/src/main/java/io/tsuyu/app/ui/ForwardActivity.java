package io.tsuyu.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

import io.tsuyu.app.R;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.service.BgService;

import io.tsuyu.app.core.Ratchet;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.model.Msg;
import io.tsuyu.app.util.Ui;

import org.json.JSONObject;

public class ForwardActivity extends AppCompatActivity {
    public static JSONObject pending; // payload {p, ty}

    private final List<Object> rows = new ArrayList<>();
    private final List<Object> allRows = new ArrayList<>();
    private final FwAdapter adapter = new FwAdapter();
    private String chatId;

    public static class FwItem {
        String uid;
        MeowUser u;
        boolean selected;
    }

    private class FwAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_user_result, p, false)) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            FwItem it = (FwItem) rows.get(pos);
            TextView name = h.itemView.findViewById(R.id.tvName);
            TextView un = h.itemView.findViewById(R.id.tvUsername);
            TextView sub = h.itemView.findViewById(R.id.tvStatus);
            View dot = h.itemView.findViewById(R.id.dot);
            View bgView = ((ViewGroup) ((ViewGroup) h.itemView).getChildAt(0)).getChildAt(0);
            TextView letterV = h.itemView.findViewById(R.id.tvLetter);
            ImageView ivV = h.itemView.findViewById(R.id.ivAvatar);
            Ui.setAvatar(ivV, bgView, letterV, it.u);
            dot.setVisibility(View.VISIBLE);
            name.setText(it.u == null ? "?" : it.u.displayName());
            un.setText(it.u != null && it.u.username != null ? "@" + it.u.username : "");
            sub.setText(it.selected ? "Выбрано ✓" : (it.u != null && it.u.online ? "в сети" : ""));
            dot.setBackgroundResource(it.u != null && it.u.online ? R.drawable.bg_online : R.drawable.bg_offline);
        }
        @Override public int getItemCount() { return rows.size(); }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forward);
        if (pending == null) pending = new JSONObject();
        chatId = Fb.myChatId(Fb.myUid(), Fb.myUid());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        ((EditText) findViewById(R.id.etSearch)).addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                try {
                    String q = s.toString().trim().toLowerCase();
                    java.util.List<Object> filtered = new java.util.ArrayList<>();
                    for (Object o : allRows) {
                        FwItem it = (FwItem) o;
                        if (q.isEmpty() || (it.u != null && (it.u.displayName().toLowerCase().contains(q)
                                || (it.u.username != null && it.u.username.toLowerCase().contains(q))))) {
                            filtered.add(o);
                        }
                    }
                    java.util.List<Object> snapshot = new java.util.ArrayList<>(rows);
                    rows.clear();
                    rows.addAll(filtered);
                    adapter.notifyDataSetChanged();
                } catch (Throwable ignored) {}
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        // my dialogs as forward targets
        Fb.fb().getReference("mychats/" + Fb.myUid()).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                rows.clear();
                allRows.clear();
                for (DataSnapshot ds : s.getChildren()) {
                    String cid = ds.getKey();
                    String other = Fb.otherOf(cid);
                    if (other == null || other.equals(Fb.myUid())) continue;
                    MeowUser u = Fb.userCache.get(other);
                    FwItem it = new FwItem();
                    it.uid = other;
                    it.u = u;
                    it.selected = false;
                    rows.add(it);
                    allRows.add(it);
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
        findViewById(R.id.btnForwardSend).setOnClickListener(v -> {
            try {
                String target = null;
                for (Object o : rows) {
                    FwItem it = (FwItem) o;
                    if (it.selected) {
                        if (target != null) {
                            Ui.toast(ForwardActivity.this, "Только один чат за раз");
                            return;
                        }
                        target = it.uid;
                    }
                }
                if (target == null) {
                    Ui.toast(ForwardActivity.this, "Выберите получателя");
                    return;
                }
                doForward(target);
            } catch (Throwable t) {
                Ui.toast(ForwardActivity.this, "Ошибка");
            }
        });
    }

    private void doForward(String target) {
        try {
            MeowUser tu = Fb.userCache.get(target);
            if (tu == null) {
                Ui.toast(this, "Загружаю профиль получателя...");
                return;
            }
            final JSONObject p;
            try {
                p = pending.has("p") ? pending.getJSONObject("p") : pending;
            } catch (Throwable t2) {
                p = new JSONObject().put("tx", "");
            }
            final String ty = pending.optString("ty", "text");
            final String cid = Fb.chatId(Fb.myUid(), target);
            final String fromUid = Fb.myUid();
            final String key = System.currentTimeMillis() + "_" + (int) (Math.random() * 999999);
            Ratchet.send(this, target, p.toString().getBytes("UTF-8"), env -> {
                if (env == null) {
                    runOnUiThread(() -> Ui.toast(ForwardActivity.this, "Ошибка шифрования"));
                    return;
                }
                try {
                    Msg m = new Msg();
                    m.key = key;
                    m.ts = System.currentTimeMillis();
                    m.from = fromUid;
                    m.type = ty;
                    m.payload = p;
                    m.e = new JSONObject(env);
                    m.fwdFrom = fromUid;
                    JSONObject patch = new JSONObject();
                    patch.put("c", cid);
                    patch.put("msgs/" + key, Msg.toDb(m));
                    patch.put("last/ts", m.ts);
                    patch.put("last/ty", ty);
                    patch.put("last/by", fromUid);
                    patch.put("last/k", key);
                    Fb.fb().getReference("chats/" + cid).updateChildren(Fb.toMap(patch));
                    BgService.writeMyChat(cid);
                    runOnUiThread(() -> {
                        Ui.toast(ForwardActivity.this, "Переслано ✓");
                        finish();
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> Ui.toast(ForwardActivity.this, "Ошибка пересылки"));
                }
            });
        } catch (Throwable t) {
            Ui.toast(this, "Ошибка");
        }
    }
}
