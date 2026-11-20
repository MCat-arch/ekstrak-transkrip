package com.ekstraktranskrip.ui.summary

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.App
import com.ekstraktranskrip.core.ai.AiPrompts
import com.ekstraktranskrip.core.ai.AiSettings
import com.ekstraktranskrip.core.ai.ApiKeyStore
import com.ekstraktranskrip.core.ai.GeminiClient
import com.ekstraktranskrip.core.ai.GeminiException
import com.ekstraktranskrip.data.AiSummary
import com.ekstraktranskrip.data.JobRow
import com.ekstraktranskrip.data.SegmentRow
import com.ekstraktranskrip.data.SummaryRow
import com.ekstraktranskrip.data.export.TranscriptExporter
import com.ekstraktranskrip.ui.common.formatDurationWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    jobId: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { App.container(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var job by remember { mutableStateOf<JobRow?>(null) }
    var segments by remember { mutableStateOf<List<SegmentRow>>(emptyList()) }
    var summary by remember { mutableStateOf<SummaryRow?>(null) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(true) }

    LaunchedEffect(jobId) {
        val loaded = withContext(Dispatchers.IO) {
            container.jobs.job(jobId) to container.jobs.segments(jobId)
        }
        job = loaded.first
        segments = loaded.second
        summary = withContext(Dispatchers.IO) { container.summaries.get(jobId) }
        hasKey = withContext(Dispatchers.IO) { ApiKeyStore.hasKey(context) }
    }

    suspend fun generate() {
        val apiKey = withContext(Dispatchers.IO) { ApiKeyStore.load(context) }
        if (apiKey.isNullOrBlank()) {
            hasKey = false
            error = "API key Gemini belum diisi."
            return
        }
        val currentJob = job ?: return
        val currentSegments = if (segments.isEmpty()) {
            withContext(Dispatchers.IO) { container.jobs.segments(jobId) }.also { segments = it }
        } else {
            segments
        }
        if (currentSegments.isEmpty()) {
            error = "Transkrip kosong, tidak ada yang bisa diringkas."
            return
        }

        generating = true
        error = null
        try {
            val model = AiSettings.model(context)
            val transcript = AiPrompts.transcriptText(currentSegments)
            val client = GeminiClient(apiKey, model)

            val raw = client.generate(
                history = listOf(
                    GeminiClient.Message(
                        GeminiClient.Message.USER,
                        AiPrompts.summaryPrompt(currentJob.sourceName, currentJob.durationMs, transcript),
                    ),
                ),
                systemInstruction = AiPrompts.SYSTEM_SUMMARY,
                temperature = 0.2,
                jsonSchema = AiPrompts.summarySchema(),
            )

            val parsed = AiSummary.parse(raw, jobId, model)
            withContext(Dispatchers.IO) { container.summaries.save(parsed) }
            summary = parsed
        } catch (e: GeminiException) {
            error = buildString {
                append(e.message)
                if (e.isAuthProblem) append("\n\nPeriksa API key di Pengaturan.")
                if (e.isQuotaProblem) append("\n\nKuota Gemini habis atau terlalu sering. Coba lagi nanti.")
            }
        } catch (t: Throwable) {
            error = t.message ?: "Gagal membuat ringkasan."
        } finally {
            generating = false
        }
    }

    val exportMd = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri: Uri? ->
        val current = summary ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val text = TranscriptExporter.summaryToMarkdown(job?.sourceName ?: "Transkrip", current)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Tidak bisa menulis file")
                }
            }
            snackbar.showSnackbar(
                result.fold({ "Ringkasan tersimpan" }, { "Gagal menyimpan: ${it.message}" }),
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Ringkasan",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (summary != null) {
                        IconButton(
                            onClick = {
                                exportMd.launch(
                                    TranscriptExporter.fileStem(job?.sourceName ?: "ringkasan", "ringkasan") + ".md",
                                )
                            },
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = "Ekspor Markdown")
                        }
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
                Column {
                    Text(
                        job?.sourceName ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            job?.durationMs?.takeIf { it > 0 }?.let { append(formatDurationWords(it)) }
                            if (segments.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append("${segments.size} potongan transkrip")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!hasKey) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "API key Gemini belum diisi",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "Transkripsi tetap gratis dan offline. Ringkasan butuh API key Gemini (gratis di AI Studio).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Button(onClick = onOpenSettings) { Text("Isi API key") }
                        }
                    }
                }
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (generating) {
                item {
                    Card {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Menyusun ringkasan…", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Gemini membaca ${segments.size} potongan transkrip. Bisa 10-40 detik.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            val current = summary
            if (current == null && !generating) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Belum ada ringkasan",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                "Ringkasan berisi inti pembahasan, keputusan, action items, dan momen penting bertimestamp.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { scope.launch { generate() } },
                                enabled = hasKey && segments.isNotEmpty(),
                            ) { Text("Buat ringkasan dengan Gemini") }
                        }
                    }
                }
            }

            if (current != null) {
                item {
                    SummarySection("Ringkasan", current.ringkasan)
                }
                if (current.keputusan.isNotBlank()) {
                    item { SummarySection("Keputusan", current.keputusan) }
                }
                if (current.actionItems.isNotBlank()) {
                    item { SummarySection("Action items", current.actionItems) }
                }
                if (current.momen.isNotBlank()) {
                    item { SummarySection("Momen penting", current.momen) }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { scope.launch { generate() } },
                            enabled = !generating,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Buat ulang")
                        }
                        OutlinedButton(
                            onClick = onOpenChat,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Tanya")
                        }
                    }
                }

                item {
                    Text(
                        "Diringkas oleh ${current.model ?: "-"}. Selalu cek ulang sebelum dipakai resmi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummarySection(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
