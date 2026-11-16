# Мультик «Шагомер»

`shagomer-multik.mp4` — объясняющий мультик (87 с, 1080×1920, озвучка на русском, субтитры).

Пересборка (нужны RHVoice с русским голосом, ffmpeg, Node + Playwright, Python + numpy):

```
python3 src/tts.py            # озвучка фраз из src/script.py и таймлайн
node src/render.js video 30 4 # кадры из src/cartoon.html -> build/seg*.mp4 и build/sfx.json
python3 src/audio.py          # голос + музыка + эффекты -> build/audio.wav
cd build && ffmpeg -f concat -safe 0 -i segs.txt -c copy video_only.mp4 && \
  ffmpeg -i video_only.mp4 -i audio.wav -c:v copy -af loudnorm=I=-16:TP=-1.5 -c:a aac -b:a 192k -shortest ../shagomer-multik.mp4
```

Текст фраз меняется в `src/script.py`, картинка — в `src/cartoon.html`.
