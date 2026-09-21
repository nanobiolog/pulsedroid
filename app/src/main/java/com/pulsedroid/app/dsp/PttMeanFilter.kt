package com.pulsedroid.app.dsp

/**
 * Moving average filter for smoothing Pulse Transit Time (PTT) estimates.
 * (Based on ubicomplab/Seismo Filter_PTT_Mean)
 */
class PttMeanFilter(size: Int = 5) {

    private val buf = DoubleArray(size) { 200.0 }
    private var count = 0

    fun step(newVal: Double): Double {
        for (i in 0 until buf.size - 1) {
            buf[i] = buf[i + 1]
        }
        buf[buf.size - 1] = newVal
        if (count < buf.size) count++
        return getMean()
    }

    fun getMean(): Double {
        if (count == 0) return 200.0
        var sum = 0.0
        val active = count.coerceAtMost(buf.size)
        val start = buf.size - active
        for (i in start until buf.size) {
            sum += buf[i]
        }
        return sum / active
    }

    fun reset() {
        buf.fill(200.0)
        count = 0
    }
}
