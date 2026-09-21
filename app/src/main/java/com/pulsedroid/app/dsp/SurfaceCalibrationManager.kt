package com.pulsedroid.app.dsp

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.sqrt

/**
 * Manages zero-pulse baseline calibration on a stable flat surface (table).
 * Determines ambient sensor noise floor so the app never triggers false heartbeats
 * when stationary.
 */
class SurfaceCalibrationManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsedroid_calibration_prefs", Context.MODE_PRIVATE)

    var noiseFloorRms: Double
        get() = prefs.getFloat("calib_noise_floor_rms", 0.015f).toDouble()
        private set(value) = prefs.edit().putFloat("calib_noise_floor_rms", value.toFloat()).apply()

    var minScgPeakThreshold: Double
        get() = prefs.getFloat("calib_min_scg_peak_thresh", 0.040f).toDouble()
        private set(value) = prefs.edit().putFloat("calib_min_scg_peak_thresh", value.toFloat()).apply()

    var isCalibrated: Boolean
        get() = prefs.getBoolean("is_surface_calibrated", false)
        private set(value) = prefs.edit().putBoolean("is_surface_calibrated", value).apply()

    private val sampleBuffer = ArrayList<Float>()
    var isCalibrating = false
        private set

    private val REQUIRED_SAMPLES = 400 // ~1.5 - 2.0 seconds of accelerometer at FASTEST rate

    fun startCalibration() {
        sampleBuffer.clear()
        isCalibrating = true
    }

    fun addSample(dynamicAcc: Float, onProgress: (percent: Int) -> Unit, onComplete: (noiseRms: Double, threshold: Double) -> Unit) {
        if (!isCalibrating) return
        sampleBuffer.add(dynamicAcc)

        val progress = ((sampleBuffer.size.toFloat() / REQUIRED_SAMPLES) * 100).toInt().coerceAtMost(100)
        onProgress(progress)

        if (sampleBuffer.size >= REQUIRED_SAMPLES) {
            isCalibrating = false

            // Compute mean dynamic acceleration
            var sum = 0.0
            for (s in sampleBuffer) {
                sum += s
            }
            val mean = sum / sampleBuffer.size

            // Compute RMS variance
            var sumSqDiff = 0.0
            for (s in sampleBuffer) {
                val diff = s - mean
                sumSqDiff += diff * diff
            }
            val rms = sqrt(sumSqDiff / sampleBuffer.size)

            // Save noise floor: any acceleration below 3x RMS noise is strictly ignored as surface noise
            val calibratedNoise = rms.coerceIn(0.002, 0.05)
            // Real SCG aortic opening peaks are typically 0.08 - 0.25 m/s^2; set gate at 3.5x noise
            val calibratedThreshold = (calibratedNoise * 3.5).coerceIn(0.025, 0.12)

            noiseFloorRms = calibratedNoise
            minScgPeakThreshold = calibratedThreshold
            isCalibrated = true

            onComplete(calibratedNoise, calibratedThreshold)
        }
    }

    fun resetToDefaults() {
        noiseFloorRms = 0.015
        minScgPeakThreshold = 0.040
        isCalibrated = false
    }
}
