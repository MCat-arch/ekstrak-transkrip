package com.ekstraktranskrip.ui.settings

import android.content.Context
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.R
import com.ekstraktranskrip.core.model.ModelCatalog
import com.ekstraktranskrip.ui.common.formatFileSize

/**
 * Attribution required because the APK redistributes whisper.cpp and Whisper
 * weights. The bundled model and every download are listed with their pinned
 * SHA-256 so a user can audit exactly which bytes the app accepted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val whisperCpp = remember { readRaw(context, R.raw.license_whisper_cpp) }
    val whisperOpenAi = remember { readRaw(context, R.raw.license_whisper_openai) }
    val apache = remember { readRaw(context, R.raw.license_apache2) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lisensi & sumber model") },
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
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTitle("Komponen")
                    Attribution(
                        "whisper.cpp",
                        "MIT · Copyright (c) 2023-2024 The ggml authors",
                    )
                    Attribution(
                        "Whisper (OpenAI)",
                        "MIT · Copyright (c) 2022 OpenAI",
                    )
                    Attribution(
                        "Tiny ID v1",
                        "Model kustom, turunan bobot Whisper (MIT), dimodifikasi.",
                    )
                    Attribution(
                        "AndroidX & Jetpack Compose",
                        "Apache License 2.0 · Copyright The Android Open Source Project",
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle("Model yang dikenal aplikasi")
                    ModelCatalog.all.forEach { spec ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(spec.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatFileSize(spec.sizeBytes)} · ${spec.sourceLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "SHA-256 ${spec.sha256}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            LicenseBlock("Lisensi whisper.cpp", whisperCpp)
            LicenseBlock("Lisensi Whisper (OpenAI)", whisperOpenAi)
            LicenseBlock("Lisensi Apache 2.0", apache)
        }
    }
}

@Composable
private fun Attribution(name: String, detail: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseBlock(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(title)
            HorizontalDivider()
            Text(
                text.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

private fun readRaw(context: Context, id: Int): String = runCatching {
    context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}.getOrDefault("Lisensi tidak tersedia.")
