/* Capture actual browser-rendered prototype UI; never edits the resulting PNGs. */
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.AIYOU_PLAYWRIGHT_MODULE || 'playwright');
function option(name, fallback) { const i = process.argv.indexOf(name); return i < 0 ? fallback : process.argv[i + 1]; }
const url = option('--url');
const output = path.resolve(option('--out', 'docs/ui-review/2026-11-04/prototype'));
if (!url) throw Error('Pass --url pointing to the standalone rendered prototype.');
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true });
  const entries = [], errors = [];
  try {
    const context = await browser.newContext({ viewport: { width: 740, height: 1050 }, deviceScaleFactor: 2 });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    async function open(tab = 'now', theme = 'blue', width = 740) {
      await page.setViewportSize({ width, height: 1050 });
      await page.goto(url);
      const frame = page.frames().find(f => f !== page.mainFrame());
      await frame.locator('.phone').waitFor();
      await frame.evaluate(() => document.fonts.ready);
      await frame.locator('[data-action="appearance"]').click();
      await frame.locator(`[data-theme-choice="${theme}"]`).click();
      await frame.locator('[data-appearance-close]').click();
      if (await frame.locator('.phone').getAttribute('data-theme') !== theme) throw Error('Theme did not settle');
      if (tab !== 'now') await frame.locator(`[data-page="${tab}"]`).click();
      await page.waitForTimeout(600);
      return frame;
    }
    async function capture(frame, name, state) {
      await page.waitForTimeout(400);
      const phone = frame.locator('.phone');
      const bounds = await phone.boundingBox();
      const actualTheme = await phone.getAttribute('data-theme');
      if (actualTheme !== state.theme) throw Error('Screenshot theme mismatch: ' + name);
      await phone.screenshot({ path: path.join(output, name + '.png'), animations: 'disabled' });
      entries.push({ file: name + '.png', kind: 'browser-prototype', ...state, cssWidth: bounds.width,
        cssHeight: bounds.height, deviceScaleFactor: 2, font: await phone.evaluate(n => getComputedStyle(n).fontFamily) });
    }
    for (const [name, tab, theme] of [
      ['01-now-blue', 'now', 'blue'], ['02-memories-blue', 'memories', 'blue'],
      ['03-tasks-blue', 'tasks', 'blue'], ['04-now-sunset', 'now', 'sunset'],
      ['05-now-night', 'now', 'night'], ['06-memories-night', 'memories', 'night'],
      ['07-tasks-night', 'tasks', 'night'],
    ]) await capture(await open(tab, theme), name, { page: tab, theme });
    let frame = await open('now', 'night');
    await frame.locator('[data-action="appearance"]').click();
    await capture(frame, '08-appearance-picker', { page: 'appearance', theme: 'night' });
    frame = await open();
    await frame.locator('.composer > button').click();
    await frame.locator('textarea').fill('今天路过花店，带了一束洋桔梗。\n\n想把这些小事，慢慢记下来。');
    await capture(frame, '09-record-editor', { page: 'record-editor', theme: 'blue', keyboard: 'No Android IME in this browser capture' });
    frame = await open('memories');
    await frame.locator('[data-action="filters"]').click();
    await capture(frame, '10-memory-filters', { page: 'filters', theme: 'blue' });
    frame = await open('tasks');
    await frame.locator('[data-action="new-task"]').click();
    await frame.locator('textarea').fill('整理旅行照片');
    await capture(frame, '11-task-editor', { page: 'task-editor', theme: 'blue' });
    frame = await open('now', 'blue', 352);
    const narrow = await frame.locator('.phone').boundingBox();
    if (Math.abs(narrow.width - 320) > 1) throw Error('Unexpected narrow prototype width: ' + narrow.width);
    await capture(frame, '12-now-narrow-320', { page: 'now', theme: 'blue' });
    if (errors.length) throw Error(JSON.stringify(errors));
    fs.writeFileSync(path.join(output, 'capture.json'), JSON.stringify({ renderer: 'Chromium / Playwright',
      animations: 'Frozen only during PNG capture; static pictures do not prove motion quality',
      content: 'Synthetic demo records; Lulu text placeholder; no private reference image supplied',
      entries, consoleErrors: errors }, null, 2) + '\n');
    console.log(JSON.stringify({ images: entries.length, consoleErrors: errors, output }));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
