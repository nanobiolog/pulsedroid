package com.pulsedroid.app.dsp

/**
 * Spikey filter to accentuate SCG aortic ejection beats.
 * Computes energy of recent history: (sum of squares)^2.
 * (Based on ubicomplab/Seismo Filter_SCG_Spikey)
 */
class ScgSpikeyFilter(size: Int = 5) {

    private val history = DoubleArray(size)

    fun step(newVal: Double): Double {
        shiftHistory(newVal)
        var sumSquares = 0.0
        for (v in history) {
            sumSquares += v * v
        }
        return sumSquares * sumSquares
    }

    fun reset() {
        history.fill(0.0)
    }

    private fun shiftHistory(newVal: Double) {
        for (i in 0 until history.size - 1) {
            history[i] = history[i + 1]
        }
        history[history.size - 1] = newVal
    }
}
