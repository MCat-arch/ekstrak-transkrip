package com.ekstraktranskrip.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import com.ekstraktranskrip.App
import com.ekstraktranskrip.AppContainer
import com.ekstraktranskrip.MainActivity
import com.ekstraktranskrip.data.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Foreground service wrapping [TranscribeWorker].
 *
 * dataSync is the correct type: a transcription is a bounded local data
 * operation, not media playback, and it keeps the process alive when the user
 * leaves the app for 40 minutes.
 */
class TranscribeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var container: AppContainer

    @Volatile
    private var runningJobId: String? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        container = App.container(this)

        scope.launch {
            container.activeJob.filterNotNull().collect { active ->
                if (active.jobId == runningJobId) {
                    notifications()?.notify(NOTIF_ID, buildNotification(active))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            container.requestCancel()
            if (runningJobId == null) stopSelf()
            return START_NOT_STICKY
        }

        val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        val uriString = intent?.getStringExtra(EXTRA_SOURCE_URI)

        if (jobId == null || uriString == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runningJobId != null) {
            // Already busy with a job; the UI prevents this, ignore defensively.
            return START_NOT_STICKY
        }

        val sourceUri = Uri.parse(uriString)
        runningJobId = jobId
        container.cancelRequested = false
        container.activeJob.value = null
        acquireWakeLock()

        startForeground(NOTIF_ID, buildNotification(null))

        scope.launch {
            try {
                TranscribeWorker(applicationContext, container).run(jobId, sourceUri)
            } finally {
                val row = runCatching { container.jobs.job(jobId) }.getOrNull()
                val notification = when (row?.status) {
                    JobStatus.DONE -> finalNotification(
                        row.sourceName,
                        "Selesai · ${row.durationMs / 1000 / 60} menit audio",
                        jobId,
                    )
                    JobStatus.FAILED -> finalNotification(
                        row.sourceName,
                        row.errorMessage ?: "Gagal",
                        jobId,
                    )
                    JobStatus.CANCELLED -> finalNotification(row.sourceName, "Dibatalkan", jobId)
                    else -> null
                }

                if (notification != null) {
                    notifications()?.notify(NOTIF_ID, notification)
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                runningJobId = null
                releaseWakeLock()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (runningJobId != null) {
            // The process is being torn down mid-job; stop the native loop.
            container.requestCancel()
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ekstraktranskrip:transcribe")
                ?.apply { setReferenceCounted(false) }
        }
        // Timeout so a wedged job can never drain the battery forever.
        wakeLock?.acquire(6 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ------------------------------------------------------------------ notify

    private fun notifications(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun buildNotification(active: ActiveJob?): Notification {
        val percent = active?.percentOverall ?: 0
        val title = active?.fileName ?: "Menyiapkan transkripsi…"
        val text = when {
            active == null -> "Menyiapkan…"
            active.stage == JobStage.TRANSCRIBING ->
                "${active.stage.label} ${active.progress}%${formatEta(active.etaMs)}"
            else -> active.detail ?: active.stage.label
        }

        val builder = Notification.Builder(this, App.CHANNEL_TRANSCRIBE)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openJobIntent(active?.jobId))
            .setOngoing(active != null)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, percent, active == null)

        if (active != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Batal",
                    cancelIntent(),
                ).build(),
            )
        }

        return builder.build()
    }

    private fun finalNotification(fileName: String, text: String, jobId: String): Notification =
        Notification.Builder(this, App.CHANNEL_TRANSCRIBE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText(text)
            .setContentIntent(openJobIntent(jobId))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    private fun openJobIntent(jobId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (jobId != null) putExtra(MainActivity.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatEta(etaMs: Long?): String {
        if (etaMs == null || etaMs <= 0) return ""
        val totalSeconds = etaMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) " · sisa ~${minutes}m ${seconds}s" else " · sisa ~${seconds}s"
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_SOURCE_URI = "source_uri"
        const val ACTION_CANCEL = "com.ekstraktranskrip.action.CANCEL"

        fun start(context: Context, jobId: String, sourceUri: Uri) {
            val intent = Intent(context, TranscribeService::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_SOURCE_URI, sourceUri.toString())
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, TranscribeService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}
