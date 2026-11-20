package com.ekstraktranskrip.ui.home

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.core.DeviceProfile
import com.ekstraktranskrip.core.whisper.NativeBridge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        version = runCatching { NativeBridge.nativeVersion() }.getOrNull()
        loadError = NativeBridge.loadError
    }

    val am = remember { context.getSystemService(ActivityManager::class.java) }
    val totalRamMb = remember {
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        (info.totalMem / (1024 * 1024)).toInt()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostik") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Perangkat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    KeyValue("whisper.cpp", version?.let { "v$it" } ?: loadError ?: "memuat…")
                    KeyValue("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
                    KeyValue("Android", "API ${Build.VERSION.SDK_INT}")
                    KeyValue("RAM total", "$totalRamMb MB")
                    KeyValue("Low RAM", if (DeviceProfile.isLowRam(context)) "ya" else "tidak")
                    KeyValue("Thread transkripsi", DeviceProfile.threads(context).toString())
                    KeyValue("Chunk", "${DeviceProfile.chunkSeconds(context)} detik")
                }
            }

            AudioTestCard()

            Text(
                "Uji konversi memakai file audio pilihan. Hasilnya WAV 16 kHz mono di penyimpanan app sementara.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
