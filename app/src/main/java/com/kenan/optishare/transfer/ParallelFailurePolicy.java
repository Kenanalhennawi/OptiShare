package com.kenan.optishare.transfer;

/** Keeps a failed acceleration experiment from being selected again without a new benchmark. */
public final class ParallelFailurePolicy {
    private ParallelFailurePolicy() { }

    public static int recommendedStreamsAfterFailure(int currentRecommendation) {
        return currentRecommendation == 2 ? 1 : currentRecommendation;
    }
}
