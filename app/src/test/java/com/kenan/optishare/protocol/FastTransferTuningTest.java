package com.kenan.optishare.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FastTransferTuningTest {
    @Test
    public void fastChunkFitsEncryptedFrameWithSafetyMargin() {
        int safetyMargin = 128 * 1024;
        assertTrue(ResumableProtocol.DEFAULT_CHUNK_BYTES > 1024 * 1024);
        assertTrue(ResumableProtocol.DEFAULT_CHUNK_BYTES <= SessionWire.MAX_FRAME - safetyMargin);
    }

    @Test
    public void resumeAlignmentUsesFastChunkBoundary() {
        int chunk = ResumableProtocol.DEFAULT_CHUNK_BYTES;
        assertEquals(0L, ResumableProtocol.alignToChunkBoundary(chunk - 1L, chunk));
        assertEquals(chunk, ResumableProtocol.alignToChunkBoundary(chunk + 1L, chunk));
        assertEquals(3L * chunk, ResumableProtocol.alignToChunkBoundary(3L * chunk + 777L, chunk));
    }

    @Test
    public void checkpointWindowExpandsOnlyForLargeFiles() {
        long threshold = ResumableProtocol.LARGE_FILE_THRESHOLD_BYTES;
        assertEquals(ResumableProtocol.DEFAULT_CHECKPOINT_CHUNKS,
                ResumableProtocol.checkpointChunksForFile(0));
        assertEquals(ResumableProtocol.DEFAULT_CHECKPOINT_CHUNKS,
                ResumableProtocol.checkpointChunksForFile(threshold - 1));
        assertEquals(ResumableProtocol.LARGE_FILE_CHECKPOINT_CHUNKS,
                ResumableProtocol.checkpointChunksForFile(threshold));
        assertEquals(ResumableProtocol.LARGE_FILE_CHECKPOINT_CHUNKS,
                ResumableProtocol.checkpointChunksForFile(1024L * 1024L * 1024L));
    }
}
