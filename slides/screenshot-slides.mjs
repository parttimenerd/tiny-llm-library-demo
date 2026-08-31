import { chromium } from 'playwright-chromium';
import { mkdir } from 'fs/promises';
import { existsSync } from 'fs';

const OUT = '/tmp/slide-screenshots';
await mkdir(OUT, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage();
await page.setViewportSize({ width: 1280, height: 720 });

// Get total slide count from the overview page
await page.goto('http://localhost:3030/overview/', { waitUntil: 'networkidle' });
const total = await page.locator('.slidev-overview-slide').count();
console.log(`Total slides: ${total}`);

for (let i = 1; i <= total; i++) {
  await page.goto(`http://localhost:3030/${i}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(800); // let images/fonts settle
  const pad = String(i).padStart(3, '0');
  await page.screenshot({ path: `${OUT}/slide-${pad}.png`, fullPage: false });
  if (i % 5 === 0) console.log(`  ${i}/${total}`);
}

await browser.close();
console.log(`Done — screenshots in ${OUT}`);
