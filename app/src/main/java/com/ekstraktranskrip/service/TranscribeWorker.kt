package com.ekstraktranskrip.service

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.ekstraktranskrip.AppContainer
import com.ekstraktranskrip.core.DeviceProfile
import com.ekstraktranskrip.core.audio.AudioDecodeException
import com.ekstraktranskrip.core.audio.AudioDecoder
import com.ekstraktranskrip.core.model.ModelStore
import com.ekstraktranskrip.data.JobStatus
import com.ekstraktranskrip.data.SegmentRow
import com.ekstraktranskrip.data.SourceImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One job, start to finish: copy -> decode -> load model -> transcribe -> persist.
 *
 * Deliberately linear. Every step either completes or fails with a code the UI
 * can explain, because a user on a 3 GB phone will hit at least one of them.
 */
class TranscribeWorker(
    private val context: Context,
    private val container: AppContainer,
) {

    private class JobFailure(val code: String, message: String) : Exception(message)

    suspend fun run(jobId: String, sourceUri: Uri) = withContext(Dispatchers.IO) {
        val jobs = container.jobs
        val fileName = jobs.job(jobId)?.sourceName ?: "audio"
        var inputFile: File? = null
        var wavFile: File? = null
        var nativeJob: com.ekstraktranskrip.core.whisper.TranscriptionJob? = null

        fun emit(stage: JobStage, progress: Int, etaMs: Long? = null, detail: String? = null) {
            container.activeJob.value = ActiveJob(
                jobId = jobId,
                fileName = fileName,
                stage = stage,
                progress = progress.coerceIn(0, 100),
                etaMs = etaMs,
                detail = detail,
            )
        }

        try {
            // ---------------------------------------------------------------- copy
            emit(JobStage.IMPORTING, 0)
            jobs.setStage(jobId, JobStatus.IMPORTING, 0)

            val safeName = fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(60)
                .ifBlank { "audio" }
            val target = File(File(context.filesDir, "input"), "$jobId-$safeName")

            val copied = SourceImporter.copy(
                context = context,
                uri = sourceUri,
                destFile = target,
                onProgress = { p ->
                    emit(JobStage.IMPORTING, (p * 100).toInt(), detail = "Menyalin ${(p * 100).toInt()}%")
                },
            )
            inputFile = copied
            jobs.setInputPath(jobId, copied.absolutePath)

            // --------------------------------------------------------------- decode
            emit(JobStage.CONVERTING, 0)
            jobs.setStage(jobId, JobStatus.CONVERTING, 0)

            val wav = File(File(context.filesDir, "audio"), "$jobId.wav")
            wavFile = wav

            val decoded = AudioDecoder(context).decodeFile(
                path = copied.absolutePath,
                outFile = wav,
                isCancelled = { container.cancelRequested },
                onProgress = { p -> emit(JobStage.CONVERTING, (p * 100).toInt()) },
            )
            jobs.setWavPath(jobId, wav.absolutePath)
            jobs.setStage(jobId, JobStatus.CONVERTING, 100, decoded.durationMs)

            if (decoded.durationMs < 1000L) {
                throw JobFailure("TOO_SHORT", "Audio terlalu pendek (< 1 detik).")
            }

            // ---------------------------------------------------------- load model
            emit(JobStage.LOADING_MODEL, 0)
            jobs.setStage(jobId, JobStatus.LOADING_MODEL, 0)

            val model = ModelStore.activeOrBundled(context)
                ?: throw JobFailure(
                    "NO_MODEL",
                    "Model whisper belum siap. Buka Beranda lalu tekan Pulihkan model.",
                )
            if (ModelStore.isEnglishOnly(model)) {
                throw JobFailure(
                    "EN_MODEL",
                    "Model ${model.name} hanya untuk bahasa Inggris. Pilih model multilingual di Pengaturan.",
                )
            }
            jobs.setModel(jobId, model.name)

            val engine = container.engine
            val loadStarted = SystemClock.elapsedRealtime()
            if (!engine.isLoadedFor(model.absolutePath)) {
                if (!engine.load(model.absolutePath)) {
                    throw JobFailure(
                        "MODEL_LOAD",
                        "Gagal memuat ${model.name}. File mungkin rusak atau RAM tidak cukup.",
                    )
                }
            }
            emit(JobStage.LOADING_MODEL, 100, detail = "Model siap dalam ${SystemClock.elapsedRealtime() - loadStarted} ms")

            // ---------------------------------------------------------- transcribe
            jobs.setStage(jobId, JobStatus.TRANSCRIBING, 0)
            emit(JobStage.TRANSCRIBING, 0)

            val job = engine.startJob(
                wavPath = wav.absolutePath,
                language = "id",
                threads = DeviceProfile.threads(context),
                chunkSeconds = DeviceProfile.chunkSeconds(context),
            ) ?: throw JobFailure("JOB_START", "Tidak bisa memulai mesin transkripsi.")

            nativeJob = job
            container.currentNativeJob = job

            val transcribeStarted = SystemClock.elapsedRealtime()
            var lastDbWrite = 0L

            while (!job.isDone) {
                if (container.cancelRequested) job.cancel()

                val progress = job.progress.coerceIn(0, 100)
                val elapsed = SystemClock.elapsedRealtime() - transcribeStarted
                val processedMs = decoded.durationMs * progress / 100

                val eta = if (progress in 1..99 && processedMs > 1000) {
                    ((decoded.durationMs - processedMs) * elapsed / processedMs).coerceAtLeast(0)
                } else {
                    null
                }

                emit(JobStage.TRANSCRIBING, progress, eta)

                val now = SystemClock.elapsedRealtime()
                if (now - lastDbWrite > 1500) {
                    lastDbWrite = now
                    jobs.setProgress(jobId, progress)
                }

                delay(POLL_MS)
            }

            val totalElapsed = SystemClock.elapsedRealtime() - transcribeStarted

            if (!job.isOk) {
                val message = job.error.ifBlank { "Transkripsi gagal." }
                if (container.cancelRequested) {
                    throw JobFailure("CANCELLED", "Dibatalkan.")
                }
                throw JobFailure("TRANSCRIBE_FAILED", message)
            }

            // ------------------------------------------------------------- persist
            emit(JobStage.FINISHING, 100)
            jobs.setStage(jobId, JobStatus.TRANSCRIBING, 100)

            val count = job.segmentCount
            val segments = ArrayList<SegmentRow>(count)
            for (i in 0 until count) {
                val segment = job.segmentAt(i) ?: continue
                segments.add(SegmentRow(idx = i, t0Ms = segment.t0Ms, t1Ms = segment.t1Ms, text = segment.text))
            }

            if (segments.isEmpty()) {
                throw JobFailure(
                    "NO_SPEECH",
                    "Tidak ada ucapan yang terdeteksi. Coba audio yang lebih jelas.",
                )
            }

            jobs.replaceSegments(jobId, segments)

            val rtf = if (decoded.durationMs > 0) {
                totalElapsed.toDouble() / decoded.durationMs.toDouble()
            } else {
                null
            }
            jobs.finish(jobId, JobStatus.DONE, rtf = rtf)

            // Source audio and wav are no longer needed: the user still has the
            // original, and a finished job only needs its transcript.
            wav.delete()
            jobs.setWavPath(jobId, null)
            inputFile?.delete()
            jobs.setInputPath(jobId, null)

            emit(JobStage.FINISHING, 100, detail = "Selesai")
            container.activeJob.value = null
        } catch (cancelled: AudioDecodeException) {
            if (cancelled.code == "CANCELLED" || container.cancelRequested) {
                jobs.finish(jobId, JobStatus.CANCELLED, errorCode = "CANCELLED", errorMessage = "Dibatalkan.")
            } else {
                jobs.finish(jobId, JobStatus.FAILED, errorCode = cancelled.code, errorMessage = cancelled.message)
            }
            wavFile?.delete()
            jobs.setWavPath(jobId, null)
            inputFile?.delete()
            jobs.setInputPath(jobId, null)
            container.activeJob.value = null
        } catch (failure: JobFailure) {
            val cancelled = failure.code == "CANCELLED" || container.cancelRequested
            jobs.finish(
                jobId,
                if (cancelled) JobStatus.CANCELLED else JobStatus.FAILED,
                errorCode = failure.code,
                errorMessage = failure.message,
            )
            wavFile?.delete()
            jobs.setWavPath(jobId, null)
            if (failure.code != "CANCELLED") {
                inputFile?.delete()
                jobs.setInputPath(jobId, null)
            }
            container.activeJob.value = null
        } catch (t: Throwable) {
            jobs.finish(
                jobId,
                JobStatus.FAILED,
                errorCode = "UNEXPECTED",
                errorMessage = t.message ?: t.javaClass.simpleName,
            )
            wavFile?.delete()
            jobs.setWavPath(jobId, null)
            inputFile?.delete()
            jobs.setInputPath(jobId, null)
            container.activeJob.value = null
        } finally {
            container.currentNativeJob = null
            nativeJob?.release()
        }
    }

    private companion object {
        const val POLL_MS = 300L
    }
}
