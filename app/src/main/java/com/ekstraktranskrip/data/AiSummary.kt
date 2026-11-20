package com.ekstraktranskrip.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini returns JSON matching [com.ekstraktranskrip.core.ai.AiPrompts.summarySchema].
 * The DB stores display-ready text so the UI and the .md export stay trivial.
 */
object AiSummary {

    fun parse(rawJson: String, jobId: String, model: String): SummaryRow {
        val json = JSONObject(stripFences(rawJson))

        val ringkasan = json.optString("ringkasan").trim().ifBlank { "Tidak ada ringkasan." }

        val keputusan = buildList {
            val array = json.optJSONArray("keputusan") ?: JSONArray()
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }

        val actionItems = buildList {
            val array = json.optJSONArray("action_items") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val tugas = item.optString("tugas").trim()
                if (tugas.isBlank()) continue
                val pic = item.optString("penanggung_jawab").trim()
                val tenggat = item.optString("tenggat").trim()
                val details = buildList {
                    if (pic.isNotBlank() && !pic.equals("tidak disebut", true)) add("PIC: $pic")
                    if (tenggat.isNotBlank() && !tenggat.equals("tidak disebut", true)) add("Tenggat: $tenggat")
                }
                add(if (details.isEmpty()) tugas else "$tugas — ${details.joinToString(" · ")}")
            }
        }

        val momen = buildList {
            val array = json.optJSONArray("momen") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val timestamp = item.optString("timestamp").trim()
                val judul = item.optString("judul").trim()
                val ringkas = item.optString("ringkas").trim()
                if (judul.isBlank()) continue
                val head = if (timestamp.isBlank()) judul else "$timestamp $judul"
                add(if (ringkas.isBlank()) head else "$head — $ringkas")
            }
        }

        return SummaryRow(
            jobId = jobId,
            model = model,
            createdAt = System.currentTimeMillis(),
            ringkasan = ringkasan,
            keputusan = bullets(keputusan),
            actionItems = bullets(actionItems),
            momen = bullets(momen),
        )
    }

    private fun bullets(items: List<String>): String =
        if (items.isEmpty()) "Tidak ada." else items.joinToString("\n") { "- $it" }

    private fun stripFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
