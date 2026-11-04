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
  ].join(' ');
  document.head.append(style);
})();
