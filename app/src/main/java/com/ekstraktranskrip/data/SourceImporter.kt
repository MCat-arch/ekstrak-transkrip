package com.ekstraktranskrip.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies a SAF URI into app storage.
 *
 * The transcription service must never depend on a SAF grant: grants live with
 * the activity, and we want a job to survive the UI process being recreated.
 */
object SourceImporter {

    suspend fun copy(
        context: Context,
        uri: Uri,
        destFile: File,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()

        val total = querySize(context, uri)
        val tmp = File(destFile.parentFile, destFile.name + ".part")
        var copied = 0L

        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 0.99f))
                }
            }
        } ?: error("Tidak bisa membuka file sumber")

        if (destFile.exists()) destFile.delete()
        if (!tmp.renameTo(destFile)) {
            tmp.copyTo(destFile, overwrite = true)
            tmp.delete()
        }
        onProgress(1f)
        destFile
    }

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "audio"

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)
}
