/*
 * Frame-by-frame check (run in the app's YouTube page with tools/cdp.js --file tools/frames.js).
 * Counts, on every animation frame, the feed items actually visible on screen, while it opens the
 * current item, goes back, and switches channel tabs. On a one-at-a-time feed the answer must never be > 1.
 */
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const TILE = 'ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-reel-item-renderer, ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2';
  const visibleTiles = () => {
    const out = [];
    for (const el of document.querySelectorAll(TILE)) {
      if (el.parentElement && el.parentElement.closest(TILE)) continue; // count the outer tile once
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2 || r.bottom <= 0 || r.top >= innerHeight) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || Number(cs.opacity) < 0.05) continue;
      out.push(el);
    }
    return out;
  };
  const log = [];
  let phase = 'start';
  let running = true;
  const frame = () => {
    if (!running) return;
    const tiles = visibleTiles();
    const feed = tiles.filter((t) => t.closest('ytm-rich-grid-renderer'));
    log.push({ phase, path: location.pathname, feed: feed.length, all: tiles.length });
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  const step = async (name, fn, wait = 4000) => {
    phase = name;
    fn();
    await sleep(wait);
  };

  await step('settle', () => {}, 1500);
  await step('open item', () => {
    const a = document.querySelector('[otaat-current] a[href*="/watch"]');
    if (a) a.click();
  });
  await step('back', () => history.back(), 5000);
  await step('next', () => {}, 500);
  await step('tab: shorts', () => {
    const tab = [...document.querySelectorAll('a, [role="tab"]')].find((e) => /\/shorts$/.test(e.getAttribute('href') || '') || e.textContent.trim() === 'Shorts' && e.closest('ytm-pivot-bar-renderer, [role="tablist"], .tab-bar, ytm-tab-renderer, yt-tab-shape'));
    if (tab) tab.click();
  });
  await step('tab: videos', () => {
    const tab = [...document.querySelectorAll('a, [role="tab"]')].find((e) => /\/videos$/.test(e.getAttribute('href') || '') || e.textContent.trim() === 'Videos');
    if (tab) tab.click();
  });
  await step('back again', () => history.back(), 4000);
  await step('forward', () => history.forward(), 4000);
  running = false;

  const phases = {};
  for (const f of log) {
    const p = (phases[f.phase] = phases[f.phase] || { frames: 0, maxFeed: 0, maxAll: 0, paths: new Set() });
    p.frames++;
    p.maxFeed = Math.max(p.maxFeed, f.feed);
    p.maxAll = Math.max(p.maxAll, f.all);
    p.paths.add(f.path);
  }
  for (const p of Object.values(phases)) p.paths = [...p.paths];
  const bad = log.filter((f) => f.feed > 1).length;
  return JSON.stringify({ framesTotal: log.length, framesWithMoreThanOneFeedItem: bad, phases }, null, 1);
})();
