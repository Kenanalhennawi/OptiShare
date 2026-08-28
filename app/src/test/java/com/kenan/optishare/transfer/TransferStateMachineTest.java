package com.kenan.optishare.transfer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransferStateMachineTest {
    @Test public void happyPathIsExplicit() {
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.IDLE, TransferStateMachine.State.DISCOVERING));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.DISCOVERING, TransferStateMachine.State.NEGOTIATING));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.NEGOTIATING, TransferStateMachine.State.AUTHENTICATING));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.AUTHENTICATING, TransferStateMachine.State.AWAITING_APPROVAL));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.AWAITING_APPROVAL, TransferStateMachine.State.TRANSFERRING));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.TRANSFERRING, TransferStateMachine.State.VERIFYING));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.VERIFYING, TransferStateMachine.State.COMPLETED));
    }

    @Test public void pauseAndReconnectPreserveAValidRouteBack() {
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.TRANSFERRING, TransferStateMachine.State.PAUSED));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.PAUSED, TransferStateMachine.State.RECONNECTING));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.RECONNECTING, TransferStateMachine.State.NEGOTIATING));
        assertTrue(TransferStateMachine.canTransition(TransferStateMachine.State.RECONNECTING, TransferStateMachine.State.TRANSFERRING));
    }

    @Test public void terminalAttemptsCannotBeReanimated() {
        for (TransferStateMachine.State terminal : new TransferStateMachine.State[]{TransferStateMachine.State.COMPLETED, TransferStateMachine.State.PARTIAL, TransferStateMachine.State.FAILED, TransferStateMachine.State.CANCELLED}) {
            assertTrue(TransferStateMachine.terminal(terminal));
            for (TransferStateMachine.State target : TransferStateMachine.State.values())
                assertFalse(TransferStateMachine.canTransition(terminal, target));
        }
    }

    @Test public void unsafeSkipsAreRejected() {
        assertFalse(TransferStateMachine.canTransition(TransferStateMachine.State.DISCOVERING, TransferStateMachine.State.TRANSFERRING));
        assertFalse(TransferStateMachine.canTransition(TransferStateMachine.State.AUTHENTICATING, TransferStateMachine.State.COMPLETED));
        assertFalse(TransferStateMachine.canTransition(TransferStateMachine.State.TRANSFERRING, TransferStateMachine.State.AUTHENTICATING));
    }
}
