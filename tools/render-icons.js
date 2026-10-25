// Renders CleanFrame's white SVG marks to 240x240 PNGs for the Android app.
// Usage: node tools/render-icons.js <cleanframe/app/assets/icons> <out dir>
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const [src, out] = process.argv.slice(2);
const names = ['youtube', 'netflix', 'primevideo', 'apps', 'art', 'settings'];
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 240, height: 240 } });
  for (const n of names) {
    const svg = fs.readFileSync(path.join(src, n + '.svg'), 'utf8').replace('<svg ', '<svg width="240" height="240" ');
    await page.setContent('<html><body style="margin:0;background:transparent">' + svg + '</body></html>');
    await page.locator('svg').screenshot({ path: path.join(out, n + '.png'), omitBackground: true });
  }
  await browser.close();
})();
