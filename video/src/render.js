// Рендер мультика: node render.js stills t1,t2,...  |  node render.js video [fps] [workers]
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path'), { spawn } = require('child_process');
const ROOT = path.resolve(__dirname, '..'), BUILD = path.join(ROOT, 'build');
const TL = JSON.parse(fs.readFileSync(path.join(BUILD, 'timeline.json'), 'utf8'));
const HTML = 'file://' + path.join(__dirname, 'cartoon.html');

async function openPage(browser) {
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  page.on('pageerror', e => { console.error('PAGE ERROR', e); process.exit(1); });
  await page.addInitScript(tl => { window.TL = tl; }, TL);
  await page.goto(HTML);
  await page.evaluate(async () => { await document.fonts.load('800 100px Inter'); await document.fonts.ready; });
  return page;
}

(async () => {
  const mode = process.argv[2] || 'video';
  const browser = await chromium.launch();
  if (mode === 'stills') {
    const times = process.argv[3].split(',').map(Number);
    const dir = path.join(BUILD, 'stills'); fs.mkdirSync(dir, { recursive: true });
    const page = await openPage(browser);
    for (const t of times) {
      await page.evaluate(t => window.draw(t), t);
      await page.screenshot({ path: path.join(dir, `t${t.toFixed(2)}.png`) });
    }
  } else {
    const fps = +(process.argv[3] || 30), W = +(process.argv[4] || 4);
    const N = Math.ceil(TL.duration * fps), per = Math.ceil(N / W);
    {
      const page = await openPage(browser);
      fs.writeFileSync(path.join(BUILD, 'sfx.json'), JSON.stringify(await page.evaluate(() => window.buildSfx()), null, 0));
      await page.close();
    }
    const t0 = Date.now();
    let done = 0;
    await Promise.all([...Array(W)].map(async (_, wi) => {
      const a = wi * per, b = Math.min(N, (wi + 1) * per);
      const page = await openPage(browser);
      const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'png', '-i', '-',
        '-c:v', 'libx264', '-preset', 'medium', '-crf', '19', '-pix_fmt', 'yuv420p', '-r', String(fps), path.join(BUILD, `seg${wi}.mp4`)], { stdio: ['pipe', 'inherit', 'inherit'] });
      for (let f = a; f < b; f++) {
        await page.evaluate(t => window.draw(t), f / fps);
        const buf = await page.screenshot({ type: 'png' });
        if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
        if (++done % 150 === 0) console.log(`${done}/${N} кадров, ${((Date.now() - t0) / 1000).toFixed(0)} с`);
      }
      ff.stdin.end();
      await new Promise(r => ff.on('close', r));
    }));
    fs.writeFileSync(path.join(BUILD, 'segs.txt'), [...Array(W)].map((_, i) => `file 'seg${i}.mp4'`).join('\n') + '\n');
    console.log('Готово:', N, 'кадров');
  }
  await browser.close();
})();
