package com.tsuyu.messenger.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.LruCache;

import com.tsuyu.messenger.crypto.ProfileCrypto;

/**
 * High-performance local SQLite storage for decrypted message payloads.
 *
 * Double Ratchet message keys are single-use / ephemeral and ratchet forward.
 * Once a message is decrypted (via background service, dialog preview, or chat activity),
 * its payload is saved to this local encrypted store.
 * Subsequent reads load instantly from memory/disk without touching Double Ratchet,
 * preventing AEAD tag mismatch errors and out-of-order ratchet desynchronization.
 */
public class LocalMessageStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "tsuyu_local_messages.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_NAME = "cached_messages";

    private static LocalMessageStore instance;
    private final LruCache<String, String> memCache = new LruCache<>(500);

    private LocalMessageStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static synchronized LocalMessageStore get(Context context) {
        if (instance == null) {
            instance = new LocalMessageStore(context);
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                "msg_id TEXT PRIMARY KEY, " +
                "me_uid TEXT NOT NULL, " +
                "peer_uid TEXT NOT NULL, " +
                "ts INTEGER NOT NULL, " +
                "payload TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_peer ON " + TABLE_NAME + " (me_uid, peer_uid)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_msg ON " + TABLE_NAME + " (me_uid, msg_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    private static String cacheKey(String me, String msgId) {
        return me + ":" + msgId;
    }

    /**
     * Retrieves decrypted plaintext JSON payload from local cache.
     * Returns null if message has not been decrypted / cached yet.
     */
    public synchronized String getPayload(String me, String msgId) {
        if (me == null || msgId == null) return null;
        String cached = memCache.get(cacheKey(me, msgId));
        if (cached != null) return cached;

        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.query(TABLE_NAME, new String[]{"payload"},
                    "msg_id = ? AND me_uid = ?", new String[]{msgId, me},
                    null, null, null, "1")) {
                if (c != null && c.moveToFirst()) {
                    String sealed = c.getString(0);
                    String plain = ProfileCrypto.open(me, sealed);
                    if (plain != null) {
                        memCache.put(cacheKey(me, msgId), plain);
                        return plain;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Persists decrypted plaintext JSON payload to encrypted local SQLite database.
     */
    public synchronized void putPayload(String me, String peerUid, String msgId, long ts, String payload) {
        if (me == null || msgId == null || payload == null) return;
        memCache.put(cacheKey(me, msgId), payload);

        try {
            String sealed = ProfileCrypto.seal(me, payload);
            if (sealed == null) return;

            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("msg_id", msgId);
            cv.put("me_uid", me);
            cv.put("peer_uid", peerUid != null ? peerUid : "");
            cv.put("ts", ts);
            cv.put("payload", sealed);
            cv.put("created_at", System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_NAME, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) { }
    }

    public synchronized void deleteMessage(String me, String msgId) {
        if (me == null || msgId == null) return;
        memCache.remove(cacheKey(me, msgId));
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_NAME, "msg_id = ? AND me_uid = ?", new String[]{msgId, me});
        } catch (Exception ignored) { }
    }

    public synchronized void clearChat(String me, String peerUid) {
        if (me == null || peerUid == null) return;
        memCache.evictAll();
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_NAME, "me_uid = ? AND peer_uid = ?", new String[]{me, peerUid});
        } catch (Exception ignored) { }
    }

    public synchronized void clearAll() {
        memCache.evictAll();
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_NAME, null, null);
        } catch (Exception ignored) { }
    }
}
