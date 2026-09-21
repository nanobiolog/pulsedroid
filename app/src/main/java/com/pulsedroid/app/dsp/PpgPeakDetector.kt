package com.pulsedroid.app.dsp

/**
 * Detects systolic peaks in the filtered PPG signal using an adaptive dynamic threshold.
 * (Based on ubicomplab/Seismo Filter_PPG_Detector)
 */
class PpgPeakDetector(private val pulseDelay: Int = 12) {

    private var lastVal = 0.0
    private var pulseCounter = 0
    private var seekingPeak = false
    private var counter = 0

    fun isPeak(newVal: Double, threshold: Double): Boolean {
        var isPeak = false
        if (counter < pulseDelay * 2) {
            // Ignore early transient peaks before filters settle
        } else if (pulseCounter > 0) {
            if (seekingPeak && newVal < lastVal) {
                isPeak = true
                seekingPeak = false
            }
            pulseCounter--
        } else {
            if (lastVal < threshold && newVal >= threshold) {
                pulseCounter = pulseDelay
                seekingPeak = true
            }
        }
        lastVal = newVal
        counter++
        return isPeak
    }

    fun reset() {
        lastVal = 0.0
        pulseCounter = 0
        seekingPeak = false
        counter = 0
    }
}
