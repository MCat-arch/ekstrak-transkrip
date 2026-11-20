package com.ekstraktranskrip.service

enum class JobStage(val label: String) {
    IMPORTING("Menyalin file"),
    CONVERTING("Mengonversi ke 16 kHz mono"),
    LOADING_MODEL("Memuat model"),
    TRANSCRIBING("Mentranskripsi"),
    FINISHING("Menyimpan hasil"),
}

data class ActiveJob(
    val jobId: String,
    val fileName: String,
    val stage: JobStage,
    val progress: Int,
    val etaMs: Long? = null,
    val detail: String? = null,
) {
    val percentOverall: Int
        get() = when (stage) {
            JobStage.IMPORTING -> (progress * 0.05f).toInt()
            JobStage.CONVERTING -> (5 + progress * 0.10f).toInt()
            JobStage.LOADING_MODEL -> 16
            JobStage.TRANSCRIBING -> (17 + progress * 0.80f).toInt()
            JobStage.FINISHING -> 98
        }.coerceIn(0, 100)
}
