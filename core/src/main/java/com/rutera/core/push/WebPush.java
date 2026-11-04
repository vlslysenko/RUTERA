package com.rutera.core.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web Push with the JDK only: message encryption with the "aes128gcm" content coding (RFC 8291, RFC 8188) and VAPID
 * authorization (RFC 8292, an ES256 JWT). Keys are P-256: public keys as uncompressed points (65 bytes), the private
 * key as its 32-byte scalar, both base64url without padding, as browsers and push services use them.
 */
public final class WebPush {

    static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final ECParameterSpec P256 = p256();

    private WebPush() {
    }

    /** The application server's VAPID key pair. */
    public record Vapid(ECPublicKey publicKey, ECPrivateKey privateKey, String publicKeyText, String subject) {
    }

    // --- keys ----------------------------------------------------------------------------------------------

    public static Vapid vapid(String publicKey, String privateKey, String subject) throws GeneralSecurityException {
        ECPublicKey pub = publicKey(decode(publicKey));
        ECPrivateKey priv = privateKey(decode(privateKey));
        byte[] probe = "rutera".getBytes(StandardCharsets.US_ASCII);
        if (!verify(pub, probe, sign(priv, probe))) {
            throw new GeneralSecurityException("The VAPID public key does not belong to the private key");
        }
        return new Vapid(pub, priv, B64.encodeToString(encodePoint(pub)), subject);
    }

    /** A new key pair: {publicKey, privateKey}, base64url. */
    public static String[] generateKeys() throws GeneralSecurityException {
        KeyPair pair = newPair();
        byte[] d = toFixed(((ECPrivateKey) pair.getPrivate()).getS(), 32);
        return new String[] {B64.encodeToString(encodePoint((ECPublicKey) pair.getPublic())), B64.encodeToString(d)};
    }

    static ECPublicKey publicKey(byte[] point) throws GeneralSecurityException {
        if (point.length != 65 || point[0] != 0x04) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        if (scalar.length != 32) {
            throw new GeneralSecurityException("Not a 32-byte P-256 private key");
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
    }

    static byte[] encodePoint(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(toFixed(key.getW().getAffineX(), 32), 0, out, 1, 32);
        System.arraycopy(toFixed(key.getW().getAffineY(), 32), 0, out, 33, 32);
        return out;
    }

    static KeyPair newPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    // --- encryption (RFC 8291) -----------------------------------------------------------------------------

    /** The encrypted body for a subscription's keys, with a fresh server key pair and salt. */
    public static byte[] encrypt(byte[] plaintext, String p256dh, String auth) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, decode(p256dh), decode(auth), newPair(), salt);
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair server, byte[] salt)
            throws GeneralSecurityException {
        if (authSecret.length != 16) {
            throw new GeneralSecurityException("The auth secret is not 16 bytes");
        }
        if (plaintext.length > RECORD_SIZE - 16 - 1 - 86) {
            throw new GeneralSecurityException("Payload too large for one record");
        }
        ECPublicKey ua = publicKey(uaPublic);
        byte[] asPublic = encodePoint((ECPublicKey) server.getPublic());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(server.getPrivate());
        agreement.doPhase(ua, true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {1})), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {2}));

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), ciphertext);
    }

    // --- VAPID (RFC 8292) ----------------------------------------------------------------------------------

    /** The Authorization header for a request to the push service of {@code endpoint}, valid 12 hours. */
    public static String authorization(Vapid vapid, String endpoint, long nowSeconds) throws GeneralSecurityException {
        URI uri = URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + json(audience) + "\",\"exp\":" + (nowSeconds + 12 * 3600)
                + ",\"sub\":\"" + json(vapid.subject()) + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        String signature = B64.encodeToString(sign(vapid.privateKey(),
                signingInput.getBytes(StandardCharsets.US_ASCII)));
        return "vapid t=" + signingInput + "." + signature + ", k=" + vapid.publicKeyText();
    }

    /** ES256 signature as the 64 raw bytes R || S that JWT needs. */
    static byte[] sign(ECPrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    static boolean verify(ECPublicKey key, byte[] data, byte[] signature) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(key);
        s.update(data);
        return s.verify(signature);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    static byte[] decode(String base64url) throws GeneralSecurityException {
        try {
            return B64D.decode(base64url.strip().replace('+', '-').replace('/', '_').replace("=", ""));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GeneralSecurityException("Not base64url");
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] toFixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        byte[] out = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
