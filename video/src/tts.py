"""Озвучка фраз через RHVoice и расчёт таймлайна -> build/timeline.json + build/voice/*.wav"""
import json, os, subprocess, sys, wave
sys.path.insert(0, os.path.dirname(__file__))
from script import SCENES

VOICE = os.environ.get("VOICE", "irina")
BUILD = os.path.join(os.path.dirname(__file__), "..", "build")
os.makedirs(os.path.join(BUILD, "voice"), exist_ok=True)

LEAD = 0.6       # пауза перед первой фразой сцены
GAP = 0.35       # пауза между фразами
TAIL = {"title": 0.9, "walk": 0.8, "goal": 1.2, "sync": 1.0, "pay": 1.0, "safe": 0.9, "start": 1.0, "outro": 2.8}

t = 0.0
timeline = []
for name, chunks in SCENES:
    scene = dict(name=name, start=round(t, 3), chunks=[])
    t += LEAD
    for i, c in enumerate(chunks):
        path = os.path.join(BUILD, "voice", f"{name}_{i}.wav")
        subprocess.run(["RHVoice-test", "-p", VOICE, "-r", "92", "-R", "44100", "-o", path],
                       input=c.get("say", c["cap"]).encode(), check=True, capture_output=True)
        with wave.open(path) as w:
            d = w.getnframes() / w.getframerate()
        scene["chunks"].append(dict(cap=c["cap"], start=round(t, 3), end=round(t + d, 3), wav=os.path.basename(path)))
        t += d + GAP
    t += TAIL[name] - GAP
    scene["end"] = round(t, 3)
    timeline.append(scene)

out = dict(duration=round(t, 3), scenes=timeline)
with open(os.path.join(BUILD, "timeline.json"), "w") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
for s in timeline:
    print(f"{s['name']:6s} {s['start']:6.2f} – {s['end']:6.2f}  ({s['end']-s['start']:.1f} с)")
print("Итого:", out["duration"], "с")
