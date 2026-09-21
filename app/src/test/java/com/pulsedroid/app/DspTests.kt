package com.pulsedroid.app

import com.pulsedroid.app.dsp.BpEstimator
import com.pulsedroid.app.dsp.HeartRateEstimator
import com.pulsedroid.app.dsp.MinMaxCombTracker
import com.pulsedroid.app.dsp.PpgBandpassFilter
import com.pulsedroid.app.dsp.PttMeanFilter
import com.pulsedroid.app.dsp.ScgBandpassFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class DspTests {

    @Test
    fun testScgBandpassFilterStability() {
        val filter = ScgBandpassFilter()
        var maxOutput = 0.0
        for (i in 0 until 400) {
            val t = i / 400.0
            val x = sin(2.0 * Math.PI * 35.0 * t)
            val y = filter.step(x)
            if (kotlin.math.abs(y) > maxOutput) {
                maxOutput = kotlin.math.abs(y)
            }
        }
        assertTrue("Output should respond to in-band frequency", maxOutput > 0.01)
    }

    @Test
    fun testPpgBandpassFilterStability() {
        val filter = PpgBandpassFilter()
        var maxOutput = 0.0
        for (i in 0 until 100) {
            val x = if (i % 20 == 0) 10.0 else 0.0
            val y = filter.step(x)
            if (kotlin.math.abs(y) > maxOutput) {
                maxOutput = kotlin.math.abs(y)
            }
        }
        assertTrue("Filter output should be non-zero on impulse", maxOutput > 0.0)
    }

    @Test
    fun testPttMeanFilter() {
        val pttFilter = PttMeanFilter(5)
        pttFilter.step(210.0)
        pttFilter.step(220.0)
        val mean = pttFilter.step(200.0)
        assertTrue("Mean should be within reasonable bounds", mean in 190.0..230.0)
    }

    @Test
    fun testBpEstimator() {
        val estimator = BpEstimator()
        val baseline = estimator.estimate(200.0)
        assertEquals("Baseline SBP at 200ms should be 120", 120, baseline.systolic)
        assertEquals("Baseline DBP at 200ms should be 80", 80, baseline.diastolic)

        val highBp = estimator.estimate(170.0)
        assertTrue("Shorter PTT must produce higher SBP", highBp.systolic > baseline.systolic)

        val lowBp = estimator.estimate(230.0)
        assertTrue("Longer PTT must produce lower SBP", lowBp.systolic < baseline.systolic)
    }

    @Test
    fun testHeartRateEstimator() {
        val estimator = HeartRateEstimator()
        val intervalNs = 800_000_000L // 800 ms -> 75 BPM
        var t = 1_000_000_000L
        for (i in 0 until 10) {
            estimator.onBeatDetected(t)
            t += intervalNs
        }
        assertEquals("Heart rate should be ~75 BPM", 75, estimator.currentBpm)
    }

    @Test
    fun testTableNoiseFloorGatingOutputsZeroBpm() {
        // Simulate phone on a flat table: tiny sensor noise (0.002 to 0.005 m/s^2)
        val tracker = MinMaxCombTracker(combSize = 240, combSkip = 1)
        val rng = Random(42)
        val noiseAmplitude = 0.004

        for (i in 0 until 500) {
            val noise = (rng.nextDouble() - 0.5) * noiseAmplitude
            tracker.step(noise)
        }

        val minGateThreshold = 0.040 // Minimum threshold required for human cardiac pulse
        assertFalse(
            "Stationary table noise must NOT pass amplitude gating threshold",
            tracker.hasSufficientAmplitude(minGateThreshold)
        )

        val threshold = tracker.getThreshold(0.40, absoluteMinFloor = minGateThreshold)
        assertEquals(
            "Threshold on table noise must be clamped to Double.MAX_VALUE or absolute floor",
            Double.MAX_VALUE,
            threshold,
            0.001
        )
    }

    @Test
    fun testTimeoutDecayToZeroBpm() {
        val estimator = HeartRateEstimator()
        val intervalNs = 800_000_000L // 75 BPM
        var t = 1_000_000_000L
        for (i in 0 until 6) {
            estimator.onBeatDetected(t)
            t += intervalNs
        }
        assertTrue("Heart rate should be detected while beats arrive", estimator.currentBpm > 0)

        // Simulate placing phone on table (no beats for 3.0 seconds)
        val timeOnTableNs = t + 3_000_000_000L
        estimator.checkTimeout(timeOnTableNs)

        assertEquals("Heart rate must decay to 0 when no beats occur for 3s", 0, estimator.currentBpm)
    }
}
