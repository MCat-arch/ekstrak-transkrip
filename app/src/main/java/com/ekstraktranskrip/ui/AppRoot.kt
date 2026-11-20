package com.ekstraktranskrip.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ekstraktranskrip.core.model.ModelStore
import com.ekstraktranskrip.ui.chat.ChatScreen
import com.ekstraktranskrip.ui.home.DiagnosticsScreen
import com.ekstraktranskrip.ui.home.HomeScreen
import com.ekstraktranskrip.ui.job.JobScreen
import com.ekstraktranskrip.ui.onboarding.OnboardingScreen
import com.ekstraktranskrip.ui.settings.LicensesScreen
import com.ekstraktranskrip.ui.settings.SettingsScreen
import com.ekstraktranskrip.ui.summary.SummaryScreen
import com.ekstraktranskrip.ui.transcript.TranscriptScreen

sealed interface Route {
    data object Home : Route
    data class Job(val jobId: String) : Route
    data class Transcript(val jobId: String) : Route
    data class Summary(val jobId: String) : Route
    data class Chat(val jobId: String) : Route
    data object Settings : Route
    data object Diagnostics : Route
    data object Licenses : Route
}

/**
 * Hand-rolled navigation.
 *
 * Five screens with a linear back stack do not justify a navigation library
 * (and its version alignment with Compose). This is ~20 lines and does exactly
 * what the app needs.
 */
@Composable
fun AppRoot(
    jobRequest: String? = null,
    onJobRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var onboardingDone by remember { mutableStateOf(ModelStore.isOnboardingDone(context)) }
    var stack by remember { mutableStateOf<List<Route>>(listOf(Route.Home)) }

    val push: (Route) -> Unit = { route -> stack = stack + route }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    val home: () -> Unit = { stack = listOf(Route.Home) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // First launch: provision the bundled model before showing the app.
    if (!onboardingDone) {
        OnboardingScreen(
            onDone = {
                ModelStore.setOnboardingDone(context)
                onboardingDone = true
            },
        )
        return
    }

    // Notification taps deliver a job id; open that job (or its result).
    LaunchedEffect(jobRequest) {
        if (jobRequest != null) {
            stack = stack + Route.Job(jobRequest)
            onJobRequestConsumed()
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (val route = stack.last()) {
        Route.Home -> HomeScreen(
            onOpenJob = { jobId -> push(Route.Job(jobId)) },
            onOpenTranscript = { jobId -> push(Route.Transcript(jobId)) },
            onOpenSettings = { push(Route.Settings) },
        )

        is Route.Job -> JobScreen(
            jobId = route.jobId,
            onBack = pop,
            onFinished = { jobId ->
                stack = stack.dropLast(1) + Route.Transcript(jobId)
            },
        )

        is Route.Transcript -> TranscriptScreen(
            jobId = route.jobId,
            onBack = pop,
            onOpenSummary = { push(Route.Summary(route.jobId)) },
            onOpenChat = { push(Route.Chat(route.jobId)) },
        )

        is Route.Summary -> SummaryScreen(
            jobId = route.jobId,
            onBack = pop,
            onOpenChat = { push(Route.Chat(route.jobId)) },
            onOpenSettings = { push(Route.Settings) },
        )

        is Route.Chat -> ChatScreen(
            jobId = route.jobId,
            onBack = pop,
        )

        Route.Settings -> SettingsScreen(
            onBack = pop,
            onOpenDiagnostics = { push(Route.Diagnostics) },
            onOpenLicenses = { push(Route.Licenses) },
        )

        Route.Diagnostics -> DiagnosticsScreen(onBack = { home() })

        Route.Licenses -> LicensesScreen(onBack = pop)
    }
}
