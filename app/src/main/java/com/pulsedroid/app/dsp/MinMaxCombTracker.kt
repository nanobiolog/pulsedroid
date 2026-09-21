package com.pulsedroid.app.dsp

/**
 * Tracks local signal minimum and maximum by storing decimated values in a circular comb buffer.
 * Provides adaptive threshold calculations for peak detection with absolute amplitude gating.
 * (Based on ubicomplab/Seismo Filter_MinMax)
 */
class MinMaxCombTracker(
    private val combSize: Int,
    private val combSkip: Int = 1
) {
    private val combBuf = DoubleArray(combSize)
    private var counter = 0

    fun step(value: Double) {
        if (counter % combSkip == 0) {
            val index = (counter / combSkip) % combSize
            combBuf[index] = value
        }
        counter++
    }

    fun getMax(): Double {
        val limit = if (counter / combSkip > combSize) combSize else (counter / combSkip).coerceAtLeast(1)
        var maxVal = combBuf[0]
        for (i in 0 until limit) {
            if (combBuf[i] > maxVal) {
                maxVal = combBuf[i]
            }
        }
        return maxVal
    }

    fun getMin(): Double {
        val limit = if (counter / combSkip > combSize) combSize else (counter / combSkip).coerceAtLeast(1)
        var minVal = combBuf[0]
        for (i in 0 until limit) {
            if (combBuf[i] < minVal) {
                minVal = combBuf[i]
            }
        }
        return minVal
    }

    fun getPeakToPeak(): Double {
        return getMax() - getMin()
    }

    fun hasSufficientAmplitude(minThreshold: Double): Boolean {
        return getPeakToPeak() >= minThreshold
    }

    fun getMiddle(): Double {
        return (getMax() + getMin()) / 2.0
    }

    fun getThreshold(fraction: Double, absoluteMinFloor: Double = 0.0): Double {
        val min = getMin()
        val max = getMax()
        val p2p = max - min
        if (p2p < absoluteMinFloor) {
            // Signal amplitude is below noise floor - prevent false threshold trigger
            return Double.MAX_VALUE
        }
        if (fraction >= 1.0) return max
        if (fraction <= 0.0) return min
        val adaptive = min + p2p * fraction
        return adaptive.coerceAtLeast(absoluteMinFloor)
    }

    fun reset() {
        combBuf.fill(0.0)
        counter = 0
    }
}
