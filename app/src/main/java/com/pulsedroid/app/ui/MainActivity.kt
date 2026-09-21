package com.pulsedroid.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CombinedVibration
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
                R.id.btn_mode_dual -> engine.mode = PulseMeasurementEngine.SensingMode.CHEST_AND_FINGER_SCG_PPG
                R.id.btn_mode_scg -> engine.mode = PulseMeasurementEngine.SensingMode.CHEST_ONLY_SCG
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

        // Recording toggle
        binding.btnToggleRecording.setOnClickListener {
            if (engine.isRecording) {
                engine.stopRecording()
            } else {
                engine.startRecording()
            }
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
        if (engine.mode == PulseMeasurementEngine.SensingMode.CHEST_AND_FINGER_SCG_PPG) {
            binding.tvHeaderSubtitle.text = "Chest SCG + Finger Optical PPG"
            binding.tvInstruction.text = getString(R.string.instruction_dual)
            binding.tvBpValue.text = "-- / --"
            binding.tvBpCategory.text = "PTT: -- ms"
        } else {
            binding.tvHeaderSubtitle.text = "Chest Seismocardiography Only"
            binding.tvInstruction.text = getString(R.string.instruction_scg)
            binding.tvBpValue.text = "N/A"
            binding.tvBpCategory.text = "Select Dual Mode for BP"
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
        }
    }

    // Measurement Callbacks
    override fun onMetricsUpdated(
        heartRateBpm: Int,
        hrvMs: Double,
        pttMs: Double,
        bpReading: BpEstimator.BpReading?,
        stabilityScore: Float,
        isHeartbeatTick: Boolean
    ) {
        // Update Heart Rate
        if (heartRateBpm > 0) {
            binding.tvHrValue.text = heartRateBpm.toString()
            binding.tvHrvValue.text = String.format("HRV: %.1f ms", hrvMs)
        }

        // Pulse Animation on Beat
        if (isHeartbeatTick) {
            animateHeartbeat()
        }

        // Update Blood Pressure
        if (bpReading != null) {
            binding.tvBpValue.text = "${bpReading.systolic} / ${bpReading.diastolic}"
            binding.tvBpCategory.text = "${bpReading.classification} • PTT: ${String.format("%.0f", pttMs)} ms"
        }

        // Update Stability Pill
        when {
            stabilityScore > 0.75f -> {
                binding.tvStabilityText.text = "Stable"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_green)
            }
            stabilityScore > 0.45f -> {
                binding.tvStabilityText.text = "Stabilizing..."
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_amber)
            }
            else -> {
                binding.tvStabilityText.text = "Motion Alert"
                binding.viewStabilityDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_crimson)
            }
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
            Toast.makeText(this, "Recording session started...", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnToggleRecording.text = getString(R.string.btn_record)
            binding.btnToggleRecording.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_crimson))
        }
    }

    override fun onSessionSaved(summary: SessionSummary, exportDir: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Session Saved")
            .setMessage("Recording completed successfully!\n\nDuration: ${summary.durationSeconds}s\nAvg HR: ${summary.avgBpm} BPM\nEst. BP: ${summary.estimatedSbp}/${summary.estimatedDbp} mmHg\n\nCSVs saved to:\n${exportDir.name}")
            .setPositiveButton("View in History") { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_history
            }
            .setNegativeButton("OK", null)
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

        // Haptic pulse
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(25)
        }
    }

    // Volume key trigger (from Seismo App_MeasurementFragment)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && engine.isMeasuring) {
            if (engine.isRecording) {
                engine.stopRecording()
            } else {
                engine.startRecording()
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
                    row.findViewById<TextView>(R.id.tv_item_duration).text = "${item.durationSeconds}s duration"
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
        engine.stopMeasurement()
        super.onDestroy()
    }
}
