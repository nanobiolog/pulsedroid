package com.pulsedroid.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Manages high-frequency chest accelerometer and gyroscope sensing.
 * Detects seismocardiographic cardiac vibrations and user motion stability.
 */
class ChestSensorManager(
    context: Context,
    private val onAccelData: (timestampNs: Long, x: Float, y: Float, z: Float, stabilityScore: Float) -> Unit,
    private val onGyroData: (timestampNs: Long, x: Float, y: Float, z: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Moving window for motion variance/stability
    private val motionHistory = FloatArray(30)
    private var motionIndex = 0

    var isListening = false
        private set

    fun start() {
        if (isListening) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        isListening = true
    }

    fun stop() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Measure instantaneous dynamic acceleration (excluding 1G gravity ~ 9.8 m/s^2)
                val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                val dynamicAcc = kotlin.math.abs(magnitude - 9.81f)

                motionHistory[motionIndex] = dynamicAcc
                motionIndex = (motionIndex + 1) % motionHistory.size

                // Stability score: 1.0 (perfectly still on chest) to 0.0 (high motion / shaking)
                var varianceSum = 0f
                for (v in motionHistory) {
                    varianceSum += v
                }
                val avgMotion = varianceSum / motionHistory.size
                val stability = (1.0f - (avgMotion / 1.5f)).coerceIn(0.0f, 1.0f)

                onAccelData(now, x, y, z, stability)
            }
            Sensor.TYPE_GYROSCOPE -> {
                onGyroData(now, event.values[0], event.values[1], event.values[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
