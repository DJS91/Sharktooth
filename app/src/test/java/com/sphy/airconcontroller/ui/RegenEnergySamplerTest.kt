package com.sphy.airconcontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RegenEnergySamplerTest {
    @Test fun integratesNegativePowerOnlyIncludingSignChanges() {
        val sampler = RegenEnergySampler()
        assertEquals(0.0, sampler.sample(1, 0, -36.0), 0.0)
        assertEquals(0.020, sampler.sample(1, 2_000, -36.0), 1e-9)
        assertEquals(0.005, sampler.sample(1, 4_000, 36.0), 1e-9)
        assertEquals(0.0, sampler.sample(1, 6_000, 36.0), 0.0)
    }

    @Test fun skipsMissingReadingsLongGapsDayChangesAndStoppedSessions() {
        val sampler = RegenEnergySampler()
        sampler.sample(1, 0, -36.0)
        assertEquals(0.0, sampler.sample(1, 2_000, Double.NaN), 0.0)
        assertEquals(0.0, sampler.sample(1, 4_000, -36.0), 0.0)
        assertEquals(0.0, sampler.sample(1, 10_000, -36.0), 0.0)
        assertEquals(0.0, sampler.sample(2, 12_000, -36.0), 0.0)
        assertEquals(0.020, sampler.sample(2, 14_000, -36.0), 1e-9)
        sampler.reset()
        assertEquals(0.0, sampler.sample(2, 16_000, -36.0), 0.0)
    }
}
