package com.pulsedroid.app.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports raw sensor streams to CSV matching ubicomplab/Seismo's file format.
 */
object CsvExporter {

    fun exportSession(
        context: Context,
        accelSamples: List<AccelSample>,
        gyroSamples: List<GyroSample>,
        ppgSamples: List<PpgSample>,
        pttSamples: List<PttSample>,
        summary: SessionSummary
    ): File {
        val rootDir = File(context.getExternalFilesDir(null), "recordings")
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
        val sessionDir = File(rootDir, "session_$timestamp")
        sessionDir.mkdirs()

        // 1. Accel data
        val accelFile = File(sessionDir, "seismo_accel_$timestamp.csv")
        PrintWriter(accelFile).use { writer ->
            writer.println("accel_time,accel_x,accel_y,accel_z")
            for (s in accelSamples) {
                writer.println("${s.timestampNs},${s.x},${s.y},${s.z}")
            }
        }

        // 2. Gyro data
        val gyroFile = File(sessionDir, "seismo_gyro_$timestamp.csv")
        PrintWriter(gyroFile).use { writer ->
            writer.println("gyro_time,gyro_x,gyro_y,gyro_z")
            for (s in gyroSamples) {
                writer.println("${s.timestampNs},${s.x},${s.y},${s.z}")
            }
        }

        // 3. PPG data
        val ppgFile = File(sessionDir, "seismo_ppg_$timestamp.csv")
        PrintWriter(ppgFile).use { writer ->
            writer.println("ppg_time,ppg_r,ppg_g,ppg_b")
            for (s in ppgSamples) {
                writer.println("${s.timestampNs},${s.r},${s.g},${s.b}")
            }
        }

        // 4. PTT data
        val pttFile = File(sessionDir, "seismo_ptt_$timestamp.csv")
        PrintWriter(pttFile).use { writer ->
            writer.println("ptt_time,ptt")
            for (s in pttSamples) {
                writer.println("${s.timestampNs},${s.pttMs}")
            }
        }

        // 5. Summary file
        val summaryFile = File(sessionDir, "seismo_summary_$timestamp.csv")
        PrintWriter(summaryFile).use { writer ->
            writer.println("field,value")
            writer.println("session_id,${summary.id}")
            writer.println("timestamp,${summary.timestampFormatted}")
            writer.println("duration_sec,${summary.durationSeconds}")
            writer.println("mode,${summary.modeName}")
            writer.println("avg_bpm,${summary.avgBpm}")
            writer.println("avg_ptt_ms,${summary.avgPttMs}")
            writer.println("estimated_sbp,${summary.estimatedSbp}")
            writer.println("estimated_dbp,${summary.estimatedDbp}")
            writer.println("bp_category,${summary.bpCategory}")
            writer.println("total_accel_samples,${accelSamples.size}")
            writer.println("total_ppg_samples,${ppgSamples.size}")
            writer.println("total_ptt_samples,${pttSamples.size}")
        }

        return sessionDir
    }
}
