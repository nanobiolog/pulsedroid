package com.pulsedroid.app.dsp

/**
 * Spikey filter to accentuate PPG systolic peaks relative to local valley baseline.
 * (Based on ubicomplab/Seismo Filter_PPG_Spikey)
 */
class PpgSpikeyFilter(size: Int = 5) {

    private val history = DoubleArray(size)

    fun step(newVal: Double): Double {
        var minVal = history[0]
        for (i in 0 until history.size - 1) {
            if (history[i + 1] < minVal) {
                minVal = history[i + 1]
            }
            history[i] = history[i + 1]
        }
        history[history.size - 1] = newVal

        var output = 0.0
        for (v in history) {
            output += (v - minVal)
        }
        return output * output
    }

    fun reset() {
        history.fill(0.0)
    }
}
