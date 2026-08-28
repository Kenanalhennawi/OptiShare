package com.kenan.optishare.transfer;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PcSecureWireTest {
    @Test public void hkdfAndAesGcmMatchWindowsGoldenVector() throws Exception {
        byte[] shared = range(0, 32);
        byte[] salt = range(32, 64);
        byte[] key = PcSecureWire.deriveKey(shared, salt);
        assertEquals("cf2407d9e2499ed91b23511130092e5e85c7a380ef8523014c0b3d47b4db1456", hex(key));

        byte[] record = PcSecureWire.encryptWithNonce(key, 0L, true,
                "OptiShare secure frame".getBytes(StandardCharsets.UTF_8), range(0, 12));
        assertEquals("0c000102030405060708090a0baaa7610c5497f76554f3b997dba5295820aac345202c08125c8ad35c4d9fd38d3fa0f9ff1fa9", hex(record));
        assertArrayEquals("OptiShare secure frame".getBytes(StandardCharsets.UTF_8),
                PcSecureWire.decrypt(key, 0L, true, record));
    }

    @Test public void directionAndSequenceAreAuthenticated() throws Exception {
        byte[] key = PcSecureWire.deriveKey(range(0, 32), range(32, 64));
        byte[] record = PcSecureWire.encryptWithNonce(key, 7L, true, new byte[]{1, 2, 3}, range(0, 12));
        expectAuthenticationFailure(() -> PcSecureWire.decrypt(key, 8L, true, record));
        expectAuthenticationFailure(() -> PcSecureWire.decrypt(key, 7L, false, record));
    }

    @Test public void oversizedRecordsAreRejectedBeforeAllocation() throws Exception {
        byte[] key = PcSecureWire.deriveKey(range(0, 32), range(32, 64));
        expectAuthenticationFailure(() -> PcSecureWire.decrypt(key, 0L, true,
                new byte[PcSecureWire.MAX_RECORD_BYTES + 1]));
    }

    private static void expectAuthenticationFailure(Checked action) throws Exception {
        try { action.run(); fail("Expected secure record rejection"); }
        catch (GeneralSecurityException expected) { }
    }

    private static byte[] range(int start, int end) {
        byte[] result = new byte[end - start];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (start + i);
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private interface Checked { void run() throws Exception; }
}
