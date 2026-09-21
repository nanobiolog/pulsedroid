package com.pulsedroid.app.dsp

/**
 * 4th order real-time Butterworth bandpass filter for Seismocardiography (SCG).
 * Sampling frequency: ~400 Hz
 * Low cutoff: 20 Hz, High cutoff: 50 Hz
 * Isolates the aortic valve opening vibration from the raw chest accelerometer signal.
 * (Based on ubicomplab/Seismo Filter_SCG_Bandpass)
 */
class ScgBandpassFilter {

    private val xBuf = DoubleArray(9)
    private val yBuf = DoubleArray(9)

    private val xCoeffs = doubleArrayOf(
        1.0, 0.0, -4.0, 0.0, 6.0, 0.0, -4.0, 0.0, 1.0
    )
    private val yCoeffs = doubleArrayOf(
        0.0, -0.1067997882, 0.9717485707, -4.0611282729, 10.1448928397,
        -16.5338807306, 17.9662318299, -12.6797170177, 5.2973787891
    )

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
