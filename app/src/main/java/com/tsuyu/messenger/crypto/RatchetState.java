package com.tsuyu.messenger.crypto;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.HashMap;
import java.util.Map;

/** Serializable Double Ratchet state for one conversation. */
public class RatchetState {

    public byte[] dhsPriv;      // our current ratchet private key
    public byte[] dhsPub;       // our current ratchet public key
    public byte[] dhrPub;       // remote ratchet public key (may be null before first recv)
    public byte[] rootKey;      // RK
    public byte[] chainSend;    // CKs
    public byte[] chainRecv;    // CKr
    public int sendCount;       // Ns
    public int recvCount;       // Nr
    public int prevChainLen;    // PN

    /** Skipped message keys: "base64(dhPub)|index" -> messageKey. */
    public final Map<String, byte[]> skipped = new HashMap<>();

    public static final int MAX_SKIP = 1000;

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("dhsPriv", CryptoUtil.b64(dhsPriv));
        o.put("dhsPub", CryptoUtil.b64(dhsPub));
        if (dhrPub != null) o.put("dhrPub", CryptoUtil.b64(dhrPub));
        o.put("rootKey", CryptoUtil.b64(rootKey));
        if (chainSend != null) o.put("chainSend", CryptoUtil.b64(chainSend));
        if (chainRecv != null) o.put("chainRecv", CryptoUtil.b64(chainRecv));
        o.put("sendCount", sendCount);
        o.put("recvCount", recvCount);
        o.put("prevChainLen", prevChainLen);
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, byte[]> e : skipped.entrySet()) {
            JSONObject s = new JSONObject();
            s.put("k", e.getKey());
            s.put("v", CryptoUtil.b64(e.getValue()));
            arr.put(s);
        }
        o.put("skipped", arr);
        return o;
    }

    public static RatchetState fromJson(JSONObject o) throws Exception {
        RatchetState s = new RatchetState();
        s.dhsPriv = CryptoUtil.unb64(o.getString("dhsPriv"));
        s.dhsPub = CryptoUtil.unb64(o.getString("dhsPub"));
        s.dhrPub = o.has("dhrPub") ? CryptoUtil.unb64(o.getString("dhrPub")) : null;
        s.rootKey = CryptoUtil.unb64(o.getString("rootKey"));
        s.chainSend = o.has("chainSend") ? CryptoUtil.unb64(o.getString("chainSend")) : null;
        s.chainRecv = o.has("chainRecv") ? CryptoUtil.unb64(o.getString("chainRecv")) : null;
        s.sendCount = o.optInt("sendCount");
        s.recvCount = o.optInt("recvCount");
        s.prevChainLen = o.optInt("prevChainLen");
        JSONArray arr = o.optJSONArray("skipped");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                s.skipped.put(e.getString("k"), CryptoUtil.unb64(e.getString("v")));
            }
        }
        return s;
    }
}
