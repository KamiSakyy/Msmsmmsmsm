package io.tsuyu.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.tsuyu.app.R;
import io.tsuyu.app.core.Crypto;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.model.Msg;
import io.tsuyu.app.util.Ui;

/** Export chat as ZIP: decrypted transcript + selected media files. */
public class ExportChatActivity extends AppCompatActivity {
    private static final int EXPORT_FILE = 901;
    private String chatId;
    private String peerUid;
    private String peerName = "chat";
    private byte[] pendingBytes;
    private Button btn;
    private TextView progress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);
        chatId = getIntent().getStringExtra("chatId");
        peerUid = Fb.otherOf(chatId);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btn = findViewById(R.id.btnExport);
        progress = findViewById(R.id.tvProgress);
        Fb.fetchUser(peerUid, map -> {
            MeowUser u = Fb.parseUserRaw(map);
            if (u != null) {
                peerName = u.username == null ? u.displayName() : u.username;
            }
            runOnUiThread(() -> Ui.toast(ExportChatActivity.this, "Чат: @" + peerName));
        });
        btn.setOnClickListener(v -> buildZip());
    }

    private void buildZip() {
        boolean text = ((CheckBox) findViewById(R.id.cbText)).isChecked();
        boolean photo = ((CheckBox) findViewById(R.id.cbPhoto)).isChecked();
        boolean voice = ((CheckBox) findViewById(R.id.cbVoice)).isChecked();
        boolean circle = ((CheckBox) findViewById(R.id.cbCircle)).isChecked();
        boolean video = ((CheckBox) findViewById(R.id.cbVideo)).isChecked();
        boolean music = ((CheckBox) findViewById(R.id.cbMusic)).isChecked();
        boolean collage = ((CheckBox) findViewById(R.id.cbCollage)).isChecked();
        if (text == false && photo == false && voice == false && circle == false
                && video == false && music == false && collage == false) {
            Ui.toast(this, "Выберите, что выгрузить");
            return;
        }
        btn.setEnabled(false);
        progress.setText("Расшифровываю...");
        final boolean fText = text, fPhoto = photo, fVoice = voice, fCircle = circle;
        final boolean fVideo = video, fMusic = music, fCollage = collage;

        Fb.fb().getReference("chats/" + chatId + "/msgs").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                new Thread(() -> {
                    try {
                        Map<String, Msg> all = new HashMap<>();
                        for (DataSnapshot ds : s.getChildren()) {
                            Msg m = Msg.fromDb(ExportChatActivity.this, peerUid, ds);
                            if (m != null) all.put(ds.getKey(), m);
                        }
                        java.util.TreeMap<Long, Msg> sorted = new java.util.TreeMap<>();
                        for (Msg m : all.values()) sorted.put(m.ts, m);

                        StringBuilder transcript = new StringBuilder();
                        transcript.append("Tsuyu — экспорт чата с @").append(peerName).append("\n");
                        transcript.append("Дата: ").append(new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.ROOT).format(new java.util.Date())).append("\n");
                        transcript.append("Double Ratchet E2EE: содержимое расшифровано на устройстве\n\n");

                        ByteArrayOutputStream zip = new ByteArrayOutputStream();
                        ZipOutputStream zos = new ZipOutputStream(zip);
                        zos.putNextEntry(new ZipEntry("transcript.txt"));
                        zos.write(transcript.toString().getBytes("UTF-8"));
                        int i = 0;
                        for (Msg m : sorted.values()) {
                            String who = m.from.equals(Fb.myUid()) ? "Я" : (Fb.userCache.get(m.from) == null ? "?" : Fb.userCache.get(m.from).displayName());
                            String line = "[" + Ui.dateLabel(m.ts) + " " + Ui.timeHM(m.ts) + "] " + who + ": ";
                            String ty = m.type == null ? "text" : m.type;
                            boolean include = false;
                            String label = "";
                            switch (ty) {
                                case "text":
                                    if (fText) { include = true; label = m.text() == null ? "" : m.text(); }
                                    break;
                                case "photo":
                                    if (fPhoto) { include = true; label = "[фото]"; }
                                    break;
                                case "video":
                                    if (fVideo) { include = true; label = "[видео " + Ui.durText(m.dur()) + "]"; }
                                    break;
                                case "voice":
                                    if (fVoice) { include = true; label = "[голосовое " + Ui.durText(m.dur()) + "]"; }
                                    break;
                                case "circle":
                                    if (fCircle) { include = true; label = "[кружочек " + Ui.durText(m.dur()) + "]"; }
                                    break;
                                case "music":
                                    if (fMusic) { include = true; label = "[музыка: " + (m.name() == null ? "?" : m.name()) + "]"; }
                                    break;
                                case "collage":
                                    if (fCollage) { include = true; label = "[коллаж " + (m.collage() == null ? 0 : m.collage().length()) + " фото]"; }
                                    break;
                                case "call":
                                    if (fText) { include = true; label = "[звонок]"; }
                                    break;
                            }
                            if (include == false) continue;
                            transcript.append(line).append(label).append("\n");
                            // media files
                            try {
                                byte[] data = decodeMedia(m);
                                if (data != null && data.length > 0) {
                                    String ext = "photo".equals(ty) ? "jpg"
                                            : "music".equals(ty) ? (m.mediaType() == null ? "m4a" : m.mediaType())
                                            : "mp4";
                                    String fname = ty + "_" + (i++) + "_" + Ui.dateLabel(m.ts).replace('.', '_') + "." + ext;
                                    zos.putNextEntry(new ZipEntry("media/" + fname));
                                    zos.write(data);
                                    zos.closeEntry();
                                }
                            } catch (Throwable t) {
                                // skip media on error
                            }
                        }
                        zos.closeEntry();
                        // write transcript fully (it grew)
                        zos.putNextEntry(new ZipEntry("transcript_full.txt"));
                        zos.write(transcript.toString().getBytes("UTF-8"));
                        zos.closeEntry();
                        zos.close();
                        pendingBytes = zip.toByteArray();
                        final byte[] data = pendingBytes;
                        runOnUiThread(() -> {
                            btn.setEnabled(true);
                            progress.setText("Готово (" + (data.length / 1024) + " КБ). Сохраняю...");
                            Intent ii = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            ii.addCategory(Intent.CATEGORY_OPENABLE);
                            ii.setType("application/zip");
                            ii.putExtra(Intent.EXTRA_TITLE, "tsuyu_chat_" + peerName + ".zip");
                            startActivityForResult(ii, EXPORT_FILE);
                        });
                    } catch (Throwable t) {
                        runOnUiThread(() -> {
                            btn.setEnabled(true);
                            progress.setText("Ошибка: " + t.getMessage());
                        });
                    }
                }).start();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private byte[] decodeMedia(Msg m) {
        try {
            if (m.payload == null) return null;
            if (m.payload.has("m")) {
                String b64 = m.payload.getString("m");
                return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            }
            if (m.payload.has("items")) {
                // collage: pick first
                JSONArray items = m.payload.getJSONArray("items");
                if (items.length() > 0) {
                    return android.util.Base64.decode(items.getJSONObject(0).getString("m"), android.util.Base64.DEFAULT);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_FILE && resultCode == RESULT_OK && data != null
                && data.getData() != null && pendingBytes != null) {
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(data.getData());
                os.write(pendingBytes);
                os.close();
                progress.setText("Сохранено ✓");
                Ui.toast(this, "Экспорт сохранён ✓");
            } catch (Throwable t) {
                progress.setText("Ошибка записи");
            }
        }
    }
}
