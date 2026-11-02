/*
 * App-only changes to the YouTube adapter (content/sites/youtube.js), for YouTube's phone site:
 *  - Queue: the phone site has no play queue, so the app keeps its own. Queued videos play one after
 *    another when a video ends (or from "Up next" in the app's bar).
 *  - Autoplay: when a video ends, YouTube's phone site starts a recommendation. That is stopped
 *    (unless you are playing a playlist you chose), and the app's queue plays instead, if it has anything.
 */
(function () {
  'use strict';
  const O = globalThis.OTAAT;
  const yt = O.adapters.youtube;

  Object.defineProperty(yt, 'hasQueue', { get: () => true, configurable: true });

  yt.queue = async function queue(el, info) {
    if (!this.isVideo(info)) return { ok: false, message: 'Only videos can be queued' };
    const r = await O.send('queue.add', { item: info });
    if (r && r.ok) return { ok: true, message: `Added to Up next (${r.size} waiting)` };
    return { ok: false, message: 'Could not add it to Up next' };
  };

  const isWatch = () => location.pathname === '/watch';
  const inPlaylist = () => !!new URLSearchParams(location.search).get('list');
  const adPlaying = () => {
    const player = document.querySelector('#movie_player, .html5-video-player');
    return !!player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
  };

  // Capture phase on window runs before the player's own listeners on the <video> element,
  // so YouTube never hears that the video ended and doesn't move on to a recommendation.
  window.addEventListener(
    'ended',
    (e) => {
      if (!(e.target instanceof HTMLVideoElement) || !isWatch()) return;
      if (inPlaylist() || adPlaying()) return; // a playlist you chose carries on; an ad hands over to the video
      if (O.settings.hide.ytAutoplay) e.stopImmediatePropagation();
      O.send('queue.ended', { videoId: new URLSearchParams(location.search).get('v') || '' });
    },
    true
  );

  // Backup: switch YouTube's own autoplay toggle off on watch pages. At most twice per video, never during
  // an ad, so it never fights you if you turn it back on.
  const guard = { video: '', clicks: 0 };
  setInterval(() => {
    if (!isWatch() || !O.settings.hide.ytAutoplay || document.hidden) return;
    if (adPlaying()) return;
    // m.youtube.com (2026): <button class="ytwAutonavToggleButtonHost" aria-pressed="true" aria-label="Autoplay is on">
    const btn = document.querySelector(
      'button[class*="AutonavToggleButton"][aria-pressed="true"], button.ytm-autonav-toggle-button-container[aria-pressed="true"], .ytp-autonav-toggle-button[aria-checked="true"]'
    );
    if (!btn) return;
    const video = new URLSearchParams(location.search).get('v') || '';
    if (video !== guard.video) {
      guard.video = video;
      guard.clicks = 0;
    }
    if (guard.clicks < 2) {
      (btn.closest('button') || btn).click();
      guard.clicks++;
    }
  }, 1500);
})();
