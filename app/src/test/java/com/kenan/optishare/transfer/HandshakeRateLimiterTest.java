package com.kenan.optishare.transfer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HandshakeRateLimiterTest {
    @Test public void blocksBurstAndReopensAfterWindow() {
        HandshakeRateLimiter limiter = new HandshakeRateLimiter();
        for (int i = 0; i < HandshakeRateLimiter.MAX_ATTEMPTS; i++) {
            assertTrue(limiter.allow("192.168.1.50", 1000L));
        }
        assertFalse(limiter.allow("192.168.1.50", 1000L));
        assertTrue(limiter.allow("192.168.1.50",
                1000L + HandshakeRateLimiter.WINDOW_MS));
    }

    @Test public void isolatesDifferentAddressesAndRejectsMissingIdentity() {
        HandshakeRateLimiter limiter = new HandshakeRateLimiter();
        for (int i = 0; i < HandshakeRateLimiter.MAX_ATTEMPTS; i++) {
            assertTrue(limiter.allow("10.0.0.2", 5L));
        }
        assertFalse(limiter.allow("10.0.0.2", 5L));
        assertTrue(limiter.allow("10.0.0.3", 5L));
        assertFalse(limiter.allow(" ", 5L));
    }
}
