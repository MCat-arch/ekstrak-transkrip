package com.ekstraktranskrip.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ekstraktranskrip.App
import com.ekstraktranskrip.core.ai.AiSettings
import com.ekstraktranskrip.core.ai.ApiKeyStore
import com.ekstraktranskrip.core.ai.GeminiClient
import com.ekstraktranskrip.core.ai.GeminiException
import com.ekstraktranskrip.core.model.ModelCatalog
import com.ekstraktranskrip.core.model.ModelDownloads
import com.ekstraktranskrip.core.model.ModelSpec
import com.ekstraktranskrip.core.model.ModelStore
import com.ekstraktranskrip.ui.common.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { App.container(context) }
    val scope = rememberCoroutineScope()
    val downloads by container.downloads.state.collectAsState()
    val finishedDownloads = downloads.values
        .filter { it.status == ModelDownloads.Status.DONE }
        .map { it.modelId }
        .toSet()

    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var activeModel by remember { mutableStateOf<File?>(null) }
    var storageBytes by remember { mutableStateOf(0L) }
    var showModelList by remember { mutableStateOf(false) }
    var modelBusy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var pendingDownload by remember { mutableStateOf<ModelSpec?>(null) }

    var keyMasked by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf(AiSettings.model(context)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    fun refreshModels() {
        scope.launch {
            models = withContext(Dispatchers.IO) { ModelStore.list(context) }
            activeModel = withContext(Dispatchers.IO) { ModelStore.activeOrBundled(context) }
            storageBytes = withContext(Dispatchers.IO) { ModelStore.totalBytes(context) }
        }
    }

    fun restoreBundled() {
        scope.launch {
            modelBusy = true
            modelMessage = null
            modelError = null
            runCatching { withContext(Dispatchers.IO) { ModelStore.ensureBundled(context) } }
                .onSuccess { file ->
                    ModelStore.setActive(context, file)
                    modelMessage = "Model bawaan siap dipakai."
                    refreshModels()
                }
                .onFailure { modelError = it.message ?: "Gagal memulihkan model bawaan." }
            modelBusy = false
        }
    }

    fun deleteModel(file: File) {
        scope.launch {
            val spec = ModelStore.specFor(file)
            val deleted = withContext(Dispatchers.IO) { ModelStore.delete(context, file) }
            if (spec != null) container.downloads.forget(spec.id)
            if (deleted) {
                modelMessage = "Model dihapus."
                refreshModels()
            } else {
                modelError = "File model tidak bisa dihapus."
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshModels()
        keyMasked = withContext(Dispatchers.IO) { ApiKeyStore.masked(context) }
    }

    // A download finishing means a new file appeared on disk.
    LaunchedEffect(finishedDownloads) {
        if (finishedDownloads.isNotEmpty()) refreshModels()
    }

    val importModel = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importError = null
            val name = runCatching {
                context.contentResolver
                    .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()

            if (name != null && !name.endsWith(".bin", ignoreCase = true)) {
                importError = "File harus berekstensi .bin (model ggml multilingual)."
                importing = false
                return@launch
            }

            runCatching { withContext(Dispatchers.IO) { ModelStore.importFrom(context, uri, name) } }
                .onSuccess { file ->
                    if (ModelStore.isEnglishOnly(file)) {
                        withContext(Dispatchers.IO) { ModelStore.delete(context, file) }
                        importError = "Model .en hanya untuk bahasa Inggris dan tidak bisa dipakai di app ini."
                    } else {
                        ModelStore.setActive(context, file)
                        modelMessage = "Model ${file.name} diimpor dan diaktifkan."
                        refreshModels()
                    }
                }
                .onFailure { importError = it.message ?: "Gagal mengimpor model." }
            importing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
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
            // ------------------------------------------------------ whisper model
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionTitle("Model transkripsi")
                    Text(
                        "Mengubah audio jadi teks. Semua model berjalan offline; unduhan hanya untuk menambah pilihan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ActiveModelRow(active = activeModel, storageBytes = storageBytes)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showModelList = !showModelList }) {
                            Text(if (showModelList) "Tutup daftar" else "Kelola model")
                        }
                        if (activeModel == null) {
                            Button(onClick = { restoreBundled() }, enabled = !modelBusy) {
                                Text(if (modelBusy) "Menyiapkan…" else "Pulihkan model")
                            }
                        }
                        if (modelBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }

                    if (modelBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    modelMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    modelError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    if (showModelList) {
                        HorizontalDivider()
                        ModelCatalog.all.forEach { spec ->
                            val installed = models.firstOrNull { it.name == spec.fileName }
                            ModelRow(
                                spec = spec,
                                installed = installed,
                                isActive = installed != null &&
                                    installed.absolutePath == activeModel?.absolutePath,
                                entry = downloads[spec.id],
                                onActivate = {
                                    if (installed != null) {
                                        ModelStore.setActive(context, installed)
                                        modelMessage = "${spec.displayName} diaktifkan."
                                        refreshModels()
                                    }
                                },
                                onDelete = { if (installed != null) pendingDelete = installed },
                                onDownload = { pendingDownload = spec },
                                onCancel = { container.downloads.cancel(spec) },
                                onRestore = { restoreBundled() },
                            )
                            HorizontalDivider()
                        }

                        val imported = models.filter { ModelStore.specFor(it) == null }
                        if (imported.isNotEmpty()) {
                            Text(
                                "Model lain (impor manual)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            imported.forEach { file ->
                                ImportedModelRow(
                                    file = file,
                                    isActive = file.absolutePath == activeModel?.absolutePath,
                                    onActivate = {
                                        ModelStore.setActive(context, file)
                                        modelMessage = "${file.name} diaktifkan."
                                        refreshModels()
                                    },
                                    onDelete = { pendingDelete = file },
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Text(
                        "Lanjutan",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { importModel.launch(arrayOf("*/*")) },
                            enabled = !importing,
                        ) {
                            Text(if (importing) "Mengimpor…" else "Impor dari file")
                        }
                        if (importing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    Text(
                        "Untuk model ggml multilingual milikmu sendiri. Model .en ditolak karena app ini memakai Bahasa Indonesia.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    importError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // -------------------------------------------------------- gemini key
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionTitle("Gemini API key")
                    Text(
                        "Dipakai untuk ringkasan dan tanya-jawab. Transkripsi tetap 100% offline. " +
                            "Key disimpan terenkripsi (Android Keystore) dan hanya dikirim ke Google.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    keyMasked?.let { masked ->
                        Text(
                            "Tersimpan: $masked",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (keyMasked == null) "Tempel API key" else "Ganti API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val value = keyInput.trim()
                                if (value.isEmpty()) return@Button
                                scope.launch {
                                    withContext(Dispatchers.IO) { ApiKeyStore.save(context, value) }
                                    keyMasked = withContext(Dispatchers.IO) { ApiKeyStore.masked(context) }
                                    keyInput = ""
                                    savedMessage = "API key tersimpan terenkripsi."
                                    testResult = null
                                    testError = null
                                }
                            },
                            enabled = keyInput.isNotBlank(),
                        ) { Text("Simpan") }

                        if (keyMasked != null) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { ApiKeyStore.clear(context) }
                                        keyMasked = null
                                        savedMessage = "API key dihapus."
                                    }
                                },
                            ) { Text("Hapus") }
                        }
                    }

                    savedMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ------------------------------------------------------------- model
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionTitle("Model Gemini")
                    Text(
                        "Default: ${AiSettings.DEFAULT_MODEL}. Kalau model ini tidak tersedia lagi, isi nama model lain " +
                            "atau cek daftar model di tombol Tes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nama model") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                AiSettings.setModel(context, modelName)
                                savedMessage = "Model disimpan: ${modelName.trim()}"
                            },
                            enabled = modelName.isNotBlank(),
                        ) { Text("Simpan model") }

                        Button(
                            onClick = {
                                scope.launch {
                                    testing = true
                                    testResult = null
                                    testError = null
                                    try {
                                        val key = withContext(Dispatchers.IO) { ApiKeyStore.load(context) }
                                            ?: error("Belum ada API key.")
                                        val client = GeminiClient(key, modelName.trim())
                                        val available = client.listModels()
                                        testResult = if (available.isEmpty()) {
                                            "Terhubung, tetapi tidak ada model generateContent."
                                        } else {
                                            "Terhubung. Contoh model: " + available.take(6).joinToString(", ")
                                        }
                                    } catch (e: GeminiException) {
                                        testError = e.message
                                    } catch (t: Throwable) {
                                        testError = t.message ?: "Gagal terhubung."
                                    } finally {
                                        testing = false
                                    }
                                }
                            },
                            enabled = !testing && keyMasked != null,
                        ) { Text(if (testing) "Menguji…" else "Tes koneksi") }
                    }

                    testResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    testError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // --------------------------------------------------------- licenses
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLicenses),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Lisensi & sumber model", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "whisper.cpp, Whisper, model kustom, dan komponen AndroidX",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // --------------------------------------------------------- diagnostics
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDiagnostics),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.BugReport, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Diagnostik", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Info perangkat, versi whisper.cpp, uji konversi audio",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(
                "Privasi: audio, transkrip, dan riwayat chat disimpan hanya di HP ini. " +
                    "Hanya teks yang kamu kirim ke Gemini yang keluar dari perangkat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ---------------------------------------------------------------- dialogs
    pendingDownload?.let { spec ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Unduh ${spec.displayName}?") },
            text = {
                Text(
                    "Ukuran ${formatFileSize(spec.sizeBytes)} dan memakai kuota internet. " +
                        "Model yang sudah ada tetap bisa dipakai selama unduhan berjalan.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDownload = null
                        container.downloads.download(spec)
                    },
                ) { Text("Unduh") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text("Batal") }
            },
        )
    }

    pendingDelete?.let { file ->
        val spec = ModelStore.specFor(file)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus model?") },
            text = {
                Text(
                    "\"${ModelStore.displayName(file)}\" (${formatFileSize(file.length())}) akan dihapus dari HP." +
                        if (spec?.isBundled == true) " Model bawaan bisa dipulihkan lagi kapan saja." else "",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        deleteModel(file)
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
private fun ActiveModelRow(active: File?, storageBytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (active != null) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (active != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                active?.let { ModelStore.displayName(it) } ?: "Belum ada model",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                active?.let { file ->
                    val source = when {
                        ModelStore.isBundled(file) -> "bawaan"
                        ModelStore.specFor(file) != null -> "unduhan"
                        else -> "impor"
                    }
                    "${formatFileSize(file.length())} · $source · multilingual"
                } ?: "Tekan Pulihkan model untuk menyiapkan model bawaan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${formatFileSize(storageBytes)} terpakai",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    installed: File?,
    isActive: Boolean,
    entry: ModelDownloads.Entry?,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
) {
    val busy = entry?.status == ModelDownloads.Status.DOWNLOADING ||
        entry?.status == ModelDownloads.Status.VERIFYING

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isActive) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    spec.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatFileSize(spec.sizeBytes)} · ${spec.tagline}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) {
                Text(
                    "Aktif",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        when {
            entry == null -> Unit

            entry.status == ModelDownloads.Status.DOWNLOADING -> {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append("${(entry.progress * 100).toInt()}%")
                            entry.message?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Batal") }
                }
            }

            entry.status == ModelDownloads.Status.VERIFYING -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Memeriksa checksum…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            entry.status == ModelDownloads.Status.FAILED -> {
                Text(
                    entry.message ?: "Unduhan gagal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (installed != null) {
                    Text(
                        "Model yang sudah terpasang tetap bisa dipakai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> Unit
        }

        when {
            installed != null -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (!isActive) {
                        TextButton(onClick = onActivate) { Text("Aktifkan") }
                    }
                    if (!spec.isBundled) {
                        TextButton(onClick = onDelete) { Text("Hapus") }
                    }
                }
            }

            !busy -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    when {
                        spec.isBundled -> Button(onClick = onRestore) { Text("Pulihkan") }
                        spec.isDownloadable -> {
                            Button(onClick = onDownload) {
                                Text(if (entry?.status == ModelDownloads.Status.FAILED) "Coba lagi" else "Unduh")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportedModelRow(
    file: File,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isActive) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatFileSize(file.length())} · impor manual",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isActive) {
            TextButton(onClick = onActivate) { Text("Aktifkan") }
        }
        TextButton(onClick = onDelete) { Text("Hapus") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}
