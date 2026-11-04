# Funnel: One Thing At A Time for YouTube, on Android

An Android app you use instead of the YouTube app. It is YouTube's own phone site (`m.youtube.com`) with
the engine of the [One Thing At A Time](https://github.com/NumberEnchantress/One-Thing-At-A-Time) Firefox extension built in:

- **Home, Subscriptions and Shorts show one video at a time.** You decide what happens to each with the
  buttons below the page, then the next one appears.
- **Everything drawn on screen is logged**, once per 24 hours per video, in the same Markdown and JSONL
  files as the extension.
- **Recommended videos, end-of-video suggestions, autoplay of the next recommendation, recommendation
  shelves in search and ads in feeds are hidden.**

| Button | What it does | Logged as |
|---|---|---|
| **Next →** | Shows the next video | `skipped` |
| **Later** | Saves it to your YouTube **Watch later** playlist and shows the next one. If YouTube refuses or doesn't answer (or you're signed out), it goes on the app's **Later list** instead, and the log says so. | `deferred` |
| **Queue** | Adds it to **Up next** (the app's own play queue: YouTube's phone site has none) and shows the next one. When a video ends, the first one in Up next plays. | `queued` |

The row under the buttons is always there, one tap each: today's count, **Logs**, **Watch later**,
**Later list** (with Up next) and **Settings**.

Items you already sorted never come back in your feeds, including items sorted in the Firefox extension
if its log folder is synced to the phone (see below).

## Installing

This is a personal app, installed directly (not from the Play Store).

1. Build it (below). `app-debug.apk` installs as **Funnel (test)**; for everyday use, build and sign the
   release version (see *Release signing*).
2. Copy the APK to the phone and open it. Android asks you to allow installs from that app (Files,
   Drive, …) once.
3. Open **Funnel**, choose a log folder when asked (for example `Documents/OTAAT`), and sign in to
   YouTube with the **You** tab. You sign in on Google's own page; the app never sees your password.
4. To open YouTube links in Funnel instead of the YouTube app: Android **Settings → Apps → Funnel →
   Open by default → Add links**, and tick the YouTube addresses. You can also **Share** a video to
   Funnel from any app.

Debug builds install as a separate app, **Funnel (test)**, with their own data, so testing never touches
your real logs.

## Your log files

Same layout and line format as the extension, with the device name in the file names so phone and PC
files can share one folder without ever writing to the same file:

```
OTAAT/
  2026-11/
    2026-11-02/
      seen.md            ← written by Firefox on the PC
      events.jsonl       ← written by Firefox on the PC
      seen.phone.md      ← written by this app
      skipped.phone.md
      deferred.phone.md
      queued.phone.md
      events.phone.jsonl
```

The device name (`phone`) can be changed in Settings. Every entry is first stored in the app's own
database; the files follow within a couple of seconds, and straight away when you leave the app. With no
folder chosen yet, nothing is lost: everything is written as soon as you choose one.

**Safety rules for your files** (the same as the extension's native writer):

- The app only ever **adds lines to the end** of its own files. It never shortens, replaces or deletes
  anything.
- **No line is ever written twice.** For each file it records the last entry written.
- **No half lines.** Before each write it notes exactly what it is about to append. If the phone dies in
  the middle of a write, the next start finishes that exact write (or, if nothing arrived, writes it
  again), so a line is never cut off or doubled.

**Syncing with the PC.** Point a folder-sync app (Syncthing, FolderSync, …) at the phone's log folder
and the extension's log folder on your computer. Because the phone writes only `*.phone.*` files and Firefox only the plain
ones, the two never collide. With **Settings → Skip items sorted on other devices** on (the default), the
app also reads the other devices' `events.jsonl` files in that folder, so a video you skipped on the PC
doesn't come up on the phone.

**Exporting.** Logs → Markdown / CSV / JSON saves the entries shown through Android's "save as" screen;
**Copy** puts them on the clipboard.

## Good to know

- **Signing in.** YouTube's Home feed is empty when you're signed out, and Watch later needs an account.
  Google sometimes refuses sign-in inside apps; the app presents itself to YouTube like Chrome on the same
  phone to avoid that, but if Google still says *"This browser or app may not be secure"*, tell the
  developer.
- **Ads inside videos are not removed.** Ads in feeds and under videos are hidden (as in the extension);
  removing video ads would mean tampering with YouTube's player, which YouTube actively fights.
- **No gaps where things were hidden.** On Home and Subscriptions the one video is a large card in the
  middle of the screen; under a video, its description takes the place of the recommendations; YouTube's
  "Open App" button (which would only try to open the YouTube app) is gone.
- **Autoplay.** When a video ends, YouTube isn't told, so it can't start a recommendation. Playlists you
  open yourself still play through; Up next plays if it has anything.
- **Background play and picture-in-picture are not supported yet.** Like the free YouTube app, a video
  stops when you leave the app.
- **YouTube's terms of service.** The app shows YouTube's own site and hides parts of it, as the browser
  extension does. It's meant for your own personal use.
- **If YouTube changes its site** so that the app can't find a feed, the feed stays hidden for a few
  seconds and then shows normally, with "Couldn't find this feed" in the bar. Everything on screen is
  still logged. **Settings → YouTube → Paused** turns the one-at-a-time feeds off at once.
- Everything stays on the phone. The app talks only to YouTube / Google (for the site and sign-in) and
  the folder you chose. No analytics, no servers of its own.

## Development

The engine is the extension's own code, unchanged: `extension/` holds the extension files the app uses
(see `extension/README.md`), and `tools/bundle.js` builds the app's injected script and pages from them
plus the app's `js/` and `web/`. A fix to the extension reaches the app by copying the files over and
running the bundler.

```bash
npm install                     # test tools (jsdom)
npm run bundle                  # rebuild app/src/main/assets from extension/, js/ and web/
npm test                        # injected-script tests (jsdom) + Java-vs-JS log format check (needs a JDK)
./gradlew assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk  ("Funnel (test)")
./gradlew assembleRelease       # → needs a signing key (see below)
```

Needs Node 20+, JDK 17+ (`JAVA_HOME`) and the Android SDK (`local.properties` → `sdk.dir`, or `ANDROID_HOME`).

Layout:

```
  app/src/main/java/com/otaat/funnel/
    MainActivity.java   YouTube screen: WebView + document-start injection + native button bar
    PagesActivity.java  Logs / Later / Settings pages (the extension's pages, served from assets)
    Bridge.java         messages from the pages (the extension's background messages)
    Store.java          SQLite store (port of extension/background/db.js)
    FileSink.java       log files in the chosen folder: append-only, journalled
    Format.java         Markdown / JSONL lines (port of extension/shared/format.js, checked byte for byte)
    Urls.java           which links stay in the app; YouTube links → phone site
    App.java            settings, the single I/O thread, scheduling file writes
  js/          shim.js (browser API → app), ui.js (bar → native), youtube-app.js (Up next, autoplay)
  web/         the app's Settings page and the shims for the extension's pages
  tools/       bundle.js · cdp.js (DevTools on a test device) · frames.js (frame-by-frame check) · FormatCheck.java
  tests/       inject.test.js · format.test.js · fixtures/ (YouTube phone markup)
  extension/   the One Thing At A Time extension files the app is built from
```

**Checking on a device or emulator** (debug builds allow DevTools):

```bash
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.otaat.funnel.debug)
node tools/cdp.js "document.documentElement.getAttribute('otaat-q')"
node tools/cdp.js --file tools/frames.js     # counts visible feed items on every frame while it opens, goes back, …
```

`frames.js` must report `framesWithMoreThanOneFeedItem: 0`.

**Release signing.** Create a key once (`keytool -genkeypair -v -keystore funnel.jks -alias funnel
-keyalg RSA -keysize 4096 -validity 36500`), keep it safe and out of the repository, and sign with
`apksigner sign --ks funnel.jks app/build/outputs/apk/release/app-release-unsigned.apk`. Updates must be
signed with the same key.

## Status (first version, 2 November 2026)

Checked on an Android 16 emulator against the live `m.youtube.com` (signed out: Home, a channel's videos tab
with that surface switched on, Shorts):

- **One item at a time:** 0 frames with more than one feed item visible, out of about 5,000 recorded frame
  by frame while opening a video, going back and forward, opening Shorts and switching YouTube's bottom
  tabs; and 0 frames with two Shorts visible over 1,125 frames of Next / Later presses on Shorts.
- **Logs:** written in the extension's exact format (the Java writer is compared byte for byte with
  `format.js` in the tests); six force-kills in the middle of use left the files with no repeated, missing
  or half lines, matching the app's database.
- **Not yet checked:** anything that needs a signed-in account (Subscriptions, Home recommendations,
  Watch later accepting a video), a real phone, and a real power cut (the database and files are written
  with full disk syncs for that case).
- YouTube's phone site sometimes moves back one Short by itself a moment after Next. The app logs what was
  actually on screen, so a button pressed just then applies to the Short you were shown again (the bar
  says *Already sorted before*).
