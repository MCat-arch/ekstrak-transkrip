package com.ekstraktranskrip.core.model

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

class ModelException(message: String) : Exception(message)

/**
 * Where ggml models live on device.
 *
 * Two folders are scanned:
 *
 *  - filesDir/models      small models (bundled + user imports)
 *  - externalFiles/models large downloads (base), readable by whisper directly
 *
 * The bundled model is copied from the APK assets exactly once, atomically,
 * and verified against a pinned SHA-256. whisper.cpp needs a real file path,
 * so extracting the asset is unavoidable, but it never happens twice.
 */
object ModelStore {

    private const val PREFS = "model_store"
    private const val KEY_ACTIVE_NAME = "active_model_name"
    private const val KEY_ACTIVE_PATH = "active_model_path" // legacy, <= 0.1.0
    private const val KEY_ONBOARDING_DONE = "onboarding_done"
    private const val COPY_BUFFER = 256 * 1024

    /** Headroom on top of the model itself: transcripts, WAV, database. */
    private const val MIN_FREE_BYTES = 48L * 1024 * 1024

    fun internalDir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /** App-specific external storage. No permission needed, survives updates. */
    fun externalDir(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        return File(base, "models").apply { if (!exists()) mkdirs() }
    }

    fun dirs(context: Context): List<File> =
        listOfNotNull(internalDir(context), externalDir(context))

    fun list(context: Context): List<File> =
        dirs(context)
            .flatMap { dir ->
                dir.listFiles { file -> file.isFile && file.name.endsWith(".bin", true) }
                    ?.toList()
                    .orEmpty()
            }
            .distinctBy { it.name }
            .sortedWith(compareBy<File> { catalogOrder(it.name) }.thenBy { it.name })

    fun find(context: Context, fileName: String): File? =
        dirs(context).asSequence().map { File(it, fileName) }.firstOrNull { it.isFile }

    fun specFor(file: File): ModelSpec? = ModelCatalog.byFileName(file.name)

    fun displayName(file: File): String = specFor(file)?.displayName ?: file.name

    /** Copies a user-picked model file into app storage. Blocking. */
    fun importFrom(context: Context, uri: Uri, displayName: String?): File {
        val dir = internalDir(context)
        val safeName = (displayName ?: "model.bin")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "model.bin" }
        val target = File(dir, safeName)
        val tmp = File(dir, "$safeName.part")

        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
        } ?: throw ModelException("Tidak bisa membaca file model")

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    fun setActive(context: Context, file: File) {
        prefs(context).edit()
            .putString(KEY_ACTIVE_NAME, file.name)
            .putString(KEY_ACTIVE_PATH, file.absolutePath)
            .apply()
    }

    fun active(context: Context): File? {
        val prefs = prefs(context)
        val name = prefs.getString(KEY_ACTIVE_NAME, null)
            ?: prefs.getString(KEY_ACTIVE_PATH, null)?.let { File(it).name }
        return name?.let { find(context, it) }
    }

    /** Best guess when nothing is configured yet: the bundled model, then tiny. */
    fun defaultCandidate(context: Context): File? {
        val all = list(context)
        return all.firstOrNull { it.name == ModelCatalog.bundledTinyQ5.fileName }
            ?: all.firstOrNull { it.name.startsWith("ggml-tiny") && !isEnglishOnly(it) }
            ?: all.firstOrNull()
    }

    fun activeOrBundled(context: Context): File? = active(context) ?: defaultCandidate(context)

    /** tiny.en cannot transcribe Indonesian; surface it early instead of failing silently. */
    fun isEnglishOnly(file: File): Boolean = file.name.contains(".en.")

    fun isBundled(file: File): Boolean =
        file.name == ModelCatalog.bundledTinyQ5.fileName

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    fun totalBytes(context: Context): Long = list(context).sumOf { it.length() }

    fun delete(context: Context, file: File): Boolean {
        if (active(context)?.absolutePath == file.absolutePath) {
            prefs(context).edit().remove(KEY_ACTIVE_NAME).remove(KEY_ACTIVE_PATH).apply()
        }
        return file.delete()
    }

    /**
     * Extracts ggml-tiny-q5_1.bin from the APK into internal storage.
     *
     * Idempotent. Blocking: call from Dispatchers.IO. Throws [ModelException]
     * with a message meant for the user when it cannot finish.
     */
    @Synchronized
    fun ensureBundled(context: Context, onProgress: (Float) -> Unit = {}): File {
        val spec = ModelCatalog.bundledTinyQ5
        val assetPath = spec.assetPath ?: throw ModelException("Model bawaan tidak dikonfigurasi.")
        val dir = internalDir(context)
        val target = File(dir, spec.fileName)

        if (target.isFile && target.length() == spec.sizeBytes) {
            if (active(context) == null) setActive(context, target)
            onProgress(1f)
            return target
        }
        if (target.exists()) target.delete()

        val free = dir.usableSpace
        if (free in 1 until spec.sizeBytes + MIN_FREE_BYTES) {
            val needMb = (spec.sizeBytes + MIN_FREE_BYTES) / (1024 * 1024) + 1
            throw ModelException(
                "Ruang penyimpanan hampir penuh. Butuh sekitar $needMb MB kosong.",
            )
        }

        val tmp = File(dir, "${spec.fileName}.part")
        tmp.delete()
        try {
            context.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress((total.toDouble() / spec.sizeBytes).toFloat().coerceIn(0f, 1f))
                    }
                    output.flush()
                }
            }

            if (tmp.length() != spec.sizeBytes) {
                throw ModelException("Salinan model tidak lengkap. Coba lagi.")
            }
            if (!tmp.sha256().equals(spec.sha256, ignoreCase = true)) {
                throw ModelException("Model bawaan rusak (checksum tidak cocok).")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw if (t is ModelException) {
                t
            } else {
                ModelException("Gagal menyiapkan model bawaan: ${t.message ?: t.javaClass.simpleName}")
            }
        }

        if (active(context) == null) setActive(context, target)
        onProgress(1f)
        return target
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun catalogOrder(fileName: String): Int =
        ModelCatalog.all.indexOfFirst { it.fileName == fileName }.let { if (it < 0) 99 else it }
}

/** Lowercase hex SHA-256 of a file. Blocking, ~0.3 s for a 32 MB model. */
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
