#!/usr/bin/env node
/*
 * Development helper: run JavaScript in the app's WebView on a test device or emulator, through Chrome
 * DevTools (debug builds only). First: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
 *
 *   node tools/cdp.js "document.title"          evaluate an expression (awaited), print the result
 *   node tools/cdp.js --file check.js           evaluate a file's contents
 *   node tools/cdp.js --url https://… "expr"    navigate first (waits for load), then evaluate
 */
'use strict';
const fs = require('fs');

async function main() {
  const args = process.argv.slice(2);
  let url = null;
  let expr = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--url') url = args[++i];
    else if (args[i] === '--file') expr = fs.readFileSync(args[++i], 'utf8');
    else expr = args[i];
  }
  const targets = await (await fetch('http://127.0.0.1:9222/json')).json();
  // CDP_TARGET=appassets picks the app's own pages; the default is the YouTube page.
  const want = new RegExp(process.env.CDP_TARGET || 'youtube\\.com');
  const target = targets.find((t) => t.type === 'page' && want.test(t.url)) || targets[0];
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r, j) => {
    ws.onopen = r;
    ws.onerror = j;
  });
  let seq = 0;
  const pending = new Map();
  const events = [];
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) {
      pending.get(d.id)(d);
      pending.delete(d.id);
    } else if (d.method) events.push(d);
  };
  const send = (method, params = {}) =>
    new Promise((r) => {
      const id = ++seq;
      pending.set(id, r);
      ws.send(JSON.stringify({ id, method, params }));
    });
  if (url) {
    await send('Page.enable');
    await send('Page.navigate', { url });
    const end = Date.now() + 20000;
    while (Date.now() < end && !events.some((e) => e.method === 'Page.loadEventFired')) await new Promise((r) => setTimeout(r, 100));
  }
  if (expr) {
    const res = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    const r = res.result || {};
    if (r.exceptionDetails) console.log('EXCEPTION', JSON.stringify(r.exceptionDetails, null, 1).slice(0, 2000));
    else console.log(typeof r.result.value === 'string' ? r.result.value : JSON.stringify(r.result.value, null, 1));
  }
  ws.close();
}
main().catch((e) => {
  console.error(e);
  process.exit(1);
});
