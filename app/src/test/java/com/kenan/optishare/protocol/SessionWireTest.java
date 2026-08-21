package com.kenan.optishare.protocol;

import com.kenan.optishare.model.TransferItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class SessionWireTest {
    @Test public void manifestRoundTripPreservesMetadata() throws Exception {
        BatchManifest.Entry entry = new BatchManifest.Entry(
                "file-1", "photo.jpg", "image/jpeg", 123456L,
                TransferItem.Category.PHOTO, new byte[]{1,2,3,4});
        BatchManifest original = new BatchManifest("session-1", 100L, Arrays.asList(entry));
        BatchManifest decoded = SessionWire.decodeManifest(SessionWire.encodeManifest(original));
        assertEquals("session-1", decoded.getSessionId());
        assertEquals(1, decoded.getEntries().size());
        assertEquals("photo.jpg", decoded.getEntries().get(0).name);
        assertEquals(123456L, decoded.getEntries().get(0).size);
        assertEquals(TransferItem.Category.PHOTO, decoded.getEntries().get(0).category);
        assertArrayEquals(new byte[]{1,2,3,4}, decoded.getEntries().get(0).sha256);
    }

    @Test public void resumeOffsetsRoundTrip() throws Exception {
        Map<String, Long> original = new LinkedHashMap<>();
        original.put("a", 262144L);
        original.put("b", 1048576L);
        Map<String, Long> decoded = SessionWire.decodeOffsets(SessionWire.encodeOffsets(original));
        assertEquals(original, decoded);
    }

    @Test public void chunkRoundTripPreservesOffsetAndPayload() throws Exception {
        byte[] bytes = new byte[]{9,8,7,6,5};
        SessionWire.Chunk decoded = SessionWire.decodeChunk(SessionWire.encodeChunk("x", 512L, bytes, bytes.length));
        assertEquals("x", decoded.fileId);
        assertEquals(512L, decoded.offset);
        assertArrayEquals(bytes, decoded.data);
    }
}
