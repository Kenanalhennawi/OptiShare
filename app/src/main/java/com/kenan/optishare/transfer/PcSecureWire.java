package com.kenan.optishare.transfer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Cryptographic record primitives shared by Android and the native Windows v2 protocol. */
public final class PcSecureWire {
    public static final byte[] MAGIC = "OPTISHARE-PC-2\n".getBytes(StandardCharsets.US_ASCII);
    public static final int MAX_RECORD_BYTES = 1_048_640;
    private static final byte[] INFO = "OptiShare-PC-v2/session".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RNG = new SecureRandom();

    private PcSecureWire() { }

    public static KeyPair createEphemeralKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RNG);
        return generator.generateKeyPair();
    }

    public static byte[] sharedSecret(KeyPair own, byte[] peerPublicKey) throws GeneralSecurityException {
        if (own == null || peerPublicKey == null || peerPublicKey.length < 64 || peerPublicKey.length > 512) {
            throw new GeneralSecurityException("Invalid ECDH public key");
        }
        PublicKey peer = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(peerPublicKey));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own.getPrivate());
        agreement.doPhase(peer, true);
        return agreement.generateSecret();
    }

    public static byte[] deriveKey(byte[] sharedSecret, byte[] salt) throws GeneralSecurityException {
        if (sharedSecret == null || sharedSecret.length < 16) throw new GeneralSecurityException("Shared secret is too short");
        if (salt == null || salt.length != 32) throw new GeneralSecurityException("Session salt must be 32 bytes");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(sharedSecret);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(INFO);
        mac.update((byte) 1);
        byte[] key = mac.doFinal();
        java.util.Arrays.fill(prk, (byte) 0);
        return key;
    }

    public static byte[] encrypt(byte[] key, long sequence, boolean clientToServer,
                                 byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        RNG.nextBytes(nonce);
        return encryptWithNonce(key, sequence, clientToServer, plaintext, nonce);
    }

    static byte[] encryptWithNonce(byte[] key, long sequence, boolean clientToServer,
                                   byte[] plaintext, byte[] nonce) throws GeneralSecurityException {
        validate(key, plaintext, nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(sequence, clientToServer));
        byte[] encrypted = cipher.doFinal(plaintext);
        ByteBuffer result = ByteBuffer.allocate(1 + nonce.length + encrypted.length);
        result.put((byte) nonce.length).put(nonce).put(encrypted);
        return result.array();
    }

    public static byte[] decrypt(byte[] key, long sequence, boolean clientToServer,
                                 byte[] record) throws GeneralSecurityException {
        if (record == null || record.length < 30 || record.length > MAX_RECORD_BYTES) {
            throw new GeneralSecurityException("Invalid secure record length");
        }
        ByteBuffer input = ByteBuffer.wrap(record);
        int nonceLength = input.get() & 0xff;
        if (nonceLength != 12 || input.remaining() <= nonceLength + 16) {
            throw new GeneralSecurityException("Invalid secure record");
        }
        byte[] nonce = new byte[nonceLength];
        input.get(nonce);
        byte[] encrypted = new byte[input.remaining()];
        input.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(sequence, clientToServer));
        return cipher.doFinal(encrypted);
    }

    public static String securityCode(byte[] clientPublicKey, byte[] serverPublicKey,
                                      byte[] salt) throws GeneralSecurityException {
        if (clientPublicKey == null || serverPublicKey == null || salt == null || salt.length != 32) {
            throw new GeneralSecurityException("Invalid security transcript");
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(MAGIC);
        sha.update(intBytes(clientPublicKey.length));
        sha.update(clientPublicKey);
        sha.update(intBytes(serverPublicKey.length));
        sha.update(serverPublicKey);
        sha.update(salt);
        byte[] digest = sha.digest();
        int value = ((digest[0] & 0xff) << 16) | ((digest[1] & 0xff) << 8) | (digest[2] & 0xff);
        return String.format(java.util.Locale.US, "%06d", value % 1_000_000);
    }

    private static byte[] aad(long sequence, boolean clientToServer) {
        return ((clientToServer ? "client:" : "server:") + sequence).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] intBytes(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }

    private static void validate(byte[] key, byte[] plaintext, byte[] nonce) throws GeneralSecurityException {
        if (key == null || key.length != 32) throw new GeneralSecurityException("AES-256 key required");
        if (nonce == null || nonce.length != 12) throw new GeneralSecurityException("GCM nonce must be 12 bytes");
        if (plaintext == null || plaintext.length > MAX_RECORD_BYTES - 29) throw new GeneralSecurityException("Secure record plaintext is too large");
    }
}
