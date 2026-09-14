package io.tsuyu.app.model;

import org.json.JSONArray;
import org.json.JSONObject;

/** A chat message: unencrypted meta + decrypted payload (after ratchet decrypt). */
public class Msg {
    public String key;        // RTDB key (id)
    public String from;       // sender uid
    public long ts;           // send time
    public String type;       // text|photo|video|voice|music|circle|collage|call
    public JSONObject payload; // decrypted payload or null (still encrypted/failed)
    public int decryptState;  // 0 unknown, 1 ok, 2 pending, 3 failed
    public long editedTs;
    public String replyTo;
    public String fwdFrom;
    public boolean hiddenByMe;
    public long readByMe;
    public long deliveredToMe;
    public long deletedForAll; // >0 means deleted for both
    public JSONObject reactions; // uid -> 1
    public JSONObject meta;    // raw meta for preview (type, dur, w, h)
    public JSONObject e;       // ratchet envelope (unencrypted, in RTDB)

    public static java.util.Map<String, Object> toDb(Msg msg) {
        java.util.Map<String, Object> d = new java.util.HashMap<>();
        d.put("k", msg.key);
        d.put("f", msg.from);
        d.put("t", msg.ts);
        d.put("ty", msg.type == null ? "text" : msg.type);
        if (msg.e != null) d.put("e", io.tsuyu.app.core.Fb.toMap(msg.e));
        java.util.Map<String, Object> m2 = new java.util.HashMap<>();
        if (msg.replyTo != null) m2.put("reply", msg.replyTo);
        if (msg.fwdFrom != null) m2.put("fw", msg.fwdFrom);
        if (m2.isEmpty() == false) d.put("m2", m2);
        if (msg.reactions != null && msg.reactions.length() > 0) {
            java.util.Map<String, Object> r = new java.util.HashMap<>();
            java.util.Iterator<String> it = msg.reactions.keys();
            while (it.hasNext()) r.put(it.next(), 1);
            d.put("r", r);
        }
        return d;
    }

    /** Parse + decrypt a msg snapshot (device-side ratchet). */
    public static Msg fromDb(android.content.Context ctx, String peerUid, com.google.firebase.database.DataSnapshot s) {
        try {
            Msg msg = new Msg();
            msg.key = s.getKey();
            msg.from = s.child("f").getValue(String.class);
            Long ts = s.child("t").getValue(Long.class);
            msg.ts = ts == null ? 0 : ts;
            msg.type = s.child("ty").getValue(String.class);
            Long ed = s.child("ed").getValue(Long.class);
            msg.editedTs = ed == null ? 0 : ed;
            msg.replyTo = s.child("m2").child("reply").getValue(String.class);
            msg.fwdFrom = s.child("m2").child("fw").getValue(String.class);
            msg.hiddenByMe = Boolean.TRUE.equals(s.child("hb").child(io.tsuyu.app.core.Fb.myUid()).getValue(Boolean.class));
            Long rdTs = s.child("rd").child(io.tsuyu.app.core.Fb.myUid()).getValue(Long.class);
            msg.readByMe = rdTs == null ? 0 : rdTs;
            Long dTs = s.child("d").child(io.tsuyu.app.core.Fb.myUid()).getValue(Long.class);
            msg.deliveredToMe = dTs == null ? 0 : dTs;
            Long delTs = s.child("del").getValue(Long.class);
            msg.deletedForAll = delTs == null ? 0 : delTs;
            msg.reactions = new JSONObject();
            com.google.firebase.database.DataSnapshot r = s.child("r");
            if (r.exists()) {
                for (com.google.firebase.database.DataSnapshot rr : r.getChildren()) msg.reactions.put(rr.getKey(), 1);
            }
            Object eo = s.child("e").getValue();
            if (eo instanceof java.util.Map) {
                JSONObject env = new JSONObject((java.util.Map<String, Object>) eo);
                byte[] payload = io.tsuyu.app.core.Ratchet.receive(ctx, peerUid, env);
                if (payload != null) {
                    msg.payload = new JSONObject(new String(payload, "UTF-8"));
                    msg.decryptState = 1;
                } else {
                    msg.decryptState = 3;
                }
            } else {
                msg.decryptState = 3;
            }
            return msg;
        } catch (Throwable t) {
            return null;
        }
    }

    // payload convenience
    public String text() { return payload == null ? null : payload.optString("tx", null); }
    public String mediaB64() { return payload == null ? null : payload.optString("m", null); }
    public String mediaType() { return payload == null ? null : payload.optString("wt", "jpg"); }
    public int w() { return payload == null ? 0 : payload.optInt("w", 0); }
    public int h() { return payload == null ? 0 : payload.optInt("h", 0); }
    public double dur() { return payload == null ? 0 : payload.optDouble("dur", 0); }
    public String name() { return payload == null ? null : payload.optString("name", null); }
    public double[] wave() {
        if (payload == null) return null;
        try {
            JSONArray a = payload.optJSONArray("wave");
            if (a == null) return null;
            double[] out = new double[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.getDouble(i);
            return out;
        } catch (Throwable t) { return null; }
    }
    public JSONArray collage() {
        return payload == null ? null : payload.optJSONArray("items");
    }
}
