package com.kenan.optishare.transfer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ParallelFailurePolicyTest {
    @Test public void failedAccelerationIsDemotedUntilNextBenchmark() {
        assertEquals(1, ParallelFailurePolicy.recommendedStreamsAfterFailure(2));
        assertEquals(1, ParallelFailurePolicy.recommendedStreamsAfterFailure(1));
    }
}
