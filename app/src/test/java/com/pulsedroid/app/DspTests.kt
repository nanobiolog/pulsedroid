package com.pulsedroid.app

import com.pulsedroid.app.dsp.BpEstimator
import com.pulsedroid.app.dsp.HeartRateEstimator
import com.pulsedroid.app.dsp.PpgBandpassFilter
import com.pulsedroid.app.dsp.PttMeanFilter
import com.pulsedroid.app.dsp.ScgBandpassFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class DspTests {

    @Test
    fun testScgBandpassFilterStability() {
        val filter = ScgBandpassFilter()
        // Pass 35 Hz sine wave (within 20-50 Hz passband, fs=400)
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

        // Shorter PTT -> Faster pulse wave -> Higher blood pressure
        val highBp = estimator.estimate(170.0)
        assertTrue("Shorter PTT must produce higher SBP", highBp.systolic > baseline.systolic)

        // Longer PTT -> Slower pulse wave -> Lower blood pressure
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
}
