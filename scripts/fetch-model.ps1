param(
    [switch]$Force
)

# Unduh model bawaan ke models/ sebelum build pertama.
# models/ di-gitignore; script ini yang membuat clone baru bisa di-build.

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$modelDir = Join-Path $repoRoot "models"
New-Item -ItemType Directory -Force -Path $modelDir | Out-Null

$dest = Join-Path $modelDir "ggml-tiny-q5_1.bin"
$expected = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
$url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin"

if ((Test-Path $dest) -and -not $Force) {
    $actual = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
    if ($actual -eq $expected) {
        Write-Host "Model sudah ada dan valid: $dest" -ForegroundColor Green
        exit 0
    }
    throw "File ada tapi checksum tidak cocok. Jalankan ulang dengan -Force."
}

Write-Host "Mengunduh model bawaan (31 MB)..."
curl.exe -L --fail --progress-bar -o "$dest.part" $url
if ($LASTEXITCODE -ne 0) { throw "Unduhan gagal" }

$actual = (Get-FileHash "$dest.part" -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $expected) {
    Remove-Item "$dest.part" -Force
    throw "Checksum tidak cocok. Diharapkan $expected, dapat $actual"
}

Move-Item "$dest.part" $dest -Force
Write-Host "OK: $dest" -ForegroundColor Green
