package com.tsuyu.messenger;

import static org.junit.Assert.*;

import com.tsuyu.messenger.crypto.CryptoUtil;
import com.tsuyu.messenger.crypto.DoubleRatchet;
import com.tsuyu.messenger.crypto.RatchetState;
import com.tsuyu.messenger.crypto.X3DH;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CryptoTest {

    private static byte[] utf8(String s) throws Exception { return s.getBytes("UTF-8"); }
    private static String str(byte[] b) throws Exception { return new String(b, "UTF-8"); }

    @Test
    public void base64RoundTrip() {
        for (int len = 0; len < 200; len++) {
            byte[] data = CryptoUtil.random(len);
            assertArrayEquals("len " + len, data, CryptoUtil.unb64(CryptoUtil.b64(data)));
        }
    }

    @Test
    public void hkdfIsDeterministic() {
        byte[] ikm = CryptoUtil.random(32);
        byte[] salt = CryptoUtil.random(32);
        byte[] a = CryptoUtil.hkdf(ikm, salt, "info".getBytes(), 64);
        byte[] b = CryptoUtil.hkdf(ikm, salt, "info".getBytes(), 64);
        assertArrayEquals(a, b);
        assertEquals(64, a.length);
    }

    @Test
    public void x25519AgreementMatches() {
        byte[][] a = CryptoUtil.generateX25519();
        byte[][] b = CryptoUtil.generateX25519();
        assertArrayEquals(CryptoUtil.dh(a[0], b[1]), CryptoUtil.dh(b[0], a[1]));
    }

    @Test
    public void ed25519SignVerify() {
        byte[][] k = CryptoUtil.generateEd25519();
        byte[] msg = CryptoUtil.random(64);
        byte[] sig = CryptoUtil.sign(k[0], msg);
        assertTrue(CryptoUtil.verify(k[1], msg, sig));
        msg[0] ^= 1;
        assertFalse(CryptoUtil.verify(k[1], msg, sig));
    }

    @Test
    public void x3dhSharedSecretsMatch() {
        byte[][] aliceId = CryptoUtil.generateX25519();
        byte[][] bobId = CryptoUtil.generateX25519();
        byte[][] bobSpk = CryptoUtil.generateX25519();

        X3DH.Initiated init = X3DH.initiate(aliceId[0], bobId[1], bobSpk[1]);
        byte[] bobSk = X3DH.respond(bobId[0], bobSpk[0], aliceId[1], init.ephemeralPub);
        assertArrayEquals(init.sharedSecret, bobSk);
    }

    /** Full asynchronous flow: Alice writes to an offline Bob who has never messaged her. */
    @Test
    public void asynchronousFirstMessageDecrypts() throws Exception {
        byte[][] aliceId = CryptoUtil.generateX25519();
        byte[][] bobId = CryptoUtil.generateX25519();
        byte[][] bobSpk = CryptoUtil.generateX25519();

        X3DH.Initiated init = X3DH.initiate(aliceId[0], bobId[1], bobSpk[1]);
        RatchetState alice = DoubleRatchet.initSender(init.sharedSecret, bobSpk[1]);
        JSONObject env = DoubleRatchet.encrypt(alice, utf8("Привет, это Tsuyu!"));

        byte[] bobSk = X3DH.respond(bobId[0], bobSpk[0], aliceId[1], init.ephemeralPub);
        RatchetState bob = DoubleRatchet.initReceiver(bobSk, bobSpk[0], bobSpk[1]);
        assertEquals("Привет, это Tsuyu!", str(DoubleRatchet.decrypt(bob, env)));
    }

    @Test
    public void bidirectionalConversationWithRatchetSteps() throws Exception {
        Object[] pair = establish();
        RatchetState alice = (RatchetState) pair[0];
        RatchetState bob = (RatchetState) pair[1];

        for (int round = 0; round < 12; round++) {
            String am = "alice-" + round;
            assertEquals(am, str(DoubleRatchet.decrypt(bob, DoubleRatchet.encrypt(alice, utf8(am)))));
            String bm = "bob-" + round;
            assertEquals(bm, str(DoubleRatchet.decrypt(alice, DoubleRatchet.encrypt(bob, utf8(bm)))));
        }
    }

    @Test
    public void outOfOrderAndSkippedMessages() throws Exception {
        Object[] pair = establish();
        RatchetState alice = (RatchetState) pair[0];
        RatchetState bob = (RatchetState) pair[1];

        List<JSONObject> envs = new ArrayList<>();
        for (int i = 0; i < 8; i++) envs.add(DoubleRatchet.encrypt(alice, utf8("m" + i)));

        // Deliver last first, then the rest in reverse: every one must still decrypt.
        assertEquals("m7", str(DoubleRatchet.decrypt(bob, envs.get(7))));
        for (int i = 0; i < 7; i++) {
            assertEquals("m" + i, str(DoubleRatchet.decrypt(bob, envs.get(i))));
        }
    }

    @Test
    public void largeMediaPayloadRoundTrip() throws Exception {
        Object[] pair = establish();
        RatchetState alice = (RatchetState) pair[0];
        RatchetState bob = (RatchetState) pair[1];

        byte[] blob = CryptoUtil.random(512 * 1024); // ~512 KB "photo"
        String b64 = CryptoUtil.b64(blob);
        JSONObject env = DoubleRatchet.encrypt(alice, utf8(b64));
        assertArrayEquals(blob, CryptoUtil.unb64(str(DoubleRatchet.decrypt(bob, env))));
    }

    @Test
    public void tamperedCiphertextIsRejected() throws Exception {
        Object[] pair = establish();
        RatchetState alice = (RatchetState) pair[0];
        RatchetState bob = (RatchetState) pair[1];

        JSONObject env = DoubleRatchet.encrypt(alice, utf8("secret"));
        byte[] ct = CryptoUtil.unb64(env.getString("ct"));
        ct[0] ^= 0x40;
        env.put("ct", CryptoUtil.b64(ct));
        try {
            DoubleRatchet.decrypt(bob, env);
            fail("tampered ciphertext must not authenticate");
        } catch (Exception expected) {
            // AEAD tag failure
        }
    }

    @Test
    public void stateSurvivesSerialization() throws Exception {
        Object[] pair = establish();
        RatchetState alice = (RatchetState) pair[0];
        RatchetState bob = (RatchetState) pair[1];

        JSONObject env1 = DoubleRatchet.encrypt(alice, utf8("before reload"));
        RatchetState reloaded = RatchetState.fromJson(RatchetState.fromJson(bob.toJson()).toJson());
        assertEquals("before reload", str(DoubleRatchet.decrypt(reloaded, env1)));

        // continue on the reloaded state
        JSONObject reply = DoubleRatchet.encrypt(reloaded, utf8("after reload"));
        assertEquals("after reload", str(DoubleRatchet.decrypt(alice, reply)));
    }

    /** Alice sends first; Bob receives it so both sides hold live chains. */
    private Object[] establish() throws Exception {
        byte[][] aliceId = CryptoUtil.generateX25519();
        byte[][] bobId = CryptoUtil.generateX25519();
        byte[][] bobSpk = CryptoUtil.generateX25519();

        X3DH.Initiated init = X3DH.initiate(aliceId[0], bobId[1], bobSpk[1]);
        RatchetState alice = DoubleRatchet.initSender(init.sharedSecret, bobSpk[1]);
        JSONObject hello = DoubleRatchet.encrypt(alice, utf8("hello"));

        byte[] bobSk = X3DH.respond(bobId[0], bobSpk[0], aliceId[1], init.ephemeralPub);
        RatchetState bob = DoubleRatchet.initReceiver(bobSk, bobSpk[0], bobSpk[1]);
        DoubleRatchet.decrypt(bob, hello);
        return new Object[]{alice, bob};
    }
}
