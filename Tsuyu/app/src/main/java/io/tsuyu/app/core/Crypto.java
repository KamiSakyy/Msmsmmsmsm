package io.tsuyu.app.core;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/**
 * Primitive crypto: X25519 ECDH, HKDF-SHA256, AES-256-GCM, PBKDF2, KDF2.
 * All keys are 32 bytes (base64 encoded on the wire).
 */
public class Crypto {
    public static final String BC = "BC";
    private static final SecureRandom RNG = new SecureRandom();

    // ---------- base64 ----------
    public static String b64(byte[] data) {
        return data == null ? null : Base64.encodeToString(data, Base64.NO_WRAP | Base64.NO_PADDING);
    }
    public static byte[] unb64(String s) {
        if (s == null) return null;
        return Base64.decode(s, Base64.DEFAULT);
    }

    // ---------- random ----------
    public static byte[] rand(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    // ---------- X25519 ----------
    /** Generate X25519 keypair. Returns [priv(32), pub(32)] concatenated (64 bytes). */
    public static byte[] x25519Generate() {
        byte[] seed = rand(32);
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(seed, 0);
        X25519PublicKeyParameters pub = priv.generatePublicKey();
        byte[] out = new byte[64];
        System.arraycopy(seed, 0, out, 0, 32);
        System.arraycopy(pub.getEncoded(), 0, out, 32, 32);
        return out;
    }

    /** ECDH: priv(32) x pub(32) -> 32 bytes shared secret. */
    public static byte[] x25519(byte[] priv, byte[] pub) {
        try {
            X25519PrivateKeyParameters p = new X25519PrivateKeyParameters(priv, 0);
            X25519PublicKeyParameters q = new X25519PublicKeyParameters(pub, 0);
            X25519Agreement agr = new X25519Agreement();
            agr.init(p);
            byte[] out = new byte[32];
            int len = agr.calculateSecret(q, out, 0);
            byte[] r = Arrays.copyOf(out, len);
            // constant-time reject all-zero
            int zero = 0;
            for (byte b : r) zero |= (b == 0) ? 0 : 1;
            if (zero == 0) throw new IllegalArgumentException("bad ecdh");
            return r;
        } catch (Throwable t) {
            throw new RuntimeException("ECDH failed", t);
        }
    }

    // ---------- SHA256 / HMAC / HKDF ----------
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static String sha256Hex(byte[] data) {
        byte[] d = sha256(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** HKDF-SHA256 (RFC 5869). */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        if (salt == null) salt = new byte[32];
        if (info == null) info = new byte[0];
        byte[] prk = hmac(salt, ikm);
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int offset = 0;
        int i = 1;
        while (offset < len) {
            byte[] in = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, in, 0, t.length);
            System.arraycopy(info, 0, in, t.length, info.length);
            in[in.length - 1] = (byte) i;
            t = hmac(prk, in);
            int n = Math.min(t.length, len - offset);
            System.arraycopy(t, 0, out, offset, n);
            offset += n;
            i++;
        }
        return out;
    }

    /**
     * KDF2(chainKey, dhSecret) -> [messageKey, newChainKey]
     * Domain-separated, like Signal's KDF2.
     */
    public static byte[][] kdf2(byte[] chainKey, byte[] dhSecret) {
        byte[] mk = hkdf(chainKey, dhSecret, str("TSUYU-MK"), 32);
        byte[] ck = hkdf(chainKey, dhSecret, str("TSUYU-CK"), 32);
        return new byte[][]{mk, ck};
    }

    private static byte[] str(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    // ---------- AES-GCM ----------
    /** Encrypt: returns iv(12) | ciphertext | tag(16). */
    public static byte[] aesGcmEncrypt(byte[] key32, byte[] plain) {
        try {
            byte[] iv = rand(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain == null ? new byte[0] : plain);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(iv);
            bos.write(ct);
            return bos.toByteArray();
        } catch (Throwable t) { throw new RuntimeException("AES-GCM enc failed", t); }
    }

    public static byte[] aesGcmDecrypt(byte[] key32, byte[] ivCtTag) {
        try {
            if (ivCtTag == null || ivCtTag.length < 29) throw new IllegalArgumentException("bad ct");
            byte[] iv = Arrays.copyOfRange(ivCtTag, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, iv));
            return c.doFinal(Arrays.copyOfRange(ivCtTag, 12, ivCtTag.length));
        } catch (Throwable t) { throw new RuntimeException("AES-GCM dec failed", t); }
    }

    // ---------- PBKDF2 (for key export encryption) ----------
    public static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int len) {
        try {
            byte[] out = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new javax.crypto.spec.PBEKeySpec(
                            new String(password, "UTF-8").toCharArray(), salt, iterations, len * 8))
                    .getEncoded();
            return out;
        } catch (Throwable t) { throw new RuntimeException("PBKDF2 failed", t); }
    }

}
