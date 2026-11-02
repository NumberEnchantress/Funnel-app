/*
 * Stands in for Firefox's `browser` API inside the app's WebView. Everything goes over `port`, the
 * message channel the app adds to YouTube's pages only (see MainActivity). Requests carry an id and get
 * one answer; the app can also push messages (settings changes, button presses).
 *
 * Runs inside the bundle wrapper (tools/bundle.js), which defines `port`, `TOKEN` and
 * `OTAAT_INITIAL_SETTINGS`. Every message carries TOKEN, a secret the app puts into this script; page
 * scripts can't read it (this script runs first, in its own closure), so they can't use the channel.
 */
const browser = (() => {
  let seq = 0;
  const pending = new Map();
  const storageListeners = [];
  const messageListeners = [];

  // addEventListener, not onmessage: a page script can't replace it.
  port.addEventListener('message', (ev) => {
    let m;
    try {
      m = JSON.parse(ev.data);
    } catch (_) {
      return;
    }
    if (!m || typeof m !== 'object') return;
    if (m.re !== undefined) {
      const p = pending.get(m.re);
      if (!p) return;
      pending.delete(m.re);
      clearTimeout(p.timer);
      p.resolve(m.result);
      return;
    }
    const list = m.type === 'storage' ? storageListeners : m.type === 'message' ? messageListeners : [];
    for (const fn of list) {
      try {
        if (m.type === 'storage') fn(m.changes || {}, 'local');
        else fn(m.message || {}, {});
      } catch (e) {
        console.warn('[Funnel]', e);
      }
    }
  });

  const uid = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`);

  function request(msg, timeout = 20000) {
    if (msg && msg.type === 'log' && Array.isArray(msg.entries)) {
      // Each entry gets an id once (kept on the entry, so a resend reuses it): the app stores it at most once.
      for (const e of msg.entries) if (e && !e.uid) e.uid = uid();
      // A log write is never given up on: an answer of "failed" while it was really still queued would let
      // the user press Next again and log it twice.
      timeout = 0;
    }
    return new Promise((resolve) => {
      const id = ++seq;
      const timer = timeout
        ? setTimeout(() => {
            pending.delete(id);
            resolve({ error: 'The app did not answer' });
          }, timeout)
        : null;
      pending.set(id, { resolve, timer });
      try {
        port.postMessage(JSON.stringify({ id, token: TOKEN, msg }));
      } catch (e) {
        clearTimeout(timer);
        pending.delete(id);
        resolve({ error: String((e && e.message) || e) });
      }
    });
  }

  /** One-way message (no answer expected). */
  function post(msg) {
    try {
      port.postMessage(JSON.stringify({ token: TOKEN, msg }));
    } catch (_) {
      /* the page is going away */
    }
  }

  // Tells the app this page is ready to receive its messages.
  post({ type: 'hello', url: location.href });

  return {
    post,
    runtime: {
      sendMessage: (msg) => request(msg),
      onMessage: { addListener: (fn) => messageListeners.push(fn) },
    },
    storage: {
      local: {
        async get() {
          if (OTAAT_INITIAL_SETTINGS) return { settings: OTAAT_INITIAL_SETTINGS };
          const r = await request({ type: 'settings.get' });
          return { settings: r && r.settings };
        },
        async set() {
          /* settings are changed in the app's own Settings page */
        },
      },
      onChanged: { addListener: (fn) => storageListeners.push(fn) },
    },
  };
})();
