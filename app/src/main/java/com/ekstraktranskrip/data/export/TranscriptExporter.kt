package com.ekstraktranskrip.data.export

import com.ekstraktranskrip.data.SegmentRow
import com.ekstraktranskrip.data.SummaryRow
import java.util.Locale

/**
 * Transcript -> file text.
 *
 * Plain text keeps timestamps because that is what users search for later
 * ("momen"). SRT is offered for subtitle use.
 */
object TranscriptExporter {

    fun toPlainText(
        sourceName: String,
        segments: List<SegmentRow>,
        includeTimestamps: Boolean = true,
    ): String = buildString {
        appendLine(sourceName)
        appendLine("=".repeat(minOf(sourceName.length, 40)))
        appendLine()
        for (segment in segments) {
            if (includeTimestamps) {
                append('[').append(srtTime(segment.t0Ms, compact = true)).append("] ")
            }
            appendLine(segment.text)
        }
    }

    fun toSrt(segments: List<SegmentRow>): String = buildString {
        segments.forEachIndexed { index, segment ->
            appendLine(index + 1)
            append(srtTime(segment.t0Ms)).append(" --> ").appendLine(srtTime(segment.t1Ms))
            appendLine(segment.text)
            appendLine()
        }
    }

    fun summaryToMarkdown(sourceName: String, summary: SummaryRow): String = buildString {
        appendLine("# Ringkasan: $sourceName")
        appendLine()
        appendLine("## Ringkasan")
        appendLine(summary.ringkasan.ifBlank { "-" })
        appendLine()
        appendLine("## Keputusan")
        appendLine(summary.keputusan.ifBlank { "-" })
        appendLine()
        appendLine("## Action items")
        appendLine(summary.actionItems.ifBlank { "-" })
        appendLine()
        appendLine("## Momen penting")
        appendLine(summary.momen.ifBlank { "-" })
        appendLine()
        appendLine("---")
        appendLine("Dibuat dengan Ekstrak Transkrip · model ${summary.model ?: "?"}")
    }

    /** "HH:MM:SS,mmm" for SRT; compact "HH:MM:SS" or "MM:SS" for plain text. */
    fun srtTime(ms: Long, compact: Boolean = false): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        return if (compact) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
        }
    }

    /** Filename-safe stem, e.g. "Rapat_Mingguan_2026-11-20". */
    fun fileStem(sourceName: String, suffix: String = ""): String {
        val base = sourceName
            .substringBeforeLast('.', sourceName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .take(50)
            .ifBlank { "transkrip" }
        return if (suffix.isBlank()) base else "${base}_$suffix"
    }
}
