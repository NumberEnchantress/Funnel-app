package com.otaat.funnel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers the messages the pages send (the extension's background messages, plus a few for the app).
 * YouTube's pages may only use the small set the extension's content scripts could; the app's own
 * pages (Logs / Later / Settings) get the rest.
 */
final class Bridge {
    interface Host {
        void onUiUpdate(JSONObject state);
        void onToast(String text, String kind, int ms);
        void openPage(String page, String hash);
        void openUrl(String url);
        void exportFile(String filename, String text, String mime);
        void chooseFolder();
        Context context();
    }

    private static final Set<String> YOUTUBE_ALLOWED = new HashSet<>(Arrays.asList(
            "hello", "ui.update", "ui.toast", "log", "filterSorted", "counts", "openPage",
            "later.nativeConfirmed", "settings.get", "queue.add", "queue.ended"));

    private static final Set<String> PAGES_ALLOWED = new HashSet<>(Arrays.asList(
            "counts", "totals", "events.query", "later.list", "later.update", "later.remove", "sorted.reset",
            "files.status", "files.syncNow", "files.rebuild", "openPage", "settings.get", "settings.set",
            "app.get", "app.set", "export", "clipboard", "folder.choose", "queue.list", "queue.remove", "queue.play"));

    private final Host host;
    private final boolean youtubePage;
    private final App app = App.get();
    /** YouTube pages: the secret the injected script carries (page scripts can't read it). */
    private String token;
    /** YouTube pages: the page that last said hello (the one on screen). */
    private JavaScriptReplyProxy current;

    Bridge(Host host, boolean youtubePage) {
        this.host = host;
        this.youtubePage = youtubePage;
    }

    void setToken(String token) {
        this.token = token;
    }

    /** The listener for WebViewCompat.addWebMessageListener: main frame only, one request at a time. */
    WebViewCompat.WebMessageListener listener(java.util.function.Consumer<JavaScriptReplyProxy> onPage) {
        return (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
            if (!isMainFrame || message.getType() != WebMessageCompat.TYPE_STRING) return;
            JSONObject wrapper;
            try {
                wrapper = new JSONObject(message.getData());
            } catch (JSONException | NullPointerException e) {
                return;
            }
            JSONObject msg = wrapper.optJSONObject("msg");
            if (msg == null) return;
            if (youtubePage) {
                // Only the app's own script knows the token; anything else on the page is ignored.
                if (token == null || !token.equals(wrapper.optString("token"))) return;
                String t = msg.optString("type");
                if (t.equals("hello")) {
                    current = replyProxy;
                    if (onPage != null) onPage.accept(replyProxy);
                } else if ((t.equals("ui.update") || t.equals("ui.toast")) && replyProxy != current) {
                    return; // a page that is going away: its bar state no longer applies
                }
            }
            boolean wantsReply = wrapper.has("id");
            long id = wrapper.optLong("id");
            handle(msg, result -> {
                if (!wantsReply) return;
                try {
                    JSONObject out = new JSONObject();
                    out.put("re", id);
                    out.put("result", result);
                    replyProxy.postMessage(out.toString());
                } catch (JSONException | IllegalStateException e) {
                    Log.w(App.TAG, "reply failed", e);
                }
            });
        };
    }

    interface Reply {
        void send(JSONObject result);
    }

    private static JSONObject error(String m) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", m);
        } catch (JSONException ignored) {
            // not possible
        }
        return o;
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (JSONException ignored) {
            // not possible
        }
        return o;
    }

    /** Called on the main thread; answers on the main thread. */
    void handle(JSONObject msg, Reply reply) {
        String type = msg.optString("type");
        if (!(youtubePage ? YOUTUBE_ALLOWED : PAGES_ALLOWED).contains(type)) {
            reply.send(error("not allowed: " + type));
            return;
        }
        // Things for the screen happen here, on the main thread.
        switch (type) {
            case "hello":
                reply.send(ok());
                return;
            case "ui.update":
                host.onUiUpdate(msg.optJSONObject("state"));
                reply.send(ok());
                return;
            case "ui.toast":
                host.onToast(msg.optString("text"), msg.optString("kind", "info"), msg.optInt("ms", 2800));
                reply.send(ok());
                return;
            case "openPage": {
                String page = msg.optString("page");
                if (page.equals("later-list")) page = "later";
                if (!Arrays.asList("logs", "later", "options").contains(page)) {
                    reply.send(error("unknown page"));
                    return;
                }
                host.openPage(page, msg.optString("hash", ""));
                reply.send(ok());
                return;
            }
            case "export":
                host.exportFile(msg.optString("filename", "export.txt"), msg.optString("text"), msg.optString("mime", "text/plain"));
                reply.send(ok());
                return;
            case "clipboard": {
                ClipboardManager cm = (ClipboardManager) host.context().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("One Thing At A Time", msg.optString("text")));
                reply.send(ok());
                return;
            }
            case "folder.choose":
                host.chooseFolder();
                reply.send(ok());
                return;
            default:
                break;
        }
        // Everything else touches the store (I/O thread) or the files (files thread, which can be slow).
        java.util.concurrent.ExecutorService ex = type.startsWith("files.") || type.equals("app.set") ? app.filesIo : app.io;
        ex.execute(() -> {
            JSONObject result;
            try {
                result = work(type, msg);
            } catch (Exception e) {
                Log.w(App.TAG, type + " failed", e);
                result = error(String.valueOf(e.getMessage()));
            }
            JSONObject r = result;
            app.main.post(() -> reply.send(r));
        });
    }

    private String today() {
        return Format.localDate(System.currentTimeMillis());
    }

    /** On the I/O thread. */
    private JSONObject work(String type, JSONObject msg) throws Exception {
        Store store = app.store;
        JSONObject r = new JSONObject();
        switch (type) {
            case "log": {
                JSONArray entries = msg.optJSONArray("entries");
                if (entries == null) entries = new JSONArray();
                int[] repeats = {0};
                List<Format.Event> stored = store.addEvents(entries, app.seenRepeatHours(), repeats);
                if (!stored.isEmpty()) app.scheduleSync(1500);
                r.put("stored", stored.size() + repeats[0]);
                r.put("handled", entries.length());
                r.put("counts", store.countsFor(today()));
                return r;
            }
            case "filterSorted": {
                JSONArray keys = msg.optJSONArray("keys");
                List<String> list = new ArrayList<>();
                for (int i = 0; keys != null && i < keys.length() && i < 2000; i++) list.add(keys.optString(i));
                r.put("sorted", new JSONArray(store.filterSorted(list)));
                return r;
            }
            case "counts":
                r.put("counts", store.countsFor(today()));
                r.put("date", today());
                return r;
            case "totals":
                return store.totals();
            case "events.query": {
                JSONObject f = msg.optJSONObject("filter");
                return store.queryEvents(f == null ? new JSONObject() : f);
            }
            case "later.list":
                r.put("items", store.laterList());
                return r;
            case "later.update": {
                JSONObject patch = msg.optJSONObject("patch");
                if (patch != null && patch.has("done")) store.laterSetDone(msg.optString("key"), patch.optBoolean("done"));
                return ok();
            }
            case "later.remove":
                store.laterRemove(msg.optString("key"));
                return ok();
            case "later.nativeConfirmed": {
                String key = msg.optString("key");
                if (key.startsWith("youtube:")) store.laterRemove(key);
                return ok();
            }
            case "sorted.reset":
                store.resetSorted();
                app.main.post(() -> {
                    if (app.youtube != null) app.youtube.settingsChanged(); // the feed forgets what it knew
                });
                return ok();
            case "files.syncNow":
                app.syncNow();
                return fileStatus();
            case "files.rebuild":
                try {
                    app.files.rebuild(app.device());
                } catch (Exception e) {
                    Log.w(App.TAG, "rebuild failed", e);
                }
                return fileStatus();
            case "files.status":
                return fileStatus();
            case "settings.get":
                r.put("settings", app.settings());
                return r;
            case "settings.set": {
                JSONObject s = msg.optJSONObject("settings");
                if (s == null) return error("no settings");
                app.saveSettings(s);
                app.main.post(() -> {
                    if (app.youtube != null) app.youtube.settingsChanged();
                });
                return ok();
            }
            case "app.get":
                return appSettings();
            case "app.set":
                if (msg.has("device")) app.setDevice(msg.optString("device"));
                if (msg.has("importOthers")) {
                    app.setImportOthers(msg.optBoolean("importOthers"));
                    app.importNow();
                }
                return appSettings();
            case "queue.add": {
                int size = store.queueAdd(msg.optJSONObject("item"));
                r.put("ok", size >= 0);
                r.put("size", size);
                return r;
            }
            case "queue.list":
                r.put("items", store.queueList());
                return r;
            case "queue.remove":
                store.queueTake(msg.optString("key"));
                return ok();
            case "queue.play":
            case "queue.ended": {
                JSONObject next = store.queueTake(type.equals("queue.play") ? msg.optString("key") : null);
                r.put("played", next != null);
                if (next != null) {
                    String url = next.optString("url");
                    app.main.post(() -> host.openUrl(url));
                }
                return r;
            }
            default:
                return error("unknown message: " + type);
        }
    }

    private JSONObject appSettings() throws JSONException {
        JSONObject r = new JSONObject();
        r.put("device", app.device());
        r.put("importOthers", app.importOthers());
        return r;
    }

    private JSONObject fileStatus() throws JSONException {
        FileSink f = app.files;
        JSONObject r = new JSONObject();
        r.put("folder", f.hasFolder() ? f.folderLabel() : "");
        r.put("device", app.device());
        r.put("pending", f.pending());
        r.put("lastWriteTs", f.lastWriteTs);
        r.put("lastWriteCount", f.lastWriteCount);
        r.put("lastError", f.lastError);
        r.put("imported", app.store.importedCount());
        // What the extension's Logs page expects (logs.js renderFileStatus).
        r.put("platform", "android");
        r.put("output", f.hasFolder() ? "native" : JSONObject.NULL);
        JSONObject nat = new JSONObject();
        nat.put("ok", f.hasFolder());
        nat.put("folder", f.hasFolder() ? f.folderLabel() + " (this phone's files end in ." + app.device() + ".md)" : "");
        r.put("native", nat);
        r.put("setting", "auto");
        return r;
    }

    static Uri parse(String url) {
        try {
            return Uri.parse(url);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
