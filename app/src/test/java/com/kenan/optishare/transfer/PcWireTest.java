package com.kenan.optishare.transfer;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PcWireTest {
    @Test public void metadataMatchesNativeWindowsBigEndianGoldenVector() throws Exception {
        assertEquals("00000005612e747874000000000000000a746578742f706c61696e0000000000000005",
                hex(PcWire.metadataVector("a.txt", "", "text/plain", 5L)));
    }

    @Test public void constantsMatchNativeReceiverContract() {
        assertEquals("4f50544953484152452d50432d310a", hex(PcWire.MAGIC));
        assertEquals(0x0F7152E2, PcWire.COMPLETION_MARKER);
    }

    @Test public void oversizedUtf8MetadataIsRejectedBeforeWrite() throws Exception {
        try {
            PcWire.writeString(new DataOutputStream(new ByteArrayOutputStream()), "ééé", 5);
            fail("Expected metadata bound failure");
        } catch (IllegalArgumentException expected) {
            assertEquals("Metadata too long", expected.getMessage());
        }
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
