package com.pulsedroid.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
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
        STEP1_TABLE_CALIBRATION,
        STEP2_PLACE_ON_CHEST,
        STEP3_MEASURING_15S
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: PulseMeasurementEngine
    private lateinit var historyRepo: HistoryRepository

    private var currentStage = WizardStage.IDLE
    private var vibrator: Vibrator? = null
    private var lastBeatAnimTime = 0L

    // 15-second measurement timer
    private var recordingTimer: CountDownTimer? = null
    private val RECORDING_DURATION_MS = 15_000L

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startGuidedFlow()
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
                startGuidedFlow()
            }
            WizardStage.STEP1_TABLE_CALIBRATION -> {
                resetGuidedFlow()
            }
            WizardStage.STEP2_PLACE_ON_CHEST -> {
                advanceToStep3Measuring()
            }
            WizardStage.STEP3_MEASURING_15S -> {
                resetGuidedFlow()
            }
        }
    }

    private fun startGuidedFlow() {
        renderWizardStage(WizardStage.STEP1_TABLE_CALIBRATION)
        engine.startMeasurement(engine.mode)
        engine.startSurfaceCalibration()
    }

    private fun advanceToStep2PlaceOnChest() {
        hapticTick(60)
        renderWizardStage(WizardStage.STEP2_PLACE_ON_CHEST)
    }

    private fun advanceToStep3Measuring() {
        hapticTick(60)
        renderWizardStage(WizardStage.STEP3_MEASURING_15S)
        engine.startRecording()

        recordingTimer?.cancel()
        recordingTimer = object : CountDownTimer(RECORDING_DURATION_MS, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = RECORDING_DURATION_MS - millisUntilFinished
                val percent = ((elapsed.toFloat() / RECORDING_DURATION_MS) * 100).toInt().coerceIn(0, 100)
                binding.pbWizardProgress.progress = percent
                binding.tvWizardTitle.text = "Step 3: Measuring (${(millisUntilFinished / 1000) + 1}s remaining)"
            }

            override fun onFinish() {
                binding.pbWizardProgress.progress = 100
                hapticTick(100)
                engine.stopRecording()
            }
        }.start()
    }

    private fun resetGuidedFlow() {
        recordingTimer?.cancel()
        recordingTimer = null
        if (engine.isMeasuring) {
            engine.stopMeasurement()
        }
        binding.waveformView.clear()
        binding.tvHrValue.text = "--"
        binding.tvHrvValue.text = "HRV: -- ms"
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

                binding.tvWizardTitle.text = "Ready for Guided Measurement"
                binding.tvWizardDesc.text = "Tap below to begin Step 1 (Table Zero Calibration) to calibrate against false sensor noise."
                binding.pbWizardProgress.visibility = View.GONE

                binding.btnWizardAction.text = getString(R.string.btn_start_checkup)
                binding.btnWizardAction.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.btnWizardAction.setTextColor(Color.BLACK)
                binding.toggleModeGroup.isEnabled = true
            }
            WizardStage.STEP1_TABLE_CALIBRATION -> {
                binding.stepTag1.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.stepTag2.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                binding.stepTag3.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

                binding.tvWizardTitle.text = getString(R.string.wizard_step1_title)
                binding.tvWizardDesc.text = getString(R.string.wizard_step1_desc)
                binding.pbWizardProgress.visibility = View.VISIBLE
                binding.pbWizardProgress.progress = 0

                binding.btnWizardAction.text = "Cancel Guide"
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

                binding.btnWizardAction.text = getString(R.string.btn_placed_on_chest)
                binding.btnWizardAction.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
                binding.btnWizardAction.setTextColor(Color.BLACK)
                binding.toggleModeGroup.isEnabled = false
            }
            WizardStage.STEP3_MEASURING_15S -> {
                binding.stepTag1.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.stepTag2.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.stepTag3.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))

                binding.tvWizardTitle.text = getString(R.string.wizard_step3_title)
                binding.tvWizardDesc.text = getString(R.string.wizard_step3_desc)
                binding.pbWizardProgress.visibility = View.VISIBLE
                binding.pbWizardProgress.progress = 0

                binding.btnWizardAction.text = "Cancel Measurement"
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
        if (currentStage == WizardStage.STEP1_TABLE_CALIBRATION) {
            binding.pbWizardProgress.progress = percent
            binding.tvWizardTitle.text = "Step 1: Calibrating Surface Baseline ($percent%)"
        }
    }

    override fun onCalibrationCompleted(noiseRms: Double, threshold: Double) {
        if (currentStage == WizardStage.STEP1_TABLE_CALIBRATION) {
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
        // Handle automatic transition from Step 2 to Step 3 when stable chest contact confirmed
        if (currentStage == WizardStage.STEP2_PLACE_ON_CHEST) {
            if (contactState == PulseMeasurementEngine.ContactState.CHEST_CONTACT_STABLE && heartRateBpm > 0) {
                advanceToStep3Measuring()
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
        if (currentStage == WizardStage.STEP3_MEASURING_15S || currentStage == WizardStage.STEP2_PLACE_ON_CHEST) {
            binding.waveformView.addSamples(scgFiltered, ppgFiltered, isScgBeat, isPpgBeat)
        }
    }

    override fun onRecordingStateChanged(isRecording: Boolean) {
        // Managed by wizard
    }

    override fun onSessionSaved(summary: SessionSummary, exportDir: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Heart Check Finished! 🎉")
            .setMessage("Your Results:\n\n❤️ Heart Rate: ${summary.avgBpm} beats/min\n🩺 Blood Pressure: ${summary.estimatedSbp} / ${summary.estimatedDbp} mmHg\n📊 Status: ${summary.bpCategory}\n\nAll saved safely in your history!")
            .setPositiveButton("View in History") { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_history
                resetGuidedFlow()
            }
            .setNegativeButton("Done") { _, _ ->
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
        recordingTimer?.cancel()
        engine.stopMeasurement()
        super.onDestroy()
    }
}
