#include "transcribe_job.h"

#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#define TAG "WhisperJob"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kSampleRate = 16000;
constexpr int kOverlapMs = 1000;
// Part of the overlap handled by the previous chunk: segments that start
// earlier than this are duplicates and get dropped.
constexpr int kOverlapDropMs = 700;

struct WavInfo {
    FILE * fp = nullptr;
    long dataOffset = 0;
    long dataBytes = 0;
    int sampleRate = 0;
    int channels = 0;
    int bits = 0;
};

uint32_t read_le32(const unsigned char * p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t read_le16(const unsigned char * p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

bool open_wav(const std::string & path, WavInfo & out) {
    FILE * fp = fopen(path.c_str(), "rb");
    if (fp == nullptr) return false;

    unsigned char header[12];
    if (fread(header, 1, 12, fp) != 12 || memcmp(header, "RIFF", 4) != 0 ||
        memcmp(header + 8, "WAVE", 4) != 0) {
        fclose(fp);
        return false;
    }

    bool gotFmt = false;
    bool gotData = false;

    while (true) {
        unsigned char chunk[8];
        if (fread(chunk, 1, 8, fp) != 8) break;
        const uint32_t size = read_le32(chunk + 4);
        if (memcmp(chunk, "fmt ", 4) == 0) {
            if (size < 16) break;
            unsigned char fmt[16];
            if (fread(fmt, 1, 16, fp) != 16) break;
            out.channels = read_le16(fmt + 2);
            out.sampleRate = static_cast<int>(read_le32(fmt + 4));
            out.bits = read_le16(fmt + 14);
            gotFmt = true;
            if (size > 16) fseek(fp, static_cast<long>(size) - 16, SEEK_CUR);
        } else if (memcmp(chunk, "data", 4) == 0) {
            out.dataOffset = ftell(fp);
            out.dataBytes = static_cast<long>(size);
            gotData = true;
            fseek(fp, static_cast<long>(size), SEEK_CUR);
        } else {
            fseek(fp, static_cast<long>(size) + (size & 1), SEEK_CUR);
        }
        if (gotFmt && gotData) break;
    }

    if (!gotFmt || !gotData || out.dataBytes <= 0) {
        fclose(fp);
        return false;
    }

    out.fp = fp;
    return true;
}

struct ChunkState {
    TranscribeJob * job = nullptr;
    int64_t baseMs = 0;
    int chunkIndex = 0;
    int nChunks = 1;
};

void on_new_segment(whisper_context * ctx, whisper_state * /*state*/, int n_new, void * user_data) {
    auto * chunk = static_cast<ChunkState *>(user_data);
    if (chunk == nullptr || chunk->job == nullptr) return;

    const int total = whisper_full_n_segments(ctx);
    const int first = total - n_new;
    if (first < 0) return;

    std::lock_guard<std::mutex> lock(chunk->job->mu);
    for (int i = first; i < total; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;  // cs -> ms
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char * text = whisper_full_get_segment_text(ctx, i);

        const int64_t absT0 = chunk->baseMs + t0;
        const int64_t absT1 = chunk->baseMs + t1;

        if (chunk->chunkIndex > 0 && absT0 < chunk->baseMs + kOverlapDropMs) {
            continue;  // duplicate from the overlap region
        }
        if (text == nullptr) continue;

        std::string s(text);
        // trim
        const auto begin = s.find_first_not_of(" \t\r\n");
        if (begin == std::string::npos) continue;
        const auto end = s.find_last_not_of(" \t\r\n");
        s = s.substr(begin, end - begin + 1);

        SegmentRec rec;
        rec.t0 = absT0;
        rec.t1 = absT1 > absT0 ? absT1 : absT0 + 100;
        rec.text = std::move(s);
        chunk->job->segments.push_back(std::move(rec));
    }
}

void on_progress(whisper_context * /*ctx*/, whisper_state * /*state*/, int progress, void * user_data) {
    auto * chunk = static_cast<ChunkState *>(user_data);
    if (chunk == nullptr || chunk->job == nullptr) return;
    // Map chunk-local progress onto the whole file so the bar never jumps back.
    const int nChunks = chunk->nChunks > 0 ? chunk->nChunks : 1;
    const int total = (chunk->chunkIndex * 100 + progress) / nChunks;
    chunk->job->progress.store(std::min(100, std::max(0, total)));
}

bool on_abort(void * user_data) {
    auto * chunk = static_cast<ChunkState *>(user_data);
    return chunk != nullptr && chunk->job != nullptr && chunk->job->cancel.load();
}

}  // namespace

TranscribeJob::~TranscribeJob() {
    stopAndJoin();
}

void TranscribeJob::start() {
    worker = std::thread(transcribe_worker, this);
}

void TranscribeJob::stopAndJoin() {
    cancel.store(true);
    if (worker.joinable()) worker.join();
}

std::string TranscribeJob::getError() const {
    std::lock_guard<std::mutex> lock(mu);
    return error;
}

size_t TranscribeJob::segmentCount() const {
    std::lock_guard<std::mutex> lock(mu);
    return segments.size();
}

std::string TranscribeJob::segmentAt(size_t index) const {
    std::lock_guard<std::mutex> lock(mu);
    if (index >= segments.size()) return std::string();
    const SegmentRec & s = segments[index];
    return std::to_string(s.t0) + "|" + std::to_string(s.t1) + "|" + s.text;
}

void transcribe_worker(TranscribeJob * job) {
    auto fail = [job](const std::string & message) {
        std::lock_guard<std::mutex> lock(job->mu);
        job->error = message;
        job->ok.store(false);
        job->done.store(true);
        LOGE("%s", message.c_str());
    };

    if (job->ctx == nullptr) {
        fail("Model belum dimuat.");
        return;
    }

    WavInfo wav;
    if (!open_wav(job->wavPath, wav)) {
        fail("WAV tidak bisa dibaca: " + job->wavPath);
        return;
    }

    if (wav.sampleRate != kSampleRate || wav.channels != 1 || wav.bits != 16) {
        fclose(wav.fp);
        fail("WAV tidak sesuai (harus 16 kHz mono PCM16).");
        return;
    }

    const int64_t totalFrames = wav.dataBytes / 2;
    const int64_t totalMs = totalFrames * 1000 / kSampleRate;
    const int64_t chunkFrames = static_cast<int64_t>(job->chunkSeconds) * kSampleRate;
    const int64_t overlapFrames = static_cast<int64_t>(kOverlapMs) * kSampleRate / 1000;

    const int nChunks = static_cast<int>(std::max<int64_t>(1, (totalFrames + chunkFrames - 1) / chunkFrames));
    LOGI("job start: %lld frames (%.1f s), %d chunks, %d threads",
         static_cast<long long>(totalFrames), totalMs / 1000.0, nChunks, job->threads);

    std::vector<float> buffer(static_cast<size_t>(chunkFrames + overlapFrames));

    for (int c = 0; c < nChunks; ++c) {
        if (job->cancel.load()) {
            fclose(wav.fp);
            {
                std::lock_guard<std::mutex> lock(job->mu);
                job->error = "Dibatalkan.";
            }
            job->ok.store(false);
            job->done.store(true);
            return;
        }

        const int64_t startFrame = static_cast<int64_t>(c) * chunkFrames;
        const int64_t framesToRead =
                std::min(chunkFrames + (c + 1 < nChunks ? overlapFrames : 0), totalFrames - startFrame);
        if (framesToRead <= 0) break;

        if (fseek(wav.fp, wav.dataOffset + static_cast<long>(startFrame * 2), SEEK_SET) != 0) {
            fclose(wav.fp);
            fail("Gagal seek di file WAV.");
            return;
        }

        std::vector<int16_t> pcm(static_cast<size_t>(framesToRead));
        const size_t read = fread(pcm.data(), sizeof(int16_t), static_cast<size_t>(framesToRead), wav.fp);
        if (read == 0) {
            fclose(wav.fp);
            fail("Gagal membaca PCM dari WAV.");
            return;
        }

        buffer.resize(read);
        for (size_t i = 0; i < read; ++i) {
            buffer[i] = static_cast<float>(pcm[i]) / 32768.0f;
        }

        ChunkState chunk;
        chunk.job = job;
        chunk.baseMs = startFrame * 1000 / kSampleRate;
        chunk.chunkIndex = c;
        chunk.nChunks = nChunks;

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.language = job->language.c_str();
        params.n_threads = job->threads;
        params.no_context = true;
        params.single_segment = false;
        params.suppress_blank = true;
        params.suppress_nst = true;
        params.temperature_inc = 0.2f;
        params.max_initial_ts = 1.0f;
        params.initial_prompt =
                "Transkripsi rapat bahasa Indonesia dengan tanda baca yang benar.";
        params.new_segment_callback = on_new_segment;
        params.new_segment_callback_user_data = &chunk;
        params.progress_callback = on_progress;
        params.progress_callback_user_data = &chunk;
        params.abort_callback = on_abort;
        params.abort_callback_user_data = &chunk;

        LOGI("chunk %d/%d: base=%lld ms, frames=%zu", c + 1, nChunks,
             static_cast<long long>(chunk.baseMs), read);

        const int rc = whisper_full(job->ctx, params, buffer.data(), static_cast<int>(read));
        if (rc != 0) {
            fclose(wav.fp);
            if (job->cancel.load()) {
                {
                    std::lock_guard<std::mutex> lock(job->mu);
                    job->error = "Dibatalkan.";
                }
                job->ok.store(false);
                job->done.store(true);
            } else {
                fail("whisper_full gagal (kode " + std::to_string(rc) + ").");
            }
            return;
        }

        // Progress across chunks: (completed + current) / nChunks.
        job->progress.store(static_cast<int>(((c + 1) * 100) / nChunks));
    }

    fclose(wav.fp);

    {
        std::lock_guard<std::mutex> lock(job->mu);
        job->ok.store(true);
        LOGI("job done: %zu segments", job->segments.size());
    }
    job->done.store(true);
}
