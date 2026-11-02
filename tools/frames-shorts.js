/*
 * Shorts check (tools/cdp.js --file tools/frames-shorts.js): for 30 s, on every frame, how many Shorts
 * are on screen, and whether the page height changed. Press Next / Later on the app's bar meanwhile.
 * At rest exactly one Short must fill the screen; between Shorts, YouTube's own slide animation shows the
 * old one leaving as the new one arrives (as in the YouTube app), which is reported separately.
 */
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const frames = [];
  let running = true;
  const heights = new Set();
  const frame = () => {
    if (!running) return;
    heights.add(innerHeight);
    let mostly = 0;
    let any = 0;
    for (const el of document.querySelectorAll('.ytShortsCarouselCarouselItem')) {
      const r = el.getBoundingClientRect();
      const shown = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0);
      if (shown > 2) any++;
      if (shown > innerHeight * 0.25) mostly++;
    }
    frames.push({ any, mostly, path: location.pathname });
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  await sleep(30000);
  running = false;
  // "At rest": the frame and the 5 before it all had the same single Short.
  let restBad = 0;
  for (let i = 5; i < frames.length; i++) {
    const still = frames.slice(i - 5, i + 1).every((f) => f.path === frames[i].path && f.any === frames[i].any);
    if (still && frames[i].any !== 1) restBad++;
  }
  return JSON.stringify({
    frames: frames.length,
    shortsVisited: [...new Set(frames.map((f) => f.path))],
    framesWithTwoMostlyVisible: frames.filter((f) => f.mostly > 1).length,
    framesInSlideAnimation: frames.filter((f) => f.any > 1).length,
    restingFramesNotExactlyOne: restBad,
    pageHeightsSeen: [...heights],
  });
})();
