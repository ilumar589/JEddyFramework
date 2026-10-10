#!/usr/bin/env python3
"""Synthesizes the iso demo's sounds (deterministic, so the cook cache stays warm):

  assets/audio/fireball.wav        an explosion: low thump, roaring noise and crackle (1.4 s)
  assets/audio/crypt_ambience.wav  a seamless 8 s loop: low drone and draughts (ambience bus)

Both 22.05 kHz mono 16-bit. Made here, so they are as free as the code (no third-party audio).
Run: python3 samples/iso-demo/tools/sounds.py
"""
import math
import os
import random
import struct
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "audio")


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    peak = max(1e-9, max(abs(s) for s in samples))
    gain = 0.89 / peak
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s * gain)) * 32767)) for s in samples))


def lowpass(samples, cutoff):
    """One-pole low-pass; cutoff may be a function of the sample index."""
    out, y = [], 0.0
    for i, x in enumerate(samples):
        c = cutoff(i) if callable(cutoff) else cutoff
        a = 1 - math.exp(-2 * math.pi * c / RATE)
        y += a * (x - y)
        out.append(y)
    return out


def fireball():
    rnd = random.Random(7)
    n = int(1.4 * RATE)
    noise = [rnd.uniform(-1, 1) for _ in range(n)]
    roar = lowpass(noise, lambda i: 4000 * math.exp(-i / (0.25 * RATE)) + 300)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / RATE
        f = 40 + 90 * math.exp(-t / 0.08)
        phase += 2 * math.pi * f / RATE
        thump = math.sin(phase) * math.exp(-t / 0.25)
        attack = min(1.0, t / 0.004)
        body = roar[i] * math.exp(-t / 0.35) * 2.2
        crackle = (rnd.random() < 0.004 * math.exp(-t / 0.5)) * rnd.uniform(-1, 1) * 1.5
        out.append(attack * (thump * 1.3 + body) + crackle)
    return out


def ambience():
    rnd = random.Random(11)
    seconds, fade = 8, 1
    n, m = seconds * RATE, (seconds + fade) * RATE
    wind = lowpass([rnd.uniform(-1, 1) for _ in range(m)], 380)
    # Crossfade the tail into the head so the loop has no seam.
    for i in range(fade * RATE):
        k = i / (fade * RATE)
        wind[i] = wind[i] * k + wind[n + i] * (1 - k)
    out = []
    for i in range(n):
        t = i / RATE
        # Whole cycles in 8 s keep the drone seamless too.
        drone = (
            math.sin(2 * math.pi * 55 * t) * 0.5
            + math.sin(2 * math.pi * 55.375 * t) * 0.4
            + math.sin(2 * math.pi * 82.5 * t) * 0.2
        )
        gust = 0.6 + 0.4 * math.sin(2 * math.pi * t / 8 + 1.0) * math.sin(2 * math.pi * 3 * t / 8)
        out.append(drone * 0.35 + wind[i] * 3.0 * gust)
    return out


if __name__ == "__main__":
    write("fireball.wav", fireball())
    write("crypt_ambience.wav", ambience())
    print("wrote", OUT)
