package com.rutera.core.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

/** A browser in the tests: its subscription keys and the user agent side of RFC 8291 to read what arrives. */
public final class TestPushDevice {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair keys;
    private final byte[] auth = new byte[16];

    public TestPushDevice() throws Exception {
        keys = WebPush.newPair();
        new SecureRandom().nextBytes(auth);
    }

    public String p256dh() {
        return B64.encodeToString(WebPush.encodePoint((ECPublicKey) keys.getPublic()));
    }

    public String auth() {
        return B64.encodeToString(auth);
    }

    /** The plaintext of an aes128gcm body sent to this device. */
    public String decrypt(byte[] body) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt();
        byte[] asPublic = new byte[in.get()];
        in.get(asPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init((ECPrivateKey) keys.getPrivate());
        agreement.doPhase(WebPush.publicKey(asPublic), true);
        byte[] uaPublic = WebPush.encodePoint((ECPublicKey) keys.getPublic());
        byte[] prkKey = hmac(auth, agreement.generateSecret());
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.writeBytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII));
        info.writeBytes(uaPublic);
        info.writeBytes(asPublic);
        info.write(1);
        byte[] prk = hmac(salt, hmac(prkKey, info.toByteArray()));
        byte[] cek = Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        int end = padded.length - 1;
        while (padded[end] == 0) {
            end--;
        }
        if (padded[end] != 2) {
            throw new IllegalStateException("no last-record delimiter");
        }
        return new String(padded, 0, end, StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
