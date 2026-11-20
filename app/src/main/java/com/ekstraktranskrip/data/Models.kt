package com.ekstraktranskrip.data

enum class JobStatus {
    QUEUED,
    IMPORTING,
    CONVERTING,
    LOADING_MODEL,
    TRANSCRIBING,
    DONE,
    FAILED,
    CANCELLED;

    val isFinished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED

    companion object {
        fun fromDb(value: String?): JobStatus =
            entries.firstOrNull { it.name == value } ?: QUEUED
    }
}

data class JobRow(
    val id: String,
    val sourceName: String,
    val inputPath: String?,
    val wavPath: String?,
    val durationMs: Long,
    val status: JobStatus,
    val progress: Int,
    val language: String,
    val modelName: String?,
    val createdAt: Long,
    val finishedAt: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val rtf: Double?,
)

data class SegmentRow(
    val idx: Int,
    val t0Ms: Long,
    val t1Ms: Long,
    val text: String,
)

data class SummaryRow(
    val jobId: String,
    val model: String?,
    val createdAt: Long,
    val ringkasan: String,
    val keputusan: String,
    val actionItems: String,
    val momen: String,
)

data class ChatMessageRow(
    val id: Long,
    val jobId: String,
    val role: String,       // "user" | "model"
    val content: String,
    val createdAt: Long,
)
