package com.kenan.optishare.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdaptiveRouteOrchestratorTest {
    @Test public void exposesVerifiedLanOnlyAsDirectFallback() {
        assertEquals("192.168.1.24", AdaptiveRouteOrchestrator.verifiedLanFallback(
                RoutePerformanceStore.ROUTE_DIRECT, " 192.168.1.24 "));
        assertNull(AdaptiveRouteOrchestrator.verifiedLanFallback(
                RoutePerformanceStore.ROUTE_LAN, "192.168.1.24"));
        assertNull(AdaptiveRouteOrchestrator.verifiedLanFallback(
                RoutePerformanceStore.ROUTE_DIRECT, " "));
    }

    @Test public void switchesOnlyAfterDirectRecoveryFails() {
        assertFalse(AdaptiveRouteOrchestrator.shouldSwitchToLan(
                RoutePerformanceStore.ROUTE_DIRECT, "192.168.1.24", true));
        assertTrue(AdaptiveRouteOrchestrator.shouldSwitchToLan(
                RoutePerformanceStore.ROUTE_DIRECT, "192.168.1.24", false));
        assertFalse(AdaptiveRouteOrchestrator.shouldSwitchToLan(
                RoutePerformanceStore.ROUTE_LAN, "192.168.1.24", false));
    }
}
