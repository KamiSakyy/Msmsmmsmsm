package com.tsuyu.messenger.crypto;

/**
 * X3DH key agreement (no one-time pre-keys: asynchronous, always-available variant).
 *
 * SK = HKDF( DH(IKa, SPKb) || DH(EKa, IKb) || DH(EKa, SPKb) )
 */
public final class X3DH {

    private static final byte[] INFO = "TsuyuX3DH".getBytes();

    private X3DH() {}

    public static class Initiated {
        public byte[] sharedSecret;
        public byte[] ephemeralPub;
    }

    /** Sender side: derive SK toward a peer bundle we may never have talked to. */
    public static Initiated initiate(byte[] ourIdPriv, byte[] peerIdPub, byte[] peerSpkPub) {
        byte[][] eph = CryptoUtil.generateX25519();
        byte[] dh1 = CryptoUtil.dh(ourIdPriv, peerSpkPub);
        byte[] dh2 = CryptoUtil.dh(eph[0], peerIdPub);
        byte[] dh3 = CryptoUtil.dh(eph[0], peerSpkPub);

        byte[] ikm = concat(dh1, dh2, dh3);
        Initiated out = new Initiated();
        out.sharedSecret = CryptoUtil.hkdf(ikm, new byte[32], INFO, 32);
        out.ephemeralPub = eph[1];
        return out;
    }

    /** Receiver side: reconstruct the same SK from the sender's identity + ephemeral key. */
    public static byte[] respond(byte[] ourIdPriv, byte[] ourSpkPriv, byte[] peerIdPub, byte[] peerEphPub) {
        byte[] dh1 = CryptoUtil.dh(ourSpkPriv, peerIdPub);
        byte[] dh2 = CryptoUtil.dh(ourIdPriv, peerEphPub);
        byte[] dh3 = CryptoUtil.dh(ourSpkPriv, peerEphPub);
        byte[] ikm = concat(dh1, dh2, dh3);
        return CryptoUtil.hkdf(ikm, new byte[32], INFO, 32);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
