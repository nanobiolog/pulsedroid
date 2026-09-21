package com.pulsedroid.app.dsp

/**
 * Tracks local signal minimum and maximum by storing decimated values in a circular comb buffer.
 * Provides adaptive threshold calculations for peak detection.
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

    fun getMiddle(): Double {
        return (getMax() + getMin()) / 2.0
    }

    fun getThreshold(fraction: Double): Double {
        if (fraction >= 1.0) return getMax()
        if (fraction <= 0.0) return getMin()
        val min = getMin()
        val max = getMax()
        return min + (max - min) * fraction
    }

    fun reset() {
        combBuf.fill(0.0)
        counter = 0
    }
}
