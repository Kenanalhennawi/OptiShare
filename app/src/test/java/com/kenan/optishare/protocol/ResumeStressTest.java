package com.kenan.optishare.protocol;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Deterministic stress coverage for interrupted and multi-file resume state. */
public class ResumeStressTest {
    @Test
    public void tenThousandRandomResumeNegotiationsNeverAdvancePastEitherPeer() {
        Random random = new Random(0x0A5715L);
        int chunk = ResumableProtocol.DEFAULT_CHUNK_BYTES;

        for (int i = 0; i < 10_000; i++) {
            long fileSize = 1L + Math.abs(random.nextLong() % (4L * 1024L * 1024L * 1024L));
            long sender = Math.abs(random.nextLong() % (fileSize + 1L));
            long receiver = Math.abs(random.nextLong() % (fileSize + 1L));

            long negotiated = ResumableProtocol.negotiateOffset(sender, receiver, fileSize);
            long aligned = ResumableProtocol.alignToChunkBoundary(negotiated, chunk);

            assertTrue(negotiated <= sender);
            assertTrue(negotiated <= receiver);
            assertTrue(negotiated <= fileSize);
            assertTrue(aligned <= negotiated);
            assertEquals(0L, aligned % chunk);
        }
    }

    @Test
    public void thousandFileResumeStateRoundTripsExactly() throws Exception {
        Map<String, Long> offsets = new LinkedHashMap<>();
        int chunk = ResumableProtocol.DEFAULT_CHUNK_BYTES;
        for (int i = 0; i < 1_000; i++) {
            offsets.put("file-" + i, (long) i * chunk);
        }
        ResumeState source = new ResumeState("STRESS-SESSION-1000", 123456789L, offsets);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ResumableProtocol.writeResumeRequest(new DataOutputStream(bytes), source);
        ResumeState decoded = ResumableProtocol.readResumeRequest(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals(source.getSessionId(), decoded.getSessionId());
        assertEquals(1_000, decoded.getConfirmedOffsets().size());
        for (Map.Entry<String, Long> entry : offsets.entrySet()) {
            assertEquals(entry.getValue().longValue(), decoded.getConfirmedOffset(entry.getKey()));
        }
    }

    @Test
    public void checkpointPolicyStaysDeterministicAcrossBoundary() {
        long threshold = ResumableProtocol.LARGE_FILE_THRESHOLD_BYTES;
        assertEquals(ResumableProtocol.DEFAULT_CHECKPOINT_CHUNKS,
                ResumableProtocol.checkpointChunksForFile(threshold - 1));
        assertEquals(ResumableProtocol.LARGE_FILE_CHECKPOINT_CHUNKS,
                ResumableProtocol.checkpointChunksForFile(threshold));
        assertEquals(ResumableProtocol.LARGE_FILE_CHECKPOINT_CHUNKS,
                ResumableProtocol.checkpointChunksForFile(8L * 1024L * 1024L * 1024L));
    }
}
