# Ekstrak Transkrip

Transkripsi audio/video di perangkat (whisper.cpp) → teks + timestamp, lalu
ringkasan dan tanya-jawab opsional lewat Gemini (BYOK). Tanpa server sendiri,
tanpa upload audio.

Target: Android arm64, minSdk 26, dioptimalkan untuk HP 3–4 GB RAM.

## Status

Fase 1–5 sudah ditulis lengkap (siap diuji di device):

| Fase | Isi |
|---|---|
| 1 | Transkripsi audio lokal: impor file → 16 kHz mono → whisper.cpp → segmen bertimestamp → simpan SQLite → ekspor TXT/SRT |
| 2 | Video: pemilih file menerima `video/*`; decoder hanya mengambil track audio |
| 3 | Ringkasan Gemini: key terenkripsi Keystore, ringkasan + keputusan + action items + momen bertimestamp, ekspor `.md` |
| 4 | Chat/Q&A atas transkrip dengan streaming SSE, riwayat lokal |
| 5 | Distribusi: model q5_1 dibundel di APK, onboarding sekali jalan, katalog model (tiny fp16 / base / kustom) dengan unduhan terverifikasi SHA-256, layar lisensi, release signing |

APK saat ini: **46 MB**, langsung jalan offline tanpa impor model.

Yang belum: uji di perangkat dengan rekaman Indonesia asli, diarization pembicara,
player untuk lompat ke timestamp.

## Arsitektur

```
UI (Compose)  ── observe ──> SQLite (SQLiteOpenHelper, tanpa Room/KSP)
    │
    └─ start ──> TranscribeService (FGS dataSync + wakelock)
                     │
                     ├─ SourceImporter   SAF URI → file internal
                     ├─ AudioDecoder     MediaExtractor → MediaCodec → downmix → sinc resampler → WAV
                     ├─ WhisperEngine    JNI, model size tetap di RAM
                     └─ TranscribeJob    native thread + polling (tanpa callback JNI)
```

Keputusan penting:

- **Tanpa ffmpeg-kit** (sudah retired). MediaExtractor + MediaCodec + resampler
  sinc native (`core/audio/AudioDecoder.kt`). Sudah divalidasi numerik: ripple
  passband 0 dB, stopband 88–91 dB.
- **Tanpa Room/KSP**. Empat tabel, skema eksplisit, nol risiko versi KSP vs
  compiler.
- **Native job + polling**. `whisper_full()` jalan di `std::thread` sendiri;
  Kotlin polling progress/segmen tiap 300 ms. Tidak ada panggilan JNI dari
  native thread, jadi tidak ada urusan AttachCurrentThread.
- **Chunking**: 5 menit (low-RAM) / 10 menit, overlap 1 detik. Audio dibaca per
  chunk, jadi rekaman 2 jam tidak butuh 230 MB float di RAM.
- **Model bawaan di APK**. `ggml-tiny-q5_1.bin` (31 MB) diekstrak sekali ke
  `filesDir/models/` saat onboarding, atomik + verifikasi SHA-256. Setelah itu
  app bisa dipakai tanpa internet sama sekali.
- **Model lain opsional**. Tiny fp16, base, dan model kustom diunduh lewat
  DownloadManager ke `externalFiles/models/`, selalu diverifikasi ukuran +
  SHA-256 sebelum dipakai.
- **Bahasa tetap `id`**, model multilingual (`.en` ditolak lebih awal).

## Build

Clone baru butuh model bawaan dulu (tidak di-commit, 31 MB):

```powershell
.\scripts\fetch-model.ps1
```

Script mengunduh `ggml-tiny-q5_1.bin` dari HuggingFace (commit dipatok) dan
memverifikasi SHA-256. Setelah itu:

```powershell
.\scripts\build.ps1            # debug APK
.\scripts\build.ps1 -Release   # release APK
.\scripts\build.ps1 -Install   # build + adb install
```

Manual:

```powershell
$env:ANDROID_HOME="D:\android-sdk"
$env:GRADLE_USER_HOME="D:\gradle-home"
.\gradlew.bat :app:assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`
APK release: `app/build/outputs/apk/release/app-release.apk`
Salinan siap bagikan: `dist/EkstrakTranskrip-1.1.0.apk`

### Release signing

- Keystore: `D:\keys\ekstrak-release.jks` (alias `ekstrak`, PKCS12, RSA 4096,
  berlaku sampai 2054). **Jangan commit, jangan kirim lewat chat publik.**
- Kredensial dibaca dari `keystore.properties` di root project (gitignored).
- Kalau file itu tidak ada, `assembleRelease` gagal dengan pesan jelas — bukan
  diam-diam memakai debug key.
- Kehilangan keystore = tidak bisa merilis update yang menimpa instalasi lama.

`scripts/build.ps1 -Release` masih menunjuk nama `app-release-unsigned.apk`;
setelah signing aktif, pakai path `app-release.apk`.

### Toolchain (mesin ini)

| Komponen | Lokasi / versi |
|---|---|
| JDK | 17 |
| Android SDK | `D:\android-sdk` |
| Platform / Build-tools | android-36 / 36.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 (dari SDK) |
| Gradle | 8.14.3 (wrapper) |
| Gradle home | `D:\gradle-home` |
| AGP / Kotlin | 8.13.2 / 2.2.21 |
| Compose BOM | 2025.09.00 |

> NDK 30 + CMake 4.x **tidak bisa dipakai**: whisper.cpp masih
> `cmake_minimum_required(VERSION 3.5)` dan CMake 4 menghapus kompatibilitas itu.
> Compose BOM 2026.x menuntut compileSdk 37 + AGP 9.1.

## Model

Satu model ikut di dalam APK, sisanya opsional dari **Pengaturan → Kelola model**.

| Model | Ukuran | Cara | Catatan |
|---|---|---|---|
| Tiny q5_1 (bawaan) | 31 MB | asset APK | selalu tersedia, offline |
| Tiny fp16 | 74 MB | unduh (HuggingFace, commit dipatok) | akurasi tiny terbaik |
| Base | 142 MB | unduh (HuggingFace, commit dipatok) | akurasi lebih baik, RAM lebih besar |
| Tiny ID v1 | 30 MB | unduh (Drive) | model kustom Bahasa Indonesia |

Semua unduhan diverifikasi ukuran + SHA-256. Kalau host mengembalikan halaman
HTML (mis. kuota Drive habis), file ditolak dan user melihat pesan yang jelas.

Impor manual tetap ada di **Pengaturan → Lanjutan** untuk model ggml
multilingual milik sendiri. `ggml-tiny.en.bin` hanya bahasa Inggris dan ditolak.

SHA-256 yang dipakai:

```
tiny-q5_1  818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7
tiny fp16  be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
base       60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe
tiny-id-v1 5c4da0f11919f70085993c2a157ef1a4c24896ab0613c76d3c8741f8b061aeb8
```

## Gemini (opsional)

Transkripsi 100% offline. Ringkasan/chat butuh API key dari Google AI Studio:

1. **Pengaturan → Tempel API key → Simpan** (dienkripsi AES-GCM, kunci di
   Android Keystore, hanya ciphertext di SharedPreferences).
2. **Tes koneksi** memanggil `GET /v1beta/models` dan menampilkan model yang
   tersedia. Kalau `gemini-2.5-flash` sudah tidak ada, ganti di kolom model.

Hanya teks transkrip yang dikirim ke Google; audio tidak pernah keluar dari HP.
Riwayat chat disimpan lokal di SQLite.

## Alur pakai

1. Instal APK → onboarding mengekstrak model bawaan (sekali, tanpa internet).
2. **Transkripsi baru** → pilih audio/video.
3. Progres jalan di foreground service; boleh tinggalkan app.
4. Buka hasil → cari kata, salin, bagikan, ekspor TXT/SRT.
5. **Ringkas** (Gemini) atau **Tanya** (chat dengan timestamp).
6. Opsional: **Pengaturan → Kelola model** untuk mengunduh base atau model
   Bahasa Indonesia kustom.

## Pengujian yang disiapkan

- `tools/check_resampler.py` — verifikasi numerik resampler (passband/stopband/alias).
- `testdata/` — 13 format hasil konversi `jfk.wav` (mp3 CBR/VBR, m4a, 3gp, ogg,
  opus, mkv, flac, wav 8k/44.1k, aiff; wma memang tidak didukung Android).
- `Diagnostik` di app: versi native, RAM, thread/chunk, uji konversi audio.

## Struktur

```
app/src/main/cpp/
  CMakeLists.txt          # whisper.cpp + ggml, arm64 only, OpenMP off
  whisper_jni.cpp         # JNI: model, resampler, job
  transcribe_job.cpp      # worker native, chunking, dedupe overlap
  resampler.cpp           # sinc band-limited
  whisper.cpp/            # vendored v1.7.4
app/src/main/java/com/ekstraktranskrip/
  App.kt                  # Application + AppContainer
  MainActivity.kt
  core/audio/             # AudioDecoder, WavWriter
  core/whisper/           # NativeBridge, WhisperEngine
  core/model/             # ModelCatalog, ModelStore (bundled + impor), ModelDownloads
  core/ai/                # ApiKeyStore, GeminiClient, AiPrompts
  data/                   # DB, repo, importer, ekspor, JobStarter
  service/                # TranscribeService + worker + ActiveJob
  ui/                     # AppRoot, onboarding, home, job, transcript, summary, chat, settings
```

Model bawaan tidak di-commit: `models/ggml-tiny-q5_1.bin` disalin ke
`app/src/main/assets/models/` oleh task Gradle `prepareBundledModel` setiap
build. Folder `models/` di-gitignore.
