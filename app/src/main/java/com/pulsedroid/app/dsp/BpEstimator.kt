package com.pulsedroid.app.dsp

/**
 * Blood Pressure (BP) Estimator using Pulse Transit Time (PTT) based on
 * the Moens-Korteweg and Bramwell-Hill physiological models used in Seismo.
 *
 * PTT is inversely related to arterial stiffness and Blood Pressure:
 *   SBP = a_s / PTT + b_s
 *   DBP = a_d / PTT + b_d
 */
class BpEstimator(
    var calibRefPtt: Double = 200.0,
    var calibRefSbp: Int = 120,
    var calibRefDbp: Int = 80
) {
    data class BpReading(
        val systolic: Int,
        val diastolic: Int,
        val pttMs: Double,
        val classification: String
    )

    // Slope coefficients empirically determined for typical adult brachial-radial arterial path
    private val slopeSbp = 24000.0 // At 200ms -> 120 mmHg; delta of 20ms shifts ~12-15 mmHg
    private val slopeDbp = 15000.0

    fun estimate(pttMs: Double): BpReading {
        // Bound PTT to realistic physiological range (80ms to 350ms)
        val clampedPtt = pttMs.coerceIn(80.0, 350.0)

        // Baseline shift relative to calibrated reference point
        val sbpOffset = calibRefSbp - (slopeSbp / calibRefPtt)
        val dbpOffset = calibRefDbp - (slopeDbp / calibRefPtt)

        val rawSbp = (slopeSbp / clampedPtt) + sbpOffset
        val rawDbp = (slopeDbp / clampedPtt) + dbpOffset

        val sbp = rawSbp.toInt().coerceIn(80, 220)
        val dbp = rawDbp.toInt().coerceIn(50, 140).coerceAtMost(sbp - 20)

        val classification = classifyBp(sbp, dbp)
        return BpReading(sbp, dbp, clampedPtt, classification)
    }

    fun calibrate(refSbp: Int, refDbp: Int, currentPttMs: Double) {
        calibRefSbp = refSbp
        calibRefDbp = refDbp
        calibRefPtt = currentPttMs.coerceIn(120.0, 300.0)
    }

    private fun classifyBp(sbp: Int, dbp: Int): String {
        return when {
            sbp < 120 && dbp < 80 -> "Normal"
            sbp in 120..129 && dbp < 80 -> "Elevated"
            sbp in 130..139 || dbp in 80..89 -> "Stage 1 Hypertension"
            sbp >= 140 || dbp >= 90 -> "Stage 2 Hypertension"
            else -> "Normal"
        }
    }
}
