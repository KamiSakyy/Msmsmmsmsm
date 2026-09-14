package io.tsuyu.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.tsuyu.app.core.Fb;
import io.tsuyu.app.core.Ratchet;
import io.tsuyu.app.model.Msg;
import io.tsuyu.app.notif.Notifier;

import org.json.JSONObject;

/** Invisible activity: handles direct reply from notifications. */
public class ReplyHandlerActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String chatId = null;
        String text = null;
        Intent intent = getIntent();
        try {
            android.os.Bundle resB = androidx.core.app.RemoteInput.getResultsFromIntent(intent);
            if (resB != null) {
                for (String k : resB.keySet()) {
                    androidx.core.app.RemoteInput r = resB.getParcelable(k);
                    if (r == null) continue;
                    CharSequence res = r.getResultsText(intent);
                    if (res != null) text = res.toString().trim();
                }
            }
            chatId = intent.getStringExtra("chatId");
        } catch (Throwable ignored) {}

        boolean ok = false;
        if (chatId != null && text != null && text.length() > 0) {
            try {
                final String cid = chatId;
                final String tx = text;
                final String me = Fb.myUid();
                final String other = Fb.otherOf(cid);
                final String key = System.currentTimeMillis() + "_" + (int) (Math.random() * 999999);
                Ratchet.send(this, other, new JSONObject().put("tx", tx).toString().getBytes("UTF-8"), env -> {
                    if (env == null) return;
                    try {
                        Msg m = new Msg();
                        m.key = key;
                        m.ts = System.currentTimeMillis();
                        m.from = me;
                        m.type = "text";
                        m.payload = new JSONObject().put("tx", tx);
                        m.e = new JSONObject(env);
                        JSONObject patch = new JSONObject();
                        patch.put("c", cid);
                        patch.put("msgs/" + key, Msg.toDb(m));
                        patch.put("last/ts", m.ts);
                        patch.put("last/ty", "text");
                        patch.put("last/by", me);
                        patch.put("last/k", key);
                        Fb.fb().getReference("chats/" + cid).updateChildren(Fb.toMap(patch));
                        ok = true;
                    } catch (Throwable t) {
                        ok = false;
                    }
                });
                Thread.sleep(2500); // let the async send complete before finishing
            } catch (Throwable t) {
                ok = false;
            }
        }
        setResult(ok ? RESULT_OK : RESULT_CANCELED);
        finish();
    }
}
