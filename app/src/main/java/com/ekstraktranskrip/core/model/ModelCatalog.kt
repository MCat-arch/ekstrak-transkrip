package com.ekstraktranskrip.core.model

/**
 * The complete set of whisper models the app knows about.
 *
 * One model ships inside the APK so the first launch works offline; the rest
 * are optional downloads. Every remote entry is pinned to an exact size and
 * SHA-256, so a silent change on the host is detected instead of trusted.
 */
data class ModelSpec(
    val id: String,
    val fileName: String,
    val displayName: String,
    val tagline: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Asset path inside the APK, for the bundled model. */
    val assetPath: String? = null,
    /** Direct download URL, for optional models. */
    val url: String? = null,
    /** Human readable provenance shown on the license screen. */
    val sourceLabel: String,
) {
    val isBundled: Boolean get() = assetPath != null
    val isDownloadable: Boolean get() = url != null
}

object ModelCatalog {

    private const val HF_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$HF_REVISION"

    /** Shipped inside the APK. Never downloaded, never deleted by the user. */
    val bundledTinyQ5 = ModelSpec(
        id = "tiny-q5_1",
        fileName = "ggml-tiny-q5_1.bin",
        displayName = "Tiny q5_1 (bawaan)",
        tagline = "Paling cepat, hemat RAM. Cocok untuk HP 3 GB.",
        sizeBytes = 32_152_673L,
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        assetPath = "models/ggml-tiny-q5_1.bin",
        sourceLabel = "Bundled in APK · ggerganov/whisper.cpp @$HF_REVISION",
    )

    val tinyFp16 = ModelSpec(
        id = "tiny-fp16",
        fileName = "ggml-tiny.bin",
        displayName = "Tiny fp16",
        tagline = "Akurasi sedikit lebih baik, unduhan lebih besar.",
        sizeBytes = 77_691_713L,
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        url = "$HF_BASE/ggml-tiny.bin",
        sourceLabel = "HuggingFace · ggerganov/whisper.cpp @$HF_REVISION",
    )

    val base = ModelSpec(
        id = "base",
        fileName = "ggml-base.bin",
        displayName = "Base",
        tagline = "Akurasi lebih baik untuk rekaman panjang. Butuh RAM lebih.",
        sizeBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        url = "$HF_BASE/ggml-base.bin",
        sourceLabel = "HuggingFace · ggerganov/whisper.cpp @$HF_REVISION",
    )

    /** Fine-tuned Tiny for Indonesian, hosted by the app author on Drive. */
    val tinyIndonesian = ModelSpec(
        id = "tiny-id-v1",
        fileName = "ggml-tiny-id-q5_v1.bin",
        displayName = "Tiny ID v1",
        tagline = "Model kustom untuk Bahasa Indonesia. Diunduh sekali.",
        sizeBytes = 29_875_721L,
        sha256 = "5c4da0f11919f70085993c2a157ef1a4c24896ab0613c76d3c8741f8b061aeb8",
        url = "https://drive.usercontent.google.com/download" +
            "?id=1DK7S4faGry0IVYSHNDHJKeFyROybx1Dh&export=download&confirm=t",
        sourceLabel = "Google Drive · model kustom (turunan OpenAI Whisper, MIT)",
    )

    /** Shown in settings, bundled first. */
    val all: List<ModelSpec> = listOf(bundledTinyQ5, tinyFp16, base, tinyIndonesian)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    fun byFileName(fileName: String): ModelSpec? = all.firstOrNull { it.fileName == fileName }
}
