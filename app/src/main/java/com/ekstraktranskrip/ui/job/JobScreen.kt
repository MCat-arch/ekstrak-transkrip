package com.ekstraktranskrip.ui.job

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.App
import com.ekstraktranskrip.data.JobRow
import com.ekstraktranskrip.data.JobStatus
import com.ekstraktranskrip.service.ActiveJob
import com.ekstraktranskrip.service.JobStage
import com.ekstraktranskrip.service.TranscribeService
import com.ekstraktranskrip.ui.common.formatDurationWords
import com.ekstraktranskrip.ui.common.formatEta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobScreen(
    jobId: String,
    onBack: () -> Unit,
    onFinished: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = remember { App.container(context) }

    var job by remember { mutableStateOf<JobRow?>(null) }
    val activeByFlow by container.activeJob.collectAsState()
    val active: ActiveJob? = activeByFlow?.takeIf { it.jobId == jobId }

    LaunchedEffect(jobId) {
        while (true) {
            val row = withContext(Dispatchers.IO) { container.jobs.job(jobId) }
            job = row
            if (row?.status == JobStatus.DONE) {
                onFinished(jobId)
                return@LaunchedEffect
            }
            if (row == null) return@LaunchedEffect
            delay(500)
        }
    }

    val percent = when {
        active != null -> active.percentOverall
        job?.status == JobStatus.DONE -> 100
        else -> job?.progress ?: 0
    }

    val stageIndex = when (active?.stage) {
        JobStage.IMPORTING, null -> if (job?.status == JobStatus.QUEUED) 0 else 0
        JobStage.CONVERTING -> 1
        JobStage.LOADING_MODEL -> 2
        JobStage.TRANSCRIBING -> 3
        JobStage.FINISHING -> 4
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transkripsi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                job?.sourceName ?: "Memuat…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            when (job?.status) {
                JobStatus.FAILED -> FailureCard(
                    message = job?.errorMessage ?: "Terjadi kesalahan.",
                    code = job?.errorCode,
                    onBack = onBack,
                )

                JobStatus.CANCELLED -> FailureCard(
                    message = "Transkripsi dibatalkan.",
                    code = null,
                    onBack = onBack,
                )

                else -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "${active?.stage?.label ?: stageLabel(job?.status)} · $percent%",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            active?.etaMs?.let { eta ->
                                Text(
                                    "Perkiraan selesai ${formatEta(eta) ?: "-"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            job?.takeIf { it.durationMs > 0 }?.let { current ->
                                Text(
                                    "Durasi audio ${formatDurationWords(current.durationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    StageList(stageIndex)

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        ),
                    ) {
                        Text(
                            "Boleh tinggalkan aplikasi. Transkripsi jalan di latar belakang dan tetap selesai " +
                                "(baterai bisa sedikit lebih boros).",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    OutlinedButton(
                        onClick = { TranscribeService.cancel(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Batalkan") }
                }
            }
        }
    }
}

@Composable
private fun FailureCard(message: String, code: String?, onBack: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Transkripsi gagal",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (code != null) {
                Text(
                    "Kode: $code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                )
            }
            Button(onClick = onBack) { Text("Kembali") }
        }
    }
}

@Composable
private fun StageList(current: Int) {
    val stages = listOf(
        "Menyalin file",
        "Mengonversi ke 16 kHz mono",
        "Memuat model",
        "Mentranskripsi",
        "Menyimpan hasil",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stages.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            when {
                                index < current -> MaterialTheme.colorScheme.primary
                                index == current -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (index < current) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index <= current) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun stageLabel(status: JobStatus?): String = when (status) {
    JobStatus.QUEUED -> "Menunggu"
    JobStatus.IMPORTING -> "Menyalin file"
    JobStatus.CONVERTING -> "Mengonversi"
    JobStatus.LOADING_MODEL -> "Memuat model"
    JobStatus.TRANSCRIBING -> "Mentranskripsi"
    JobStatus.DONE -> "Selesai"
    JobStatus.FAILED -> "Gagal"
    JobStatus.CANCELLED -> "Dibatalkan"
    null -> "Menyiapkan"
}
