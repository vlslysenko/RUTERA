package com.rutera.core.push;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Web Push crypto: the example of RFC 8291 Appendix A, a decryption round trip and the VAPID JWT. */
class WebPushTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void encryptsTheExampleOfRfc8291() throws Exception {
        byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
        KeyPair server = new KeyPair(
                WebPush.publicKey(WebPush.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                WebPush.privateKey(WebPush.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
        byte[] body = WebPush.encrypt(plaintext,
                WebPush.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                WebPush.decode("BTBZMqHH6r4Tts7J_aSIgg"), server, WebPush.decode("DGv6ra1nlYgDCS1FRnbzlw"));
        assertThat(B64.encodeToString(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy2"
                + "7mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyou"
                + "BWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void aDeviceDecryptsWhatTheServerEncrypts() throws Exception {
        KeyPair device = WebPush.newPair();
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        String json = "{\"title\":\"Nowy kurs T-2026-0017\",\"body\":\"Start 05.11.2026 06:00\"}";
        byte[] body = WebPush.encrypt(json.getBytes(StandardCharsets.UTF_8),
                B64.encodeToString(WebPush.encodePoint((ECPublicKey) device.getPublic())), B64.encodeToString(auth));
        assertThat(new String(decrypt(body, device, auth), StandardCharsets.UTF_8)).isEqualTo(json);
        assertThatThrownBy(() -> WebPush.encrypt(new byte[4000], B64.encodeToString(
                WebPush.encodePoint((ECPublicKey) device.getPublic())), B64.encodeToString(auth)))
                .hasMessageContaining("too large");
    }

    @Test
    void vapidSignsAJwtForTheOriginOfTheEndpoint() throws Exception {
        String[] keys = WebPush.generateKeys();
        WebPush.Vapid vapid = WebPush.vapid(keys[0], keys[1], "mailto:ops@example.com");
        String header = WebPush.authorization(vapid, "https://fcm.googleapis.com/fcm/send/abc:def", 1_800_000_000L);
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys[0]);
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1800043200,"
                + "\"sub\":\"mailto:ops@example.com\"}");
        assertThat(WebPush.verify(vapid.publicKey(), (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        String[] other = WebPush.generateKeys();
        assertThatThrownBy(() -> WebPush.vapid(keys[0], other[1], "mailto:x@example.com"))
                .hasMessageContaining("does not belong");
    }

    /** The user agent side of RFC 8291, to check the server side. */
    private static byte[] decrypt(byte[] body, KeyPair device, byte[] auth) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt();
        byte[] asPublic = new byte[in.get()];
        in.get(asPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init((ECPrivateKey) device.getPrivate());
        agreement.doPhase(WebPush.publicKey(asPublic), true);
        byte[] uaPublic = WebPush.encodePoint((ECPublicKey) device.getPublic());
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
        assertThat(padded[end]).as("last record delimiter").isEqualTo((byte) 2);
        return Arrays.copyOf(padded, end);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
