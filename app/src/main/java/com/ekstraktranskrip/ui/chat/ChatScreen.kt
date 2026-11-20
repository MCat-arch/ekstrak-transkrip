package com.ekstraktranskrip.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.App
import com.ekstraktranskrip.core.ai.AiPrompts
import com.ekstraktranskrip.core.ai.AiSettings
import com.ekstraktranskrip.core.ai.ApiKeyStore
import com.ekstraktranskrip.core.ai.GeminiClient
import com.ekstraktranskrip.core.ai.GeminiException
import com.ekstraktranskrip.data.ChatMessageRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    jobId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { App.container(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf<List<ChatMessageRow>>(emptyList()) }
    var transcript by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }

    LaunchedEffect(jobId) {
        val loaded = withContext(Dispatchers.IO) {
            val job = container.jobs.job(jobId)
            val segments = container.jobs.segments(jobId)
            Triple(
                job?.sourceName ?: "Transkrip",
                AiPrompts.transcriptText(segments),
                container.chats.messages(jobId),
            )
        }
        fileName = loaded.first
        transcript = loaded.second
        messages = loaded.third
    }

    LaunchedEffect(messages.size, streamingText) {
        val last = messages.size + if (streamingText.isNotEmpty()) 1 else 0
        if (last > 0) {
            runCatching { listState.animateScrollToItem(last - 1) }
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || streaming) return

        input = ""
        error = null

        scope.launch {
            if (transcript.isBlank()) {
                val loaded = withContext(Dispatchers.IO) {
                    AiPrompts.transcriptText(container.jobs.segments(jobId))
                }
                if (loaded.isBlank()) {
                    error = "Transkrip belum siap."
                    return@launch
                }
                transcript = loaded
            }

            val apiKey = withContext(Dispatchers.IO) { ApiKeyStore.load(context) }
            if (apiKey.isNullOrBlank()) {
                error = "API key Gemini belum diisi. Buka Pengaturan dulu."
                return@launch
            }

            val userRow = ChatMessageRow(
                id = 0,
                jobId = jobId,
                role = "user",
                content = text,
                createdAt = System.currentTimeMillis(),
            )
            val history = (messages + userRow).takeLast(20).map {
                GeminiClient.Message(
                    role = if (it.role == "user") GeminiClient.Message.USER else GeminiClient.Message.MODEL,
                    text = it.content,
                )
            }
            messages = messages + userRow
            withContext(Dispatchers.IO) { container.chats.add(jobId, "user", text) }

            streaming = true
            streamingText = ""
            try {
                val client = GeminiClient(apiKey, AiSettings.model(context))
                val systemInstruction = AiPrompts.SYSTEM_CHAT +
                    "\n\nTranskrip:\n---\n" + transcript + "\n---"

                val builder = StringBuilder()
                client.stream(history, systemInstruction).collect { delta ->
                    builder.append(delta)
                    streamingText = builder.toString()
                }

                val answer = builder.toString().trim()
                if (answer.isNotEmpty()) {
                    withContext(Dispatchers.IO) { container.chats.add(jobId, "model", answer) }
                    messages = messages + ChatMessageRow(
                        id = 0,
                        jobId = jobId,
                        role = "model",
                        content = answer,
                        createdAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: GeminiException) {
                error = buildString {
                    append(e.message)
                    if (e.isAuthProblem) append("\n\nPeriksa API key di Pengaturan.")
                    if (e.isQuotaProblem) append("\n\nKuota Gemini habis. Coba lagi nanti.")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                error = t.message ?: "Gagal menghubungi Gemini."
            } finally {
                streaming = false
                streamingText = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tanya transkrip", maxLines = 1)
                        Text(
                            fileName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
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
                .imePadding(),
        ) {
            error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { error = null }) {
                            Text("×", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty() && !streaming) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Tanyakan apa saja tentang isi rekaman. Jawaban menyebut timestamp supaya mudah dicek.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val suggestions = listOf(
                                "Apa keputusan utama rapat ini?",
                                "Siapa saja yang mendapat tugas?",
                                "Ringkas 3 poin terpenting.",
                                "Bagian mana yang membahas anggaran?",
                            )
                            suggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = { send(suggestion) },
                                    label = { Text(suggestion) },
                                )
                            }
                        }
                    }
                }

                items(messages.size) { index ->
                    val message = messages[index]
                    MessageBubble(text = message.content, isUser = message.role == "user")
                }

                if (streaming) {
                    item {
                        if (streamingText.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Gemini sedang membaca transkrip…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            MessageBubble(text = streamingText, isUser = false)
                        }
                    }
                }
            }

            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Tulis pertanyaan…") },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { send(input) },
                        enabled = !streaming && input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Kirim")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(text: String, isUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
