package com.tsuyu.messenger.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.data.Repo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Picks a conversation and re-encrypts the payload for the new recipient. */
public class ForwardActivity extends AppCompatActivity {

    public static Models.Message payload;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forward);

        Repo repo = Repo.get(this);
        String me = repo.uid();
        if (me == null || payload == null) { finish(); return; }

        RecyclerView list = findViewById(R.id.forwardList);
        list.setLayoutManager(new LinearLayoutManager(this));
        List<Models.User> users = new ArrayList<>();
        SearchAdapter adapter = new SearchAdapter(this, users, u -> {
            forwardTo(repo, u);
            finish();
        });
        list.setAdapter(adapter);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());

        repo.db().getReference("userChats").child(me)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        for (DataSnapshot c : s.getChildren()) {
                            repo.userRef(c.getKey()).addListenerForSingleValueEvent(
                                    new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot u) {
                                            if (!u.exists()) return;
                                            users.add(Repo.parseUser(u));
                                            adapter.submit(new ArrayList<>(users));
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) { }
                                    });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { }
                });
    }

    private void forwardTo(Repo repo, Models.User target) {
        try {
            JSONObject p = new JSONObject();
            p.put("type", payload.type);
            p.put("text", payload.text == null ? "" : payload.text);
            p.put("fwd", payload.outgoing ? "вас" : "собеседника");
            if (!payload.attachments.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Models.Attachment a : payload.attachments) {
                    JSONObject o = new JSONObject();
                    o.put("t", a.type);
                    o.put("d", a.data);
                    if (a.thumb != null) o.put("th", a.thumb);
                    o.put("w", a.width);
                    o.put("h", a.height);
                    o.put("dur", a.durationMs);
                    if (a.fileName != null) o.put("fn", a.fileName);
                    if (a.waveform != null) {
                        JSONArray wf = new JSONArray();
                        for (int x : a.waveform) wf.put(x);
                        o.put("wf", wf);
                    }
                    arr.put(o);
                }
                p.put("att", arr);
            }
            repo.sendMessage(target, p, null);
            Toast.makeText(this, "Переслано", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось переслать", Toast.LENGTH_SHORT).show();
        }
    }
}
