package com.ekstraktranskrip.core.ai

import com.ekstraktranskrip.data.SegmentRow
import org.json.JSONArray
import org.json.JSONObject

/**
 * All prompts in one place, in Indonesian, with explicit anti-hallucination
 * instructions. The transcript is the only source of truth.
 */
object AiPrompts {

    val SYSTEM_SUMMARY = """
        Kamu asisten notulen rapat berbahasa Indonesia.
        Kamu HANYA boleh memakai isi transkrip yang diberikan. Jangan menambah fakta, nama, angka, atau kesimpulan yang tidak ada di transkrip.
        Jika sebuah bagian tidak dibahas, tulis "Tidak ada" atau "Tidak disebut".
        Selalu pertahankan timestamp asli dari transkrip (format jam:menit:detik).
    """.trimIndent()

    val SYSTEM_CHAT = """
        Kamu asisten yang menjawab pertanyaan tentang satu transkrip rekaman berbahasa Indonesia.
        Aturan:
        1. Jawab HANYA berdasarkan transkrip di bawah. Jika informasinya tidak ada, katakan dengan jujur "Tidak ada di transkrip".
        2. Saat menyebut suatu kejadian, sertakan timestamp dari transkrip, misalnya [00:12:30].
        3. Jawab ringkas, langsung ke inti, dalam bahasa Indonesia.
        4. Jangan mengarang nama orang, angka, atau keputusan.
    """.trimIndent()

    /**
     * Formats transcript lines as "[HH:MM:SS] text".
     * Capped so a 4 hour recording does not blow the request size.
     */
    fun transcriptText(segments: List<SegmentRow>, maxChars: Int = 320_000): String {
        val builder = StringBuilder(minOf(maxChars, 64 * 1024))
        for (segment in segments) {
            val line = "[${clock(segment.t0Ms)}] ${segment.text}\n"
            if (builder.length + line.length > maxChars) {
                builder.append("\n[... transkrip dipotong karena batas panjang ...]\n")
                break
            }
            builder.append(line)
        }
        return builder.toString()
    }

    fun summaryPrompt(sourceName: String, durationMs: Long, transcript: String): String = """
        Berikut transkrip rekaman "$sourceName" (durasi ${durationMs / 60000} menit).

        Buat ringkasan terstruktur:
        - ringkasan: 4-8 kalimat padat tentang isi dan tujuan pertemuan.
        - keputusan: daftar keputusan yang benar-benar diambil. Kosongkan jika tidak ada.
        - action_items: daftar tugas lanjutan. Isi penanggung_jawab dan tenggat hanya jika disebut di transkrip; jika tidak, tulis "Tidak disebut".
        - momen: 3-8 momen penting dengan timestamp persis dari transkrip, berisi judul singkat dan penjelasan satu kalimat.

        Transkrip:
        ---
        $transcript
        ---
    """.trimIndent()

    fun summarySchema(): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put(
            "properties",
            JSONObject().apply {
                put("ringkasan", JSONObject().put("type", "STRING"))
                put(
                    "keputusan",
                    JSONObject().apply {
                        put("type", "ARRAY")
                        put("items", JSONObject().put("type", "STRING"))
                    },
                )
                put(
                    "action_items",
                    JSONObject().apply {
                        put("type", "ARRAY")
                        put(
                            "items",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put("tugas", JSONObject().put("type", "STRING"))
                                        put("penanggung_jawab", JSONObject().put("type", "STRING"))
                                        put("tenggat", JSONObject().put("type", "STRING"))
                                    },
                                )
                                put("required", JSONArray(listOf("tugas", "penanggung_jawab", "tenggat")))
                            },
                        )
                    },
                )
                put(
                    "momen",
                    JSONObject().apply {
                        put("type", "ARRAY")
                        put(
                            "items",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put("timestamp", JSONObject().put("type", "STRING"))
                                        put("judul", JSONObject().put("type", "STRING"))
                                        put("ringkas", JSONObject().put("type", "STRING"))
                                    },
                                )
                                put("required", JSONArray(listOf("timestamp", "judul", "ringkas")))
                            },
                        )
                    },
                )
            },
        )
        put("required", JSONArray(listOf("ringkasan", "keputusan", "action_items", "momen")))
    }

    fun clock(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
