'use strict';
/*
 * The app writes its log lines in Java (Format.java). They must be byte-for-byte what the extension's
 * shared/format.js writes, so phone and desktop logs read the same. Compiles Format.java with the JDK
 * (JAVA_HOME, or javac on the PATH) and compares tricky events. Skipped when no JDK is found.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const ANDROID = path.join(__dirname, '..');
const F = require(path.join(ANDROID, 'extension', 'shared', 'format.js'));

function jdkTool(name) {
  const exe = process.platform === 'win32' ? `${name}.exe` : name;
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, 'bin', exe));
  candidates.push(exe);
  for (const c of candidates) {
    try {
      execFileSync(c, ['-version'], { stdio: 'pipe' });
      return c;
    } catch (_) {
      /* next */
    }
  }
  return null;
}

const b64 = (s) => (s == null ? '-' : Buffer.from(String(s), 'utf8').toString('base64'));
const unb64 = (s) => Buffer.from(s, 'base64').toString('utf8');

const base = {
  site: 'youtube',
  kind: 'video',
  surface: 'home',
  pageUrl: 'https://m.youtube.com/',
};
const EVENTS = [
  { ...base, id: 1, ts: Date.UTC(2026, 9, 14, 18, 48, 14), action: 'seen', key: 'youtube:NiCgcUTL9_Q', url: 'https://www.youtube.com/watch?v=NiCgcUTL9_Q', title: 'The Absurd Engineering Behind the Internet', author: 'Veritasium', thumb: 'https://i.ytimg.com/vi/NiCgcUTL9_Q/mqdefault.jpg', extra: { duration: '17:03' } },
  { ...base, id: 2, ts: Date.UTC(2026, 10, 1, 4, 2, 3), action: 'skipped', key: 'youtube:abc', url: 'https://www.youtube.com/watch?v=abc', title: '  Lots   of\twhite\nspace [and] brackets\\ ', author: 'A B', extra: {} },
  { ...base, id: 3, ts: Date.UTC(2026, 10, 1, 23, 59, 59), action: 'deferred', key: 'youtube:emoji', url: 'https://www.youtube.com/shorts/emoji', kind: 'short', title: 'Emoji 😀 and lone \ud83d surrogate', author: 'Ch "quoted"', note: 'watch later refused: extension Later list', surface: 'shorts' },
  { ...base, id: 4, ts: Date.UTC(2026, 0, 2, 12, 0, 0), action: 'queued', key: 'youtube:long', url: 'https://www.youtube.com/watch?v=(paren) x', title: 'x'.repeat(198) + '😀😀 tail', author: 'y'.repeat(100) },
  { ...base, id: 5, ts: Date.UTC(2026, 5, 6, 7, 8, 9), action: 'seen', key: 'youtube:post:Ug', kind: 'post', url: 'https://www.youtube.com/post/Ug', title: '', snippet: 'A community post\nwith two lines and a   separator', extra: { community: 'r/x' } }, // (empty extras are never stored)
  { ...base, id: 6, ts: Date.UTC(2026, 5, 6, 7, 8, 9), action: 'seen', key: 'youtube:nourl', title: 'Control \u0001 chars \u007f', snippet: 'Different snippet' },
  { id: 7, ts: Date.UTC(2026, 2, 29, 1, 30, 0), action: 'seen', site: 'youtube', key: 'youtube:k7' },
];

test('Format.java writes exactly what format.js writes', (t) => {
  const javac = jdkTool('javac');
  const java = jdkTool('java');
  if (!javac || !java) {
    t.skip('no JDK found (set JAVA_HOME)');
    return;
  }
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'funnel-format-'));
  execFileSync(javac, ['-d', out, path.join(ANDROID, 'app', 'src', 'main', 'java', 'com', 'otaat', 'funnel', 'Format.java'), path.join(ANDROID, 'tools', 'FormatCheck.java')], { stdio: 'pipe' });
  const input = EVENTS.map((ev) =>
    [ev.id, ev.ts, ev.day, ev.action, ev.site, ev.key, ev.kind, ev.url, ev.title, ev.author, ev.snippet, ev.surface, ev.pageUrl, ev.thumb, ev.note]
      .map((v) => (v === undefined ? '-' : b64(v)))
      .concat(ev.extra ? Object.entries(ev.extra).flat().map(b64).join(',') : '-')
      .join('\t')
  ).join('\n');
  const lines = execFileSync(java, ['-cp', out, 'FormatCheck'], { input, encoding: 'utf8' }).trim().split('\n');
  assert.equal(lines.length, EVENTS.length);
  EVENTS.forEach((ev, i) => {
    const [md, jsonl, header, paths] = lines[i].split('\t').map(unb64);
    assert.equal(md, F.mdLine(ev), `mdLine #${ev.id}`);
    assert.equal(jsonl, F.jsonlLine(ev), `jsonlLine #${ev.id}`);
    // Same header as Firefox writes it ("# Seen — Sunday, 1 November 2026"; Node's own en-GB date drops the
    // comma, so the expected title is put together from its parts). The description line names the app.
    const day = F.dayOf(ev);
    const [y, m, d] = day.split('-').map(Number);
    const dt = new Date(y, m - 1, d);
    const part = (o) => dt.toLocaleDateString('en-GB', o);
    const title = `${part({ weekday: 'long' })}, ${d} ${part({ month: 'long' })} ${y}`;
    assert.equal(header, `# ${F.ACTION_TITLES[ev.action]} — ${title}

Written by the Funnel (One Thing At A Time) Android app. Newest entries at the bottom.

`, `header #${ev.id}`);
    assert.equal(paths, `${F.dayFolder(day)}/${ev.action}.phone.md|${F.dayFolder(day)}/events.phone.jsonl`);
  });
});

test('the day title is written the way Firefox writes it in the desktop logs', () => {
  // The first line of a seen.md written by the extension in Firefox: "# Seen — Sunday, 1 November 2026".
  assert.equal(new Date(2026, 10, 1).toLocaleDateString('en-GB', { weekday: 'long' }), 'Sunday');
});
