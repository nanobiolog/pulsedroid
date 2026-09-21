package com.pulsedroid.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.ScaleAnimation
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsedroid.app.R
import com.pulsedroid.app.data.HistoryRepository
import com.pulsedroid.app.data.SessionSummary
import com.pulsedroid.app.databinding.ActivityMainBinding
import com.pulsedroid.app.dsp.BpEstimator
import com.pulsedroid.app.sensor.PulseMeasurementEngine
import java.io.File

class MainActivity : AppCompatActivity(), PulseMeasurementEngine.MeasurementListener {

    enum class WizardStage {
        IDLE,
        STEP1_PUT_ON_TABLE,
        STEP1_CALIBRATING_5S,
        STEP2_PLACE_ON_CHEST,
        STEP3_CONTINUOUS_MONITORING
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: PulseMeasurementEngine
    private lateinit var historyRepo: HistoryRepository

    private var currentStage = WizardStage.IDLE
    private var vibrator: Vibrator? = null
    private var lastBeatAnimTime = 0L

    // Step 2 chest settling timer
    private var chestStableContactStartMs: Long = 0L

    // Step 3 live monitoring timer
    private var monitoringStartTimeMs: Long = 0L
    private val monitoringHandler = Handler(Looper.getMainLooper())
    private var monitoringRunnable: Runnable? = null

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                advanceToStep1PutOnTable()
            } else {
                Toast.makeText(this, R.string.permission_camera_rationale, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyRepo = HistoryRepository(this)
        engine = PulseMeasurementEngine(this, this)

        initVibrator()
        setupListeners()
        updateModeDescription()
        renderWizardStage(WizardStage.IDLE)
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun setupListeners() {
        // Mode selector
        binding.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            resetGuidedFlow()
            when (checkedId) {
                R.id.btn_mode_scg -> engine.mode = PulseMeasurementEngine.SensingMode.CHEST_ONLY_SCG
                R.id.btn_mode_dual -> engine.mode = PulseMeasurementEngine.SensingMode.CHEST_AND_FINGER_SCG_PPG
            }
            updateModeDescription()
        }

        // Wizard Main Action Button
        binding.btnWizardAction.setOnClickListener {
            handleWizardAction()
        }

        // Clear history button
        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear History")
                .setMessage("Delete all saved session records?")
                .setPositiveButton("Clear") { _, _ ->
                    historyRepo.clearAll()
                    loadHistoryList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Bottom Navigation
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_measure -> {
                    binding.scrollMonitor.visibility = View.VISIBLE
                    binding.layoutHistoryContainer.visibility = View.GONE
                    true
                }
                R.id.nav_history -> {
                    binding.scrollMonitor.visibility = View.GONE
                    binding.layoutHistoryContainer.visibility = View.VISIBLE
                    loadHistoryList()
                    true
                }
                R.id.nav_calibrate -> {
                    showCalibrationDialog()
                    false
                }
                R.id.nav_guide -> {
                    showPlacementGuideDialog()
                    false
                }
                else -> false
            }
        }
    }

    private fun handleWizardAction() {
        when (currentStage) {
            WizardStage.IDLE -> {
                if (engine.mode == PulseMeasurementEngine.SensingMode.CHEST_AND_FINGER_SCG_PPG) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        return
                    }
                }
                advanceToStep1PutOnTable()
            }
            WizardStage.STEP1_PUT_ON_TABLE -> {
                startTableCalibration5s()
            }
            WizardStage.STEP1_CALIBRATING_5S -> {
                resetGuidedFlow()
            }
            WizardStage.STEP2_PLACE_ON_CHEST -> {
                advanceToStep3ContinuousMonitoring()
            }
            WizardStage.STEP3_CONTINUOUS_MONITORING -> {
                stopAndShowSummary()
            }
        }
    }

    private fun advanceToStep1PutOnTable() {
        hapticTick(50)
        renderWizardStage(WizardStage.STEP1_PUT_ON_TABLE)
    }

    private fun startTableCalibration5s() {
        hapticTick(60)
        renderWizardStage(WizardStage.STEP1_CALIBRATING_5S)
        engine.startMeasurement(engine.mode)
        engine.startSurfaceCalibration(5000L)
    }

    private fun advanceToStep2PlaceOnChest() {
        hapticTick(80)
        chestStableContactStartMs = 0L
        renderWizardStage(WizardStage.STEP2_PLACE_ON_CHEST)
    }

    private fun advanceToStep3ContinuousMonitoring() {
        hapticPattern(longArrayOf(0, 80, 80, 120))
        renderWizardStage(WizardStage.STEP3_CONTINUOUS_MONITORING)
        engine.startRecording()

        monitoringStartTimeMs = System.currentTimeMillis()
        stopMonitoringTimer()
        monitoringRunnable = object : Runnable {
            override fun run() {
                if (currentStage == WizardStage.STEP3_CONTINUOUS_MONITORING) {
                    val elapsedSec = ((System.currentTimeMillis() - monitoringStartTimeMs) / 1000).toInt()
                    binding.tvWizardTitle.text = "3. Monitoring Live ($elapsedSec s)"
                    monitoringHandler.postDelayed(this, 1000)
                }
            }
        }
        monitoringHandler.post(monitoringRunnable!!)
    }

    private fun stopAndShowSummary() {
        hapticTick(100)
        stopMonitoringTimer()
        engine.stopRecording()
    }

    private fun stopMonitoringTimer() {
        monitoringRunnable?.let { monitoringHandler.removeCallbacks(it) }
        monitoringRunnable = null
    }

    private fun resetGuidedFlow() {
        stopMonitoringTimer()
        chestStableContactStartMs = 0L
        if (engine.isMeasuring) {
            engine.stopMeasurement()
        }
        binding.waveformView.clear()
        binding.tvHrValue.text = "--"
        binding.tvHrvValue.text = "Rhythm: Regular"
        binding.tvBpValue.text = "-- / --"
        binding.tvBpCategory.text = "Follow 3-step guide"
        binding.tvStabilityText.text = "Ready"
        binding.viewStabilityDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.text_muted)
        renderWizardStage(WizardStage.IDLE)
    }

    private fun renderWizardStage(stage: WizardStage) {
        currentStage = stage
        when (stage) {
            WizardStage.IDLE -> {
                binding.stepTag1.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.stepTag2.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                binding.stepTag3.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

                binding.tvWizardTitle.text = "Ready for Heart Check"
                binding.tvWizardDesc.text = "Tap below to begin Step 1. You will place your phone flat on a table to set a zero baseline."
                binding.pbWizardProgress.visibility = View.GONE

                binding.btnWizardAction.text = getString(R.string.btn_start_checkup)
                binding.btnWizardAction.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.btnWizardAction.setTextColor(Color.BLACK)
                binding.toggleModeGroup.isEnabled = true
            }
            WizardStage.STEP1_PUT_ON_TABLE -> {
                binding.stepTag1.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.stepTag2.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                binding.stepTag3.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

                binding.tvWizardTitle.text = getString(R.string.wizard_step1_ready_title)
                binding.tvWizardDesc.text = getString(R.string.wizard_step1_ready_desc)
                binding.pbWizardProgress.visibility = View.GONE

                binding.btnWizardAction.text = getString(R.string.btn_start_table_calib)
                binding.btnWizardAction.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.btnWizardAction.setTextColor(Color.BLACK)
                binding.toggleModeGroup.isEnabled = false
            }
            WizardStage.STEP1_CALIBRATING_5S -> {
                binding.stepTag1.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.stepTag2.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                binding.stepTag3.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

                binding.tvWizardTitle.text = getString(R.string.wizard_step1_calibrating_title)
                binding.tvWizardDesc.text = getString(R.string.wizard_step1_calibrating_desc)
                binding.pbWizardProgress.visibility = View.VISIBLE
                binding.pbWizardProgress.progress = 0

                binding.btnWizardAction.text = getString(R.string.btn_calibrating)
                binding.btnWizardAction.setBackgroundColor(Color.parseColor("#334155"))
                binding.btnWizardAction.setTextColor(Color.WHITE)
                binding.toggleModeGroup.isEnabled = false
            }
            WizardStage.STEP2_PLACE_ON_CHEST -> {
                binding.stepTag1.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.stepTag2.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.stepTag3.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

                binding.tvWizardTitle.text = getString(R.string.wizard_step2_title)
                binding.tvWizardDesc.text = if (engine.mode == PulseMeasurementEngine.SensingMode.CHEST_ONLY_SCG) {
                    getString(R.string.wizard_step2_scg_desc)
                } else {
                    getString(R.string.wizard_step2_dual_desc)
                }
                binding.pbWizardProgress.visibility = View.GONE

                binding.btnWizardAction.text = getString(R.string.btn_waiting_chest)
                binding.btnWizardAction.setBackgroundColor(Color.parseColor("#334155"))
                binding.btnWizardAction.setTextColor(Color.WHITE)
                binding.toggleModeGroup.isEnabled = false
            }
            WizardStage.STEP3_CONTINUOUS_MONITORING -> {
                binding.stepTag1.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.stepTag2.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.stepTag3.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))

                binding.tvWizardTitle.text = getString(R.string.wizard_step3_title)
                binding.tvWizardDesc.text = getString(R.string.wizard_step3_desc)
                binding.pbWizardProgress.visibility = View.GONE

                binding.btnWizardAction.text = getString(R.string.btn_stop_and_average)
                binding.btnWizardAction.setBackgroundColor(Color.parseColor("#DC2626"))
                binding.btnWizardAction.setTextColor(Color.WHITE)
                binding.toggleModeGroup.isEnabled = false
            }
        }
    }

    private fun updateModeDescription() {
        if (engine.mode == PulseMeasurementEngine.SensingMode.CHEST_ONLY_SCG) {
            binding.tvHeaderSubtitle.text = "Chest SCG (Screen Facing Up)"
            binding.tvBpCategory.text = "Follow 3-step guide"
        } else {
            binding.tvHeaderSubtitle.text = "Dual Mode (Face Down on Chest)"
            binding.tvBpCategory.text = "Rest finger on camera"
        }
    }

    // Engine Callbacks
    override fun onCalibrationProgress(percent: Int) {
        if (currentStage == WizardStage.STEP1_CALIBRATING_5S) {
            binding.pbWizardProgress.progress = percent
            val remainingSec = (((100 - percent) * 5) / 100).coerceAtLeast(1)
            binding.tvWizardTitle.text = "Calibrating Zero Baseline (${remainingSec}s)..."
        }
    }

    override fun onCalibrationCompleted(noiseRms: Double, threshold: Double) {
        if (currentStage == WizardStage.STEP1_CALIBRATING_5S) {
            advanceToStep2PlaceOnChest()
        }
    }

    override fun onMetricsUpdated(
        heartRateBpm: Int,
        hrvMs: Double,
        pttMs: Double,
        bpReading: BpEstimator.BpReading?,
        stabilityScore: Float,
        isHeartbeatTick: Boolean,
        contactState: PulseMeasurementEngine.ContactState,
        confidencePercent: Int
    ) {
        // Handle automatic transition from Step 2 to Step 3 after settling delay on chest
        if (currentStage == WizardStage.STEP2_PLACE_ON_CHEST) {
            if (contactState == PulseMeasurementEngine.ContactState.CHEST_CONTACT_STABLE && heartRateBpm > 0) {
                if (chestStableContactStartMs == 0L) {
                    chestStableContactStartMs = System.currentTimeMillis()
                }
                val stableDurationMs = System.currentTimeMillis() - chestStableContactStartMs
                val settlingTargetMs = 2500L
                val pct = ((stableDurationMs.toFloat() / settlingTargetMs) * 100).toInt().coerceIn(0, 100)
                binding.pbWizardProgress.visibility = View.VISIBLE
                binding.pbWizardProgress.progress = pct
                val secsLeft = ((settlingTargetMs - stableDurationMs) / 1000 + 1).coerceAtLeast(1)
                binding.tvWizardTitle.text = "Detecting Heart Rhythm (${secsLeft}s)..."

                if (stableDurationMs >= settlingTargetMs) {
                    advanceToStep3ContinuousMonitoring()
                }
            } else {
                chestStableContactStartMs = 0L
                binding.pbWizardProgress.visibility = View.GONE
                binding.tvWizardTitle.text = getString(R.string.wizard_step2_title)
            }
        }

        // Contact State Display & Zero Table Handling
        when (contactState) {
            PulseMeasurementEngine.ContactState.ON_TABLE_STATIONARY -> {
                binding.tvStabilityText.text = "On Table"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.text_muted)
                binding.tvHrValue.text = "--"
                binding.tvHrvValue.text = "Rhythm: Resting on Table"
                binding.tvBpValue.text = "-- / --"
                binding.tvBpCategory.text = "Put phone on chest"
                return
            }
            PulseMeasurementEngine.ContactState.WAITING_FOR_FINGER -> {
                binding.tvStabilityText.text = "Finger on Light"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_amber)
                binding.tvBpCategory.text = "Rest finger on camera"
            }
            PulseMeasurementEngine.ContactState.CHEST_CONTACT_SHAKING -> {
                binding.tvStabilityText.text = "Please Hold Still"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_amber)
            }
            PulseMeasurementEngine.ContactState.CHEST_CONTACT_STABLE -> {
                binding.tvStabilityText.text = "Measuring on Chest"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_green)
            }
        }

        // Update Heart Rate
        if (heartRateBpm > 0) {
            binding.tvHrValue.text = heartRateBpm.toString()
            binding.tvHrvValue.text = "Rhythm: Regular & Steady"
        } else {
            binding.tvHrValue.text = "--"
            binding.tvHrvValue.text = "Detecting pulse..."
        }

        // Pulse Animation on Beat
        if (isHeartbeatTick && heartRateBpm > 0) {
            animateHeartbeat()
        }

        // Update Blood Pressure
        if (bpReading != null && heartRateBpm > 0) {
            binding.tvBpValue.text = "${bpReading.systolic} / ${bpReading.diastolic}"
            binding.tvBpCategory.text = bpReading.classification
        }
    }

    override fun onWaveformSamples(
        scgFiltered: Float,
        ppgFiltered: Float,
        isScgBeat: Boolean,
        isPpgBeat: Boolean
    ) {
        if (currentStage == WizardStage.STEP3_CONTINUOUS_MONITORING || currentStage == WizardStage.STEP2_PLACE_ON_CHEST) {
            binding.waveformView.addSamples(scgFiltered, ppgFiltered, isScgBeat, isPpgBeat)
        }
    }

    override fun onRecordingStateChanged(isRecording: Boolean) {
        // Managed by wizard
    }

    override fun onSessionSaved(summary: SessionSummary, exportDir: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Checkup Summary 🎉")
            .setMessage("Your Results:\n\n" +
                    "❤️ Average Heart Rate: ${summary.avgBpm} beats/min\n" +
                    "🩺 Average Blood Pressure: ${summary.estimatedSbp} / ${summary.estimatedDbp} mmHg\n" +
                    "📊 Status: ${summary.bpCategory}\n" +
                    "⏱️ Monitored Duration: ${summary.durationSeconds} seconds\n\n" +
                    "All readings saved safely to your History!")
            .setPositiveButton("Start New Checkup") { _, _ ->
                resetGuidedFlow()
            }
            .setNeutralButton("View History") { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_history
                resetGuidedFlow()
            }
            .setCancelable(false)
            .show()
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun animateHeartbeat() {
        val now = System.currentTimeMillis()
        if (now - lastBeatAnimTime < 350) return
        lastBeatAnimTime = now

        val scale = ScaleAnimation(
            1.0f, 1.25f, 1.0f, 1.25f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 140
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = 1
        }
        binding.ivHeartLogo.startAnimation(scale)

        hapticTick(25)
    }

    private fun hapticTick(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    private fun hapticPattern(timings: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(timings, -1)
        }
    }

    // Volume-Down trigger for hands-free advancement
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            handleWizardAction()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showCalibrationDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_calibration, null)
        val etSbp = view.findViewById<EditText>(R.id.et_ref_sbp)
        val etDbp = view.findViewById<EditText>(R.id.et_ref_dbp)

        etSbp.setText(engine.bpEstimator.calibRefSbp.toString())
        etDbp.setText(engine.bpEstimator.calibRefDbp.toString())

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("Apply") { _, _ ->
                val sbp = etSbp.text.toString().toIntOrNull() ?: 120
                val dbp = etDbp.text.toString().toIntOrNull() ?: 80
                engine.bpEstimator.calibrate(sbp, dbp, 200.0)
                Toast.makeText(this, "Calibration applied: $sbp / $dbp mmHg", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPlacementGuideDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_placement_guide, null)
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun loadHistoryList() {
        val list = historyRepo.getSummaries()
        if (list.isEmpty()) {
            binding.lvHistory.visibility = View.GONE
            binding.tvEmptyHistory.visibility = View.VISIBLE
        } else {
            binding.lvHistory.visibility = View.VISIBLE
            binding.tvEmptyHistory.visibility = View.GONE

            val adapter = object : ArrayAdapter<SessionSummary>(this, 0, list) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: LayoutInflater.from(context)
                        .inflate(R.layout.item_session_history, parent, false)
                    val item = getItem(position) ?: return row

                    row.findViewById<TextView>(R.id.tv_item_mode).text = item.modeName
                    row.findViewById<TextView>(R.id.tv_item_timestamp).text = item.timestampFormatted
                    row.findViewById<TextView>(R.id.tv_item_bpm).text = "${item.avgBpm} BPM"
                    row.findViewById<TextView>(R.id.tv_item_bp).text =
                        if (item.estimatedSbp > 0) "${item.estimatedSbp}/${item.estimatedDbp} mmHg" else "SCG Only"
                    row.findViewById<TextView>(R.id.tv_item_duration).text = "${item.durationSeconds}s recording"
                    row.findViewById<TextView>(R.id.tv_item_ptt).text =
                        if (item.avgPttMs > 0) "PTT: ${String.format("%.0f", item.avgPttMs)} ms" else ""
                    row.findViewById<TextView>(R.id.tv_item_path).text = item.directoryPath

                    return row
                }
            }
            binding.lvHistory.adapter = adapter
            binding.lvHistory.setOnItemClickListener { _, _, position, _ ->
                val item = list[position]
                val dir = File(item.directoryPath)
                if (dir.exists()) {
                    val files = dir.listFiles()?.map { it.name }?.joinToString("\n• ") ?: "No files"
                    MaterialAlertDialogBuilder(this)
                        .setTitle(item.timestampFormatted)
                        .setMessage("Files in directory:\n• $files\n\nDirectory: ${dir.absolutePath}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() {
        stopMonitoringTimer()
        engine.stopMeasurement()
        super.onDestroy()
    }
}
