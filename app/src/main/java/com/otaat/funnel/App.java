package com.otaat.funnel;

import android.app.Application;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide state: the store, the file writer and the settings, plus the single I/O thread every
 * database and file operation runs on (so they never race each other).
 */
public final class App extends Application {
    static final String TAG = "Funnel";
    private static App instance;

    final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "funnel-io"));
    /** Writing and reading the log files: slow (each append waits for the disk), so never in front of `io`. */
    final ExecutorService filesIo = Executors.newSingleThreadExecutor(r -> new Thread(r, "funnel-files"));
    final Handler main = new Handler(Looper.getMainLooper());
    Store store;
    FileSink files;
    private SharedPreferences prefs;

    /** The YouTube screen, when it exists (gets settings changes and items sorted elsewhere). */
    MainActivity youtube;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("funnel", MODE_PRIVATE);
        store = new Store(this);
        files = new FileSink(this, store);
        String folder = prefs.getString("folder", null);
        if (folder != null) files.setFolder(Uri.parse(folder));
        scheduleSync(3000); // anything still waiting from last time
    }

    // ------------------------------------------------------------------ settings

    /** The extension-shaped settings (shared/settings.js merges them with its defaults). */
    JSONObject settings() {
        try {
            return new JSONObject(prefs.getString("settings", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    void saveSettings(JSONObject s) {
        prefs.edit().putString("settings", s.toString()).apply();
    }

    double seenRepeatHours() {
        return settings().optDouble("seenRepeatHours", 24);
    }

    String device() {
        return prefs.getString("device", "phone");
    }

    /** Letters, digits and dashes only (it goes into file names). */
    static String cleanDevice(String s) {
        String d = (s == null ? "" : s).trim().toLowerCase().replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        if (d.length() > 24) d = d.substring(0, 24);
        return d.isEmpty() ? "phone" : d;
    }

    void setDevice(String d) {
        prefs.edit().putString("device", cleanDevice(d)).apply();
    }

    boolean importOthers() {
        return prefs.getBoolean("importOthers", true);
    }

    void setImportOthers(boolean on) {
        prefs.edit().putBoolean("importOthers", on).apply();
    }

    String folder() {
        return prefs.getString("folder", null);
    }

    boolean welcomed() {
        return prefs.getBoolean("welcomed", false);
    }

    void setWelcomed() {
        prefs.edit().putBoolean("welcomed", true).apply();
    }

    void setFolder(Uri tree) {
        prefs.edit().putString("folder", tree.toString()).apply();
        filesIo.execute(() -> {
            files.setFolder(tree);
            syncNow();
            importNow();
        });
    }

    // ------------------------------------------------------------------ background work

    private boolean syncQueued;
    private long syncDue;

    /** Write new entries to the files after `delay` ms (an earlier request is never pushed back). */
    void scheduleSync(long delay) {
        long due = System.currentTimeMillis() + delay;
        synchronized (this) {
            if (syncQueued && syncDue <= due) return;
            syncQueued = true;
            syncDue = due;
        }
        main.postDelayed(() -> filesIo.execute(() -> {
            synchronized (this) {
                if (System.currentTimeMillis() < syncDue - 50) return; // superseded by an earlier one
                syncQueued = false;
            }
            syncNow();
        }), delay);
    }

    /** On the files thread. */
    void syncNow() {
        try {
            files.sync(device());
        } catch (Exception e) {
            Log.w(TAG, "writing log files failed", e);
            scheduleSync(60_000); // try again in a minute
        }
    }

    /** On the files thread: pick up items sorted on other devices (their events files in the log folder). */
    void importNow() {
        if (!importOthers() || !files.hasFolder()) return;
        try {
            List<String[]> found = files.readOthersSorted(device());
            if (found.isEmpty()) return;
            List<String> added = store.importSorted(found);
            if (!added.isEmpty()) main.post(() -> {
                if (youtube != null) youtube.sortedElsewhere(added);
            });
        } catch (Exception e) {
            Log.w(TAG, "reading other devices' logs failed", e);
        }
    }
}
