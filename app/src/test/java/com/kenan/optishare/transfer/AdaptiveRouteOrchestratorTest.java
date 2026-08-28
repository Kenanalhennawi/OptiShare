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

    @Test public void rediscoveryIsBoundedAndKeepsLastKnownHostWhenNothingIsFound() {
        assertTrue(AdaptiveRouteOrchestrator.shouldRediscoverLan(RoutePerformanceStore.ROUTE_LAN, 1));
        assertFalse(AdaptiveRouteOrchestrator.shouldRediscoverLan(RoutePerformanceStore.ROUTE_LAN, 2));
        assertTrue(AdaptiveRouteOrchestrator.shouldRediscoverLan(RoutePerformanceStore.ROUTE_LAN, 4));
        assertTrue(AdaptiveRouteOrchestrator.shouldRediscoverLan(RoutePerformanceStore.ROUTE_LAN, 7));
        assertFalse(AdaptiveRouteOrchestrator.shouldRediscoverLan(RoutePerformanceStore.ROUTE_DIRECT, 1));
        assertEquals("192.168.1.24", AdaptiveRouteOrchestrator.selectRecoveredLanHost(
                "192.168.1.24", null));
        assertEquals("192.168.1.37", AdaptiveRouteOrchestrator.selectRecoveredLanHost(
                "192.168.1.24", " 192.168.1.37 "));
    }
}
