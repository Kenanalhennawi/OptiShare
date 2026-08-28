package com.kenan.optishare.transfer;

/** Pure routing policy. Discovery never connects automatically; this only handles an active send. */
public final class AdaptiveRouteOrchestrator {
    private AdaptiveRouteOrchestrator() { }

    public static String verifiedLanFallback(String currentRoute, String discoveredHost) {
        if (!RoutePerformanceStore.ROUTE_DIRECT.equals(currentRoute)) return null;
        if (discoveredHost == null || discoveredHost.trim().isEmpty()) return null;
        return discoveredHost.trim();
    }

    public static boolean shouldSwitchToLan(String currentRoute, String fallbackHost,
                                            boolean directRecovered) {
        return RoutePerformanceStore.ROUTE_DIRECT.equals(currentRoute)
                && !directRecovered
                && fallbackHost != null
                && !fallbackHost.trim().isEmpty();
    }
}
