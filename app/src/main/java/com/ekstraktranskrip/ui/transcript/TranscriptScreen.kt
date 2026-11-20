package com.ekstraktranskrip.ui.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.App
import com.ekstraktranskrip.data.JobRow
import com.ekstraktranskrip.data.SegmentRow
import com.ekstraktranskrip.data.export.TranscriptExporter
import com.ekstraktranskrip.ui.common.formatDate
import com.ekstraktranskrip.ui.common.formatDurationWords
import com.ekstraktranskrip.ui.common.formatTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(
    jobId: String,
    onBack: () -> Unit,
    onOpenSummary: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { App.container(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var job by remember { mutableStateOf<JobRow?>(null) }
    var segments by remember { mutableStateOf<List<SegmentRow>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(jobId) {
        val loaded = withContext(Dispatchers.IO) {
            container.jobs.job(jobId) to container.jobs.segments(jobId)
        }
        job = loaded.first
        segments = loaded.second
    }

    val filtered = remember(segments, query) {
        if (query.isBlank()) {
            segments
        } else {
            val needle = query.trim().lowercase(Locale("id", "ID"))
            segments.filter { it.text.lowercase(Locale("id", "ID")).contains(needle) }
        }
    }

    val plainText = remember(segments, job) {
        TranscriptExporter.toPlainText(job?.sourceName ?: "Transkrip", segments)
    }

    fun copyAll() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Transkrip", plainText))
        scope.launch { snackbar.showSnackbar("Transkrip disalin") }
    }

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, job?.sourceName ?: "Transkrip")
            putExtra(Intent.EXTRA_TEXT, plainText)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan transkrip"))
    }

    val exportTxt = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? -> uri?.let { writeExport(context, it, plainText, snackbar, scope) } }

    val exportSrt = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-subrip"),
    ) { uri: Uri? -> uri?.let { writeExport(context, it, TranscriptExporter.toSrt(segments), snackbar, scope) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        job?.sourceName ?: "Transkrip",
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
                    IconButton(onClick = { share() }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Bagikan")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Salin semua") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                copyAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Ekspor TXT") },
                            onClick = {
                                menuOpen = false
                                exportTxt.launch(
                                    TranscriptExporter.fileStem(job?.sourceName ?: "transkrip") + ".txt",
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Ekspor SRT") },
                            onClick = {
                                menuOpen = false
                                exportSrt.launch(
                                    TranscriptExporter.fileStem(job?.sourceName ?: "transkrip") + ".srt",
                                )
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenSummary,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Ringkas")
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
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    buildString {
                        append("${segments.size} potongan")
                        job?.durationMs?.takeIf { it > 0 }?.let { append(" · ${formatDurationWords(it)}") }
                        job?.modelName?.let { append(" · $it") }
                        job?.rtf?.let { append(" · RTF %.2f".format(Locale.US, it)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                job?.createdAt?.let {
                    Text(
                        formatDate(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    placeholder = { Text("Cari kata di transkrip…") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                if (query.isNotBlank()) {
                    Text(
                        "${filtered.size} hasil",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            if (segments.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Transkrip masih kosong.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                ) {
                    itemsIndexed(filtered, key = { _, item -> item.idx }) { _, segment ->
                        SegmentRowView(
                            segment = segment,
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                val line = "[${formatTimestamp(segment.t0Ms)}] ${segment.text}"
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Potongan", line))
                                scope.launch { snackbar.showSnackbar("Potongan disalin") }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentRowView(segment: SegmentRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            formatTimestamp(segment.t0Ms),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(56.dp).padding(top = 2.dp),
        )
        Text(
            segment.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun writeExport(
    context: Context,
    uri: Uri,
    text: String,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("Tidak bisa menulis file")
            }
        }
        snackbar.showSnackbar(
            result.fold(
                onSuccess = { "File tersimpan" },
                onFailure = { "Gagal menyimpan: ${it.message}" },
            ),
        )
    }
}
