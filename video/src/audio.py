"""Звук мультика: голос + фоновая музыка + эффекты -> build/audio.wav"""
import json, os, wave
import numpy as np

SR = 44100
BUILD = os.path.join(os.path.dirname(__file__), "..", "build")
tl = json.load(open(os.path.join(BUILD, "timeline.json")))
sfx = json.load(open(os.path.join(BUILD, "sfx.json")))
DUR = tl["duration"] + 0.3
N = int(DUR * SR)
rng = np.random.default_rng(7)


def read_wav(path):
    with wave.open(path) as w:
        sr, ch, sw = w.getframerate(), w.getnchannels(), w.getsampwidth()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    if sr != SR:
        x = np.arange(0, len(data), sr / SR)
        data = np.interp(x, np.arange(len(data)), data).astype(np.float32)
    return data


def place(buf, sig, t, gain=1.0):
    i = int(t * SR)
    if i < 0:
        sig, i = sig[-i:], 0
    j = min(len(buf), i + len(sig))
    if j > i:
        buf[i:j] += sig[: j - i] * gain


def tt(d):
    return np.arange(int(d * SR)) / SR


def onepole_lp(x, a):
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc += a[i] * (x[i] - acc)
        y[i] = acc
    return y


# ---------- голос ----------
voice = np.zeros(N, np.float32)
for s in tl["scenes"]:
    for c in s["chunks"]:
        v = read_wav(os.path.join(BUILD, "voice", c["wav"]))
        v = v / (np.abs(v).max() + 1e-9) * 0.9
        place(voice, v, c["start"])

# ---------- музыка ----------
BPM = 112
beat = 60 / BPM
mtof = lambda m: 440 * 2 ** ((m - 69) / 12)


def marimba(f, d=0.9):
    t = tt(d)
    s = np.sin(2 * np.pi * f * t) * np.exp(-t / 0.32) + 0.22 * np.sin(2 * np.pi * f * 3.93 * t) * np.exp(-t / 0.05)
    s *= np.minimum(1, t / 0.003)
    return s.astype(np.float32)


def bass(f, d=0.55):
    t = tt(d)
    s = (np.sin(2 * np.pi * f * t) + 0.35 * np.sin(4 * np.pi * f * t)) * np.exp(-t / 0.35) * np.minimum(1, t / 0.006)
    return s.astype(np.float32)


def kick():
    t = tt(0.25)
    f = 45 + 75 * np.exp(-t / 0.03)
    return (np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.09)).astype(np.float32)


def shaker():
    t = tt(0.06)
    n = rng.standard_normal(len(t)).astype(np.float32)
    n = np.diff(n, prepend=0)  # high-pass
    return n * np.exp(-t / 0.018) * 0.5


def snap():
    t = tt(0.15)
    n = rng.standard_normal(len(t)).astype(np.float32)
    n = onepole_lp(n, np.full(len(n), 0.35, np.float32)) - onepole_lp(n, np.full(len(n), 0.05, np.float32))
    return n * np.exp(-t / 0.045)


musL = np.zeros(N, np.float32)
musR = np.zeros(N, np.float32)
chords = [[60, 64, 67], [55, 59, 62], [57, 60, 64], [53, 57, 60]]  # C G Am F
pattern = [0, 2, 1, 3, 2, 1, 2, 3]
K, SH, SN = kick(), shaker(), snap()
bar = 4 * beat
nb = int(DUR / bar) + 1
for b in range(nb):
    ch = chords[b % 4]
    notes = ch + [ch[0] + 12]
    t0 = b * bar
    for e in range(8):
        n = notes[pattern[e]] + 12
        sig = marimba(mtof(n)) * (0.55 if e % 2 else 0.7)
        pan = 0.35 if e % 2 else -0.35
        place(musL, sig, t0 + e * beat / 2, 1 - pan)
        place(musR, sig, t0 + e * beat / 2, 1 + pan)
    for q in range(4):
        tq = t0 + q * beat
        if q in (0, 2):
            place(musL, bass(mtof(ch[0] - 24)), tq, 1.0)
            place(musR, bass(mtof(ch[0] - 24)), tq, 1.0)
            place(musL, K, tq, 0.55)
            place(musR, K, tq, 0.55)
        else:
            place(musL, SN, tq, 0.22)
            place(musR, SN, tq, 0.22)
        place(musL, SH, tq + beat / 2, 0.10)
        place(musR, SH, tq + beat / 2, 0.14)
    if b % 4 == 3:  # маленький «тру-ля-ля» колокольчиком в конце фразы
        for k, n in enumerate([ch[0] + 24, ch[1] + 24, ch[2] + 24]):
            place(musL, marimba(mtof(n), 0.6), t0 + 3 * beat + k * beat / 3, 0.35)
            place(musR, marimba(mtof(n), 0.6), t0 + 3 * beat + k * beat / 3, 0.35)

# приглушаем музыку под голосом
env = np.abs(voice)
win = int(0.25 * SR)
env = np.convolve(env, np.ones(win) / win, mode="same")
duck = 1 - 0.5 * np.clip(env / 0.05, 0, 1)
t_all = np.arange(N) / SR
fade = np.clip((DUR - 0.3 - t_all) / 1.8, 0, 1) * np.clip(t_all / 0.4, 0, 1)
mus_gain = 0.16 * duck * fade
musL *= mus_gain
musR *= mus_gain

# ---------- эффекты ----------
def s_pop():
    t = tt(0.12)
    f = 380 + 700 * (t / 0.12)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.045) * 0.7


def s_step():
    t = tt(0.09)
    n = rng.standard_normal(len(t)).astype(np.float32)
    n = onepole_lp(n, np.full(len(n), 0.08, np.float32))
    return (n * 3 + 0.5 * np.sin(2 * np.pi * 95 * t)) * np.exp(-t / 0.025)


def bell(f, d=1.4):
    t = tt(d)
    s = np.zeros_like(t)
    for k, (m, a, dec) in enumerate([(1, 1, .9), (2.0, .35, .5), (3.01, .2, .3), (4.17, .12, .2), (5.43, .08, .12)]):
        s += a * np.sin(2 * np.pi * f * m * t) * np.exp(-t / dec)
    return s * np.minimum(1, t / 0.002)


def s_ding():
    out = np.zeros(int(1.8 * SR))
    for k, n in enumerate([72, 76, 79, 84]):
        place(out, bell(mtof(n + 12)) * 0.35, k * 0.09)
    return out


def s_coin():
    t1, t2 = tt(0.07), tt(0.35)
    a = np.sign(np.sin(2 * np.pi * 1318 * t1)) * 0.25 * np.exp(-t1 / 0.05)
    b = np.sign(np.sin(2 * np.pi * 1760 * t2)) * 0.25 * np.exp(-t2 / 0.1)
    s = np.concatenate([a, b])
    return onepole_lp(s.astype(np.float32), np.full(len(s), 0.3, np.float32))


def s_whoosh():
    d = 0.6
    t = tt(d)
    n = rng.standard_normal(len(t)).astype(np.float32)
    a = (0.02 + 0.25 * np.sin(np.pi * t / d) ** 2).astype(np.float32)
    s = onepole_lp(n, a)
    return s * np.sin(np.pi * t / d) ** 2 * 0.9


def s_stamp():
    t = tt(0.25)
    n = rng.standard_normal(len(t)).astype(np.float32)
    n = onepole_lp(n, np.full(len(n), 0.12, np.float32))
    f = 70 + 80 * np.exp(-t / 0.02)
    return (np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.9 + n * 2) * np.exp(-t / 0.06)


def s_tick():
    t = tt(0.1)
    return (np.sin(2 * np.pi * 1900 * t) * np.exp(-t / 0.02) * 0.5 + np.sin(2 * np.pi * 2850 * t) * np.exp(-t / 0.012) * 0.3)


def s_blip():
    t = tt(0.12)
    f = np.where(t < 0.05, 880, 1320)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.05) * 0.45


def s_sparkle():
    out = np.zeros(int(1.2 * SR))
    for k in range(7):
        n = [84, 88, 91, 96, 91, 96, 100][k]
        place(out, bell(mtof(n), 0.5) * 0.12, k * 0.06 + rng.random() * 0.02)
    return out


def s_fanfare():
    out = np.zeros(int(2.2 * SR))
    for k, n in enumerate([60, 64, 67, 72]):
        place(out, marimba(mtof(n + 12), 1.0) * 0.6, k * 0.11)
        place(out, bell(mtof(n + 24), 1.0) * 0.15, k * 0.11)
    for k, n in enumerate([72, 76, 79]):
        place(out, marimba(mtof(n + 12), 1.2) * 0.5, 0.5)
        place(out, bell(mtof(n + 12), 1.4) * 0.15, 0.5)
    place(out, s_sparkle(), 0.55, 1.2)
    return out


SFX = dict(pop=s_pop(), step=s_step(), ding=s_ding(), coin=s_coin(), whoosh=s_whoosh(), stamp=s_stamp(),
           tick=s_tick(), blip=s_blip(), sparkle=s_sparkle(), fanfare=s_fanfare())
fxL = np.zeros(N, np.float32)
fxR = np.zeros(N, np.float32)
for e in sfx:
    sig = SFX[e["type"]].astype(np.float32)
    pan = (rng.random() - 0.5) * 0.3
    place(fxL, sig, e["t"], 0.32 * e["gain"] * (1 - pan))
    place(fxR, sig, e["t"], 0.32 * e["gain"] * (1 + pan))

# ---------- сведение ----------
L = voice + musL + fxL
R = voice + musR + fxR
st = np.stack([L, R], axis=1)
st = st / np.abs(st).max() * 0.89
pcm = (st * 32767).astype(np.int16)
with wave.open(os.path.join(BUILD, "audio.wav"), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
rms = lambda x: 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-9)
print(f"audio.wav: {DUR:.1f} с; голос {rms(voice):.1f} dB, музыка {rms(musL):.1f} dB, эффекты {rms(fxL):.1f} dB")
