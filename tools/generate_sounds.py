"""Reproduzierbare Klangentwürfe; Python-Standardbibliothek, keine Fremdsamples."""
import math
import random
import struct
import wave
from pathlib import Path

RATE = 48000
OUT = Path(__file__).resolve().parents[1] / "sounds"
DURATIONS = {"click": .065, "woodblock": .065, "hihat": .12,
             "snare": .20, "cowbell": .23, "beep": .09}


def synth(kind, accent, semitones=0, sample_rate=RATE):
    rng = random.Random(741)
    duration = DURATIONS[kind]
    samples = []
    previous = 0.0
    clap_low = 0.0
    noise_low = 0.0
    snare_low = 0.0
    # Irregular seed collisions rather than a periodically modulated hiss.
    grains = [(0.0, 1.0)]
    grain_rng = random.Random(219)
    collision = .001
    while collision < .075:
        grains.append((collision, grain_rng.uniform(.3, 1)))
        collision += grain_rng.uniform(.002, .007)
    for i in range(round(duration * sample_rate)):
        t = i / sample_rate
        noise = rng.uniform(-1, 1)
        high = (noise - previous) * .5
        previous = noise
        pitch = (1.24 if accent else 1.0) * 2 ** (semitones / 12)
        if kind == "click":
            tone = math.sin(2 * math.pi * 2400 * pitch * t)
            body = math.sin(2 * math.pi * 1650 * pitch * t)
            edge = math.sin(2 * math.pi * 4800 * pitch * t)
            value = math.tanh(1.8 * (.65 * tone + .25 * body + .20 * edge))
            value *= math.exp(-t / .007)
            value += .48 * high * math.exp(-t / .0025)
        elif kind == "woodblock":
            value = sum(gain * math.sin(2 * math.pi * freq * pitch * t)
                        * math.exp(-t / decay) for freq, gain, decay in
                        [(820, .48, .008), (1377, .30, .005), (2173, .20, .003)])
            value += .48 * high * math.exp(-t / .0035)
        elif kind == "hihat":
            metal = sum(math.tanh(8 * math.sin(2 * math.pi * f * pitch * t))
                        for f in (4210, 5381, 6197, 7841, 9323, 10513)) / 6
            value = (.78 * high + .22 * metal) * math.exp(-t / .018)
        elif kind == "snare":
            # Falling membrane pitch plus a separately damped, band-limited wire bed.
            tuning = 1.45 if accent else 1.05
            phase = 2 * math.pi * tuning * (185 * t + 95 * .003 * (1 - math.exp(-t / .003)))
            body = .72 * math.sin(phase) * math.exp(-t / .020)
            body += .28 * math.sin(phase * 1.59) * math.exp(-t / .012)
            noise_low += (.16 if accent else .07) * (noise - noise_low)
            snare_low += .65 * ((noise - noise_low) - snare_low)
            wire_decay = .034 if accent else .022
            wires = snare_low * (1 - math.exp(-t / .0006)) * math.exp(-t / wire_decay)
            wire_gain = 1.15 if accent else 1.05
            body_gain = .60 if accent else .30
            value = body_gain * body + wire_gain * wires + .25 * high * math.exp(-t / .0012)
        elif kind == "cowbell":
            # Independently damped modes avoid the buzzy square-wave beating.
            value = sum(gain * math.sin(2 * math.pi * freq * pitch * t + phase)
                        * math.exp(-t / decay)
                        for freq, gain, decay, phase in
                        [(562, .65, .026, 0), (863, .32, .018, .4),
                         (1729, .12, .008, 1.1), (2543, .08, .004, .7)])
            value += .22 * high * math.exp(-t / .0015)
        elif kind == "beep":
            value = (math.sin(2 * math.pi * 1320 * pitch * t)
                     + .12 * math.sin(2 * math.pi * 2640 * pitch * t))
            value *= min(1, t / .001) * math.exp(-t / .014)
        elif kind == "shaker":
            # Discrete shell/seed contacts: short resonant grains, no noise bed.
            value = 0.0
            for index, (start, gain) in enumerate(grains):
                age = t - start
                if 0 <= age < .008:
                    frequency = 1900 + (index * 977 % 4100)
                    contact = math.sin(2 * math.pi * frequency * age)
                    contact += .35 * math.sin(2 * math.pi * frequency * 1.71 * age)
                    value += gain * contact * math.exp(-age / .00055) * math.exp(-start / .035)
        elif kind == "handclap":
            # Fused, uneven palm impacts with a midrange body, no regular retrigger.
            envelope = sum(gain * (1 - math.exp(-(t - start) / .0004))
                            * math.exp(-(t - start) / .002)
                           for start, gain in [(0, 1), (.0017, .55), (.0049, .32)]
                           if t >= start)
            noise_low += .08 * (noise - noise_low)
            clap_low += .40 * ((noise - noise_low) - clap_low)
            body = 0.0
            for start, gain in [(0, 1), (.0017, .55), (.0049, .32)]:
                age = t - start
                if age >= 0:
                    # Rapid pitch relaxation and heavily damped palm/cavity modes.
                    for frequency, strength, decay in [(460, .5, .003), (1120, .32, .002), (2380, .18, .001)]:
                        phase = 2 * math.pi * frequency * (age + .00025 * (1 - math.exp(-age / .0007)))
                        body += gain * strength * math.sin(phase) * math.exp(-age / decay)
            value = .22 * clap_low * envelope + .78 * body
        # Tiny attack avoids a discontinuity; end taper makes every file end at zero.
        value *= min(1, t / .00025) * min(1, (duration - t) / .004)
        samples.append(value)
    peak = max(abs(v) for v in samples)
    level = .85 if accent else .68
    return [v * level / peak for v in samples]


def write(name, samples):
    with wave.open(str(OUT / name), "wb") as wav:
        wav.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        wav.writeframes(b"".join(struct.pack("<h", round(v * 32767)) for v in samples))


def main():
    OUT.mkdir(exist_ok=True)
    for kind in DURATIONS:
        normal, accent = synth(kind, False), synth(kind, True)
        write(f"{kind}-normal.wav", normal)
        write(f"{kind}-accent.wav", accent)
        demo = [0.0] * (RATE * 4)
        for beat in range(8):
            sound = accent if beat % 4 == 0 else normal
            start = beat * RATE // 2
            for i, value in enumerate(sound):
                demo[start + i] += value
        write(f"{kind}-demo.wav", demo)
    print(f"{len(DURATIONS) * 3} WAV-Dateien erzeugt: {OUT}")


if __name__ == "__main__":
    main()
