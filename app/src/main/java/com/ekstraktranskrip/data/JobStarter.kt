package com.ekstraktranskrip.data

import android.content.Context
import android.net.Uri
import com.ekstraktranskrip.AppContainer
import com.ekstraktranskrip.service.TranscribeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Creates the database row and hands the URI to the foreground service. */
object JobStarter {

    suspend fun start(context: Context, container: AppContainer, uri: Uri): String {
        val jobId = UUID.randomUUID().toString()
        val name = SourceImporter.displayName(context, uri)

        // A persistable grant makes the job survive a process restart during
        // the copy step. Not every provider supports it, hence the runCatching.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val row = JobRow(
            id = jobId,
            sourceName = name,
            inputPath = null,
            wavPath = null,
            durationMs = 0,
            status = JobStatus.QUEUED,
            progress = 0,
            language = "id",
            modelName = null,
            createdAt = System.currentTimeMillis(),
            finishedAt = null,
            errorCode = null,
            errorMessage = null,
            rtf = null,
        )

        withContext(Dispatchers.IO) { container.jobs.insert(row) }
        try {
            TranscribeService.start(context, jobId, uri)
        } catch (t: Throwable) {
            // Background-start restrictions or a killed service: do not leave a
            // row stuck on "Menunggu" forever.
            withContext(Dispatchers.IO) {
                container.jobs.finish(
                    jobId,
                    JobStatus.FAILED,
                    errorCode = "SERVICE_START",
                    errorMessage = "Tidak bisa memulai layanan transkripsi: ${t.message}",
                )
            }
            throw t
        }
        return jobId
    }
}
