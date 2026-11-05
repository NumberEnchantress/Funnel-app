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

  // Autoplay off at the source (YouTube's PREF cookie, f5=30000) is done by the extension's adapter
  // (content/sites/youtube.js autoplayOffInPrefs, since 1.1.5) as each page is set up.

  // YouTube's switch itself is turned off by the extension's own guard (yt-main.js guardTick, since 1.1.5):
  // at most twice per video and never during an ad, so it never fights you.

  // ---------------------------------------------------------------- no empty screens (see js/app.css)

  // Watch pages: with the recommendations hidden, the video's own description fills the space under it.
  const URL_RE = /https?:\/\/[^\s<>"]+[^\s<>".,;:!?)\]'"]/g;

  function cardColors() {
    // The same look as YouTube's own comments card on this page (light or dark theme).
    const card = document.querySelector('yt-video-metadata-carousel-view-model, ytm-comments-entry-point-header-renderer');
    const meta = document.querySelector('yt-video-metadata-view-model, ytm-slim-video-metadata-section-renderer');
    const text = meta ? getComputedStyle(meta).color : '';
    let back = card ? getComputedStyle(card).backgroundColor : '';
    if (!back || back === 'rgba(0, 0, 0, 0)') {
      const dark = /rgb\((\d+)/.exec(text || '') && Number(/rgb\((\d+)/.exec(text)[1]) > 128;
      back = dark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.05)';
    }
    return { text, back };
  }

  function describe() {
    const old = document.querySelector('.otaat-description');
    const host = isWatch() && O.settings.hide.ytRelated && document.querySelector('ytm-single-column-watch-next-results-renderer');
    if (!host) {
      if (old) old.remove();
      return;
    }
    const video = new URLSearchParams(location.search).get('v') || '';
    if (old && old.dataset.video === video && host.contains(old)) return;
    let text = '';
    try {
      const details = document.querySelector('#movie_player').getPlayerResponse().videoDetails;
      if (details && details.videoId === video) text = String(details.shortDescription || '');
    } catch (_) {
      /* the player isn't ready yet: try again on the next tick */
    }
    if (old) old.remove();
    if (!text.trim()) return;
    const card = document.createElement('div');
    card.className = 'otaat-description';
    card.dataset.video = video;
    const { text: color, back } = cardColors();
    card.style.color = color;
    card.style.background = back;
    const title = document.createElement('h3');
    title.textContent = 'Description';
    card.append(title);
    // Plain text, with its links made clickable (they open like any link: YouTube here, others in the browser).
    let last = 0;
    for (const m of text.matchAll(URL_RE)) {
      card.append(document.createTextNode(text.slice(last, m.index)));
      const a = document.createElement('a');
      a.href = m[0];
      a.textContent = m[0];
      card.append(a);
      last = m.index + m[0].length;
    }
    card.append(document.createTextNode(text.slice(last)));
    host.append(card);
  }

  setInterval(() => {
    if (!document.hidden) describe();
  }, 500);
})();
