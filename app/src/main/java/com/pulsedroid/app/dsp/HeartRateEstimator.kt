package com.pulsedroid.app.dsp

import kotlin.math.sqrt

/**
 * Robust heart rate estimator from inter-beat intervals (IBI).
 * Tracks instantaneous BPM, rolling smoothed BPM, and HRV (RMSSD in ms).
 */
class HeartRateEstimator(private val windowSize: Int = 8) {

    private val beatTimestampsNs = ArrayList<Long>()
    private val recentIbiMs = ArrayList<Double>()

    var currentBpm: Int = 0
        private set
    var currentHrvMs: Double = 0.0
        private set

    fun onBeatDetected(timestampNs: Long = System.nanoTime()) {
        if (beatTimestampsNs.isNotEmpty()) {
            val lastTime = beatTimestampsNs.last()
            val ibiMs = (timestampNs - lastTime) / 1_000_000.0

            // Filter physiologically feasible IBI (300 ms -> 200 bpm, 1500 ms -> 40 bpm)
            if (ibiMs in 300.0..1500.0) {
                recentIbiMs.add(ibiMs)
                if (recentIbiMs.size > windowSize) {
                    recentIbiMs.removeAt(0)
                }

                // Compute median IBI to reject motion flutter artifacts
                val sortedIbi = recentIbiMs.sorted()
                val medianIbi = sortedIbi[sortedIbi.size / 2]
                val instantBpm = (60_000.0 / medianIbi).toInt()

                // Smoothly update currentBpm
                currentBpm = if (currentBpm == 0) instantBpm else (0.7 * currentBpm + 0.3 * instantBpm).toInt()

                // Compute HRV (RMSSD: Root Mean Square of Successive Differences)
                if (recentIbiMs.size >= 3) {
                    var sumDiffSq = 0.0
                    for (i in 0 until recentIbiMs.size - 1) {
                        val diff = recentIbiMs[i + 1] - recentIbiMs[i]
                        sumDiffSq += diff * diff
                    }
                    currentHrvMs = sqrt(sumDiffSq / (recentIbiMs.size - 1))
                }
            }
        }
        beatTimestampsNs.add(timestampNs)
        if (beatTimestampsNs.size > 50) {
            beatTimestampsNs.removeAt(0)
        }
    }

    fun reset() {
        beatTimestampsNs.clear()
        recentIbiMs.clear()
        currentBpm = 0
        currentHrvMs = 0.0
    }
}
