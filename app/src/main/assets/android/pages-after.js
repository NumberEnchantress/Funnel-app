/* After the extension's common.js: exports are saved through Android's "save as" screen. */
(function () {
  'use strict';
  window.PAGE.download = (filename, text, mime) => {
    window.PAGE.send('export', { filename: String(filename), text: String(text), mime: String(mime || 'text/plain') }).catch((e) => alert(e.message));
  };
  // The app's pages link to each other; "Settings" is the app's own page.
  document.documentElement.classList.add('android', 'app');
  const style = document.createElement('style');
  style.textContent = [
    '.desktop-only { display: none !important; }',
    'html.app .page { padding-bottom: 32px; }',
    // An empty list is one quiet line, not a tall empty box.
    'html.app .empty { padding: 10px 0; text-align: left; }',
    // Today's numbers as an even grid (three across on a phone) instead of ragged wrapping tiles.
    'html.app .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(96px, 1fr)); }',
    'html.app .stat { min-width: 0; }',
    // Export buttons: their label on one line, the four buttons as one even row under it.
    'html.app .tools > span.muted { flex: 1 0 100%; }',
    'html.app .tools > button { flex: 1 1 0; justify-content: center; min-width: 0; }',
  ].join(' ');
  document.head.append(style);
})();
