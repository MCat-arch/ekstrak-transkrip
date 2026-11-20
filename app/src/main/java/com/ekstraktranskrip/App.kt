package com.ekstraktranskrip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.ekstraktranskrip.core.whisper.TranscriptionJob

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        createNotificationChannel()
        runCatching { container.jobs.failInterruptedJobs() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_TRANSCRIBE,
            "Transkripsi",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progres transkripsi audio"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_TRANSCRIBE = "transcribe"

        lateinit var instance: App
            private set

        fun container(context: Context): AppContainer =
            (context.applicationContext as App).container
    }
}

/**
 * Process-wide singletons. Deliberately not Hilt: four objects, no generated
 * code, nothing to keep in sync with the compiler.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val db = com.ekstraktranskrip.data.AppDatabase(appContext)
    val jobs = com.ekstraktranskrip.data.JobRepository(db)
    val summaries = com.ekstraktranskrip.data.SummaryRepository(db)
    val chats = com.ekstraktranskrip.data.ChatRepository(db)

    val engine = com.ekstraktranskrip.core.whisper.WhisperEngine()

    /** Optional model downloads (base, tiny fp16, custom Indonesian tiny). */
    val downloads = com.ekstraktranskrip.core.model.ModelDownloads(appContext)

    /**
     * Stage + ETA of the running job. The database stays the source of truth
     * for status; this flow just carries the fast-moving detail the UI needs.
     */
    val activeJob = kotlinx.coroutines.flow.MutableStateFlow<com.ekstraktranskrip.service.ActiveJob?>(null)

    /** Set by the service, cancelled from the UI or the notification action. */
    @Volatile
    var cancelRequested: Boolean = false

    @Volatile
    var currentNativeJob: TranscriptionJob? = null

    fun requestCancel() {
        cancelRequested = true
        currentNativeJob?.cancel()
    }
}
