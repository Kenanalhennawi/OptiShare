package com.kenan.optishare.transfer;

import android.content.Context;
import android.content.SharedPreferences;

/** Learns route quality from completed and failed transfers using an EWMA throughput score. */
public final class RoutePerformanceStore {
    public static final String ROUTE_DIRECT = "wifi-direct";
    public static final String ROUTE_LAN = "lan";
    public static final String ROUTE_PC = "pc-local";
    private static final String PREFS = "optishare_route_performance_v1";
    private static final String PARALLEL_SINGLE = "parallel_single_bps";
    private static final String PARALLEL_DUAL = "parallel_dual_bps";
    private static final String PARALLEL_GAIN = "parallel_gain_percent";
    private static final String PARALLEL_STREAMS = "parallel_recommended_streams";
    private static final String PARALLEL_UPDATED = "parallel_updated_at";
    private final SharedPreferences prefs;

    public RoutePerformanceStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void recordSuccess(String route, double bytesPerSecond) {
        if (!valid(route) || bytesPerSecond <= 0) return;
        double old = averageSpeed(route);
        int successes = successes(route);
        double next = successes == 0 ? bytesPerSecond : old * 0.70 + bytesPerSecond * 0.30;
        prefs.edit()
                .putLong("speed:" + route, Double.doubleToLongBits(next))
                .putInt("ok:" + route, Math.min(10000, successes + 1))
                .apply();
    }

    public void recordFailure(String route) {
        if (!valid(route)) return;
        prefs.edit().putInt("fail:" + route, Math.min(10000, failures(route) + 1)).apply();
    }

    public void recordParallelBenchmark(double singleBytesPerSecond, double dualBytesPerSecond, String peerFingerprint) {
        if (singleBytesPerSecond <= 0d || dualBytesPerSecond <= 0d) return;
        int gain = ParallelBenchmarkDecision.improvementPercent(singleBytesPerSecond, dualBytesPerSecond);
        int streams = ParallelBenchmarkDecision.recommendTwoStreams(singleBytesPerSecond, dualBytesPerSecond) ? 2 : 1;
        SharedPreferences.Editor edit = prefs.edit()
                .putLong(PARALLEL_SINGLE, Double.doubleToLongBits(singleBytesPerSecond))
                .putLong(PARALLEL_DUAL, Double.doubleToLongBits(dualBytesPerSecond))
                .putInt(PARALLEL_GAIN, gain)
                .putInt(PARALLEL_STREAMS, streams)
                .putLong(PARALLEL_UPDATED, System.currentTimeMillis());
        if (peerFingerprint != null && !peerFingerprint.trim().isEmpty()) edit.putString("parallel_peer_fingerprint", peerFingerprint);
        else edit.remove("parallel_peer_fingerprint");
        edit.apply();
    }

    public boolean parallelRecommended() { return recommendedStreams() == 2; }
    public String parallelPeerFingerprint() { return prefs.getString("parallel_peer_fingerprint", null); }

    public int recommendedStreams() {
        int value = prefs.getInt(PARALLEL_STREAMS, 1);
        return value == 2 ? 2 : 1;
    }

    public int parallelGainPercent() { return prefs.getInt(PARALLEL_GAIN, 0); }
    public long parallelUpdatedAt() { return prefs.getLong(PARALLEL_UPDATED, 0L); }

    public double averageSpeed(String route) {
        long bits = prefs.getLong("speed:" + route, Double.doubleToLongBits(0d));
        return Math.max(0d, Double.longBitsToDouble(bits));
    }

    public int successes(String route) { return prefs.getInt("ok:" + route, 0); }
    public int failures(String route) { return prefs.getInt("fail:" + route, 0); }

    public int score(String route) {
        if (!valid(route)) return 0;
        int base = ROUTE_DIRECT.equals(route) ? 70 : (ROUTE_LAN.equals(route) ? 60 : 55);
        double mbps = averageSpeed(route) / (1024d * 1024d);
        int speed = (int) Math.min(35, Math.round(mbps * 1.5));
        int ok = successes(route);
        int fail = failures(route);
        int reliability = ok + fail == 0 ? 0 : (int) Math.round(15d * ok / (ok + fail));
        int failurePenalty = Math.min(20, fail * 2);
        return Math.max(1, base + speed + reliability - failurePenalty);
    }

    /** How long to give Wi-Fi Direct before using an already discovered LAN receiver. */
    public long lanFallbackDelayMillis() {
        int direct = score(ROUTE_DIRECT);
        int lan = score(ROUTE_LAN);
        if (successes(ROUTE_LAN) >= 2 && lan >= direct + 12) return 700L;
        if (successes(ROUTE_DIRECT) >= 2 && direct >= lan + 12) return 5000L;
        return 2500L;
    }

    public String summary() {
        String parallel = parallelUpdatedAt() == 0L
                ? "Streams not tested"
                : (recommendedStreams() + " stream" + (recommendedStreams() == 1 ? "" : "s")
                + " recommended • " + (parallelGainPercent() >= 0 ? "+" : "") + parallelGainPercent() + "% dual gain");
        return "Direct " + score(ROUTE_DIRECT) + " • LAN " + score(ROUTE_LAN)
                + " • PC " + score(ROUTE_PC) + "\n" + parallel;
    }

    private static boolean valid(String route) {
        return ROUTE_DIRECT.equals(route) || ROUTE_LAN.equals(route) || ROUTE_PC.equals(route);
    }
}
