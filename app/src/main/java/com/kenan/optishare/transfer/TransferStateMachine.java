package com.kenan.optishare.transfer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Pure protocol state policy shared by services as OptiShare migrates to OSX/2. */
public final class TransferStateMachine {
    public enum State {
        IDLE, DISCOVERING, NEGOTIATING, AUTHENTICATING, AWAITING_APPROVAL,
        TRANSFERRING, PAUSED, RECONNECTING, VERIFYING,
        COMPLETED, PARTIAL, FAILED, CANCELLED
    }

    private static final Map<State, EnumSet<State>> ALLOWED = new EnumMap<>(State.class);
    static {
        allow(State.IDLE, State.DISCOVERING);
        allow(State.DISCOVERING, State.NEGOTIATING, State.CANCELLED, State.FAILED);
        allow(State.NEGOTIATING, State.AUTHENTICATING, State.RECONNECTING, State.CANCELLED, State.FAILED);
        allow(State.AUTHENTICATING, State.AWAITING_APPROVAL, State.CANCELLED, State.FAILED);
        allow(State.AWAITING_APPROVAL, State.TRANSFERRING, State.CANCELLED, State.FAILED);
        allow(State.TRANSFERRING, State.PAUSED, State.RECONNECTING, State.VERIFYING, State.CANCELLED, State.FAILED);
        allow(State.PAUSED, State.TRANSFERRING, State.RECONNECTING, State.CANCELLED, State.FAILED);
        allow(State.RECONNECTING, State.NEGOTIATING, State.TRANSFERRING, State.CANCELLED, State.FAILED);
        allow(State.VERIFYING, State.COMPLETED, State.PARTIAL, State.FAILED);
    }

    private TransferStateMachine() { }

    private static void allow(State from, State... targets) {
        ALLOWED.put(from, targets.length == 0 ? EnumSet.noneOf(State.class) : EnumSet.of(targets[0], targets));
    }

    public static boolean canTransition(State from, State to) {
        if (from == null || to == null || from == to) return false;
        EnumSet<State> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    public static boolean terminal(State state) {
        return state == State.COMPLETED || state == State.PARTIAL
                || state == State.FAILED || state == State.CANCELLED;
    }
}
