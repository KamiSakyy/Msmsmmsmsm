package com.tsuyu.messenger.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Environment;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.CryptoUtil;
import com.tsuyu.messenger.data.Models;
import com.tsuyu.messenger.util.Fmt;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports the conversation in plaintext (it is decrypted on-device) as a ZIP:
 * chat.txt plus the selected media, each type toggled by a checkbox.
 */
public final class ExportDialog {

    private ExportDialog() {}

    public static void show(Activity act, Models.User peer, List<Models.Message> messages) {
        String[] labels = {"Текст переписки", "Фото", "Видео", "Голосовые",
                "Кружочки", "Аудио/музыка"};
        boolean[] checked = {true, true, true, true, true, true};

        new AlertDialog.Builder(act, R.style.Theme_Tsuyu_Dialog)
                .setTitle("Скачать переписку")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("Скачать", (d, w) -> export(act, peer, messages, checked))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void export(Activity act, Models.User peer,
                               List<Models.Message> messages, boolean[] opt) {
        Toast.makeText(act, "Экспорт…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String safe = peer == null || peer.username == null
                        ? "chat" : peer.username;
                File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = act.getCacheDir();
                File zipFile = new File(dir, "Tsuyu_" + safe + "_"
                        + System.currentTimeMillis() + ".zip");

                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                    StringBuilder text = new StringBuilder();
                    text.append("Переписка Tsuyu с ")
                            .append(peer == null ? "" : peer.name)
                            .append(peer != null && peer.username != null
                                    ? " (@" + peer.username + ")" : "")
                            .append("\nРасшифровано локально: ")
                            .append(Fmt.daySeparator(System.currentTimeMillis()))
                            .append("\n\n");

                    int photo = 0, video = 0, voice = 0, circle = 0, audio = 0;

                    for (Models.Message m : messages) {
                        if (m.failed) continue;
                        String who = m.outgoing ? "Вы" : (peer == null ? "Собеседник" : peer.name);
                        String stamp = "[" + Fmt.daySeparator(m.ts) + " " + Fmt.time(m.ts) + "] ";

                        if (m.attachments.isEmpty()) {
                            if (opt[0] && m.text != null && !m.text.isEmpty())
                                text.append(stamp).append(who).append(": ")
                                        .append(m.text).append("\n");
                            continue;
                        }
                        for (Models.Attachment a : m.attachments) {
                            String name = null;
                            byte[] data = a.data == null ? null : CryptoUtil.unb64(a.data);
                            if (data == null) continue;
                            switch (a.type) {
                                case Models.T_PHOTO:
                                    if (!opt[1]) continue;
                                    name = "photos/photo_" + (++photo) + ".jpg"; break;
                                case Models.T_VIDEO:
                                    if (!opt[2]) continue;
                                    name = "videos/video_" + (++video) + ".mp4"; break;
                                case Models.T_VOICE:
                                    if (!opt[3]) continue;
                                    name = "voice/voice_" + (++voice) + ".m4a"; break;
                                case Models.T_CIRCLE:
                                    if (!opt[4]) continue;
                                    name = "circles/circle_" + (++circle) + ".mp4"; break;
                                case Models.T_AUDIO:
                                    if (!opt[5]) continue;
                                    name = "audio/" + (a.fileName == null
                                            ? "audio_" + (++audio) + ".mp3" : a.fileName); break;
                                default: continue;
                            }
                            zos.putNextEntry(new ZipEntry(name));
                            zos.write(data);
                            zos.closeEntry();
                            if (opt[0]) text.append(stamp).append(who)
                                    .append(": [вложение] ").append(name).append("\n");
                        }
                        if (opt[0] && m.text != null && !m.text.isEmpty())
                            text.append(stamp).append(who).append(": ")
                                    .append(m.text).append("\n");
                    }

                    if (opt[0]) {
                        zos.putNextEntry(new ZipEntry("chat.txt"));
                        zos.write(text.toString().getBytes("UTF-8"));
                        zos.closeEntry();
                    }
                }

                final File out = zipFile;
                act.runOnUiThread(() -> {
                    Toast.makeText(act, "Сохранено: " + out.getName(), Toast.LENGTH_LONG).show();
                    try {
                        android.net.Uri uri = FileProvider.getUriForFile(act,
                                act.getPackageName() + ".fileprovider", out);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("application/zip");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        act.startActivity(Intent.createChooser(share, "Переписка Tsuyu"));
                    } catch (Exception ignored) { }
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> Toast.makeText(act,
                        "Ошибка экспорта", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
