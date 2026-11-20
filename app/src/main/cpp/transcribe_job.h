#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "whisper.h"

/**
 * A single transcription job.
 *
 * Design notes:
 *  - whisper_full() runs on its own std::thread, so the caller (Android
 *    Service) never blocks on a multi-hour operation.
 *  - Kotlin polls progress / segments instead of receiving JNI callbacks.
 *    Calling into the JVM from a native worker thread requires attaching the
 *    thread to the VM, which is easy to get wrong; polling is boring and safe.
 *  - Audio is read chunk by chunk from the WAV, so a 2 h file never needs
 *    230 MB of float samples in RAM.
 */
struct SegmentRec {
    int64_t t0 = 0;  // ms, absolute in the source file
    int64_t t1 = 0;
    std::string text;
};

struct TranscribeJob {
    whisper_context * ctx = nullptr;
    std::string wavPath;
    std::string language;
    int threads = 4;
    int chunkSeconds = 300;

    std::thread worker;
    std::atomic<bool> cancel{false};
    std::atomic<bool> done{false};
    std::atomic<bool> ok{false};
    std::atomic<int> progress{0};

    mutable std::mutex mu;
    std::vector<SegmentRec> segments;
    std::string error;

    ~TranscribeJob();

    void start();
    void stopAndJoin();

    int getProgress() const { return progress.load(); }
    bool isDone() const { return done.load(); }
    bool isOk() const { return ok.load(); }
    std::string getError() const;
    size_t segmentCount() const;
    /** "t0|t1|text" - t in milliseconds. */
    std::string segmentAt(size_t index) const;
};

void transcribe_worker(TranscribeJob * job);
