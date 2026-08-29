package com.kenan.optishare.transfer;

import com.kenan.optishare.protocol.ResumableProtocol;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Android-only RC regression matrix for pause/recovery and per-file failure isolation. */
public class AndroidRcStressTest {
    @Test public void oneHundredPauseResumeCyclesAlwaysReturnToTransfer() {
        TransferStateMachine.State state = TransferStateMachine.State.TRANSFERRING;
        for (int i = 0; i < 100; i++) {
            assertTrue(TransferStateMachine.canTransition(state, TransferStateMachine.State.PAUSED));
            state = TransferStateMachine.State.PAUSED;
            assertTrue(TransferStateMachine.canTransition(state, TransferStateMachine.State.TRANSFERRING));
            state = TransferStateMachine.State.TRANSFERRING;
        }
        assertEquals(TransferStateMachine.State.TRANSFERRING, state);
    }

    @Test public void partialBatchIsTerminalAfterVerification() {
        assertTrue(TransferStateMachine.canTransition(
                TransferStateMachine.State.VERIFYING, TransferStateMachine.State.PARTIAL));
        assertTrue(TransferStateMachine.terminal(TransferStateMachine.State.PARTIAL));
        assertFalse(TransferStateMachine.canTransition(
                TransferStateMachine.State.PARTIAL, TransferStateMachine.State.TRANSFERRING));
    }

    @Test public void onlyLocalContentFailuresMaySkipOneFile() {
        assertTrue(TransferEngine.isLocalSourceFailure(
                new IOException("Cannot open source file: denied.jpg")));
        assertTrue(TransferEngine.isLocalSourceFailure(
                new IOException("Unexpected end of source file: changed.mp4")));
        assertFalse(TransferEngine.isLocalSourceFailure(
                new IOException("Expected checkpoint acknowledgement")));
        assertFalse(TransferEngine.isLocalSourceFailure(
                new IOException("Connection reset")));
        assertFalse(TransferEngine.isLocalSourceFailure(new IOException()));
    }

    @Test public void fiveGigabyteTailNeverResumesPastDurableChunk() {
        long size = 5L * 1024L * 1024L * 1024L;
        int chunk = ResumableProtocol.DEFAULT_CHUNK_BYTES;
        long sender = size - 17L;
        long receiver = size - 8193L;
        long negotiated = ResumableProtocol.negotiateOffset(sender, receiver, size);
        long resume = ResumableProtocol.alignToChunkBoundary(negotiated, chunk);
        assertTrue(resume <= sender);
        assertTrue(resume <= receiver);
        assertTrue(resume < size);
        assertEquals(0L, resume % chunk);
    }
}
