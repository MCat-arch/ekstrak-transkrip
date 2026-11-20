package com.ekstraktranskrip.core.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional model downloads via the system DownloadManager.
 *
 * Why DownloadManager instead of a hand-rolled HTTP client: pause/resume,
 * retries, redirects and the notification come for free, and it keeps the
 * download alive if the process dies. The trade-off is that progress must be
 * polled, which is cheap here (a handful of rows, ~1 Hz while active).
 *
 * Every finished file is verified against [ModelSpec.sha256]. A Drive quota
 * page, a truncated transfer or a swapped file all end up as the same clear
 * "checksum tidak cocok" error instead of a model that fails at load time.
 */
class ModelDownloads(private val context: Context) {

    enum class Status { DOWNLOADING, VERIFYING, DONE, FAILED }

    data class Entry(
        val modelId: String,
        val status: Status,
        val progress: Float = 0f,
        val message: String? = null,
    )

    private val prefs = context.getSharedPreferences("model_downloads", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Map<String, Entry>>(emptyMap())

    val state: StateFlow<Map<String, Entry>> = _state.asStateFlow()

    init {
        scope.launch { pollLoop() }
    }

    fun download(spec: ModelSpec) {
        val url = spec.url
        if (url == null) return

        val dm = manager
        if (dm == null) {
            fail(spec.id, "Layanan unduhan tidak tersedia di perangkat ini.")
            return
        }
        val dir = ModelStore.externalDir(context)
        if (dir == null) {
            fail(spec.id, "Penyimpanan eksternal tidak tersedia. Kosongkan ruang lalu coba lagi.")
            return
        }

        cancel(spec.id, silent = true)
        File(dir, spec.fileName).delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Model ${spec.displayName}")
            .setDescription("Mengunduh ${spec.sizeBytes / (1024 * 1024)} MB")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "models/${spec.fileName}")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        runCatching { dm.enqueue(request) }
            .onSuccess { id ->
                prefs.edit().putLong(key(spec.id), id).apply()
                set(Entry(spec.id, Status.DOWNLOADING, 0f))
            }
            .onFailure {
                fail(spec.id, "Tidak bisa memulai unduhan: ${it.message ?: it.javaClass.simpleName}")
            }
    }

    fun cancel(spec: ModelSpec) = cancel(spec.id, silent = false)

    /** Drops a finished/failed row from the UI state (file handling is separate). */
    fun forget(modelId: String) {
        _state.value = _state.value - modelId
    }

    private fun cancel(modelId: String, silent: Boolean) {
        val id = prefs.getLong(key(modelId), -1L)
        if (id >= 0) {
            runCatching { manager?.remove(id) }
            prefs.edit().remove(key(modelId)).apply()
        }
        val spec = ModelCatalog.byId(modelId)
        if (spec != null) {
            ModelStore.externalDir(context)?.let { dir ->
                File(dir, spec.fileName).delete()
                File(dir, "${spec.fileName}.part").delete()
            }
        }
        if (!silent) _state.value = _state.value - modelId
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val tracked = ModelCatalog.all.map { it.id }.filter { prefs.contains(key(it)) }
            if (tracked.isEmpty()) {
                delay(10_000)
                continue
            }

            var busy = false
            for (modelId in tracked) {
                val spec = ModelCatalog.byId(modelId) ?: continue
                val downloadId = prefs.getLong(key(modelId), -1L)
                if (downloadId < 0) continue
                val info = query(downloadId) ?: continue

                when (info.status) {
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        busy = true
                        set(Entry(spec.id, Status.DOWNLOADING, info.progress))
                    }

                    DownloadManager.STATUS_PAUSED -> {
                        busy = true
                        set(Entry(spec.id, Status.DOWNLOADING, info.progress, "Dijeda sistem"))
                    }

                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(key(modelId)).apply()
                        ModelStore.externalDir(context)?.let { dir ->
                            File(dir, spec.fileName).delete()
                        }
                        fail(spec.id, failureMessage(info.reason))
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove(key(modelId)).apply()
                        set(Entry(spec.id, Status.VERIFYING, 1f, "Memeriksa file…"))
                        verify(spec)
                    }
                }
            }
            delay(if (busy) 800 else 3_000)
        }
    }

    private suspend fun verify(spec: ModelSpec) {
        val file = ModelStore.find(context, spec.fileName)
            ?: ModelStore.externalDir(context)?.let { File(it, spec.fileName) }

        val matches = file != null &&
            file.isFile &&
            file.length() == spec.sizeBytes &&
            runCatching { withContext(Dispatchers.IO) { file.sha256() } }
                .getOrNull()
                ?.equals(spec.sha256, ignoreCase = true) == true

        if (matches && file != null) {
            if (ModelStore.active(context) == null) ModelStore.setActive(context, file)
            set(Entry(spec.id, Status.DONE, 1f))
        } else {
            file?.delete()
            fail(spec.id, "File tidak cocok dengan checksum. Coba unduh lagi.")
        }
    }

    private class Info(val status: Int, val progress: Float, val reason: Int)

    private fun query(id: Long): Info? = runCatching {
        val dm = manager ?: return@runCatching null
        dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val progress = if (total > 0) {
                (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            Info(status, progress, reason)
        }
    }.getOrNull()

    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Ruang penyimpanan tidak cukup."
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File sudah ada. Hapus model lama dulu."
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Koneksi terputus saat mengunduh."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Link unduhan bermasalah."
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            "Server menolak unduhan. Kalau ini link Drive, kuota unduhan mungkin habis."
        DownloadManager.ERROR_CANNOT_RESUME -> "Unduhan tidak bisa dilanjutkan. Coba lagi."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Penyimpanan tidak tersedia."
        else -> "Unduhan gagal (kode $reason). Periksa koneksi lalu coba lagi."
    }

    private fun fail(modelId: String, message: String) {
        set(Entry(modelId, Status.FAILED, 0f, message))
    }

    private fun set(entry: Entry) {
        _state.value = _state.value + (entry.modelId to entry)
    }

    private fun key(modelId: String) = "download_id_$modelId"
}
