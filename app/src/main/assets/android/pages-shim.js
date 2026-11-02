/*
 * The extension's Logs and Later pages talk to its background script through `browser.runtime`.
 * In the app they talk to the app instead, over `otaatPages`, a channel the app opens for its own
 * local pages only (https://appassets.androidplatform.net).
 */
(function () {
  'use strict';
  const port = window.otaatPages;
  let seq = 0;
  const pending = new Map();
  if (port) {
    port.onmessage = (ev) => {
      let m;
      try {
        m = JSON.parse(ev.data);
      } catch (_) {
        return;
      }
      const p = m && pending.get(m.re);
      if (!p) return;
      pending.delete(m.re);
      p(m.result);
    };
  }
  function sendMessage(msg) {
    return new Promise((resolve) => {
      if (!port) return resolve({ error: 'Open this page from the Funnel app.' });
      const id = ++seq;
      pending.set(id, resolve);
      port.postMessage(JSON.stringify({ id, msg }));
    });
  }
  window.browser = {
    runtime: {
      sendMessage,
      getPlatformInfo: async () => ({ os: 'android' }),
      getURL: (p) => `/app/${p}`,
      onMessage: { addListener() {} },
    },
    storage: {
      local: {
        get: async () => sendMessage({ type: 'settings.get' }),
        set: async (v) => sendMessage({ type: 'settings.set', settings: v && v.settings }),
      },
      onChanged: { addListener() {} },
    },
  };
  // Copy works through the app (the web clipboard isn't available to local pages in a WebView).
  try {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: async (text) => {
          const r = await sendMessage({ type: 'clipboard', text: String(text) });
          if (!r || !r.ok) throw new Error((r && r.error) || 'could not copy');
        },
      },
    });
  } catch (_) {
    /* keep the browser's own */
  }
})();
