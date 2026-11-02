/* "Up next" on the Later page: the app's own play queue (the phone site has none). */
(function () {
  'use strict';
  const { send, h, relTime, safeUrl } = window.PAGE;
  const F = window.OTAAT_FORMAT;
  const $ = (id) => document.getElementById(id);

  async function render() {
    const { items } = await send('queue.list');
    $('queueCount').textContent = `· ${items.length} waiting`;
    if (!items.length) {
      $('queueList').replaceChildren(h('div', { class: 'empty', text: 'Nothing queued.' }));
      return;
    }
    $('queueList').replaceChildren(
      ...items.map((it, i) =>
        h(
          'div',
          { class: 'item' },
          h('img', { class: 'thumb', src: `https://i.ytimg.com/vi/${encodeURIComponent(it.id || '')}/mqdefault.jpg`, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' }),
          h(
            'div',
            {},
            h('div', { class: 'title' }, h('a', { href: safeUrl(it.url), text: F.titleOf(it) })),
            h('div', { class: 'meta', text: [it.author, it.extra && it.extra.duration, `queued ${relTime(it.addedTs)}`].filter(Boolean).join(' · ') })
          ),
          h(
            'div',
            { class: 'actions' },
            h('button', { class: i === 0 ? 'primary' : '', text: 'Play', onclick: () => send('queue.play', { key: it.key }) }),
            h('button', {
              text: 'Remove',
              title: 'Take it off Up next (it stays in your logs)',
              onclick: async () => {
                await send('queue.remove', { key: it.key });
                render();
              },
            })
          )
        )
      )
    );
  }
  render();
  window.addEventListener('focus', render);
})();
