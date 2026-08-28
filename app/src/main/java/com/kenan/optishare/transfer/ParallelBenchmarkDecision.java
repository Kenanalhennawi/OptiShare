package com.kenan.optishare.transfer;

/** Pure decision logic for deciding whether two TCP streams materially outperform one. */
public final class ParallelBenchmarkDecision {
    private static final double MIN_GAIN_RATIO = 1.15d;

    private ParallelBenchmarkDecision() { }

    public static boolean recommendTwoStreams(double singleBytesPerSecond,
                                              double dualBytesPerSecond) {
        if (singleBytesPerSecond <= 0d || dualBytesPerSecond <= 0d) return false;
        return dualBytesPerSecond >= singleBytesPerSecond * MIN_GAIN_RATIO;
    }

    public static int improvementPercent(double singleBytesPerSecond,
                                         double dualBytesPerSecond) {
        if (singleBytesPerSecond <= 0d || dualBytesPerSecond <= 0d) return 0;
        return (int) Math.round(((dualBytesPerSecond / singleBytesPerSecond) - 1d) * 100d);
    }
}
