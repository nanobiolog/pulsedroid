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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: PulseMeasurementEngine
    private lateinit var historyRepo: HistoryRepository

    private var vibrator: Vibrator? = null
    private var lastBeatAnimTime = 0L

    // 15-second guided measurement timer
    private var recordingTimer: CountDownTimer? = null
    private val RECORDING_DURATION_MS = 15_000L

    // Active surface calibration dialog references
    private var surfaceCalibDialog: AlertDialog? = null
    private var pbCalibProgress: ProgressBar? = null
    private var tvCalibStatus: TextView? = null
    private var tvCalibValues: TextView? = null

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                engine.startMeasurement(engine.mode)
                updateSensingUiState(true)
            } else {
                Toast.makeText(this, R.string.permission_camera_rationale, Toast.LENGTH_LONG).show()
                binding.btnToggleSensing.isEnabled = true
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
            if (engine.isMeasuring) {
                engine.stopMeasurement()
                updateSensingUiState(false)
            }
            when (checkedId) {
                R.id.btn_mode_scg -> engine.mode = PulseMeasurementEngine.SensingMode.CHEST_ONLY_SCG
                R.id.btn_mode_dual -> engine.mode = PulseMeasurementEngine.SensingMode.CHEST_AND_FINGER_SCG_PPG
            }
            updateModeDescription()
        }

        // Sensing toggle
        binding.btnToggleSensing.setOnClickListener {
            if (engine.isMeasuring) {
                engine.stopMeasurement()
                updateSensingUiState(false)
            } else {
                startMeasurementWithPermissions()
            }
        }

        // 15-second guided recording toggle
        binding.btnToggleRecording.setOnClickListener {
            if (engine.isRecording) {
                cancelGuidedRecording()
            } else {
                startGuidedRecording()
            }
        }

        // Zero Surface Calibration button
        binding.btnZeroSurface.setOnClickListener {
            showSurfaceCalibrationDialog()
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

    private fun startMeasurementWithPermissions() {
        if (engine.mode == PulseMeasurementEngine.SensingMode.CHEST_AND_FINGER_SCG_PPG) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                return
            }
        }
        engine.startMeasurement(engine.mode)
        updateSensingUiState(true)
    }

    private fun updateModeDescription() {
        if (engine.mode == PulseMeasurementEngine.SensingMode.CHEST_ONLY_SCG) {
            binding.tvHeaderSubtitle.text = "Chest SCG (Screen Facing Up)"
            binding.tvInstruction.text = getString(R.string.instruction_scg)
            binding.tvBpCategory.text = "Place on chest"
        } else {
            binding.tvHeaderSubtitle.text = "Dual Mode (Face Down on Chest)"
            binding.tvInstruction.text = getString(R.string.instruction_dual)
            binding.tvBpValue.text = "-- / --"
            binding.tvBpCategory.text = "Rest finger on camera"
        }
    }

    private fun updateSensingUiState(measuring: Boolean) {
        if (measuring) {
            binding.btnToggleSensing.text = getString(R.string.btn_stop)
            binding.btnToggleSensing.setBackgroundColor(Color.parseColor("#334155"))
            binding.btnToggleSensing.setTextColor(Color.WHITE)
            binding.btnToggleRecording.isEnabled = true
            binding.toggleModeGroup.isEnabled = false
        } else {
            cancelGuidedRecording()
            binding.btnToggleSensing.text = getString(R.string.btn_start)
            binding.btnToggleSensing.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleSensing.setTextColor(Color.BLACK)
            binding.btnToggleRecording.isEnabled = false
            binding.btnToggleRecording.text = getString(R.string.btn_record)
            binding.toggleModeGroup.isEnabled = true
            binding.waveformView.clear()
            binding.tvStabilityText.text = "Ready"
            binding.viewStabilityDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.tvHrValue.text = "--"
            binding.tvHrvValue.text = "HRV: -- ms"
            binding.tvBpValue.text = "-- / --"
            binding.tvBpCategory.text = "Place on chest"
        }
    }

    private fun startGuidedRecording() {
        if (!engine.isMeasuring) return
        engine.startRecording()
        binding.layoutRecordProgress.visibility = View.VISIBLE
        binding.pbRecordProgress.progress = 0
        binding.tvProgressPercent.text = "0%"

        recordingTimer?.cancel()
        recordingTimer = object : CountDownTimer(RECORDING_DURATION_MS, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = RECORDING_DURATION_MS - millisUntilFinished
                val percent = ((elapsed.toFloat() / RECORDING_DURATION_MS) * 100).toInt().coerceIn(0, 100)
                binding.pbRecordProgress.progress = percent
                binding.tvProgressPercent.text = "$percent%"
            }

            override fun onFinish() {
                binding.pbRecordProgress.progress = 100
                binding.tvProgressPercent.text = "100%"
                engine.stopRecording()
                binding.layoutRecordProgress.visibility = View.GONE
            }
        }.start()
    }

    private fun cancelGuidedRecording() {
        recordingTimer?.cancel()
        recordingTimer = null
        binding.layoutRecordProgress.visibility = View.GONE
        if (engine.isRecording) {
            engine.stopRecording()
        }
    }

    // Measurement Callbacks
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
        // Contact State Display & Zero Table Handling
        when (contactState) {
            PulseMeasurementEngine.ContactState.ON_TABLE_STATIONARY -> {
                binding.tvStabilityText.text = "On Table (0 BPM)"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.text_muted)
                binding.tvHrValue.text = "--"
                binding.tvHrvValue.text = "No Pulse Detected"
                binding.tvBpValue.text = "-- / --"
                binding.tvBpCategory.text = "Place phone on chest"
                return
            }
            PulseMeasurementEngine.ContactState.WAITING_FOR_FINGER -> {
                binding.tvStabilityText.text = "Place Finger on Camera"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_amber)
                binding.tvBpCategory.text = "Rest index finger on flash"
            }
            PulseMeasurementEngine.ContactState.CHEST_CONTACT_SHAKING -> {
                binding.tvStabilityText.text = "Motion Detected"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_amber)
            }
            PulseMeasurementEngine.ContactState.CHEST_CONTACT_STABLE -> {
                binding.tvStabilityText.text = "Stable on Chest ($confidencePercent%)"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_green)
            }
        }

        // Update Heart Rate
        if (heartRateBpm > 0) {
            binding.tvHrValue.text = heartRateBpm.toString()
            binding.tvHrvValue.text = String.format("HRV: %.1f ms", hrvMs)
        } else {
            binding.tvHrValue.text = "--"
            binding.tvHrvValue.text = "Acquiring..."
        }

        // Pulse Animation on Beat
        if (isHeartbeatTick && heartRateBpm > 0) {
            animateHeartbeat()
        }

        // Update Blood Pressure
        if (bpReading != null && heartRateBpm > 0) {
            binding.tvBpValue.text = "${bpReading.systolic} / ${bpReading.diastolic}"
            val pttStr = if (pttMs > 0) " • PTT: ${String.format("%.0f", pttMs)} ms" else ""
            binding.tvBpCategory.text = "${bpReading.classification}$pttStr"
        }
    }

    override fun onWaveformSamples(
        scgFiltered: Float,
        ppgFiltered: Float,
        isScgBeat: Boolean,
        isPpgBeat: Boolean
    ) {
        binding.waveformView.addSamples(scgFiltered, ppgFiltered, isScgBeat, isPpgBeat)
    }

    override fun onRecordingStateChanged(isRecording: Boolean) {
        if (isRecording) {
            binding.btnToggleRecording.text = getString(R.string.btn_stop_record)
            binding.btnToggleRecording.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            binding.btnToggleRecording.text = getString(R.string.btn_record)
            binding.btnToggleRecording.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_crimson))
            binding.layoutRecordProgress.visibility = View.GONE
        }
    }

    override fun onSessionSaved(summary: SessionSummary, exportDir: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("15s Session Complete")
            .setMessage("Measurement averaged successfully!\n\nAvg Heart Rate: ${summary.avgBpm} BPM\nEst. Blood Pressure: ${summary.estimatedSbp}/${summary.estimatedDbp} mmHg (${summary.bpCategory})\nDuration: ${summary.durationSeconds}s\n\nRaw CSV files exported to:\n${exportDir.name}")
            .setPositiveButton("View in History") { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_history
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onCalibrationProgress(percent: Int) {
        pbCalibProgress?.progress = percent
        tvCalibStatus?.text = "Measuring ambient noise: $percent%"
    }

    override fun onCalibrationCompleted(noiseRms: Double, threshold: Double) {
        pbCalibProgress?.progress = 100
        tvCalibStatus?.text = "Calibration Complete!"
        tvCalibValues?.text = String.format("Noise Floor: %.4f m/s² | Pulse Gate: %.4f m/s²", noiseRms, threshold)
        Toast.makeText(this, "Surface calibrated: 0 pulse on table verified", Toast.LENGTH_SHORT).show()
        surfaceCalibDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSurfaceCalibrationDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_surface_calibration, null)
        pbCalibProgress = view.findViewById(R.id.pb_calib_progress)
        tvCalibStatus = view.findViewById(R.id.tv_calib_status)
        tvCalibValues = view.findViewById(R.id.tv_calib_values)

        tvCalibValues?.text = String.format(
            "Noise Floor: %.4f m/s² | Pulse Gate: %.4f m/s²",
            engine.surfaceCalibrationManager.noiseFloorRms,
            engine.surfaceCalibrationManager.minScgPeakThreshold
        )

        surfaceCalibDialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Calibrate Now") { dialog, _ ->
                // Keep open to perform calibration
            }
            .create()

        surfaceCalibDialog?.show()

        // Handle Calibrate Now click without dismissing dialog
        surfaceCalibDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            tvCalibStatus?.text = "Stay completely still... Calibrating..."
            pbCalibProgress?.progress = 0
            if (!engine.isMeasuring) {
                engine.startMeasurement(PulseMeasurementEngine.SensingMode.CHEST_ONLY_SCG)
            }
            engine.startSurfaceCalibration()
        }
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

        // Haptic pulse
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(25)
        }
    }

    // Volume-Down trigger for hands-free 15s measurement
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!engine.isMeasuring) {
                startMeasurementWithPermissions()
            }
            if (engine.isRecording) {
                cancelGuidedRecording()
            } else {
                startGuidedRecording()
            }
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
