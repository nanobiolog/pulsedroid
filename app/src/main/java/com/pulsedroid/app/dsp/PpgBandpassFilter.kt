package com.pulsedroid.app.dsp

/**
 * Real-time Butterworth bandpass filter for Photoplethysmography (PPG) signals.
 * Isolates the pulsatile capillary blood volume signal sampled at ~30-60 fps.
 * (Based on ubicomplab/Seismo Filter_PPG_Bandpass)
 */
class PpgBandpassFilter {

    private val xBuf = DoubleArray(5)
    private val yBuf = DoubleArray(5)

    private val xCoeffs = doubleArrayOf(1.0, 0.0, -2.0, 0.0, 1.0)
    private val yCoeffs = doubleArrayOf(0.0, -0.1715728753, 0.3053974642, -0.9279891778, 1.7799868642)

    fun step(xNew: Double): Double {
        shiftBuffer(xBuf, xNew)
        var yNew = 0.0
        for (i in xBuf.indices) {
            yNew += xCoeffs[i] * xBuf[i]
            yNew += yCoeffs[i] * yBuf[i]
        }
        shiftBuffer(yBuf, yNew)
        return yNew
    }

    fun reset() {
        xBuf.fill(0.0)
        yBuf.fill(0.0)
    }

    private fun shiftBuffer(buf: DoubleArray, newVal: Double) {
        for (i in 0 until buf.size - 1) {
            buf[i] = buf[i + 1]
        }
        buf[buf.size - 1] = newVal
    }
}
