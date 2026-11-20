#pragma once

#include <cstddef>
#include <vector>

/**
 * Streaming band-limited resampler for multichannel float PCM.
 *
 * Why hand-rolled instead of libsamplerate/speex:
 *  - no extra dependency, ~150 lines, no build system surprises
 *  - whisper only needs the speech band (<= 8 kHz), and a Kaiser-windowed
 *    sinc with adaptive length reaches ~65 dB stopband at any input rate
 *
 * Usage: process() as many times as you want, then flush() once. Output is
 * interleaved with the same channel count as input.
 */
class SincResampler {
public:
    SincResampler(int inRate, int outRate, int channels);

    /** Appends resampled interleaved frames to `out`. */
    void process(const float * in, size_t frames, std::vector<float> & out);

    /** Drains the filter tail. Call exactly once, after the last process(). */
    void flush(std::vector<float> & out);

    int inRate() const { return inRate_; }
    int outRate() const { return outRate_; }
    int channels() const { return channels_; }
    int taps() const { return taps_; }

private:
    void emit(std::vector<float> & out);

    int inRate_;
    int outRate_;
    int channels_;
    double ratio_ = 1.0;   // out / in
    double step_ = 1.0;    // input frames advanced per output frame
    double pos_ = 0.0;     // absolute (input frame) position of next output

    int taps_ = 0;
    int half_ = 0;

    std::vector<float> table_;  // [phase][tap]
    std::vector<float> buf_;    // interleaved history
    long long bufStart_ = 0;    // absolute index of buf_[0]
    long long realEnd_ = -1;
    bool flushed_ = false;
};
