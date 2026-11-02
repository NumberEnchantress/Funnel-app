/*
 * The app draws its own button bar below the WebView, so this replaces the extension's in-page bar
 * (content/ui.js): it only forwards the engine's bar state and toasts to the app. Nothing is drawn in
 * the page, so nothing can overlap YouTube's own controls, and page scripts can't press the buttons.
 */
(function () {
  'use strict';
  const O = globalThis.OTAAT;
  let state = { visible: false };
  let lastSent = '';

  O.ui = {
    onAction() {
      /* button presses arrive as 'command' messages, handled by the engine */
    },

    update(next) {
      state = { ...state, ...next };
      const json = JSON.stringify(state);
      if (json === lastSent) return;
      lastSent = json;
      browser.post({ type: 'ui.update', state });
    },

    toast(text, kind = 'info', ms = 2800) {
      browser.post({ type: 'ui.toast', text: String(text), kind: String(kind), ms: Number(ms) || 2800 });
    },

    // The bar is outside the page: the page's whole viewport is free.
    reservedBottom() {
      return 16;
    },
    reservedTop() {
      return 0;
    },
    isOwn() {
      return false;
    },
  };

  // A page restored from the back/forward cache: tell the app what its bar should show again.
  window.addEventListener('pageshow', (e) => {
    if (!e.persisted) return;
    lastSent = '';
    browser.post({ type: 'hello', url: location.href });
    O.ui.update({});
  });
})();
