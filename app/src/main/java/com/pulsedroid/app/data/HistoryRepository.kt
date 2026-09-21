package com.pulsedroid.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository to persist session summaries and reload them for the History screen.
 */
class HistoryRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsedroid_history_prefs", Context.MODE_PRIVATE)

    fun saveSummary(summary: SessionSummary) {
        val list = getSummaries().toMutableList()
        list.add(0, summary) // Newest first

        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestampFormatted)
                put("durationSeconds", item.durationSeconds)
                put("avgBpm", item.avgBpm)
                put("avgPttMs", item.avgPttMs)
                put("estimatedSbp", item.estimatedSbp)
                put("estimatedDbp", item.estimatedDbp)
                put("bpCategory", item.bpCategory)
                put("modeName", item.modeName)
                put("directoryPath", item.directoryPath)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_sessions", jsonArray.toString()).apply()
    }

    fun getSummaries(): List<SessionSummary> {
        val raw = prefs.getString("saved_sessions", null) ?: return emptyList()
        val result = mutableListOf<SessionSummary>()
        try {
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(
                    SessionSummary(
                        id = obj.getString("id"),
                        timestampFormatted = obj.getString("timestamp"),
                        durationSeconds = obj.getInt("durationSeconds"),
                        avgBpm = obj.getInt("avgBpm"),
                        avgPttMs = obj.getDouble("avgPttMs"),
                        estimatedSbp = obj.getInt("estimatedSbp"),
                        estimatedDbp = obj.getInt("estimatedDbp"),
                        bpCategory = obj.getString("bpCategory"),
                        modeName = obj.getString("modeName"),
                        directoryPath = obj.optString("directoryPath", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun clearAll() {
        prefs.edit().remove("saved_sessions").apply()
    }
}
