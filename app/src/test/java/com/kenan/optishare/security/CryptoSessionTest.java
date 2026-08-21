package com.kenan.optishare.security;

import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
}
