package com.kenan.optishare.transfer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ParallelBenchmarkDecisionTest {
    @Test public void requiresMeaningfulGain() {
        assertFalse(ParallelBenchmarkDecision.recommendTwoStreams(10d, 11.4d));
        assertTrue(ParallelBenchmarkDecision.recommendTwoStreams(10d, 11.5d));
    }

    @Test public void reportsImprovementPercent() {
        assertEquals(50, ParallelBenchmarkDecision.improvementPercent(10d, 15d));
        assertEquals(0, ParallelBenchmarkDecision.improvementPercent(0d, 15d));
    }
}
