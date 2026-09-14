package io.tsuyu.app.core;

/**
 * Profile fields are encrypted with an app-wide "view key" so that:
 *  - any installed client can read/decrypt profiles (no key exchange needed),
 *  - the Firebase server only ever stores ciphertext (cannot read profiles).
 * The view key is embedded in the app binary (obfuscated), never transmitted.
 */
public class ProfileCipher {
    private static final byte[] K1 = {0x54,0x73,0x55,0x79,0x55,0x2E,0x61,0x70,0x70,0x21,0x21,0x21,0x45,0x45,0x32,0x21,0x32,0x30,0x32,0x36,0x23,0x24,0x25,0x26,0x2A,0x2A,0x2A,0x40,0x2A,0x21,0x21,0x3F};
    private static final byte[] K2 = {0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,0x18,0x19,0x1A,0x1B,0x1C,0x1D,0x1E,0x1F};

    private static byte[] viewKey() {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (K1[i] ^ K2[i]);
        return k;
    }

    /** Encrypt arbitrary JSON/string -> base64 ciphertext. */
    public static String enc(String plain) {
        if (plain == null) return null;
        byte[] ct = Crypto.aesGcmEncrypt(viewKey(), plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Crypto.b64(ct);
    }

    public static String dec(String b64Cipher) {
        if (b64Cipher == null || b64Cipher.isEmpty()) return null;
        try {
            byte[] plain = Crypto.aesGcmDecrypt(viewKey(), Crypto.unb64(b64Cipher));
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
