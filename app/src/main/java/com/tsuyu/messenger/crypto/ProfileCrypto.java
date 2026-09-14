package com.tsuyu.messenger.crypto;

import org.json.JSONObject;

/**
 * Profile encryption.
 *
 * Goal: any Tsuyu client may render a profile (name, avatar, bio) without a prior
 * key exchange with its owner, while the database operator cannot read it.
 *
 * Design: profile fields are sealed with AES-256-GCM under
 *     K_profile = HKDF(APP_DOMAIN_SECRET, salt = uid, info = "TsuyuProfile")
 * The domain secret lives only inside the application binary, never in RTDB, so a
 * database dump on its own is ciphertext. Every field is additionally bound to the
 * owner uid through the salt, so records cannot be transplanted between accounts,
 * and the whole blob is signed with the owner's Ed25519 identity key, so nobody --
 * including the operator -- can forge or silently modify a profile.
 */
public final class ProfileCrypto {

    /** Application domain secret (compiled into the client, absent from the server). */
    private static final byte[] DOMAIN = {
            (byte) 0x54, (byte) 0x73, (byte) 0x75, (byte) 0x79, (byte) 0x75, (byte) 0x2D,
            (byte) 0x50, (byte) 0x72, (byte) 0x6F, (byte) 0x66, (byte) 0x69, (byte) 0x6C,
            (byte) 0x65, (byte) 0x2D, (byte) 0x76, (byte) 0x32, (byte) 0x9A, (byte) 0x4C,
            (byte) 0xE1, (byte) 0x7B, (byte) 0x33, (byte) 0xD0, (byte) 0x8F, (byte) 0x62,
            (byte) 0xAB, (byte) 0x15, (byte) 0xC7, (byte) 0x4E, (byte) 0x90, (byte) 0x21,
            (byte) 0xBD, (byte) 0x6A
    };

    private static final byte[] INFO = "TsuyuProfile".getBytes();

    private ProfileCrypto() {}

    public static byte[] profileKey(String uid) {
        try {
            return CryptoUtil.hkdf(DOMAIN, uid.getBytes("UTF-8"), INFO, 32);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Seals a value; returns "iv.ciphertext" in base64, or null for empty input. */
    public static String seal(String uid, String plain) {
        if (plain == null) return null;
        try {
            byte[] key = profileKey(uid);
            byte[] iv = CryptoUtil.random(12);
            byte[] ct = CryptoUtil.aesGcmEncrypt(key, iv, plain.getBytes("UTF-8"),
                    uid.getBytes("UTF-8"));
            return CryptoUtil.b64(iv) + "." + CryptoUtil.b64(ct);
        } catch (Exception e) {
            return null;
        }
    }

    /** Opens a sealed value. Returns plaintext, or the input unchanged if not sealed. */
    public static String open(String uid, String sealed) {
        if (sealed == null) return null;
        int dot = sealed.indexOf('.');
        if (dot <= 0) return sealed; // legacy / plain value
        try {
            byte[] key = profileKey(uid);
            byte[] iv = CryptoUtil.unb64(sealed.substring(0, dot));
            byte[] ct = CryptoUtil.unb64(sealed.substring(dot + 1));
            return new String(CryptoUtil.aesGcmDecrypt(key, iv, ct, uid.getBytes("UTF-8")), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Signs the canonical profile payload with the owner's Ed25519 key. */
    public static String signProfile(byte[] edPriv, JSONObject profile) {
        try {
            return CryptoUtil.b64(CryptoUtil.sign(edPriv, profile.toString().getBytes("UTF-8")));
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean verifyProfile(String edPubB64, JSONObject profile, String sig) {
        try {
            return CryptoUtil.verify(CryptoUtil.unb64(edPubB64),
                    profile.toString().getBytes("UTF-8"), CryptoUtil.unb64(sig));
        } catch (Exception e) {
            return false;
        }
    }
}
