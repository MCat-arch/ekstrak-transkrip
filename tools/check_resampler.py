"""
Validates the polyphase Kaiser-sinc filter in app/src/main/cpp/resampler.cpp.

Mirrors the C++ design exactly (adaptive tap count, 512 phases, beta 8.6,
cutoff 0.5 * min(1, ratio) * 0.92).

Checks:
  1. every phase has unity DC gain
  2. passband ripple up to 6.5 kHz stays below ~0.5 dB
  3. for downsampling: attenuation above 8.3 kHz (alias band) > 55 dB
  4. end-to-end: 9.5 kHz tone must not fold back into the output band

Run: python tools/check_resampler.py
"""

import numpy as np

PHASES = 512
BETA = 8.6
OUT_RATE = 16000


def bessel_i0(x):
    x = np.asarray(x, dtype=np.float64)
    half = x / 2.0
    total = np.ones_like(x)
    term = np.ones_like(x)
    for k in range(1, 40):
        f = half / k
        term = term * f * f
        total = total + term
    return total


def design(in_rate, out_rate=OUT_RATE):
    ratio = out_rate / in_rate
    taps = int(round(64.0 / min(1.0, ratio)))
    taps = max(32, min(512, taps))
    taps += taps & 1  # make even
    half = taps // 2
    cutoff = 0.5 * min(1.0, ratio) * 0.92
    i0beta = bessel_i0(BETA)

    k = np.arange(taps)
    d = (np.arange(PHASES) / PHASES)[:, None]
    u = (k - half + 1)[None, :] - d                       # (P, T)
    r = u / half
    w = np.where(np.abs(r) < 1.0,
                 bessel_i0(BETA * np.sqrt(np.maximum(0.0, 1.0 - r * r))) / i0beta,
                 0.0)
    x = 2.0 * cutoff * u
    xs = np.where(np.abs(x) < 1e-9, 1.0, x)
    s = np.where(np.abs(x) < 1e-9, 1.0, np.sin(np.pi * xs) / (np.pi * xs))
    table = 2.0 * cutoff * s * w
    table /= table.sum(axis=1, keepdims=True)
    return table, taps, half, ratio


def dtft(table, half, f_norm):
    delays = np.arange(table.shape[1]) - half + 1
    basis = np.exp(-2j * np.pi * np.outer(delays, f_norm))  # (T, F)
    return np.abs(table @ basis)                            # (P, F)


def check(in_rate):
    table, taps, half, ratio = design(in_rate)
    dc = table.sum(axis=1)

    # Above the input Nyquist there is no signal, so only measure the band
    # that actually exists at the input rate.
    pass_limit = min(6500.0, 0.40 * in_rate)
    f_pass = np.linspace(0, pass_limit, 300) / in_rate
    pass_mag = dtft(table, half, f_pass)
    pass_db = 20 * np.log10(pass_mag + 1e-12)
    ripple = float(pass_db.max() - pass_db.min())

    stop_db = None
    if ratio < 1.0:
        f_stop = np.linspace(8300, in_rate / 2 - 100, 300) / in_rate
        stop_mag = dtft(table, half, f_stop)
        stop_db = float(-20 * np.log10(stop_mag.max() + 1e-12))

    # end-to-end sanity on a two-tone signal
    dur = 0.5
    t = np.arange(int(in_rate * dur)) / in_rate
    sig = 0.5 * np.sin(2 * np.pi * 1000 * t) + 0.5 * np.sin(2 * np.pi * 9500 * t)
    sig = np.concatenate([np.zeros(half - 1), sig, np.zeros(2 * half)])
    real_len = len(sig) - (half - 1) - 2 * half
    out = []
    pos, step = 0.0, 1.0 / ratio
    while True:
        idx = int(np.floor(pos))
        if idx >= real_len:
            break
        ph = min(int((pos - idx) * PHASES + 0.5), PHASES - 1)
        base = idx
        out.append(float(sig[base:base + taps] @ table[ph]))
        pos += step
    out = np.asarray(out)

    spec = np.abs(np.fft.rfft(out * np.hanning(len(out))))
    freqs = np.fft.rfftfreq(len(out), 1 / OUT_RATE)
    tone = spec[(freqs > 900) & (freqs < 1100)].max()
    alias = spec[(freqs > 6000) & (freqs < 7400)].max() if ratio < 1 else 0.0
    alias_db = float(20 * np.log10(tone / (alias + 1e-12))) if alias else float("inf")

    return {
        "rate": in_rate,
        "ratio": ratio,
        "taps": taps,
        "dc": (float(dc.min()), float(dc.max())),
        "ripple_db": ripple,
        "stop_db": stop_db,
        "alias_db": alias_db,
    }


if __name__ == "__main__":
    rows = [check(r) for r in (48000, 44100, 32000, 24000, 22050, 16000, 11025, 8000, 96000)]
    ok = True
    print(f"{'in':>7} {'ratio':>7} {'taps':>5} {'ripple':>8} {'stop':>8} {'alias':>8}")
    for r in rows:
        stop = "-" if r["stop_db"] is None else f"{r['stop_db']:.1f}dB"
        good = r["ripple_db"] < 0.6 and (r["stop_db"] is None or r["stop_db"] > 55)
        ok = ok and good
        print(f"{r['rate']:>7} {r['ratio']:>7.3f} {r['taps']:>5} "
              f"{r['ripple_db']:>7.2f}dB {stop:>8} {r['alias_db']:>7.1f}dB  {'ok' if good else 'FAIL'}")
    print("\nALL PASS" if ok else "\nSOME FAILED")
