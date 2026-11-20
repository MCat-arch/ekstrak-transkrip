package com.ekstraktranskrip.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 75_000 -> "1:15", 3_725_000 -> "1:02:05" */
fun formatTimestamp(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val totalSeconds = safe / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** 3_725_000 -> "1 jam 2 menit" */
fun formatDurationWords(ms: Long): String {
    val totalMinutes = (ms / 60000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours jam $minutes menit"
        hours > 0 -> "$hours jam"
        minutes > 0 -> "$minutes menit"
        else -> "${ms / 1000} detik"
    }
}

fun formatEta(etaMs: Long?): String? {
    if (etaMs == null || etaMs <= 0) return null
    val totalSeconds = etaMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "± $hours jam $minutes menit"
        minutes > 0 -> "± $minutes menit"
        else -> "± $seconds detik"
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(epochMs))
