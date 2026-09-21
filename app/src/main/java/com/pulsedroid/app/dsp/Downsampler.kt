package com.pulsedroid.app.dsp

/**
 * Downsampler filter to reduce UI rendering load while preserving accurate waveform dynamics.
 * (Based on ubicomplab/Seismo Filter_Downsampler)
 */
class Downsampler(private val factor: Int) {

    private val buf = DoubleArray(factor)
    private var counter = 0

    fun step(value: Double): Boolean {
        buf[counter] = value
        counter = (counter + 1) % buf.size
        return counter == 0
    }

    fun getMean(): Double {
        var sum = 0.0
        for (v in buf) {
            sum += v
        }
        return sum / buf.size
    }

    fun getMax(): Double {
        var maxVal = buf[0]
        for (v in buf) {
            if (v > maxVal) maxVal = v
        }
        return maxVal
    }

    fun reset() {
        buf.fill(0.0)
        counter = 0
    }
}
