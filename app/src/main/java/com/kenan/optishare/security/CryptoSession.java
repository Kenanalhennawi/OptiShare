package com.kenan.optishare.security;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Per-transfer ephemeral ECDH + HKDF-SHA256 + AES-256-GCM, compatible with Android 5+. */
public final class CryptoSession {
    private static final int MAX_RECEIVED_FRAMES_PER_KEY = 131_072;
    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] HKDF_INFO = "OptiShare-2.0-session".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final KeyPair keyPair;
    private SecretKeySpec aesKey;
    private byte[] fingerprint;
    private final Set<Nonce> receivedNonces = new HashSet<>();

    private CryptoSession(KeyPair keyPair) { this.keyPair = keyPair; }

    public static CryptoSession create() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), RNG);
        return new CryptoSession(gen.generateKeyPair());
    }

    public byte[] publicKeyEncoded() { return keyPair.getPublic().getEncoded(); }

    public void establish(byte[] peerPublicKey, byte[] salt) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("EC");
        PublicKey peer = factory.generatePublic(new X509EncodedKeySpec(peerPublicKey));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init((PrivateKey) keyPair.getPrivate());
        agreement.doPhase(peer, true);
        byte[] shared = agreement.generateSecret();
        byte[] okm = hkdfSha256(shared, salt == null ? new byte[32] : salt, HKDF_INFO, 32);
        this.aesKey = new SecretKeySpec(okm, "AES");

        byte[] own = publicKeyEncoded();
        byte[] first = compareLexicographically(own, peerPublicKey) <= 0 ? own : peerPublicKey;
        byte[] second = first == own ? peerPublicKey : own;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update("OptiShare-security-code".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sha.update(first);
        sha.update(second);
        sha.update(salt == null ? new byte[0] : salt);
        this.fingerprint = sha.digest();

        java.util.Arrays.fill(shared, (byte) 0);
        java.util.Arrays.fill(okm, (byte) 0);
    }

    public byte[] sessionFingerprint() {
        ensureReady();
        return java.util.Arrays.copyOf(fingerprint, fingerprint.length);
    }

    public byte[] encrypt(byte[] plaintext, byte[] aad) throws Exception {
        ensureReady();
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        byte[] ciphertext = cipher.doFinal(plaintext);
        ByteBuffer out = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        out.put((byte) iv.length).put(iv).put(ciphertext);
        return out.array();
    }

    public synchronized byte[] decrypt(byte[] frame, byte[] aad) throws Exception {
        ensureReady();
        ByteBuffer in = ByteBuffer.wrap(frame);
        int ivLength = in.get() & 0xff;
        if (ivLength != 12 || in.remaining() <= ivLength) throw new IllegalArgumentException("Invalid encrypted frame");
        byte[] iv = new byte[ivLength];
        in.get(iv);
        Nonce nonce = new Nonce(iv);
        if (receivedNonces.contains(nonce)) {
            throw new GeneralSecurityException("Replayed encrypted frame");
        }
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        byte[] plaintext = cipher.doFinal(ciphertext);
        if (receivedNonces.size() >= MAX_RECEIVED_FRAMES_PER_KEY) {
            throw new GeneralSecurityException("Encrypted session frame limit exceeded; reconnect required");
        }
        receivedNonces.add(nonce);
        return plaintext;
    }

    public String shortCode() {
        ensureReady();
        int value = ((fingerprint[0] & 0xff) << 16) | ((fingerprint[1] & 0xff) << 8) | (fingerprint[2] & 0xff);
        return String.format(java.util.Locale.US, "%06d", value % 1_000_000);
    }

    private void ensureReady() {
        if (aesKey == null) throw new IllegalStateException("Crypto session not established");
    }

    private static int compareLexicographically(byte[] a, byte[] b) {
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) {
            int av = a[i] & 0xff;
            int bv = b[i] & 0xff;
            if (av != bv) return av < bv ? -1 : 1;
        }
        return Integer.compare(a.length, b.length);
    }

    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] actualSalt = salt == null || salt.length == 0 ? new byte[32] : salt;
        mac.init(new SecretKeySpec(actualSalt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        int counter = 1;
        while (out.size() < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(t);
            if (info != null) mac.update(info);
            mac.update((byte) counter++);
            t = mac.doFinal();
            out.write(t, 0, Math.min(t.length, length - out.size()));
        }
        java.util.Arrays.fill(prk, (byte) 0);
        return out.toByteArray();
    }

    private static final class Nonce {
        final long high;
        final int low;
        Nonce(byte[] iv) {
            ByteBuffer bytes = ByteBuffer.wrap(iv);
            high = bytes.getLong();
            low = bytes.getInt();
        }
        @Override public boolean equals(Object other) {
            return other instanceof Nonce && ((Nonce) other).high == high && ((Nonce) other).low == low;
        }
        @Override public int hashCode() {
            return 31 * Long.hashCode(high) + low;
        }
    }
}
