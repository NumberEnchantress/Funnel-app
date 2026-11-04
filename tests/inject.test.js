'use strict';
/*
 * The script the app injects into YouTube (app/src/main/assets/inject/youtube.js), run in jsdom on the
 * extension's captured m.youtube.com markup, with a fake app on the other end of the message channel.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { JSDOM } = require('jsdom');

const ANDROID = path.join(__dirname, '..');
const FIXTURES = path.join(ANDROID, 'tests', 'fixtures'); // m-youtube-grid.html: captured from the extension's tests
const BUNDLE = fs.readFileSync(path.join(ANDROID, 'app', 'src', 'main', 'assets', 'inject', 'youtube.js'), 'utf8');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function until(fn, message, timeout = 4000) {
  const end = Date.now() + timeout;
  for (;;) {
    const v = fn();
    if (v) return v;
    if (Date.now() > end) throw new Error(`Timed out waiting for: ${message}`);
    await sleep(25);
  }
}

/** A fake app: answers like Bridge.java and records everything the page sent. */
function fakeApp({ sorted = [] } = {}) {
  const app = { sent: [], logged: [], sorted: new Set(sorted), queue: [], ui: null, toasts: [] };
  const listeners = [];
  const deliver = (data) => listeners.forEach((fn) => fn({ data }));
  app.token = 'test-token-123';
  app.port = {
    addEventListener: (type, fn) => type === 'message' && listeners.push(fn),
    postMessage(text) {
      const { id, msg, token } = JSON.parse(text);
      if (token !== app.token) {
        app.rejected = (app.rejected || 0) + 1; // what Bridge.java does: ignore it
        return;
      }
      app.sent.push(msg);
      let result = { ok: true };
      switch (msg.type) {
        case 'log':
          for (const e of msg.entries) {
            app.logged.push(e);
            if (e.action !== 'seen') app.sorted.add(e.item.key);
          }
          result = { stored: msg.entries.length, handled: msg.entries.length, counts: { seen: 0, skipped: 0, deferred: 0, queued: 0 } };
          break;
        case 'filterSorted':
          result = { sorted: msg.keys.filter((k) => app.sorted.has(k)) };
          break;
        case 'counts':
          result = { counts: { seen: 0, skipped: 0, deferred: 0, queued: 0 } };
          break;
        case 'queue.add':
          app.queue.push(msg.item);
          result = { ok: true, size: app.queue.length };
          break;
        case 'queue.ended':
          result = { played: false };
          break;
        case 'ui.update':
          app.ui = msg.state;
          break;
        case 'ui.toast':
          app.toasts.push(msg.text);
          break;
        default:
          break;
      }
      if (id !== undefined) setTimeout(() => deliver(JSON.stringify({ re: id, result })), app.delay || 1);
    },
  };
  /** The app pushing a message to the page (bar button, settings change). */
  app.push = (m) => deliver(JSON.stringify(m));
  app.press = (name) => app.push({ type: 'message', message: { type: 'command', name } });
  return app;
}

// Work still in flight in a page a test already closed is not a failure (as in the extension's tests).
process.on('unhandledRejection', (e) => {
  if (!/is not defined|reading 'body'|reading 'documentElement'|reading 'querySelector'/.test(String(e && e.message))) throw e;
});

const pages = [];
function page({ url = 'https://m.youtube.com/', body = fs.readFileSync(path.join(FIXTURES, 'm-youtube-grid.html'), 'utf8'), settings = null, sorted, withPort = true } = {}) {
  const dom = new JSDOM(`<!doctype html><html><head></head><body>${body}</body></html>`, {
    url,
    runScripts: 'outside-only',
    pretendToBeVisual: true,
  });
  const { window } = dom;
  const app = fakeApp({ sorted });
  if (withPort) window.otaatNative = app.port;
  window.scrollTo = () => {};
  window.document.elementsFromPoint = () => [];
  window.__otaatTestHook = (O) => (window.__engine = O);
  window.IntersectionObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  window.eval(BUNDLE.replace('/*@SETTINGS@*/null', JSON.stringify(settings)).replace("'@TOKEN@'", `'${app.token}'`));
  pages.push(window);
  return { window, document: window.document, app };
}

test.afterEach(() => {
  while (pages.length) {
    const w = pages.pop();
    if (w.__engine && w.__engine.engine) w.__engine.engine.stop();
    w.close();
  }
});

const current = (doc) => doc.querySelectorAll('[otaat-current]');

test('the bundle is built from the current sources', () => {
  execFileSync(process.execPath, [path.join(ANDROID, 'tools', 'bundle.js'), '--check'], { stdio: 'pipe' });
});

test('nothing runs without the app\'s message channel (other sites, other frames)', () => {
  const { document } = page({ withPort: false });
  assert.equal(document.documentElement.getAttribute('otaat-q'), null);
  assert.equal(document.querySelector('style[data-otaat]'), null);
});

test('home: the hiding rules and flags are in place at once, before any item is picked', () => {
  const { document } = page();
  assert.ok(document.querySelector('style[data-otaat]'), 'hide.css injected');
  assert.equal(document.documentElement.getAttribute('otaat-q'), 'home');
  assert.equal(document.documentElement.getAttribute('otaat-mode'), 'collapse');
});

test('home: one video at a time; Next is logged and moves on; every shown item is logged as seen', async () => {
  const { document, app } = page();
  await until(() => current(document).length === 1, 'a current video');
  const items = [...document.querySelectorAll('ytm-rich-item-renderer')];
  assert.ok(items[0].hasAttribute('otaat-current'));
  await until(() => app.logged.some((e) => e.action === 'seen'), 'seen logged');
  await until(() => app.ui && app.ui.visible && app.ui.nextEnabled, 'the app bar enabled');
  assert.equal(app.ui.showQueue, true, 'Queue offered (the app has its own Up next)');
  app.press('next');
  await until(() => items[1].hasAttribute('otaat-current'), 'Next moves on');
  assert.equal(current(document).length, 1);
  const skipped = app.logged.find((e) => e.action === 'skipped');
  assert.equal(skipped.item.key, 'youtube:NiCgcUTL9_Q');
  assert.equal(skipped.item.url, 'https://www.youtube.com/watch?v=NiCgcUTL9_Q', 'desktop-style links, as in the extension');
  await until(() => app.logged.filter((e) => e.action === 'seen').length === 2, 'second item seen');
});

test('items sorted before (here or on another device) are skipped', async () => {
  const { document, app } = page({ sorted: ['youtube:NiCgcUTL9_Q'] });
  await until(() => current(document).length === 1, 'a current video');
  const items = [...document.querySelectorAll('ytm-rich-item-renderer')];
  assert.ok(!items[0].hasAttribute('otaat-current'));
  assert.ok(items[1].hasAttribute('otaat-current'));
  assert.ok(!app.logged.some((e) => e.item.key === 'youtube:NiCgcUTL9_Q'), 'the sorted one was never shown or logged');
});

test('an item sorted on another device while on screen leaves the feed (sortedPing)', async () => {
  const { document, app } = page();
  await until(() => current(document).length === 1, 'a current video');
  const items = [...document.querySelectorAll('ytm-rich-item-renderer')];
  app.sorted.add('youtube:NiCgcUTL9_Q');
  app.push({ type: 'storage', changes: { sortedPing: { newValue: { keys: ['youtube:NiCgcUTL9_Q'], ts: Date.now() } } } });
  await until(() => items[1].hasAttribute('otaat-current'), 'moved on');
});

test('Queue adds to the app\'s Up next, is logged as queued, and moves on', async () => {
  const { document, app } = page();
  await until(() => current(document).length === 1 && app.ui && app.ui.queueEnabled, 'ready');
  const items = [...document.querySelectorAll('ytm-rich-item-renderer')];
  app.press('queue');
  await until(() => app.logged.some((e) => e.action === 'queued'), 'queued logged');
  assert.equal(app.queue.length, 1);
  assert.equal(app.queue[0].videoId, 'NiCgcUTL9_Q');
  await until(() => items[1].hasAttribute('otaat-current'), 'moves on');
});

test('the user\'s settings are used before the page is parsed (e.g. a paused site is not hidden)', async () => {
  const { document } = page({ settings: { paused: { youtube: true } } });
  await sleep(0); // one microtask turn: the app's settings arrive as an already-resolved promise
  assert.equal(document.documentElement.getAttribute('otaat-q'), null);
});

test('a settings change from the app is applied to the open page', async () => {
  const { document, app } = page();
  await until(() => current(document).length === 1, 'a current video');
  app.push({ type: 'storage', changes: { settings: { newValue: { paused: { youtube: true } } } } });
  await until(() => !document.documentElement.hasAttribute('otaat-q'), 'feed released');
});

test('end of a video: YouTube never hears it (no autoplay of a recommendation); Up next is asked', async () => {
  const { window, app } = page({ url: 'https://m.youtube.com/watch?v=NiCgcUTL9_Q', body: '<div id="movie_player" class="html5-video-player"><video></video></div>' });
  const video = window.document.querySelector('video');
  let heard = 0;
  video.addEventListener('ended', () => heard++);
  video.dispatchEvent(new window.Event('ended'));
  assert.equal(heard, 0, 'the player\'s own "ended" listener was not reached');
  await until(() => app.sent.some((m) => m.type === 'queue.ended'), 'Up next asked');
});

test('end of a video in a playlist, or of an ad: YouTube carries on as normal', () => {
  const pl = page({ url: 'https://m.youtube.com/watch?v=NiCgcUTL9_Q&list=PL123', body: '<div id="movie_player"><video></video></div>' });
  let heard = 0;
  const v1 = pl.document.querySelector('video');
  v1.addEventListener('ended', () => heard++);
  v1.dispatchEvent(new pl.window.Event('ended'));
  assert.equal(heard, 1);
  const ad = page({ url: 'https://m.youtube.com/watch?v=NiCgcUTL9_Q', body: '<div id="movie_player" class="ad-showing"><video></video></div>' });
  const v2 = ad.document.querySelector('video');
  v2.addEventListener('ended', () => heard++);
  v2.dispatchEvent(new ad.window.Event('ended'));
  assert.equal(heard, 2);
});

test('the page never adds anything to the global scope except the bridge flags', () => {
  const { window } = page();
  for (const name of ['OTAAT', 'OTAAT_SETTINGS', 'browser']) assert.equal(window[name], undefined, name);
});

test('the message channel is hidden from page scripts, and messages without the secret are refused', async () => {
  const { window, document, app } = page();
  assert.equal(window.otaatNative, undefined, 'removed from the page once the app script has it');
  await until(() => current(document).length === 1, 'a current video');
  // A page script that kept a reference can't log, queue or open anything without the token.
  app.port.postMessage(JSON.stringify({ id: 1, msg: { type: 'log', entries: [{ action: 'skipped', item: { key: 'youtube:forged' } }] } }));
  assert.equal(app.rejected, 1);
  assert.ok(!app.logged.some((e) => e.item.key === 'youtube:forged'));
});

test('every log entry carries a unique id, kept when it is sent again', async () => {
  const { document, app } = page();
  await until(() => current(document).length === 1 && app.ui && app.ui.nextEnabled, 'ready');
  app.press('next');
  await until(() => app.logged.some((e) => e.action === 'skipped'), 'skipped logged');
  const uids = app.logged.map((e) => e.uid);
  assert.ok(uids.every(Boolean));
  assert.equal(new Set(uids).size, uids.length);
});

test('a slow answer to Next is waited for, never reported as failed (which would invite a second Next)', async () => {
  const { document, app } = page();
  await until(() => current(document).length === 1 && app.ui && app.ui.nextEnabled, 'ready');
  const items = [...document.querySelectorAll('ytm-rich-item-renderer')];
  app.delay = 1500;
  app.press('next');
  await until(() => items[1].hasAttribute('otaat-current'), 'moved on after the slow answer', 6000);
  assert.ok(!app.toasts.some((t) => /Could not write/.test(t)));
  assert.equal(app.logged.filter((e) => e.action === 'skipped').length, 1);
});

test('every script the app ships parses (a broken page script fails silently on the phone)', () => {
  const assets = path.join(ANDROID, 'app', 'src', 'main', 'assets');
  const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) => (d.isDirectory() ? walk(path.join(dir, d.name)) : [path.join(dir, d.name)]));
  const scripts = walk(assets).filter((f) => f.endsWith('.js'));
  assert.ok(scripts.length >= 8);
  for (const f of scripts) execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
});
