package com.kenan.optishare.security;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class CryptoSessionTest {
    @Test public void bothPeersDeriveSameSecurityCodeAndDecrypt() throws Exception {
        CryptoSession a = CryptoSession.create();
        CryptoSession b = CryptoSession.create();
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        a.establish(b.publicKeyEncoded(), salt);
        b.establish(a.publicKeyEncoded(), salt);
        assertEquals(a.shortCode(), b.shortCode());
        byte[] plain = "OptiShare encrypted test".getBytes("UTF-8");
        byte[] aad = new byte[]{3};
        byte[] encrypted = a.encrypt(plain, aad);
        assertArrayEquals(plain, b.decrypt(encrypted, aad));
    }

    @Test public void independentSessionsDoNotShareSecurityCode() throws Exception {
        CryptoSession a = CryptoSession.create();
        CryptoSession b = CryptoSession.create();
        CryptoSession c = CryptoSession.create();
        byte[] salt1 = new byte[32];
        byte[] salt2 = new byte[32];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt1);
        random.nextBytes(salt2);
        a.establish(b.publicKeyEncoded(), salt1);
        b.establish(a.publicKeyEncoded(), salt1);
        c.establish(a.publicKeyEncoded(), salt2);
        assertEquals(a.shortCode(), b.shortCode());
        // Extremely small collision probability exists for a six-digit human code; if it ever
        // occurs in this random test, regenerate one independent session before asserting.
        if (a.shortCode().equals(c.shortCode())) {
            c = CryptoSession.create();
            random.nextBytes(salt2);
            c.establish(a.publicKeyEncoded(), salt2);
        }
        assertNotEquals(a.shortCode(), c.shortCode());
    }

    @Test public void ciphertextTamperingIsRejected() throws Exception {
        CryptoSession a = CryptoSession.create();
        CryptoSession b = CryptoSession.create();
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        a.establish(b.publicKeyEncoded(), salt);
        b.establish(a.publicKeyEncoded(), salt);
        byte[] encrypted = a.encrypt("secret".getBytes("UTF-8"), new byte[]{1});
        encrypted[encrypted.length - 1] ^= 0x01;
        try {
            b.decrypt(encrypted, new byte[]{1});
            fail("Tampered AES-GCM ciphertext must fail authentication");
        } catch (GeneralSecurityException expected) {
            // expected
        }
    }

    @Test public void wrongFrameTypeAadIsRejected() throws Exception {
        CryptoSession a = CryptoSession.create();
        CryptoSession b = CryptoSession.create();
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        a.establish(b.publicKeyEncoded(), salt);
        b.establish(a.publicKeyEncoded(), salt);
        byte[] encrypted = a.encrypt("frame".getBytes("UTF-8"), new byte[]{3});
        try {
            b.decrypt(encrypted, new byte[]{4});
            fail("Changed authenticated frame type must fail authentication");
        } catch (GeneralSecurityException expected) {
            // expected
        }
    }

    @Test public void authenticatedFrameReplayIsRejectedWithinSession() throws Exception {
        CryptoSession sender = CryptoSession.create();
        CryptoSession receiver = CryptoSession.create();
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        sender.establish(receiver.publicKeyEncoded(), salt);
        receiver.establish(sender.publicKeyEncoded(), salt);
        byte[] encrypted = sender.encrypt("one-time".getBytes("UTF-8"), new byte[]{3});
        assertArrayEquals("one-time".getBytes("UTF-8"), receiver.decrypt(encrypted, new byte[]{3}));
        try {
            receiver.decrypt(encrypted, new byte[]{3});
            fail("Authenticated frame replay must be rejected");
        } catch (GeneralSecurityException expected) {
            // expected
        }
    }
}
