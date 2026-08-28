package com.kenan.optishare.protocol;

import com.kenan.optishare.model.TransferItem;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SessionWireTest {
    @Test public void manifestRoundTripPreservesMetadata() throws Exception {
        BatchManifest.Entry entry = new BatchManifest.Entry(
                "file-1", "photo.jpg", "image/jpeg", 123456L,
                TransferItem.Category.PHOTO, new byte[32]);
        BatchManifest original = new BatchManifest("session-1", 100L, Arrays.asList(entry));
        BatchManifest decoded = SessionWire.decodeManifest(SessionWire.encodeManifest(original));
        assertEquals("session-1", decoded.getSessionId());
        assertEquals(1, decoded.getEntries().size());
        assertEquals("photo.jpg", decoded.getEntries().get(0).name);
        assertEquals(123456L, decoded.getEntries().get(0).size);
        assertEquals(TransferItem.Category.PHOTO, decoded.getEntries().get(0).category);
        assertArrayEquals(new byte[32], decoded.getEntries().get(0).sha256);
    }

    @Test public void resumeOffsetsRoundTrip() throws Exception {
        Map<String, Long> original = new LinkedHashMap<>();
        original.put("a", 1_048_576L);
        original.put("b", 8_388_608L);
        Map<String, Long> decoded = SessionWire.decodeOffsets(SessionWire.encodeOffsets(original));
        assertEquals(original, decoded);
    }

    @Test public void chunkRoundTripPreservesOffsetAndPayload() throws Exception {
        byte[] bytes = new byte[]{9,8,7,6,5};
        SessionWire.Chunk decoded = SessionWire.decodeChunk(
                SessionWire.encodeChunk("x", 512L, bytes, bytes.length));
        assertEquals("x", decoded.fileId);
        assertEquals(512L, decoded.offset);
        assertArrayEquals(bytes, decoded.data);
    }

    @Test public void benchmarkSizeRoundTripIsBounded() throws Exception {
        assertEquals(SessionWire.BENCHMARK_TOTAL_BYTES,
                SessionWire.decodeBenchmarkSize(SessionWire.encodeBenchmarkSize(SessionWire.BENCHMARK_TOTAL_BYTES)));
        try {
            SessionWire.encodeBenchmarkSize(SessionWire.MAX_BENCHMARK_BYTES + 1L);
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test public void decodeOffsetsRejectsNegativeOffset() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeUTF("file");
        out.writeLong(-1L);
        out.flush();
        try {
            SessionWire.decodeOffsets(bytes.toByteArray());
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test public void decodeOffsetsRejectsAbsurdEntryCount() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(100_001);
        out.flush();
        try {
            SessionWire.decodeOffsets(bytes.toByteArray());
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test public void decodeManifestRejectsNegativeFileSize() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("session");
        out.writeLong(1L);
        out.writeInt(1);
        out.writeUTF("id");
        out.writeUTF("bad.bin");
        out.writeUTF("application/octet-stream");
        out.writeLong(-5L);
        out.writeInt(TransferItem.Category.OTHER.ordinal());
        out.writeInt(32);
        out.write(new byte[32]);
        out.flush();
        try {
            SessionWire.decodeManifest(bytes.toByteArray());
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test public void decodeChunkRejectsPayloadLargerThanResumeCheckpoint() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("file");
        out.writeLong(0L);
        out.writeInt(ResumableProtocol.DEFAULT_CHUNK_BYTES + 64 * 1024 + 1);
        out.flush();
        try {
            SessionWire.decodeChunk(bytes.toByteArray());
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }
}
