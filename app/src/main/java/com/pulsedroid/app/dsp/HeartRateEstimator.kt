package com.pulsedroid.app.dsp

import kotlin.math.sqrt

/**
 * Robust heart rate estimator from inter-beat intervals (IBI).
 * Tracks instantaneous BPM, rolling smoothed BPM, HRV (RMSSD in ms),
 * and automatic signal timeout decay to 0 BPM when no beats are present.
 */
class HeartRateEstimator(private val windowSize: Int = 8) {

    private val beatTimestampsNs = ArrayList<Long>()
    private val recentIbiMs = ArrayList<Double>()

    private var lastBeatTimestampNs = 0L

    var currentBpm: Int = 0
        private set
    var currentHrvMs: Double = 0.0
        private set
    var confidencePercent: Int = 0
        private set

    fun onBeatDetected(timestampNs: Long = System.nanoTime()) {
        if (lastBeatTimestampNs > 0) {
            val ibiMs = (timestampNs - lastBeatTimestampNs) / 1_000_000.0

            // Filter physiologically feasible IBI (300 ms -> 200 bpm, 1500 ms -> 40 bpm)
            if (ibiMs in 320.0..1500.0) {
                recentIbiMs.add(ibiMs)
                if (recentIbiMs.size > windowSize) {
                    recentIbiMs.removeAt(0)
                }

                // Compute median IBI to reject motion artifacts
                val sortedIbi = recentIbiMs.sorted()
                val medianIbi = sortedIbi[sortedIbi.size / 2]
                val instantBpm = (60_000.0 / medianIbi).toInt()

                // Smoothly update currentBpm with moving average
                currentBpm = if (currentBpm == 0) instantBpm else (0.65 * currentBpm + 0.35 * instantBpm).toInt()

                // Calculate confidence based on number of consistent intervals
                confidencePercent = ((recentIbiMs.size.toFloat() / windowSize) * 100).toInt().coerceIn(10, 100)

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
        lastBeatTimestampNs = timestampNs
        beatTimestampsNs.add(timestampNs)
        if (beatTimestampsNs.size > 50) {
            beatTimestampsNs.removeAt(0)
        }
    }

    /**
     * Call periodically to decay BPM to 0 if signal stops or phone is stationary.
     */
    fun checkTimeout(nowNs: Long = System.nanoTime()) {
        if (lastBeatTimestampNs > 0) {
            val elapsedSec = (nowNs - lastBeatTimestampNs) / 1_000_000_000.0
            if (elapsedSec > 2.5) {
                // No beat detected in 2.5 seconds -> Decay to 0
                currentBpm = 0
                currentHrvMs = 0.0
                confidencePercent = 0
                recentIbiMs.clear()
            }
        }
    }

    fun reset() {
        beatTimestampsNs.clear()
        recentIbiMs.clear()
        lastBeatTimestampNs = 0L
        currentBpm = 0
        currentHrvMs = 0.0
        confidencePercent = 0
    }
}
