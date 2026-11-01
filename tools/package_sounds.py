"""Package approved WAVs and fixed-envelope pitch variants for the native renderer.

Run explicitly after sound edits; normal Android builds need no Python generator.
"""
import gzip
import math
from pathlib import Path
import struct
import wave

from generate_sounds import synth, RATE

ROOT = Path(__file__).resolve().parents[1]
SOUNDS = {"STANDARDCLICK": "beep", "CUT": "click", "WOODBLOCK": "woodblock",
          "HIHAT": "hihat", "SNARE": "snare", "COWBELL": "cowbell"}


def pitched_samples(key, accent, pitch):
    if key != "click" or pitch == 0:
        return synth(key, accent, pitch)
    # Cut's nonlinear saturation generates harmonics: oversample, low-pass, then
    # decimate, instead of folding transposed overtones into the audible range.
    source = synth(key, accent, pitch, sample_rate=RATE * 4)
    cutoff = .1125  # 21.6 kHz at the 192 kHz synthesis rate
    weights = []
    for j in range(-64, 65):
        sinc = 2 * cutoff if j == 0 else math.sin(2 * math.pi * cutoff * j) / (math.pi * j)
        window = .42 + .5 * math.cos(math.pi * j / 64) + .08 * math.cos(2 * math.pi * j / 64)
        weights.append(sinc * window)
    total = sum(weights)
    result = [sum(source[index + j] * weights[j + 64] for j in range(-64, 65)
                  if 0 <= index + j < len(source)) / total for index in range(0, len(source), 4)]
    result[0] = 0.0
    level = .85 if accent else .68
    gain = level / max(abs(value) for value in result)
    return [value * gain for value in result]


def main():
    entries = []
    for name, key in SOUNDS.items():
        for pitch in (range(-12, 13) if key in ("beep", "click", "woodblock", "cowbell") else (0,)):
            for accent in (False, True):
                values = [round(v * 32767) for v in pitched_samples(key, accent, pitch)]
                if pitch == 0:
                    with wave.open(str(ROOT / "sounds" / f"{key}-{'accent' if accent else 'normal'}.wav")) as wav:
                        assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 2, RATE)
                        raw = wav.readframes(wav.getnframes())
                        assert raw == struct.pack(f"<{len(values)}h", *values), f"WAV/generator mismatch: {key}"
                assert all(math.isfinite(v) and abs(v) < 32767 for v in values)
                encoded = name.encode("ascii")
                entries.append(struct.pack(">i", len(encoded)) + encoded +
                               struct.pack(">iiii", pitch, int(accent), RATE, len(values)) +
                               struct.pack(f">{len(values)}h", *values))
    output = ROOT / "core/src/main/resources/de/takt/core/sounds.pcm.gz"
    output.parent.mkdir(parents=True, exist_ok=True)
    data = struct.pack(">ii", 0x54414331, len(entries)) + b"".join(entries)
    output.write_bytes(gzip.compress(data, mtime=0))
    print(f"{len(entries)} native sound variants, {output.stat().st_size} bytes: {output}")


if __name__ == "__main__":
    main()
