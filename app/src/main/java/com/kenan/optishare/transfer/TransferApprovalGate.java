package com.kenan.optishare.transfer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * In-process approval bridge between the foreground transfer service and the UI.
 * The foreground service keeps the process alive while an approval is pending.
 */
public final class TransferApprovalGate {
    public enum Decision { ACCEPT, DECLINE, TIMEOUT }

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Decision decision;
    }

    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();

    private TransferApprovalGate() {}

    public static Decision await(String key, long timeoutSeconds) throws InterruptedException {
        Pending pending = new Pending();
        Pending previous = PENDING.putIfAbsent(key, pending);
        if (previous != null) pending = previous;
        boolean signalled = pending.latch.await(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        PENDING.remove(key, pending);
        if (!signalled || pending.decision == null) return Decision.TIMEOUT;
        return pending.decision;
    }

    public static boolean decide(String key, boolean accept) {
        Pending pending = PENDING.get(key);
        if (pending == null) return false;
        pending.decision = accept ? Decision.ACCEPT : Decision.DECLINE;
        pending.latch.countDown();
        return true;
    }

    public static void cancelAll() {
        for (Pending pending : PENDING.values()) {
            pending.decision = Decision.DECLINE;
            pending.latch.countDown();
        }
        PENDING.clear();
    }
}
