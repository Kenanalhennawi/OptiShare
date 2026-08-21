package com.kenan.optishare.transfer;

/** Process-local approval gate used by the foreground receiver service and the visible UI. */
final class IncomingApproval {
    private static final Object LOCK = new Object();
    private static String pendingSession;
    private static Boolean decision;

    private IncomingApproval() {}

    static void begin(String sessionId) {
        synchronized (LOCK) {
            pendingSession = sessionId;
            decision = null;
        }
    }

    static boolean await(String sessionId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (LOCK) {
            while (sessionId != null && sessionId.equals(pendingSession) && decision == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                LOCK.wait(remaining);
            }
            boolean accepted = sessionId != null && sessionId.equals(pendingSession) && Boolean.TRUE.equals(decision);
            if (sessionId != null && sessionId.equals(pendingSession)) {
                pendingSession = null;
                decision = null;
            }
            return accepted;
        }
    }

    static void decide(boolean accepted) {
        synchronized (LOCK) {
            if (pendingSession == null) return;
            decision = accepted;
            LOCK.notifyAll();
        }
    }

    static void cancel() {
        synchronized (LOCK) {
            pendingSession = null;
            decision = Boolean.FALSE;
            LOCK.notifyAll();
        }
    }
}
