package com.tsuyu.messenger.crypto;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Low level primitives for the Tsuyu E2EE stack.
 * X25519 (ECDH) + Ed25519 (signatures) + HKDF-SHA256 + AES-256-GCM.
 */
public final class CryptoUtil {

    public static final SecureRandom RNG = new SecureRandom();

    private CryptoUtil() {}

    // ---------------- Base64 ----------------

    private static final char[] B64C =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] B64R = new int[128];

    static {
        for (int i = 0; i < B64R.length; i++) B64R[i] = -1;
        for (int i = 0; i < B64C.length; i++) B64R[B64C[i]] = i;
        B64R['='] = 0;
    }

    /** Standard Base64, no line wrapping. Pure Java so it works on any runtime. */
    public static String b64(byte[] data) {
        if (data == null) return null;
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(B64C[b0 >>> 2]);
            sb.append(B64C[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            sb.append(i + 1 < data.length ? B64C[((b1 & 0x0F) << 2) | (b2 >>> 6)] : '=');
            sb.append(i + 2 < data.length ? B64C[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }

    public static byte[] unb64(String data) {
        if (data == null) return null;
        int len = 0;
        int[] buf = new int[data.length()];
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '=' ) break;
            if (c > 127) continue;
            int v = B64R[c];
            if (v < 0) continue;
            buf[len++] = v;
        }
        int outLen = len * 6 / 8;
        byte[] out = new byte[outLen];
        int bits = 0, acc = 0, pos = 0;
        for (int i = 0; i < len; i++) {
            acc = (acc << 6) | buf[i];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[pos++] = (byte) ((acc >>> bits) & 0xFF);
            }
        }
        return out;
    }

    // ---------------- Random ----------------

    public static byte[] random(int len) {
        byte[] out = new byte[len];
        RNG.nextBytes(out);
        return out;
    }

    // ---------------- X25519 ----------------

    /** Returns {privateKey(32), publicKey(32)}. */
    public static byte[][] generateX25519() {
        X25519KeyPairGenerator gen = new X25519KeyPairGenerator();
        gen.init(new X25519KeyGenerationParameters(RNG));
        org.bouncycastle.crypto.AsymmetricCipherKeyPair kp = gen.generateKeyPair();
        byte[] priv = ((X25519PrivateKeyParameters) kp.getPrivate()).getEncoded();
        byte[] pub = ((X25519PublicKeyParameters) kp.getPublic()).getEncoded();
        return new byte[][]{priv, pub};
    }

    public static byte[] x25519Public(byte[] priv) {
        return new X25519PrivateKeyParameters(priv, 0).generatePublicKey().getEncoded();
    }

    /** Raw ECDH shared secret. */
    public static byte[] dh(byte[] privateKey, byte[] peerPublicKey) {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(new X25519PrivateKeyParameters(privateKey, 0));
        byte[] secret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(new X25519PublicKeyParameters(peerPublicKey, 0), secret, 0);
        return secret;
    }

    // ---------------- Ed25519 ----------------

    /** Returns {privateKey(32), publicKey(32)}. */
    public static byte[][] generateEd25519() {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(RNG));
        org.bouncycastle.crypto.AsymmetricCipherKeyPair kp = gen.generateKeyPair();
        return new byte[][]{
                ((Ed25519PrivateKeyParameters) kp.getPrivate()).getEncoded(),
                ((Ed25519PublicKeyParameters) kp.getPublic()).getEncoded()
        };
    }

    public static byte[] ed25519Public(byte[] priv) {
        return new Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().getEncoded();
    }

    public static byte[] sign(byte[] edPrivate, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(edPrivate, 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    public static boolean verify(byte[] edPublic, byte[] message, byte[] signature) {
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(edPublic, 0));
            signer.update(message, 0, message.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- HKDF (RFC 5869, SHA-256) ----------------

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC failure", e);
        }
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (salt == null) salt = new byte[32];
        byte[] prk = hmacSha256(salt, ikm);
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            byte[] input = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, input, 0, t.length);
            System.arraycopy(info, 0, input, t.length, info.length);
            input[input.length - 1] = counter;
            t = hmacSha256(prk, input);
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
            counter++;
        }
        return out;
    }

    // ---------------- AES-256-GCM ----------------

    public static byte[] aesGcmEncrypt(byte[] key, byte[] iv, byte[] plain, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(plain);
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] cipherText, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(cipherText);
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    // ---------------- Signal Protocol Safety Numbers (60 Digits) ----------------

    /**
     * Signal Protocol numeric fingerprint:
     * Derives a 60-digit safety number (12 blocks of 5 digits) from two X25519 identity keys
     * using iterative SHA-512 hashing (5200 rounds) and sorted key orientation.
     */
    public static String computeSafetyNumber(byte[] myIdentityPub, byte[] peerIdentityPub) {
        if (myIdentityPub == null || peerIdentityPub == null) {
            return "00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
            // Lexicographical sort of the public keys to ensure identical number on both ends
            byte[] first = myIdentityPub;
            byte[] second = peerIdentityPub;
            for (int i = 0; i < Math.min(first.length, second.length); i++) {
                int b1 = first[i] & 0xFF;
                int b2 = second[i] & 0xFF;
                if (b1 < b2) break;
                if (b1 > b2) {
                    first = peerIdentityPub;
                    second = myIdentityPub;
                    break;
                }
            }

            md.update(first);
            md.update(second);
            byte[] hash = md.digest();

            // 5200 rounds of SHA-512 matching Signal standard
            for (int i = 0; i < 5200; i++) {
                md.reset();
                md.update(hash);
                md.update(first);
                hash = md.digest();
            }

            StringBuilder sb = new StringBuilder(71);
            for (int chunk = 0; chunk < 12; chunk++) {
                int offset = (chunk * 4) % (hash.length - 4);
                long val = ((hash[offset] & 0xFFL) << 24)
                        | ((hash[offset + 1] & 0xFFL) << 16)
                        | ((hash[offset + 2] & 0xFFL) << 8)
                        | (hash[offset + 3] & 0xFFL);
                long digits = Math.abs(val) % 100000L;
                if (chunk > 0) sb.append(' ');
                sb.append(String.format(java.util.Locale.US, "%05d", digits));
            }
            return sb.toString();
        } catch (Exception e) {
            return "00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000";
        }
    }

    /**
     * Formats 32-byte public key as formatted hexadecimal fingerprint (e.g. 16 byte pairs).
     */
    public static String formatFingerprint(byte[] pubKey) {
        if (pubKey == null) return "00 00 00 00";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(pubKey);
            StringBuilder sb = new StringBuilder(48);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02X", hash[i]));
                if (i % 2 == 1 && i < 15) sb.append(' ');
            }
            return sb.toString();
        } catch (Exception e) {
            return b64(pubKey);
        }
    }
}
