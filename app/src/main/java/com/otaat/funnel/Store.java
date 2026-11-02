package com.otaat.funnel;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical store: every log event, the sorted set, the seen index, the Later list, Up next,
 * per-day counts and the file writer's bookkeeping. A port of the extension's background/db.js on SQLite,
 * which keeps everything across crashes and reboots. Only used from the app's single I/O thread.
 */
public final class Store extends SQLiteOpenHelper {
    private static final int VERSION = 2;

    public Store(Context context) {
        super(context, "otaat.db", null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // Every commit reaches the disk before the page is told it is saved (WAL's default would not survive
        // a power cut), and the file writer's bookkeeping is never behind the files themselves.
        db.execPerConnectionSQL("PRAGMA synchronous = FULL", null);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // AUTOINCREMENT: ids only ever increase, even after deletes (the file writer relies on it).
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, day TEXT NOT NULL,"
                + " action TEXT NOT NULL, site TEXT, key TEXT NOT NULL, kind TEXT, url TEXT, title TEXT, author TEXT,"
                + " snippet TEXT, surface TEXT, pageUrl TEXT, thumb TEXT, extra TEXT, note TEXT, uid TEXT)");
        db.execSQL("CREATE UNIQUE INDEX events_uid ON events(uid)");
        db.execSQL("CREATE INDEX events_day ON events(day)");
        db.execSQL("CREATE INDEX events_key ON events(key)");
        db.execSQL("CREATE TABLE sorted (key TEXT PRIMARY KEY, action TEXT, ts INTEGER, source TEXT)");
        db.execSQL("CREATE TABLE seen (key TEXT PRIMARY KEY, ts INTEGER)");
        db.execSQL("CREATE TABLE later (key TEXT PRIMARY KEY, item TEXT NOT NULL, addedTs INTEGER, done INTEGER DEFAULT 0, doneTs INTEGER)");
        db.execSQL("CREATE TABLE queue (pos INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT UNIQUE NOT NULL, item TEXT NOT NULL, addedTs INTEGER)");
        db.execSQL("CREATE TABLE counts (day TEXT, action TEXT, n INTEGER, PRIMARY KEY (day, action))");
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)");
        // File writer: per folder and file, the highest event id written; and the one write in progress.
        db.execSQL("CREATE TABLE written (folder TEXT, path TEXT, lastId INTEGER, PRIMARY KEY (folder, path))");
        db.execSQL("CREATE TABLE journal (folder TEXT, path TEXT, sizeBefore INTEGER, data BLOB, maxId INTEGER, PRIMARY KEY (folder, path))");
        // Other devices' events.jsonl files already read (for items sorted elsewhere).
        db.execSQL("CREATE TABLE imports (folder TEXT, path TEXT, offset INTEGER, PRIMARY KEY (folder, path))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN uid TEXT");
            db.execSQL("CREATE UNIQUE INDEX events_uid ON events(uid)");
        }
    }

    // ------------------------------------------------------------------ events

    private static final String[][] ITEM_FIELDS = {
        {"key", "300"}, {"site", "20"}, {"kind", "20"}, {"url", "2000"}, {"title", "500"},
        {"author", "200"}, {"snippet", "1000"}, {"thumb", "2000"},
    };

    private static String str(Object v) {
        if (v == null || v == JSONObject.NULL) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    /** The item fields the extension keeps (db.js pickItem), length-capped and well-formed. */
    static JSONObject pickItem(JSONObject item) throws JSONException {
        JSONObject out = new JSONObject();
        if (item == null) return out;
        for (String[] f : ITEM_FIELDS) {
            String v = str(item.opt(f[0]));
            if (v != null) out.put(f[0], Format.cut(v, Integer.parseInt(f[1])));
        }
        JSONObject extra = item.optJSONObject("extra");
        if (extra != null) {
            JSONObject e = new JSONObject();
            Iterator<String> keys = extra.keys();
            while (keys.hasNext() && e.length() < 12) {
                String k = keys.next();
                String v = str(extra.opt(k));
                if (v != null) e.put(Format.cut(k, 40), Format.cut(v, 500));
            }
            out.put("extra", e);
        }
        return out;
    }

    static Map<String, String> extraMap(String json) {
        if (json == null) return null;
        Map<String, String> m = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                m.put(k, o.optString(k));
            }
        } catch (JSONException e) {
            return m;
        }
        return m;
    }

    /**
     * Store log entries: { action, item, surface, pageUrl, note, localLater, ts }. A repeat sighting
     * inside the seenRepeatHours window is dropped. Returns the stored events, in order.
     */
    public List<Format.Event> addEvents(JSONArray entries, double seenRepeatHours, int[] repeats) throws JSONException {
        long windowMs = (long) (Math.max(0, seenRepeatHours) * 3600 * 1000);
        List<Format.Event> stored = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                JSONObject item = pickItem(entry.optJSONObject("item"));
                String action = entry.optString("action");
                String key = item.optString("key");
                if (key.isEmpty() || !Format.isAction(action)) continue;
                // The same entry sent again (a resend after a slow answer): it is stored already.
                String uid = str(entry.opt("uid"));
                if (uid != null) uid = Format.cut(uid, 80);
                if (uid != null && longQuery(db, "SELECT id FROM events WHERE uid = ?", uid) != null) {
                    if (repeats != null) repeats[0]++;
                    continue;
                }
                long ts = entry.optLong("ts", 0);
                if (ts <= 0) ts = System.currentTimeMillis();
                if (action.equals("seen")) {
                    Long prev = longQuery(db, "SELECT ts FROM seen WHERE key = ?", key);
                    if (prev != null && windowMs > 0 && ts - prev < windowMs) continue;
                    ContentValues s = new ContentValues();
                    s.put("key", key);
                    s.put("ts", ts);
                    db.insertWithOnConflict("seen", null, s, SQLiteDatabase.CONFLICT_REPLACE);
                }
                Format.Event ev = new Format.Event();
                ev.ts = ts;
                ev.day = Format.localDate(ts);
                ev.action = action;
                ev.key = key;
                ev.site = item.optString("site", null);
                ev.kind = item.optString("kind", null);
                ev.url = item.optString("url", null);
                ev.title = item.optString("title", null);
                ev.author = item.optString("author", null);
                ev.snippet = item.optString("snippet", null);
                ev.thumb = item.optString("thumb", null);
                JSONObject extra = item.optJSONObject("extra");
                ev.extra = extra == null ? null : extraMap(extra.toString());
                ev.surface = Format.cut(entry.optString("surface", ""), 40);
                ev.pageUrl = Format.cut(entry.optString("pageUrl", ""), 2000);
                String note = str(entry.opt("note"));
                if (note != null) ev.note = Format.cut(note, 200);

                ContentValues v = new ContentValues();
                v.put("ts", ev.ts);
                v.put("day", ev.day);
                v.put("action", ev.action);
                v.put("site", ev.site);
                v.put("key", ev.key);
                v.put("kind", ev.kind);
                v.put("url", ev.url);
                v.put("title", ev.title);
                v.put("author", ev.author);
                v.put("snippet", ev.snippet);
                v.put("surface", ev.surface);
                v.put("pageUrl", ev.pageUrl);
                v.put("thumb", ev.thumb);
                v.put("extra", extra == null ? null : extra.toString());
                v.put("note", ev.note);
                v.put("uid", uid);
                ev.id = db.insertOrThrow("events", null, v);
                stored.add(ev);

                if (!action.equals("seen")) {
                    ContentValues s = new ContentValues();
                    s.put("key", key);
                    s.put("action", action);
                    s.put("ts", ts);
                    s.put("source", "here");
                    db.insertWithOnConflict("sorted", null, s, SQLiteDatabase.CONFLICT_REPLACE);
                }
                if (action.equals("deferred") && entry.optBoolean("localLater", true)) {
                    JSONObject later = new JSONObject(item.toString());
                    later.put("surface", ev.surface);
                    ContentValues l = new ContentValues();
                    l.put("key", key);
                    l.put("item", later.toString());
                    l.put("addedTs", ts);
                    l.put("done", 0);
                    db.insertWithOnConflict("later", null, l, SQLiteDatabase.CONFLICT_REPLACE);
                }
                // (No UPSERT: Android 10's SQLite predates it.)
                db.execSQL("INSERT OR IGNORE INTO counts (day, action, n) VALUES (?, ?, 0)", new Object[] {ev.day, action});
                db.execSQL("UPDATE counts SET n = n + 1 WHERE day = ? AND action = ?", new Object[] {ev.day, action});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return stored;
    }

    private static Long longQuery(SQLiteDatabase db, String sql, String... args) {
        try (Cursor c = db.rawQuery(sql, args)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : null;
        }
    }

    private static final String EVENT_COLS =
            "id, ts, day, action, site, key, kind, url, title, author, snippet, surface, pageUrl, thumb, extra, note";

    private static String col(Cursor c, int i) {
        return c.isNull(i) ? null : c.getString(i);
    }

    private static Format.Event readEvent(Cursor c) {
        Format.Event ev = new Format.Event();
        ev.id = c.getLong(0);
        ev.ts = c.getLong(1);
        ev.day = col(c, 2);
        ev.action = col(c, 3);
        ev.site = col(c, 4);
        ev.key = col(c, 5);
        ev.kind = col(c, 6);
        ev.url = col(c, 7);
        ev.title = col(c, 8);
        ev.author = col(c, 9);
        ev.snippet = col(c, 10);
        ev.surface = col(c, 11);
        ev.pageUrl = col(c, 12);
        ev.thumb = col(c, 13);
        ev.extra = extraMap(col(c, 14));
        ev.note = col(c, 15);
        return ev;
    }

    public List<Format.Event> eventsAfter(long afterId, int limit) {
        List<Format.Event> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + EVENT_COLS + " FROM events WHERE id > ? ORDER BY id LIMIT " + limit,
                new String[] {String.valueOf(afterId)})) {
            while (c.moveToNext()) out.add(readEvent(c));
        }
        return out;
    }

    public long firstEventId() {
        Long v = longQuery(getReadableDatabase(), "SELECT MIN(id) FROM events");
        return v == null ? 0 : v;
    }

    /**
     * After the app's data was cleared, ids would start again at 1 and repeat ids already in this device's
     * files. Moves every stored id (and the counter) above `floor`, so the files keep increasing ids.
     */
    public void shiftIdsAbove(long floor) {
        long first = firstEventId();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (first != 0 && first <= floor) {
                long by = floor - first + 1;
                db.execSQL("UPDATE events SET id = -id");
                db.execSQL("UPDATE events SET id = -id + ?", new Object[] {by});
            }
            Long seq = longQuery(db, "SELECT seq FROM sqlite_sequence WHERE name = 'events'");
            long want = Math.max(floor, lastId(db));
            if (seq == null) db.execSQL("INSERT INTO sqlite_sequence (name, seq) VALUES ('events', ?)", new Object[] {want});
            else if (seq < want) db.execSQL("UPDATE sqlite_sequence SET seq = ? WHERE name = 'events'", new Object[] {want});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static long lastId(SQLiteDatabase db) {
        Long v = longQuery(db, "SELECT MAX(id) FROM events");
        return v == null ? 0 : v;
    }

    public boolean anythingWritten(String folder) {
        return longQuery(getReadableDatabase(), "SELECT 1 FROM written WHERE folder = ? LIMIT 1", folder) != null;
    }

    public long lastEventId() {
        Long v = longQuery(getReadableDatabase(), "SELECT MAX(id) FROM events");
        return v == null ? 0 : v;
    }

    /** An event as the extension's pages expect it (logs.js). */
    public static JSONObject eventJson(Format.Event ev) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", ev.id);
        o.put("ts", ev.ts);
        o.put("day", ev.day);
        o.put("action", ev.action);
        o.putOpt("site", ev.site);
        o.put("key", ev.key);
        o.putOpt("kind", ev.kind);
        o.putOpt("url", ev.url);
        o.putOpt("title", ev.title);
        o.putOpt("author", ev.author);
        o.putOpt("snippet", ev.snippet);
        o.putOpt("surface", ev.surface);
        o.putOpt("pageUrl", ev.pageUrl);
        o.putOpt("thumb", ev.thumb);
        if (ev.extra != null) o.put("extra", new JSONObject(ev.extra));
        o.putOpt("note", ev.note);
        return o;
    }

    private static String likeArg(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** Newest first. filter: { action, site, q, beforeId, limit, fromTs, toTs } (logs.js). */
    public JSONObject queryEvents(JSONObject filter) throws JSONException {
        int limit = (int) Math.min(Math.max(filter.optLong("limit", 100), 1), 100000);
        StringBuilder where = new StringBuilder("1");
        List<String> args = new ArrayList<>();
        String action = filter.optString("action", "");
        if (!action.isEmpty()) {
            where.append(" AND action = ?");
            args.add(action);
        }
        String site = filter.optString("site", "");
        if (!site.isEmpty()) {
            where.append(" AND site = ?");
            args.add(site);
        }
        long beforeId = filter.optLong("beforeId", 0);
        if (beforeId > 0) {
            where.append(" AND id < ?");
            args.add(String.valueOf(beforeId));
        }
        long fromTs = filter.optLong("fromTs", 0);
        if (fromTs > 0) {
            where.append(" AND ts >= ?");
            args.add(String.valueOf(fromTs));
        }
        long toTs = filter.optLong("toTs", 0);
        if (toTs > 0) {
            where.append(" AND ts <= ?");
            args.add(String.valueOf(toTs));
        }
        String q = filter.optString("q", "").trim();
        if (!q.isEmpty()) {
            where.append(" AND (title LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR snippet LIKE ? ESCAPE '\\'"
                    + " OR url LIKE ? ESCAPE '\\' OR surface LIKE ? ESCAPE '\\' OR extra LIKE ? ESCAPE '\\')");
            String like = likeArg(q);
            for (int i = 0; i < 6; i++) args.add(like);
        }
        JSONArray events = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + EVENT_COLS + " FROM events WHERE " + where
                + " ORDER BY id DESC LIMIT " + limit, args.toArray(new String[0]))) {
            while (c.moveToNext()) events.put(eventJson(readEvent(c)));
        }
        JSONObject res = new JSONObject();
        res.put("events", events);
        res.put("scanned", events.length());
        return res;
    }

    public JSONObject countsFor(String day) throws JSONException {
        JSONObject out = new JSONObject();
        for (String a : Format.ACTIONS) out.put(a, 0);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT action, n FROM counts WHERE day = ?", new String[] {day})) {
            while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        }
        return out;
    }

    private long count(String table) {
        Long v = longQuery(getReadableDatabase(), "SELECT COUNT(*) FROM " + table);
        return v == null ? 0 : v;
    }

    public JSONObject totals() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("events", count("events"));
        o.put("sorted", count("sorted"));
        o.put("later", count("later"));
        return o;
    }

    // ------------------------------------------------------------------ sorted

    public List<String> filterSorted(Collection<String> keys) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        for (String k : keys) {
            try (Cursor c = db.rawQuery("SELECT 1 FROM sorted WHERE key = ?", new String[] {k})) {
                if (c.moveToFirst()) out.add(k);
            }
        }
        return out;
    }

    /** Items sorted on another device. Never replaces this device's own record. Returns the keys that are new. */
    public List<String> importSorted(List<String[]> keyActionTs) {
        List<String> added = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String[] k : keyActionTs) {
                ContentValues v = new ContentValues();
                v.put("key", k[0]);
                v.put("action", k[1]);
                v.put("ts", Long.parseLong(k[2]));
                v.put("source", "other");
                if (db.insertWithOnConflict("sorted", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) added.add(k[0]);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    public long importedCount() {
        Long v = longQuery(getReadableDatabase(), "SELECT COUNT(*) FROM sorted WHERE source = 'other'");
        return v == null ? 0 : v;
    }

    public void resetSorted() {
        getWritableDatabase().delete("sorted", null, null);
        getWritableDatabase().delete("imports", null, null); // so other devices' files are read again later
    }

    // ------------------------------------------------------------------ Later list

    public JSONArray laterList() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT key, item, addedTs, done, doneTs FROM later ORDER BY addedTs DESC", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject(c.getString(1));
                o.put("key", c.getString(0));
                o.put("addedTs", c.getLong(2));
                o.put("done", c.getInt(3) != 0);
                if (!c.isNull(4)) o.put("doneTs", c.getLong(4));
                out.put(o);
            }
        }
        return out;
    }

    public void laterSetDone(String key, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        if (done) v.put("doneTs", System.currentTimeMillis());
        else v.putNull("doneTs");
        getWritableDatabase().update("later", v, "key = ?", new String[] {key});
    }

    public void laterRemove(String key) {
        getWritableDatabase().delete("later", "key = ?", new String[] {key});
    }

    // ------------------------------------------------------------------ Up next (the app's play queue)

    public int queueAdd(JSONObject item) throws JSONException {
        JSONObject it = pickItem(item);
        String key = it.optString("key");
        // Only YouTube videos: Up next opens these addresses by itself.
        if (key.isEmpty() || !it.optString("url").matches("https://(www\\.|m\\.)?youtube\\.com/(watch\\?v=|shorts/)[\\w-]+.*")) return -1;
        it.putOpt("id", item.optString("videoId", null));
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("item", it.toString());
        v.put("addedTs", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("queue", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return queueSize();
    }

    public int queueSize() {
        return (int) count("queue");
    }

    public JSONArray queueList() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT key, item, addedTs FROM queue ORDER BY pos", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject(c.getString(1));
                o.put("key", c.getString(0));
                o.put("addedTs", c.getLong(2));
                out.put(o);
            }
        }
        return out;
    }

    /** Takes an item off Up next (the first one when key is null) and returns it, or null. */
    public JSONObject queueTake(String key) throws JSONException {
        String sql = key == null ? "SELECT key, item FROM queue ORDER BY pos LIMIT 1" : "SELECT key, item FROM queue WHERE key = ?";
        JSONObject item = null;
        String k = null;
        try (Cursor c = getReadableDatabase().rawQuery(sql, key == null ? null : new String[] {key})) {
            if (c.moveToFirst()) {
                k = c.getString(0);
                item = new JSONObject(c.getString(1));
            }
        }
        if (k != null) getWritableDatabase().delete("queue", "key = ?", new String[] {k});
        return item;
    }

    // ------------------------------------------------------------------ key/value

    public String kvGet(String k, String fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k = ?", new String[] {k})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : fallback;
        }
    }

    public void kvSet(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ------------------------------------------------------------------ file writer bookkeeping

    public long writtenUpTo(String folder, String path) {
        Long v = longQuery(getReadableDatabase(), "SELECT lastId FROM written WHERE folder = ? AND path = ?", folder, path);
        return v == null ? 0 : v;
    }

    /** Before appending: what is about to be written, so a crash mid-write can be finished (never redone). */
    public void journalPut(String folder, String path, long sizeBefore, byte[] data, long maxId) {
        ContentValues v = new ContentValues();
        v.put("folder", folder);
        v.put("path", path);
        v.put("sizeBefore", sizeBefore);
        v.put("data", data);
        v.put("maxId", maxId);
        getWritableDatabase().insertWithOnConflict("journal", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** After appending: the file has every line up to maxId; the journal entry is done with. */
    public void markWritten(String folder, String path, long maxId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("folder", folder);
            v.put("path", path);
            v.put("lastId", maxId);
            db.insertWithOnConflict("written", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("journal", "folder = ? AND path = ?", new String[] {folder, path});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void journalDrop(String folder, String path) {
        getWritableDatabase().delete("journal", "folder = ? AND path = ?", new String[] {folder, path});
    }

    public static final class Journal {
        public String path;
        public long sizeBefore;
        public byte[] data;
        public long maxId;
    }

    public List<Journal> journal(String folder) {
        List<Journal> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT path, sizeBefore, data, maxId FROM journal WHERE folder = ?", new String[] {folder})) {
            while (c.moveToNext()) {
                Journal j = new Journal();
                j.path = c.getString(0);
                j.sizeBefore = c.getLong(1);
                j.data = c.getBlob(2);
                j.maxId = c.getLong(3);
                out.add(j);
            }
        }
        return out;
    }

    public long importOffset(String folder, String path) {
        Long v = longQuery(getReadableDatabase(), "SELECT offset FROM imports WHERE folder = ? AND path = ?", folder, path);
        return v == null ? 0 : v;
    }

    public void setImportOffset(String folder, String path, long offset) {
        ContentValues v = new ContentValues();
        v.put("folder", folder);
        v.put("path", path);
        v.put("offset", offset);
        getWritableDatabase().insertWithOnConflict("imports", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
