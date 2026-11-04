# extension/ (copied from One Thing At A Time)

These files come unchanged from the [One Thing At A Time](https://github.com/NumberEnchantress/One-Thing-At-A-Time)
Firefox extension: its feed engine, YouTube adapter, hiding rules, log format and its Logs / Later pages.
The app doesn't run them from here directly: `tools/bundle.js` builds them, together with the app's own
`js/` and `web/`, into `app/src/main/assets/`.

Only the files the app uses are copied (currently from version 1.1.4). To take a newer version of the extension, copy the same files
over from its `extension/` folder (or point the bundler at a checkout with
`OTAAT_EXTENSION=path/to/extension node tools/bundle.js`), then run `npm test`.
