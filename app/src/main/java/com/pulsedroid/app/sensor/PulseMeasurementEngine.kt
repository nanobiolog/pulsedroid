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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Central measurement coordinator running the real-time DSP pipelines for SCG and PPG.
 * (Engine based on ubicomplab/Seismo PulseSensing.java)
 */
class PulseMeasurementEngine(
    private val context: Context,
    private val listener: MeasurementListener
) {

    enum class SensingMode {
        CHEST_ONLY_SCG,
        CHEST_AND_FINGER_SCG_PPG
    }

    interface MeasurementListener {
        fun onMetricsUpdated(
            heartRateBpm: Int,
            hrvMs: Double,
            pttMs: Double,
            bpReading: BpEstimator.BpReading?,
            stabilityScore: Float,
            isHeartbeatTick: Boolean
        )

        fun onWaveformSamples(
            scgFiltered: Float,
            ppgFiltered: Float,
            isScgBeat: Boolean,
            isPpgBeat: Boolean
        )

        fun onRecordingStateChanged(isRecording: Boolean)
        fun onSessionSaved(summary: SessionSummary, exportDir: File)
        fun onError(message: String)
    }

    var mode: SensingMode = SensingMode.CHEST_AND_FINGER_SCG_PPG
    var isMeasuring = false
        private set
    var isRecording = false
        private set

    // DSP components
    val bpEstimator = BpEstimator()
    private val hrEstimator = HeartRateEstimator()
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

    // Recording session buffers
    private val recordAccelSamples = ArrayList<AccelSample>()
    private val recordGyroSamples = ArrayList<GyroSample>()
    private val recordPpgSamples = ArrayList<PpgSample>()
    private val recordPttSamples = ArrayList<PttSample>()
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
        onPpgSample = { ts, r, g, b ->
            handlePpgSample(ts, r, g, b)
        },
        onError = { msg ->
            mainHandler.post { listener.onError(msg) }
        }
    )

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
        } else null

        val summary = SessionSummary(
            id = UUID.randomUUID().toString().take(8),
            timestampFormatted = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date()),
            durationSeconds = durationSec,
            avgBpm = hrEstimator.currentBpm,
            avgPttMs = if (mode == SensingMode.CHEST_AND_FINGER_SCG_PPG) smoothedPtt else 0.0,
            estimatedSbp = bpReading?.systolic ?: 0,
            estimatedDbp = bpReading?.diastolic ?: 0,
            bpCategory = bpReading?.classification ?: "N/A (SCG Only)",
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
            summary = summary.copy(directoryPath = "")
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
        if (isRecording) {
            recordAccelSamples.add(AccelSample(ts, x, y, z))
        }

        // Seismocardiogram is primarily captured on the longitudinal/anterior-posterior axis (y or z depending on flat placement)
        val rawScg = y.toDouble()
        scgRawHistory.add(rawScg)
        if (scgRawHistory.size > 2000) scgRawHistory.removeAt(0)

        val scgFilt = scgBandpass.step(rawScg)
        val scgSpk = scgSpikey.step(scgFilt)
        scgSpikeyHistory.add(scgSpk)
        if (scgSpikeyHistory.size > 2000) scgSpikeyHistory.removeAt(0)

        scgMinMax.step(scgSpk)

        var isScgBeat = false
        // In CHEST_ONLY mode, detect heart rate directly from SCG aortic peaks
        if (mode == SensingMode.CHEST_ONLY_SCG) {
            val scgThreshold = scgMinMax.getThreshold(0.4)
            if (scgSpk > scgThreshold) {
                hrEstimator.onBeatDetected(ts)
                isScgBeat = true
            }
        }

        // Downsample for smooth 60fps graph rendering
        if (scgDownsampler.step(scgFilt)) {
            val renderVal = scgDownsampler.getMean().toFloat()
            mainHandler.post {
                listener.onWaveformSamples(
                    scgFiltered = renderVal,
                    ppgFiltered = 0f,
                    isScgBeat = isScgBeat,
                    isPpgBeat = false
                )
                if (mode == SensingMode.CHEST_ONLY_SCG) {
                    listener.onMetricsUpdated(
                        heartRateBpm = hrEstimator.currentBpm,
                        hrvMs = hrEstimator.currentHrvMs,
                        pttMs = 0.0,
                        bpReading = null,
                        stabilityScore = currentStability,
                        isHeartbeatTick = isScgBeat
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

    private fun handlePpgSample(ts: Long, r: Double, g: Double, b: Double) {
        if (isRecording) {
            recordPpgSamples.add(PpgSample(ts, r, g, b))
        }

        val ppgFilt = ppgBandpass.step(r)
        ppgFiltHistory.add(ppgFilt)
        if (ppgFiltHistory.size > 1000) ppgFiltHistory.removeAt(0)

        val ppgSpk = ppgSpikey.step(ppgFilt)
        ppgMinMax.step(ppgSpk)

        val ppgThreshold = ppgMinMax.getThreshold(0.25)
        var isPpgBeat = false
        if (ppgDetector.isPeak(ppgSpk, ppgThreshold)) {
            isPpgBeat = true
            hrEstimator.onBeatDetected(ts)
            computePtt(ts)
        }

        val bpReading = bpEstimator.estimate(smoothedPtt)

        mainHandler.post {
            listener.onWaveformSamples(
                scgFiltered = 0f,
                ppgFiltered = ppgFilt.toFloat(),
                isScgBeat = false,
                isPpgBeat = isPpgBeat
            )
            listener.onMetricsUpdated(
                heartRateBpm = hrEstimator.currentBpm,
                hrvMs = hrEstimator.currentHrvMs,
                pttMs = smoothedPtt,
                bpReading = bpReading,
                stabilityScore = currentStability,
                isHeartbeatTick = isPpgBeat
            )
        }
    }

    /**
     * Searches backwards in time to correlate the PPG capillary arrival with preceding
     * SCG aortic valve opening vibration.
     * (Directly ported from ubicomplab/Seismo ptt_calc())
     */
    private fun computePtt(ppgPeakTimeNs: Long) {
        pttCounter++
        if (pttCounter < 3) return // Ignore early transient beats
        if (scgSpikeyHistory.size < SCG_SEARCH_TO * 2) return

        // 1. Find max of SCG spikey filter in the search window
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

        // 2. Find local positive peak of raw SCG around the spikey event
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

        // Delay compensation
        val scgBeatDelay = SCG_SEARCH_TO - (scgSpikeyHistory.size - maxScgLoc)
        val ppgBeatDelay = 10 // Optical filter group delay compensation

        val ppgPeakTimeMs = (PPG_SEARCH_TO - ppgBeatDelay) * 33.33
        val scgPeakTimeMs = (SCG_SEARCH_TO - scgBeatDelay) * 2.48

        val rawPtt = scgPeakTimeMs - ppgPeakTimeMs

        // Physiological validity check (typical PTT is between 80 ms and 320 ms)
        if (rawPtt in 80.0..320.0) {
            smoothedPtt = pttMeanFilter.step(rawPtt)
            if (isRecording) {
                recordPttSamples.add(PttSample(ppgPeakTimeNs, smoothedPtt))
            }
        }
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
    }
}
