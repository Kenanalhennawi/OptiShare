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

    @Test
    public void fiveGigabyteTransferSurvivesFiftyDisconnectResumeCyclesWithoutAdvancing() {
        final long fileSize = 5L * 1024L * 1024L * 1024L;
        final int chunk = ResumableProtocol.DEFAULT_CHUNK_BYTES;
        Random random = new Random(0x5A17E5L);
        long confirmed = 0L;

        for (int cycle = 0; cycle < 50 && confirmed < fileSize; cycle++) {
            long attempted = Math.min(fileSize, confirmed
                    + (1L + random.nextInt(128)) * chunk
                    + random.nextInt(chunk));
            long receiverDurable = Math.max(confirmed, attempted - random.nextInt(chunk));
            long negotiated = ResumableProtocol.negotiateOffset(
                    attempted, receiverDurable, fileSize);
            long resume = ResumableProtocol.alignToChunkBoundary(negotiated, chunk);

            assertTrue(resume >= confirmed);
            assertTrue(resume <= attempted);
            assertTrue(resume <= receiverDurable);
            assertEquals(0L, resume % chunk);
            confirmed = resume;
        }

        assertTrue(confirmed > 0L);
        assertTrue(confirmed <= fileSize);
    }
}
