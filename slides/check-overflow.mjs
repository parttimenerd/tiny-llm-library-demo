/**
 * Overflow checker for Slidev slides.
 * Visits each slide at 1280x720 and checks whether any content overflows
 * the slide viewport. Screenshots problematic slides.
 */

import { chromium } from 'playwright';
import { mkdirSync } from 'fs';

const BASE = 'http://localhost:3099';
const TOTAL = 87;
const OUT = '/tmp/overflow-check';
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage();
await page.setViewportSize({ width: 1280, height: 720 });

const overflows = [];

for (let i = 1; i <= TOTAL; i++) {
  await page.goto(`${BASE}/${i}`, { waitUntil: 'networkidle', timeout: 10000 }).catch(() => {});

  // Wait for Slidev slide container to render
  await page.waitForSelector('.slidev-slide-content, .slide-content, [class*="slidev"]', { timeout: 3000 }).catch(() => {});
  await page.waitForTimeout(400);

  const result = await page.evaluate(() => {
    // The slide viewport is 1280x720; the slide is scaled inside .slidev-slide-container
    // We look for any element that overflows its parent significantly
    const vw = 1280;
    const vh = 720;

    // Check the main slide element
    const slideEl = document.querySelector('.slidev-slide') || document.querySelector('[class*="slide-content"]');
    if (!slideEl) return { overflow: false, reason: 'no slide element found' };

    const rect = slideEl.getBoundingClientRect();

    // Check scrollHeight > clientHeight on the slide itself
    const scrollOverflow = slideEl.scrollHeight > slideEl.clientHeight + 2 ||
                           slideEl.scrollWidth > slideEl.clientWidth + 2;

    // Check if any direct children visually extend below the fold
    let worstBottom = 0;
    let worstEl = null;
    const all = slideEl.querySelectorAll('*');
    for (const el of all) {
      const r = el.getBoundingClientRect();
      if (r.bottom > worstBottom && r.width > 0 && r.height > 0) {
        worstBottom = r.bottom;
        worstEl = el.tagName + (el.className ? '.' + [...el.classList].join('.') : '');
      }
    }

    const visualOverflow = worstBottom > vh + 10;

    return {
      overflow: scrollOverflow || visualOverflow,
      scrollOverflow,
      visualOverflow,
      worstBottom: Math.round(worstBottom),
      worstEl,
      slideRect: { width: Math.round(rect.width), height: Math.round(rect.height) },
    };
  });

  if (result.overflow) {
    const file = `${OUT}/overflow-slide-${String(i).padStart(3,'0')}.png`;
    await page.screenshot({ path: file, fullPage: false });
    overflows.push({ slide: i, ...result, screenshot: file });
    console.log(`OVERFLOW  slide ${i}: bottom=${result.worstBottom}px, el=${result.worstEl}`);
  } else {
    process.stdout.write(`ok ${i} `);
  }
}

await browser.close();

console.log('\n\n=== SUMMARY ===');
if (overflows.length === 0) {
  console.log('No overflows detected across all slides.');
} else {
  console.log(`${overflows.length} slide(s) with overflow:`);
  for (const o of overflows) {
    console.log(`  Slide ${o.slide}: worstBottom=${o.worstBottom}px  el=${o.worstEl}  screenshot=${o.screenshot}`);
  }
}
