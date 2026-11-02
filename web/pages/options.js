/* The app's Settings page. Feed, hiding and logging settings use the extension's own settings shape. */
(function () {
  'use strict';
  const { send } = window.PAGE;
  const S = window.OTAAT_SETTINGS;
  const $ = (id) => document.getElementById(id);

  const getPath = (obj, path) => path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
  function setPath(obj, path, value) {
    const keys = path.split('.');
    const last = keys.pop();
    keys.reduce((o, k) => o[k], obj)[last] = value;
  }

  let savedTimer = null;
  function flashSaved() {
    $('saved').classList.add('show');
    clearTimeout(savedTimer);
    savedTimer = setTimeout(() => $('saved').classList.remove('show'), 1200);
  }

  async function save(path, value) {
    await S.save(browser.storage.local, (s) => {
      setPath(s, path, value);
      return s;
    });
    flashSaved();
  }

  async function load() {
    const s = await S.load(browser.storage.local);
    for (const el of document.querySelectorAll('[data-path]')) {
      const v = getPath(s, el.dataset.path);
      if (el.type === 'checkbox') el.checked = !!v;
      else el.value = v == null ? '' : String(v);
    }
  }

  function wire() {
    for (const el of document.querySelectorAll('[data-path]')) {
      el.addEventListener(el.type === 'number' ? 'change' : 'input', () => {
        let v;
        if (el.type === 'checkbox') v = el.checked;
        else if (el.dataset.type === 'number') {
          v = Number(el.value);
          if (!Number.isFinite(v) || v < 0) v = getPath(S.DEFAULTS, el.dataset.path);
        } else v = el.value.trim();
        save(el.dataset.path, v);
      });
    }
  }

  async function renderFiles(st) {
    st = st || (await send('files.status'));
    const el = $('fileStatus');
    el.classList.toggle('warn', !st.folder || !!st.lastError);
    let text = st.folder
      ? `Files are written to ${st.folder}.`
      : 'No folder chosen yet. Everything is kept safely in the app until you choose one, and is all written then.';
    if (st.pending) text += ` ${st.pending} entries waiting to be written.`;
    if (st.lastWriteTs) text += ` Last written ${new Date(st.lastWriteTs).toLocaleTimeString()}.`;
    if (st.lastError) text += ` Problem: ${st.lastError}`;
    if (st.imported) text += ` ${st.imported} items sorted on other devices are skipped here.`;
    el.textContent = text;
    $('sync').disabled = $('rebuild').disabled = !st.folder;
    for (const d of document.querySelectorAll('.dev')) d.textContent = st.device;
  }

  async function loadApp() {
    const a = await send('app.get');
    $('device').value = a.device;
    $('importOthers').checked = !!a.importOthers;
  }

  $('device').addEventListener('change', async () => {
    const r = await send('app.set', { device: $('device').value });
    $('device').value = r.device;
    flashSaved();
    renderFiles();
  });
  $('importOthers').addEventListener('change', async () => {
    await send('app.set', { importOthers: $('importOthers').checked });
    flashSaved();
  });
  $('choose').addEventListener('click', () => send('folder.choose'));
  $('sync').addEventListener('click', async () => renderFiles(await send('files.syncNow')));
  $('rebuild').addEventListener('click', async () => renderFiles(await send('files.rebuild')));
  $('resetSorted').addEventListener('click', async () => {
    if (!confirm('Let every item you already sorted show up in your feeds again? Your logs are not changed.')) return;
    await send('sorted.reset');
    renderTotals();
  });

  async function renderTotals() {
    const t = await send('totals');
    $('totals').textContent = `${t.events} log entries kept in the app · ${t.sorted} sorted items · ${t.later} on the Later list.`;
  }

  // Coming back from the folder picker.
  window.addEventListener('focus', () => renderFiles());
  document.addEventListener('visibilitychange', () => !document.hidden && renderFiles());

  wire();
  load();
  loadApp();
  renderFiles();
  renderTotals();
})();
