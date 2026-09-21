package com.pulsedroid.app.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pulsedroid.app.data.AccelSample
import com.pulsedroid.app.data.CsvExporter
import com.pulsedroid.app.data.GyroSample
import com.pulsedroid.app.data.HistoryRepository
import com.pulsedroid.app.data.PpgSample
import com.pulsedroid.app.data.PttSample
import com.pulsedroid.app.data.SessionSummary
import com.pulsedroid.app.dsp.BpEstimator
import com.pulsedroid.app.dsp.Downsampler
import com.pulsedroid.app.dsp.HeartRateEstimator
import com.pulsedroid.app.dsp.MinMaxCombTracker
import com.pulsedroid.app.dsp.PpgBandpassFilter
import com.pulsedroid.app.dsp.PpgPeakDetector
import com.pulsedroid.app.dsp.PpgSpikeyFilter
import com.pulsedroid.app.dsp.PttMeanFilter
import com.pulsedroid.app.dsp.ScgBandpassFilter
import com.pulsedroid.app.dsp.ScgSpikeyFilter
import com.pulsedroid.app.dsp.SurfaceCalibrationManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Central measurement coordinator running the real-time DSP pipelines for SCG and PPG.
 * Features strict noise floor gating, surface zero calibration, and realistic physiological bounds.
 */
class PulseMeasurementEngine(
    private val context: Context,
    private val listener: MeasurementListener
) {

    enum class SensingMode {
        CHEST_ONLY_SCG,
        CHEST_AND_FINGER_SCG_PPG
    }

    enum class ContactState {
        ON_TABLE_STATIONARY,
        CHEST_CONTACT_STABLE,
        CHEST_CONTACT_SHAKING,
        WAITING_FOR_FINGER
    }

    interface MeasurementListener {
        fun onMetricsUpdated(
            heartRateBpm: Int,
            hrvMs: Double,
            pttMs: Double,
            bpReading: BpEstimator.BpReading?,
            stabilityScore: Float,
            isHeartbeatTick: Boolean,
            contactState: ContactState,
            confidencePercent: Int
        )

        fun onWaveformSamples(
            scgFiltered: Float,
            ppgFiltered: Float,
            isScgBeat: Boolean,
            isPpgBeat: Boolean
        )

        fun onRecordingStateChanged(isRecording: Boolean)
        fun onSessionSaved(summary: SessionSummary, exportDir: File)
        fun onCalibrationProgress(percent: Int)
        fun onCalibrationCompleted(noiseRms: Double, threshold: Double)
        fun onError(message: String)
    }

    var mode: SensingMode = SensingMode.CHEST_ONLY_SCG
    var isMeasuring = false
        private set
    var isRecording = false
        private set

    val surfaceCalibrationManager = SurfaceCalibrationManager(context)
    val bpEstimator = BpEstimator()
    private val hrEstimator = HeartRateEstimator(windowSize = 8)

    // DSP components
    private val scgBandpass = ScgBandpassFilter()
    private val scgSpikey = ScgSpikeyFilter(5)
    private val scgMinMax = MinMaxCombTracker(240, 1)
    private val scgDownsampler = Downsampler(4)

    private val ppgBandpass = PpgBandpassFilter()
    private val ppgSpikey = PpgSpikeyFilter(5)
    private val ppgMinMax = MinMaxCombTracker(60, 1)
    private val ppgDetector = PpgPeakDetector(12)

    private val pttMeanFilter = PttMeanFilter(5)

    // Data buffers for PTT correlation
    private val scgRawHistory = ArrayList<Double>()
    private val scgSpikeyHistory = ArrayList<Double>()
    private val ppgFiltHistory = ArrayList<Double>()

    private val SCG_SEARCH_FROM = 110
    private val SCG_SEARCH_TO = 240
    private val PPG_SEARCH_FROM = 4
    private val PPG_SEARCH_TO = 20

    private var pttCounter = 0
    private var smoothedPtt = 200.0
    private var currentStability = 1.0f
    private var lastScgBeatNs = 0L

    // Recording session buffers
    private val recordAccelSamples = ArrayList<AccelSample>()
    private val recordGyroSamples = ArrayList<GyroSample>()
    private val recordPpgSamples = ArrayList<PpgSample>()
    private val recordPttSamples = ArrayList<PttSample>()
    private val recordedBpmList = ArrayList<Int>()
    private val recordedSbpList = ArrayList<Int>()
    private val recordedDbpList = ArrayList<Int>()
    private var recordingStartTimeMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // Hardware Managers
    private val chestSensorManager = ChestSensorManager(
        context = context,
        onAccelData = { ts, x, y, z, stability ->
            handleAccelSample(ts, x, y, z, stability)
        },
        onGyroData = { ts, x, y, z ->
            handleGyroSample(ts, x, y, z)
        }
    )

    private val cameraPpgManager = CameraPpgManager(
        context = context,
        onPpgSample = { ts, r, g, b, isFingerTouching ->
            handlePpgSample(ts, r, g, b, isFingerTouching)
        },
        onError = { msg ->
            mainHandler.post { listener.onError(msg) }
        }
    )

    fun startSurfaceCalibration(durationMs: Long = 5000L) {
        surfaceCalibrationManager.startCalibration(durationMs)
    }

    fun startMeasurement(selectedMode: SensingMode) {
        if (isMeasuring) return
        this.mode = selectedMode
        resetDsp()

        chestSensorManager.start()
        if (mode == SensingMode.CHEST_AND_FINGER_SCG_PPG) {
            cameraPpgManager.start()
        }
        isMeasuring = true
    }

    fun stopMeasurement() {
        if (!isMeasuring) return
        if (isRecording) {
            stopRecording()
        }
        chestSensorManager.stop()
        cameraPpgManager.stop()
        isMeasuring = false
    }

    fun startRecording() {
        if (!isMeasuring) return
        if (isRecording) return
        recordAccelSamples.clear()
        recordGyroSamples.clear()
        recordPpgSamples.clear()
        recordPttSamples.clear()
        recordedBpmList.clear()
        recordedSbpList.clear()
        recordedDbpList.clear()
        recordingStartTimeMs = System.currentTimeMillis()
        isRecording = true
        mainHandler.post { listener.onRecordingStateChanged(true) }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val durationSec = ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).toInt().coerceAtLeast(1)

        val bpReading = if (mode == SensingMode.CHEST_AND_FINGER_SCG_PPG) {
            bpEstimator.estimate(smoothedPtt)
        } else {
            estimateBpFromScgOnly()
        }

        val finalAvgBpm = if (recordedBpmList.isNotEmpty()) {
            recordedBpmList.average().toInt().coerceIn(30, 220)
        } else {
            hrEstimator.currentBpm
        }

        val finalAvgSbp = if (recordedSbpList.isNotEmpty()) {
            recordedSbpList.average().toInt().coerceIn(80, 220)
        } else {
            bpReading.systolic
        }

        val finalAvgDbp = if (recordedDbpList.isNotEmpty()) {
            recordedDbpList.average().toInt().coerceIn(50, 140).coerceAtMost(finalAvgSbp - 20)
        } else {
            bpReading.diastolic
        }

        val finalCategory = bpEstimator.classifyBp(finalAvgSbp, finalAvgDbp)

        val summary = SessionSummary(
            id = UUID.randomUUID().toString().take(8),
            timestampFormatted = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date()),
            durationSeconds = durationSec,
            avgBpm = finalAvgBpm,
            avgPttMs = if (mode == SensingMode.CHEST_AND_FINGER_SCG_PPG) smoothedPtt else 0.0,
            estimatedSbp = finalAvgSbp,
            estimatedDbp = finalAvgDbp,
            bpCategory = finalCategory,
            modeName = if (mode == SensingMode.CHEST_AND_FINGER_SCG_PPG) "Dual SCG + PPG" else "Chest SCG",
            directoryPath = ""
        )

        // Export to CSV files
        val exportDir = CsvExporter.exportSession(
            context = context,
            accelSamples = recordAccelSamples.toList(),
            gyroSamples = recordGyroSamples.toList(),
            ppgSamples = recordPpgSamples.toList(),
            pttSamples = recordPttSamples.toList(),
            summary = summary
        )

        val finalSummary = summary.copy(directoryPath = exportDir.absolutePath)
        HistoryRepository(context).saveSummary(finalSummary)

        mainHandler.post {
            listener.onRecordingStateChanged(false)
            listener.onSessionSaved(finalSummary, exportDir)
        }
    }

    private fun handleAccelSample(ts: Long, x: Float, y: Float, z: Float, stability: Float) {
        currentStability = stability

        // Calculate dynamic acceleration magnitude (difference from 1G gravity)
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val dynamicAcc = abs(magnitude - 9.80665f)

        // Pass to surface calibration if active
        if (surfaceCalibrationManager.isCalibrating) {
            surfaceCalibrationManager.addSample(
                dynamicAcc = dynamicAcc,
                onProgress = { p -> mainHandler.post { listener.onCalibrationProgress(p) } },
                onComplete = { noise, thresh -> mainHandler.post { listener.onCalibrationCompleted(noise, thresh) } }
            )
            return
        }

        if (isRecording) {
            recordAccelSamples.add(AccelSample(ts, x, y, z))
        }

        // Seismocardiogram: Phone resting flat on chest registers aortic ejection along perpendicular/longitudinal axis
        val rawScg = y.toDouble()
        scgRawHistory.add(rawScg)
        if (scgRawHistory.size > 2000) scgRawHistory.removeAt(0)

        val scgFilt = scgBandpass.step(rawScg)
        val scgSpk = scgSpikey.step(scgFilt)
        scgSpikeyHistory.add(scgSpk)
        if (scgSpikeyHistory.size > 2000) scgSpikeyHistory.removeAt(0)

        scgMinMax.step(scgSpk)

        // Check timeout decay (e.g. if phone put on table, drop BPM to 0 after 2.5s)
        hrEstimator.checkTimeout(ts)

        // Contact detection & Noise Floor Gating
        val minPeakThreshold = surfaceCalibrationManager.minScgPeakThreshold
        val isAboveNoiseFloor = scgMinMax.hasSufficientAmplitude(minPeakThreshold)

        val contactState = when {
            !isAboveNoiseFloor -> ContactState.ON_TABLE_STATIONARY
            stability < 0.45f -> ContactState.CHEST_CONTACT_SHAKING
            else -> ContactState.CHEST_CONTACT_STABLE
        }

        var isScgBeat = false
        if (mode == SensingMode.CHEST_ONLY_SCG && isAboveNoiseFloor && contactState != ContactState.ON_TABLE_STATIONARY) {
            val scgThreshold = scgMinMax.getThreshold(0.40, absoluteMinFloor = minPeakThreshold)
            // Physiological refractory period: at least 320 ms between consecutive heartbeats (< 187 bpm)
            val timeSinceLastBeatNs = ts - lastScgBeatNs
            if (scgSpk >= scgThreshold && timeSinceLastBeatNs > 320_000_000L) {
                lastScgBeatNs = ts
                hrEstimator.onBeatDetected(ts)
                isScgBeat = true
            }
        }

        // Downsample for 60fps UI rendering
        if (scgDownsampler.step(scgFilt)) {
            val renderVal = if (contactState == ContactState.ON_TABLE_STATIONARY) 0f else scgDownsampler.getMean().toFloat()
            val currentBpm = hrEstimator.currentBpm
            val bpReading = if (mode == SensingMode.CHEST_ONLY_SCG && currentBpm > 0) {
                estimateBpFromScgOnly()
            } else null

            if (isRecording && currentBpm > 0 && isScgBeat) {
                recordedBpmList.add(currentBpm)
                if (bpReading != null) {
                    recordedSbpList.add(bpReading.systolic)
                    recordedDbpList.add(bpReading.diastolic)
                }
            }

            mainHandler.post {
                listener.onWaveformSamples(
                    scgFiltered = renderVal,
                    ppgFiltered = 0f,
                    isScgBeat = isScgBeat,
                    isPpgBeat = false
                )
                if (mode == SensingMode.CHEST_ONLY_SCG) {
                    listener.onMetricsUpdated(
                        heartRateBpm = currentBpm,
                        hrvMs = hrEstimator.currentHrvMs,
                        pttMs = 0.0,
                        bpReading = bpReading,
                        stabilityScore = currentStability,
                        isHeartbeatTick = isScgBeat,
                        contactState = contactState,
                        confidencePercent = hrEstimator.confidencePercent
                    )
                }
            }
        }
    }

    private fun handleGyroSample(ts: Long, x: Float, y: Float, z: Float) {
        if (isRecording) {
            recordGyroSamples.add(GyroSample(ts, x, y, z))
        }
    }

    private fun handlePpgSample(ts: Long, r: Double, g: Double, b: Double, isFingerTouching: Boolean) {
        if (isRecording) {
            recordPpgSamples.add(PpgSample(ts, r, g, b))
        }

        if (!isFingerTouching) {
            // Finger not covering the camera -> Gate out false PPG pulses
            hrEstimator.checkTimeout(ts)
            mainHandler.post {
                listener.onWaveformSamples(
                    scgFiltered = 0f,
                    ppgFiltered = 0f,
                    isScgBeat = false,
                    isPpgBeat = false
                )
                listener.onMetricsUpdated(
                    heartRateBpm = hrEstimator.currentBpm,
                    hrvMs = 0.0,
                    pttMs = 0.0,
                    bpReading = null,
                    stabilityScore = currentStability,
                    isHeartbeatTick = false,
                    contactState = ContactState.WAITING_FOR_FINGER,
                    confidencePercent = 0
                )
            }
            return
        }

        val ppgFilt = ppgBandpass.step(r)
        ppgFiltHistory.add(ppgFilt)
        if (ppgFiltHistory.size > 1000) ppgFiltHistory.removeAt(0)

        val ppgSpk = ppgSpikey.step(ppgFilt)
        ppgMinMax.step(ppgSpk)

        val ppgThreshold = ppgMinMax.getThreshold(0.25, absoluteMinFloor = 5.0)
        var isPpgBeat = false
        if (ppgMinMax.hasSufficientAmplitude(5.0) && ppgDetector.isPeak(ppgSpk, ppgThreshold)) {
            isPpgBeat = true
            hrEstimator.onBeatDetected(ts)
            computePtt(ts)
        }

        val currentBpm = hrEstimator.currentBpm
        val bpReading = if (currentBpm > 0) bpEstimator.estimate(smoothedPtt) else null

        if (isRecording && currentBpm > 0 && isPpgBeat) {
            recordedBpmList.add(currentBpm)
            if (bpReading != null) {
                recordedSbpList.add(bpReading.systolic)
                recordedDbpList.add(bpReading.diastolic)
            }
        }

        mainHandler.post {
            listener.onWaveformSamples(
                scgFiltered = 0f,
                ppgFiltered = ppgFilt.toFloat(),
                isScgBeat = false,
                isPpgBeat = isPpgBeat
            )
            listener.onMetricsUpdated(
                heartRateBpm = currentBpm,
                hrvMs = hrEstimator.currentHrvMs,
                pttMs = if (currentBpm > 0) smoothedPtt else 0.0,
                bpReading = bpReading,
                stabilityScore = currentStability,
                isHeartbeatTick = isPpgBeat,
                contactState = ContactState.CHEST_CONTACT_STABLE,
                confidencePercent = hrEstimator.confidencePercent
            )
        }
    }

    private fun computePtt(ppgPeakTimeNs: Long) {
        pttCounter++
        if (pttCounter < 3) return
        if (scgSpikeyHistory.size < SCG_SEARCH_TO * 2) return

        var index = scgSpikeyHistory.size - 1 - SCG_SEARCH_FROM
        val minIndex = (scgSpikeyHistory.size - 1 - SCG_SEARCH_TO).coerceAtLeast(0)

        var maxSpikeyLoc = index
        var maxSpikeyVal = scgSpikeyHistory.getOrNull(index) ?: 0.0

        while (index > minIndex) {
            val v = scgSpikeyHistory[index]
            if (v > maxSpikeyVal) {
                maxSpikeyLoc = index
                maxSpikeyVal = v
            }
            index--
        }

        val localStart = (maxSpikeyLoc - 5).coerceAtLeast(0)
        val localEnd = (localStart + 10).coerceAtMost(scgRawHistory.size - 1)
        var maxScgLoc = localStart
        var maxScg = scgRawHistory.getOrNull(localStart) ?: 0.0

        for (i in localStart..localEnd) {
            val v = scgRawHistory[i]
            if (v > maxScg) {
                maxScgLoc = i
                maxScg = v
            }
        }

        val scgBeatDelay = SCG_SEARCH_TO - (scgSpikeyHistory.size - maxScgLoc)
        val ppgBeatDelay = 10

        val ppgPeakTimeMs = (PPG_SEARCH_TO - ppgBeatDelay) * 33.33
        val scgPeakTimeMs = (SCG_SEARCH_TO - scgBeatDelay) * 2.48

        val rawPtt = scgPeakTimeMs - ppgPeakTimeMs

        if (rawPtt in 80.0..320.0) {
            smoothedPtt = pttMeanFilter.step(rawPtt)
            if (isRecording) {
                recordPttSamples.add(PttSample(ppgPeakTimeNs, smoothedPtt))
            }
        }
    }

    /**
     * Estimates Blood Pressure directly from SCG aortic acceleration dynamics and baseline calibration.
     * Peak aortic acceleration correlates with ventricular contractility and stroke volume.
     */
    private fun estimateBpFromScgOnly(): BpEstimator.BpReading {
        val peakAmp = scgMinMax.getPeakToPeak()
        // Relative amplitude modulation around reference calibration
        val normalizedAmp = (peakAmp / (surfaceCalibrationManager.minScgPeakThreshold * 3.0)).coerceIn(0.7, 1.4)
        val sbp = (bpEstimator.calibRefSbp * normalizedAmp).toInt().coerceIn(90, 180)
        val dbp = (bpEstimator.calibRefDbp * (0.8 + 0.2 * normalizedAmp)).toInt().coerceIn(60, 110)
        val classification = when {
            sbp < 120 && dbp < 80 -> "Healthy & Normal"
            sbp in 120..129 && dbp < 80 -> "Normal (Good)"
            sbp in 130..139 || dbp in 80..89 -> "Slightly High"
            sbp >= 140 || dbp >= 90 -> "High"
            else -> "Healthy & Normal"
        }
        return BpEstimator.BpReading(sbp, dbp, 0.0, classification)
    }

    private fun resetDsp() {
        scgBandpass.reset()
        scgSpikey.reset()
        scgMinMax.reset()
        scgDownsampler.reset()
        ppgBandpass.reset()
        ppgSpikey.reset()
        ppgMinMax.reset()
        ppgDetector.reset()
        pttMeanFilter.reset()
        hrEstimator.reset()
        scgRawHistory.clear()
        scgSpikeyHistory.clear()
        ppgFiltHistory.clear()
        pttCounter = 0
        smoothedPtt = 200.0
        lastScgBeatNs = 0L
    }
}
