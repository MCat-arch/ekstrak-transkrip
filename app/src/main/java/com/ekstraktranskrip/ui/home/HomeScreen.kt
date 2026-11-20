package com.ekstraktranskrip.ui.home

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.App
import com.ekstraktranskrip.core.model.ModelStore
import com.ekstraktranskrip.data.JobRow
import com.ekstraktranskrip.data.JobStarter
import com.ekstraktranskrip.data.JobStatus
import com.ekstraktranskrip.service.ActiveJob
import com.ekstraktranskrip.service.TranscribeService
import com.ekstraktranskrip.ui.common.formatDate
import com.ekstraktranskrip.ui.common.formatDurationWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenJob: (String) -> Unit,
    onOpenTranscript: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { App.container(context) }
    val scope = rememberCoroutineScope()

    var jobs by remember { mutableStateOf<List<JobRow>>(emptyList()) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<JobRow?>(null) }
    var hasModel by remember { mutableStateOf(true) }
    var restoring by remember { mutableStateOf(false) }

    val active: ActiveJob? by container.activeJob.collectAsState()
    val running = active

    fun restoreModel() {
        scope.launch {
            restoring = true
            error = null
            runCatching { withContext(Dispatchers.IO) { ModelStore.ensureBundled(context) } }
                .onSuccess { hasModel = true }
                .onFailure { error = it.message ?: "Gagal menyiapkan model bawaan." }
            restoring = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            jobs = withContext(Dispatchers.IO) { container.jobs.jobs(150) }
            hasModel = withContext(Dispatchers.IO) {
                ModelStore.activeOrBundled(context) != null
            }
            delay(1200)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            starting = true
            error = null
            runCatching { JobStarter.start(context, container, uri) }
                .onSuccess { jobId -> onOpenJob(jobId) }
                .onFailure { error = it.message ?: "Gagal memulai transkripsi." }
            starting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ekstrak Transkrip") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Pengaturan")
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NewTranscriptionCard(
                    enabled = !starting && running == null && hasModel,
                    starting = starting,
                    hasModel = hasModel,
                    onPick = {
                        if (running == null && hasModel) picker.launch(arrayOf("audio/*", "video/*"))
                    },
                )
            }

            if (!hasModel) {
                item { MissingModelCard(restoring = restoring, onRestore = { restoreModel() }) }
            }

            error?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (running != null) {
                item {
                    ActiveJobCard(
                        active = running,
                        onOpen = { onOpenJob(running.jobId) },
                        onCancel = { TranscribeService.cancel(context) },
                    )
                }
            }

            item {
                Text(
                    if (jobs.isEmpty()) "Belum ada transkrip" else "Transkrip tersimpan",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (jobs.isEmpty()) {
                item { EmptyState() }
            }

            items(jobs, key = { it.id }) { job ->
                JobListItem(
                    job = job,
                    isRunning = running?.jobId == job.id,
                    onClick = {
                        if (job.status == JobStatus.DONE) onOpenTranscript(job.id) else onOpenJob(job.id)
                    },
                    onDelete = if (running?.jobId == job.id) null else ({ pendingDelete = job }),
                )
            }
        }
    }

    pendingDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus transkrip?") },
            text = { Text("\"${job.sourceName}\" beserta ringkasan dan riwayat chat akan dihapus.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch(Dispatchers.IO) {
                            container.jobs.delete(job.id)
                            jobs = container.jobs.jobs(150)
                        }
                    },
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun NewTranscriptionCard(
    enabled: Boolean,
    starting: Boolean,
    hasModel: Boolean,
    onPick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Transkripsi baru",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                "Pilih rekaman rapat, wawancara, atau video. Audio diproses lokal di HP ini — tidak diunggah ke mana pun.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaPill(Icons.Outlined.Mic, "Audio")
                MediaPill(Icons.Outlined.Videocam, "Video")
            }
            if (!enabled && !starting) {
                Text(
                    if (!hasModel) {
                        "Siapkan model transkripsi dulu — tekan Pulihkan model di bawah."
                    } else {
                        "Selesaikan atau batalkan transkripsi yang sedang berjalan dulu."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (starting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Menyiapkan…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaPill(icon: ImageVector, label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MissingModelCard(restoring: Boolean, onRestore: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Model transkripsi belum siap",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Model bawaan tidak ditemukan di penyimpanan. Pulihkan sekali, tanpa internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRestore, enabled = !restoring) {
                Text(if (restoring) "Menyiapkan…" else "Pulihkan model")
            }
        }
    }
}

@Composable
private fun ActiveJobCard(active: ActiveJob, onOpen: () -> Unit, onCancel: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    active.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Batal") }
            }
            Text(
                "${active.stage.label} · ${active.percentOverall}%",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { active.percentOverall / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun JobListItem(
    job: JobRow,
    isRunning: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(job.status)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    job.sourceName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusLabel(job, isRunning)
                }
                Text(
                    listOfNotNull(
                        if (job.durationMs > 0) formatDurationWords(job.durationMs) else null,
                        formatDate(job.createdAt),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (job.status == JobStatus.FAILED && job.errorMessage != null) {
                    Text(
                        job.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isRunning) {
                    LinearProgressIndicator(
                        progress = { job.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Hapus",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: JobStatus) {
    val (icon, tint) = when (status) {
        JobStatus.DONE -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        JobStatus.FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        else -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
private fun StatusLabel(job: JobRow, isRunning: Boolean) {
    val text = when {
        isRunning -> "Berjalan · ${job.progress}%"
        job.status == JobStatus.DONE -> "Selesai"
        job.status == JobStatus.FAILED -> "Gagal"
        job.status == JobStatus.CANCELLED -> "Dibatalkan"
        else -> "Menunggu"
    }
    val color = when {
        isRunning -> MaterialTheme.colorScheme.primary
        job.status == JobStatus.DONE -> MaterialTheme.colorScheme.primary
        job.status == JobStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Transkrip akan muncul di sini", style = MaterialTheme.typography.titleSmall)
            Text(
                "Setiap hasil punya timestamp, bisa dicari, diekspor ke TXT/SRT, lalu diringkas dengan Gemini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
