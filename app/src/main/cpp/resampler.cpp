#include "resampler.h"

#include <algorithm>
#include <cmath>

namespace {

constexpr int kPhases = 512;
constexpr double kPi = 3.14159265358979323846;

double sinc(double x) {
    if (std::fabs(x) < 1e-9) return 1.0;
    const double px = kPi * x;
    return std::sin(px) / px;
}

double bessel_i0(double x) {
    double sum = 1.0;
    double term = 1.0;
    const double half = x * 0.5;
    for (int k = 1; k < 40; ++k) {
        const double f = half / static_cast<double>(k);
        term *= f * f;
        sum += term;
        if (term < 1e-12 * sum) break;
    }
    return sum;
}

}  // namespace

SincResampler::SincResampler(int inRate, int outRate, int channels)
    : inRate_(inRate > 0 ? inRate : 16000),
      outRate_(outRate > 0 ? outRate : 16000),
      channels_(channels > 0 ? channels : 1) {

    ratio_ = static_cast<double>(outRate_) / static_cast<double>(inRate_);
    step_ = 1.0 / ratio_;

    // Tap count is derived from the transition width we need:
    //   passband edge  ~7 kHz, stopband edge ~8 kHz  ->  df = ratio / 16
    //   Kaiser attenuation A ~= 2.285 * N * 2*pi * df + 8
    //   with N = 64 / ratio this lands at a constant ~65 dB for any input rate.
    // Shorter filters only when upsampling, where the transition is relaxed.
    int taps = static_cast<int>(std::lround(64.0 / std::min(1.0, ratio_)));
    taps = std::max(32, std::min(512, taps));
    taps = (taps + 1) & ~1;  // even

    taps_ = taps;
    half_ = taps_ / 2;

    // The filter needs history before input sample 0. Prepend half-1 zero
    // frames and let bufStart_ go negative so `base` is never out of bounds.
    if (half_ > 0) {
        buf_.assign(static_cast<size_t>(half_ - 1) * static_cast<size_t>(channels_), 0.0f);
        bufStart_ = -static_cast<long long>(half_ - 1);
    }

    table_.assign(static_cast<size_t>(kPhases) * static_cast<size_t>(taps_), 0.0f);

    // Cutoff is normalised to the input sample rate (cycles/sample).
    // 0.92 leaves a guard band so the transition ends before output Nyquist.
    const double cutoff = 0.5 * std::min(1.0, ratio_) * 0.92;
    const double beta = 8.6;  // ~80 dB sidelobes, Kaiser
    const double i0beta = bessel_i0(beta);

    std::vector<double> tapsBuf(static_cast<size_t>(taps_));

    for (int p = 0; p < kPhases; ++p) {
        const double d = static_cast<double>(p) / static_cast<double>(kPhases);
        double sum = 0.0;
        for (int k = 0; k < taps_; ++k) {
            const double u = static_cast<double>(k - half_ + 1) - d;
            const double r = u / static_cast<double>(half_);
            double w = 0.0;
            if (std::fabs(r) < 1.0) {
                w = bessel_i0(beta * std::sqrt(1.0 - r * r)) / i0beta;
            }
            const double h = 2.0 * cutoff * sinc(2.0 * cutoff * u) * w;
            tapsBuf[static_cast<size_t>(k)] = h;
            sum += h;
        }
        if (std::fabs(sum) > 1e-12) {
            const double inv = 1.0 / sum;
            for (int k = 0; k < taps_; ++k) {
                table_[static_cast<size_t>(p) * static_cast<size_t>(taps_) + static_cast<size_t>(k)] =
                        static_cast<float>(tapsBuf[static_cast<size_t>(k)] * inv);
            }
        }
    }
}

void SincResampler::process(const float * in, size_t frames, std::vector<float> & out) {
    if (in != nullptr && frames > 0) {
        const size_t old = buf_.size();
        buf_.resize(old + frames * static_cast<size_t>(channels_));
        std::copy(in, in + frames * static_cast<size_t>(channels_), buf_.begin() + static_cast<long>(old));
    }
    emit(out);
}

void SincResampler::flush(std::vector<float> & out) {
    if (flushed_) return;
    flushed_ = true;

    realEnd_ = bufStart_ + static_cast<long long>(buf_.size() / static_cast<size_t>(channels_));
    buf_.insert(buf_.end(), static_cast<size_t>(half_) * static_cast<size_t>(channels_), 0.0f);
    emit(out);
}

void SincResampler::emit(std::vector<float> & out) {
    const long long endAbs = bufStart_ + static_cast<long long>(buf_.size() / static_cast<size_t>(channels_));

    while (true) {
        const long long idx = static_cast<long long>(std::floor(pos_));
        if (idx + half_ >= endAbs) break;
        if (realEnd_ >= 0 && idx >= realEnd_) break;

        const double d = pos_ - static_cast<double>(idx);
        int phase = static_cast<int>(d * kPhases + 0.5);
        if (phase >= kPhases) phase = kPhases - 1;

        const float * h = table_.data() + static_cast<size_t>(phase) * static_cast<size_t>(taps_);
        const long long base = idx - half_ + 1 - bufStart_;

        for (int c = 0; c < channels_; ++c) {
            float acc = 0.0f;
            for (int k = 0; k < taps_; ++k) {
                acc += buf_[static_cast<size_t>((base + k) * channels_ + c)] * h[k];
            }
            out.push_back(acc);
        }

        pos_ += step_;
    }

    // Drop input that can no longer contribute to a future output.
    const long long keepFrom = static_cast<long long>(std::floor(pos_)) - half_ + 1;
    const long long drop = keepFrom - bufStart_;
    if (drop > 0) {
        const size_t dropSamples = static_cast<size_t>(drop) * static_cast<size_t>(channels_);
        if (dropSamples >= buf_.size()) {
            buf_.clear();
        } else {
            buf_.erase(buf_.begin(), buf_.begin() + static_cast<long>(dropSamples));
        }
        bufStart_ = keepFrom;
    }
}
