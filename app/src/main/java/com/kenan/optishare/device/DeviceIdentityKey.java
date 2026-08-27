package com.kenan.optishare.device;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.RequiresApi;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

/** Persistent per-install signing identity stored in Android Keystore on Android 6+. */
public final class DeviceIdentityKey {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "optishare_device_identity_v1";

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    public DeviceIdentityKey() throws Exception {
        if (!supported()) {
            throw new UnsupportedOperationException(
                    "Persistent trusted-device identity requires Android 6 or newer");
        }
        ensureKeyApi23();
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static void ensureKeyApi23() throws Exception {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return;
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, STORE);
        generator.initialize(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .build());
        generator.generateKeyPair();
    }

    public byte[] publicKeyEncoded() throws Exception {
        requireSupported();
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        java.security.cert.Certificate certificate = store.getCertificate(ALIAS);
        if (certificate == null) throw new IllegalStateException("Device identity certificate missing");
        return certificate.getPublicKey().getEncoded();
    }

    public byte[] sign(byte[] challenge) throws Exception {
        requireSupported();
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        PrivateKey privateKey = (PrivateKey) store.getKey(ALIAS, null);
        if (privateKey == null) throw new IllegalStateException("Device identity key missing");
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(challenge);
        return signature.sign();
    }

    public String fingerprint() throws Exception {
        return fingerprint(publicKeyEncoded());
    }

    private static void requireSupported() {
        if (!supported()) {
            throw new UnsupportedOperationException(
                    "Persistent trusted-device identity requires Android 6 or newer");
        }
    }

    public static boolean verify(byte[] publicKey, byte[] challenge, byte[] signatureBytes)
            throws Exception {
        PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(publicKey));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);
        signature.update(challenge);
        return signature.verify(signatureBytes);
    }

    public static String fingerprint(byte[] publicKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte b : digest) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    public static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 12) {
            return fingerprint == null ? "unknown" : fingerprint;
        }
        return fingerprint.substring(0, 6).toUpperCase(Locale.US) + "…"
                + fingerprint.substring(fingerprint.length() - 6).toUpperCase(Locale.US);
    }
}
