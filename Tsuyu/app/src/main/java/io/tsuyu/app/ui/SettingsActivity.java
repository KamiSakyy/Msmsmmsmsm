package io.tsuyu.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import io.tsuyu.app.R;
import io.tsuyu.app.TsuyuApp;
import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.ProfileCipher;
import io.tsuyu.app.model.MeowUser;
import io.tsuyu.app.notif.Notifier;
import io.tsuyu.app.util.Ui;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

public class SettingsActivity extends AppCompatActivity {
    private static final int PICK_FONT = 701;
    private static final int PICK_SOUND = 702;
    private MeowUser me;
    private JSONObject custom = new JSONObject();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        Fb.fetchUser(Fb.myUid(), map -> {
            me = Fb.parseUserRaw(map);
            if (me != null) {
                me.uid = Fb.myUid();
                Ui.loadCustom(me.custom);
                try {
                    String cj = Ui.currentCustomJson(this);
                    if (cj != null) custom = new JSONObject(cj);
                } catch (Throwable ignored) {}
                runOnUiThread(this::refresh);
            }
        });

        findViewById(R.id.setFont).setOnClickListener(v -> pickFont());
        findViewById(R.id.setFontSize).setOnClickListener(v -> askFontSize());
        findViewById(R.id.setTextStyle).setOnClickListener(v -> askTextStyle());
        findViewById(R.id.setStatusTexts).setOnClickListener(v -> askStatusTexts());
        findViewById(R.id.setSound).setOnClickListener(v -> pickSound());
        ((CheckBox) findViewById(R.id.cbSoundOn)).setOnClickListener(v -> toggleSound());
        findViewById(R.id.setGhost).setOnClickListener(v -> toggleGhost());
        ((CheckBox) findViewById(R.id.cbGhost)).setOnClickListener(v -> toggleGhost());
        findViewById(R.id.setWhoCanWrite).setOnClickListener(v -> askPrivacy("write"));
        findViewById(R.id.setWhoSeeLastSeen).setOnClickListener(v -> askPrivacy("lastSeen"));
        findViewById(R.id.setWhoSeePhoto).setOnClickListener(v -> askPrivacy("photo"));
        findViewById(R.id.setWhoSeeBio).setOnClickListener(v -> askPrivacy("bio"));
        findViewById(R.id.setKeys).setOnClickListener(v ->
                startActivity(new Intent(this, KeysActivity.class)));
        findViewById(R.id.setAbout).setOnClickListener(v ->
                Toast.makeText(this, "Tsuyu 1.0\nDouble Ratchet E2EE\nRTDB • Firebase Auth", Toast.LENGTH_LONG).show());
    }

    private void refresh() {
        try {
            TextView font = findViewById(R.id.tvFontSub);
            TextView size = findViewById(R.id.tvFontSizeSub);
            TextView style = findViewById(R.id.tvTextStyleSub);
            TextView sound = findViewById(R.id.tvSoundSub);
            TextView write = findViewById(R.id.tvWhoCanWrite);
            TextView lastSeen = findViewById(R.id.tvWhoSeeLastSeen);
            TextView photo = findViewById(R.id.tvWhoSeePhoto);
            TextView bio = findViewById(R.id.tvWhoSeeBio);
            font.setText(custom.optString("font", null) != null ? custom.getString("font") : "Системный");
            size.setText(String.valueOf((int) custom.optDouble("size", 14)));
            StringBuilder st = new StringBuilder();
            if (custom.optBoolean("bold", false)) st.append("Жирный");
            if (custom.optBoolean("italic", false)) st.append(st.length() > 0 ? " + " : "").append("Курсив");
            style.setText(st.length() == 0 ? "Обычный" : st.toString());
            sound.setText(Notifier.hasCustomSound(this) ? "Свой MP3" : "Стандартный");
            if (me != null) {
                write.setText(label(me.privacyWrite));
                lastSeen.setText(label(me.privacyLastSeen));
                photo.setText(label(me.privacyPhoto));
                bio.setText(label(me.privacyBio));
            }
            ((CheckBox) findViewById(R.id.cbSoundOn)).setChecked(Boolean.TRUE.equals(me == null ? Boolean.TRUE : me.notifyOn));
            ((CheckBox) findViewById(R.id.cbGhost)).setChecked(me != null && me.ghost);
        } catch (Throwable ignored) {}
    }

    private String label(String v) {
        if (v == null) return "Все";
        switch (v) {
            case "nobody": return "Никто";
            case "contacts": return "Только контакты";
            case "onlyAt": return "Только @";
            default: return "Все";
        }
    }

    private void pickFont() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("font/ttf");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
        i2.setType("*/*");
        i2.addCategory(Intent.CATEGORY_OPENABLE);
        Intent chooser = Intent.createChooser(i2, "Выбрать шрифт (.ttf / .otf)");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{i});
        startActivityForResult(chooser, PICK_FONT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (requestCode == PICK_FONT) {
                String name = "custom_" + System.currentTimeMillis() + ".ttf";
                File d = new File(TsuyuApp.get().getFilesDir(), "fonts");
                d.mkdirs();
                File f = new File(d, name);
                java.io.InputStream is = getContentResolver().openInputStream(data.getData());
                java.io.FileOutputStream fos = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                is.close();
                fos.close();
                android.graphics.Typeface tf = android.graphics.Typeface.createFromFile(f);
                if (tf == null) {
                    Ui.toast(this, "Неверный файл шрифта");
                    return;
                }
                custom.put("font", name);
                saveCustom();
            } else if (requestCode == PICK_SOUND) {
                File d = new File(TsuyuApp.get().getFilesDir(), "sound");
                d.mkdirs();
                String sname = "custom_sound_" + System.currentTimeMillis() + ".mp3";
                File f = new File(d, sname);
                java.io.InputStream is = getContentResolver().openInputStream(data.getData());
                java.io.FileOutputStream fos = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                is.close();
                fos.close();
                if (f.length() > 1_000_000) {
                    Ui.toast(this, "Звук длиннее ~1 секунды (макс 1 МБ)");
                    f.delete();
                    return;
                }
                byte[] mp3 = Ui.readFile(f);
                Notifier.saveCustomSound(this, mp3);
                try {
                    Fb.saveMyProfile(this, null, null, null, null, null, null, null, null, null, null,
                            true, android.util.Base64.encodeToString(mp3, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING));
                } catch (Throwable ignored) {}
                Ui.toast(this, "Свой звук установлен ✓");
            }
        } catch (Throwable t) {
            Ui.toast(this, "Ошибка");
        }
        refresh();
    }

    private void pickSound() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Выбрать звук (mp3, ~1 сек)"), PICK_SOUND);
    }

    private void toggleSound() {
        boolean on = !TsuyuApp.get().prefs().getBoolean("sound_on", true);
        Notifier.setSoundOn(this, on);
        try {
            Fb.saveMyProfile(this, null, null, null, null, null, null, null, null, null, null, on, null);
        } catch (Throwable ignored) {}
        refresh();
    }

    private void toggleGhost() {
        try {
            boolean on = me == null || me.ghost == false;
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("users/" + Fb.myUid() + "/ghost").setValue(on);
            if (on) {
                com.google.firebase.database.FirebaseDatabase.getInstance().getReference("users/" + Fb.myUid())
                        .updateChildren(new JSONObject().put("online", false).put("lastSeen", System.currentTimeMillis()).toMap());
            } else {
                Fb.setPresence(this, true);
            }
            Ui.toast(this, on ? "Призрак включён 👻" : "Призрак выключен");
            refresh();
        } catch (Throwable ignored) {}
    }

    private void askFontSize() {
        final String[] vals = {"12", "13", "14", "15", "16", "17", "18", "20", "22"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Размер текста")
                .setItems(vals, (d, which) -> {
                    try {
                        custom.put("size", Double.parseDouble(vals[which]));
                        saveCustom();
                    } catch (Throwable ignored) {}
                })
                .show();
    }

    private void askTextStyle() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Стиль текста")
                .setMultiChoiceItems(new String[]{"Жирный", "Курсив"},
                        new boolean[]{custom.optBoolean("bold", false), custom.optBoolean("italic", false)},
                        (d, which, isChecked) -> {
                            if (which == 0) {
                                try { custom.put("bold", isChecked); } catch (Throwable ignored) {}
                            } else {
                                try { custom.put("italic", isChecked); } catch (Throwable ignored) {}
                            }
                        })
                .setPositiveButton("ОК", (d, w) -> saveCustom())
                .show();
    }

    private void askStatusTexts() {
        try {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            ll.setPadding(40, 20, 40, 0);
            String[] labels = {"Печатает:", "В сети:", "Был(а) в сети:", "Сообщение отправлено:"};
            String[] keys = {"typing", "online", "lastSeen", "sent"};
            final String[] cur = {
                    custom.optString("typing", "печатает..."),
                    custom.optString("online", "в сети"),
                    custom.optString("lastSeen", "был(а)"),
                    custom.optString("sent", "отправлено")
            };
            android.widget.EditText[] ets = new android.widget.EditText[4];
            for (int i = 0; i < 4; i++) {
                TextView t = new TextView(this);
                t.setText(labels[i]);
                t.setTextColor(0xFF8E8E93);
                t.setTextSize(12);
                t.setPadding(0, 10, 0, 2);
                ll.addView(t);
                android.widget.EditText et = new android.widget.EditText(this);
                et.setText(cur[i]);
                et.setSingleLine(true);
                ll.addView(et);
                ets[i] = et;
            }
            b.setView(ll);
            b.setTitle("Тексты статусов (свои)");
            b.setPositiveButton("Сохранить", (d, w) -> {
                try {
                    for (int i = 0; i < 4; i++) custom.put(keys[i], ets[i].getText().toString().trim());
                    saveCustom();
                } catch (Throwable ignored) {}
            });
            b.show();
        } catch (Throwable ignored) {}
    }

    private void askPrivacy(String field) {
        String title = "write".equals(field) ? "Кто может мне писать"
                : "lastSeen".equals(field) ? "Кто видит время захода"
                : "photo".equals(field) ? "Кто видит фото профиля" : "Кто видит «О себе»";
        final String[] vals = {"everyone", "contacts", "onlyAt", "nobody"};
        String[] labels = {"Все", "Только контакты", "Только @", "Никто"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    try {
                        if ("write".equals(field)) Fb.saveMyProfile(this, null, null, null, null, null, null, vals[which], null, null, null, null, null);
                        else if ("lastSeen".equals(field)) Fb.saveMyProfile(this, null, null, null, null, null, null, null, vals[which], null, null, null, null);
                        else if ("photo".equals(field)) Fb.saveMyProfile(this, null, null, null, null, null, null, null, null, vals[which], null, null, null);
                        else Fb.saveMyProfile(this, null, null, null, null, null, null, null, null, null, vals[which], null, null);
                        me = null;
                        Fb.fetchUser(Fb.myUid(), map -> {
                            me = Fb.parseUserRaw(map);
                            runOnUiThread(this::refresh);
                        });
                    } catch (Throwable ignored) {}
                })
                .show();
    }

    private void saveCustom() {
        try {
            String enc = ProfileCipher.enc(custom.toString());
            Fb.saveMyProfile(this, null, null, null, null, null, enc, null, null, null, null, null, null);
            Ui.loadCustom(enc);
            refresh();
            Ui.toast(this, "Сохранено ✓");
        } catch (Throwable t) {
            Ui.toast(this, "Ошибка");
        }
    }
}
