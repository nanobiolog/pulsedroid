package com.pulsedroid.app.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data structures for recorded signals and session summaries.
 */
data class AccelSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

data class GyroSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

data class PpgSample(
    val timestampNs: Long,
    val r: Double,
    val g: Double,
    val b: Double
)

data class PttSample(
    val timestampNs: Long,
    val pttMs: Double
)

data class SessionSummary(
    val id: String,
    val timestampFormatted: String,
    val durationSeconds: Int,
    val avgBpm: Int,
    val avgPttMs: Double,
    val estimatedSbp: Int,
    val estimatedDbp: Int,
    val bpCategory: String,
    val modeName: String,
    val directoryPath: String
)
