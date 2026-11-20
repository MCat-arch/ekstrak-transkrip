package com.ekstraktranskrip.ui.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.core.audio.AudioDecodeException
import com.ekstraktranskrip.core.audio.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface AudioTestState {
    data object Idle : AudioTestState
    data class Running(val fileName: String, val progress: Float) : AudioTestState
    data class Done(val fileName: String, val result: AudioDecoder.Result, val bytes: Long) : AudioTestState
    data class Failed(val fileName: String?, val message: String, val code: String?) : AudioTestState
}

@Composable
fun AudioTestCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<AudioTestState>(AudioTestState.Idle) }
    var cancelFlag by remember { mutableStateOf(AtomicBoolean(false)) }

    fun start(uri: Uri) {
        val name = queryDisplayName(context, uri) ?: "audio"
        val flag = AtomicBoolean(false)
        cancelFlag = flag

        scope.launch {
            state = AudioTestState.Running(name, 0f)
            val outFile = File(context.filesDir, "audio/test_${System.currentTimeMillis()}.wav")

            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    AudioDecoder(context).decode(
                        uri = uri,
                        outFile = outFile,
                        isCancelled = { flag.get() },
                        onProgress = { p ->
                            if (state is AudioTestState.Running) {
                                state = AudioTestState.Running(name, p)
                            }
                        },
                    )
                }
            }

            outcome.fold(
                onSuccess = { result ->
                    state = AudioTestState.Done(name, result, outFile.length())
                },
                onFailure = { t ->
                    val decodeError = t as? AudioDecodeException
                    state = AudioTestState.Failed(
                        fileName = name,
                        message = decodeError?.message ?: (t.message ?: t.javaClass.simpleName),
                        code = decodeError?.code,
                    )
                },
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Uji konversi audio → WAV 16 kHz", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Pilih file apa pun (mp3, m4a, ogg, opus, flac, wav). Hasilnya harus: " +
                    "16.000 Hz, mono, 16-bit, durasi sama dengan sumber.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            when (val s = state) {
                AudioTestState.Idle -> Text(
                    "Belum ada file diuji.",
                    style = MaterialTheme.typography.bodySmall,
                )

                is AudioTestState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Mengonversi ${s.fileName}… ${(s.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is AudioTestState.Done -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val r = s.result
                    Text(
                        "✓ ${s.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TestRow("Sumber", "${r.sourceSampleRate} Hz · ${r.sourceChannels} ch")
                    TestRow("Hasil", "16000 Hz · mono · %d ms".format(r.durationMs))
                    TestRow("Durasi container", "%d ms".format(r.containerDurationMs))
                    TestRow("Selisih durasi", durationDelta(r.durationMs, r.containerDurationMs))
                    TestRow("RMS", "%.4f".format(Locale.US, r.rms))
                    TestRow("Ukuran WAV", "%.1f MB".format(Locale.US, s.bytes / 1048576.0))
                    TestRow("Waktu konversi", "${r.elapsedMs} ms")
                    TestRow("RTF konversi", "%.2f".format(Locale.US, r.elapsedMs.toDouble() / r.durationMs.coerceAtLeast(1)))
                    TestRow(
                        "Path",
                        r.wavFile.absolutePath.removePrefix(context.filesDir.absolutePath),
                        mono = true,
                    )
                }

                is AudioTestState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "✗ ${s.fileName ?: "file"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    s.code?.let {
                        Text(
                            "kode: $it",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { picker.launch(arrayOf("audio/*")) },
                    enabled = state !is AudioTestState.Running,
                ) { Text("Pilih audio") }

                if (state is AudioTestState.Running) {
                    OutlinedButton(onClick = { cancelFlag.set(true) }) { Text("Batal") }
                }
            }
        }
    }
}

@Composable
private fun TestRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

private fun durationDelta(actual: Long, expected: Long): String {
    if (expected <= 0) return "tidak diketahui"
    val delta = actual - expected
    val pct = delta * 100.0 / expected
    val mark = if (kotlin.math.abs(pct) <= 2.0) "✓" else "⚠︎"
    return "%+d ms (%+.1f%%) %s".format(delta, pct, mark)
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()
