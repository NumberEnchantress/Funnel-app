package com.otaat.funnel;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the log files into the folder the user chose (Storage Access Framework), in the extension's
 * layout: YYYY-MM/YYYY-MM-DD/seen.<device>.md … and events.<device>.jsonl.
 *
 * Safety rules, as for the extension's native writer:
 *  - it only ever appends to the end of a file; it never truncates, replaces or deletes anything;
 *  - a line is never written twice: per folder and file, the highest event id written is recorded;
 *  - a crash in the middle of a write is finished on the next run, from a journal of exactly what was
 *    being written, so a file never keeps half a line and nothing is written twice.
 * Only used from the app's single I/O thread.
 */
public final class FileSink {
    private static final String OCTET = "application/octet-stream"; // keeps our file names as they are
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern EVENTS = Pattern.compile("^events(\\.[A-Za-z0-9_-]+)?\\.jsonl$");

    private final ContentResolver resolver;
    private final Store store;
    private final Map<String, String> docIds = new HashMap<>(); // relative path -> document id (current folder)
    private Uri tree;

    public long lastWriteTs;
    public int lastWriteCount;
    public String lastError = "";

    public FileSink(Context context, Store store) {
        this.resolver = context.getContentResolver();
        this.store = store;
    }

    public void setFolder(Uri tree) {
        if (tree == null ? this.tree == null : tree.equals(this.tree)) return;
        this.tree = tree;
        docIds.clear();
        lastError = "";
    }

    public boolean hasFolder() {
        return tree != null;
    }

    private String folderKey() {
        return tree.toString();
    }

    /** "Documents/OTAAT" for a folder on the phone's own storage, else the provider's name for it. */
    public String folderLabel() {
        if (tree == null) return "";
        String id = DocumentsContract.getTreeDocumentId(tree);
        if ("com.android.externalstorage.documents".equals(tree.getAuthority())) {
            int colon = id.indexOf(':');
            String vol = colon > 0 ? id.substring(0, colon) : "";
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return ("primary".equals(vol) ? "" : vol + ": ") + (path.isEmpty() ? "(top level)" : path);
        }
        try (Cursor c = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                new String[] {Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall through
        }
        return id;
    }

    // ------------------------------------------------------------------ documents

    private Uri docUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
    }

    private static final class Child {
        String id, name, mime;
        long size;
    }

    private List<Child> children(String parentId) {
        List<Child> out = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = resolver.query(uri, new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Child ch = new Child();
                ch.id = c.getString(0);
                ch.name = c.getString(1);
                ch.mime = c.getString(2);
                ch.size = c.isNull(3) ? 0 : c.getLong(3);
                out.add(ch);
            }
        }
        return out;
    }

    private String child(String parentId, String name) {
        for (Child c : children(parentId)) if (name.equals(c.name)) return c.id;
        return null;
    }

    /** The document id for a relative path; created (folders and file) when `create` is set. */
    private String resolve(String rel, boolean create) throws IOException {
        String hit = docIds.get(rel);
        if (hit != null) return hit;
        String[] parts = rel.split("/");
        String id = DocumentsContract.getTreeDocumentId(tree);
        StringBuilder sofar = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (sofar.length() > 0) sofar.append('/');
            sofar.append(parts[i]);
            String known = docIds.get(sofar.toString());
            if (known != null) {
                id = known;
                continue;
            }
            String next = child(id, parts[i]);
            if (next == null) {
                if (!create) return null;
                boolean dir = i < parts.length - 1;
                Uri made = DocumentsContract.createDocument(resolver, docUri(id), dir ? Document.MIME_TYPE_DIR : OCTET, parts[i]);
                if (made == null) throw new IOException("Couldn't create " + sofar);
                next = DocumentsContract.getDocumentId(made);
                // A provider that renamed it (e.g. "seen.phone.md (1)") would break the layout: say so.
                String name = displayName(made);
                if (name != null && !name.equals(parts[i])) throw new IOException("The folder renamed " + parts[i] + " to " + name);
            }
            docIds.put(sofar.toString(), next);
            id = next;
        }
        return id;
    }

    private String displayName(Uri uri) {
        try (Cursor c = resolver.query(uri, new String[] {Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private long size(Uri uri) throws IOException {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("Couldn't open " + uri);
            return pfd.getStatSize();
        }
    }

    /** Appends bytes to the end of the file and waits until they are on disk. */
    private void append(Uri uri, byte[] data) throws IOException {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "wa")) {
            if (pfd == null) throw new IOException("Couldn't open " + uri + " for writing");
            FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
    }

    // ------------------------------------------------------------------ writing

    /** Finish (or forget) a write that was cut short by a crash. */
    private void recover(String folder) throws IOException {
        for (Store.Journal j : store.journal(folder)) {
            String id = resolve(j.path, false);
            long size = id == null ? 0 : size(docUri(id));
            if (size <= j.sizeBefore) {
                store.journalDrop(folder, j.path); // nothing of it arrived: written again as usual
            } else if (size >= j.sizeBefore + j.data.length) {
                store.markWritten(folder, j.path, j.maxId); // all of it arrived
            } else {
                // Part of it arrived: append exactly the rest, so the half line is completed.
                append(docUri(id), Arrays.copyOfRange(j.data, (int) (size - j.sizeBefore), j.data.length));
                store.markWritten(folder, j.path, j.maxId);
            }
        }
    }

    private String wmKey() {
        return "wm:" + folderKey();
    }

    /**
     * The highest event id already in this device's own events files here. Non-zero after the app's data
     * was cleared (or it was reinstalled) while the old files are still in the folder.
     */
    private long highestOwnId(String device) throws IOException {
        String own = "events." + device + ".jsonl";
        long max = 0;
        String root = DocumentsContract.getTreeDocumentId(tree);
        for (Child month : children(root)) {
            if (!Document.MIME_TYPE_DIR.equals(month.mime) || !MONTH.matcher(month.name).matches()) continue;
            for (Child day : children(month.id)) {
                if (!Document.MIME_TYPE_DIR.equals(day.mime) || !DAY.matcher(day.name).matches()) continue;
                for (Child f : children(day.id)) {
                    if (!f.name.equals(own) || f.size == 0) continue;
                    // Only the end of the file: its last complete lines have the highest ids.
                    String tail = readTail(docUri(f.id), Math.max(0, f.size - 64 * 1024));
                    for (String line : tail.split("\n")) {
                        try {
                            max = Math.max(max, new JSONObject(line).optLong("id", 0));
                        } catch (JSONException cutOff) {
                            // the first line of the tail, cut in the middle
                        }
                    }
                }
            }
        }
        return max;
    }

    private String readTail(Uri uri, long from) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return "";
            long skipped = 0;
            while (skipped < from) {
                long s = in.skip(from - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Write everything not yet in the files. Throws on a problem (the next run carries on from there). */
    public void sync(String device) throws IOException {
        if (tree == null) return;
        String folder = folderKey();
        try {
            recover(folder);
            // First write to this folder: if it already holds this device's files (data cleared, reinstall),
            // carry on from their highest id instead of repeating ids.
            String seededKey = "seeded:" + device + ":" + folder;
            if (store.kvGet(seededKey, null) == null) {
                if (!store.anythingWritten(folder)) {
                    long high = highestOwnId(device);
                    if (high > 0) store.shiftIdsAbove(high);
                }
                store.kvSet(seededKey, "1");
            }
            long wm = Long.parseLong(store.kvGet(wmKey(), "0"));
            int count = 0;
            for (;;) {
                List<Format.Event> batch = store.eventsAfter(wm, 300);
                if (batch.isEmpty()) break;
                for (Format.FileAppend f : Format.appendsFor(batch, device)) {
                    long last = store.writtenUpTo(folder, f.path);
                    if (f.maxId() <= last) continue;
                    Uri uri = docUri(resolve(f.path, true));
                    long size = size(uri);
                    byte[] data = f.bytes(size == 0, last);
                    store.journalPut(folder, f.path, size, data, f.maxId());
                    append(uri, data);
                    store.markWritten(folder, f.path, f.maxId());
                }
                wm = batch.get(batch.size() - 1).id;
                store.kvSet(wmKey(), String.valueOf(wm));
                count += batch.size();
            }
            if (count > 0) {
                lastWriteTs = System.currentTimeMillis();
                lastWriteCount = count;
            }
            lastError = "";
        } catch (FileNotFoundException | SecurityException e) {
            docIds.clear(); // a folder or file was moved or deleted outside the app: look again next time
            lastError = describe(e);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } catch (IOException | RuntimeException e) {
            docIds.clear();
            lastError = describe(e);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (e instanceof SecurityException) return "The app no longer has access to the folder. Choose it again in Settings.";
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** Write the whole history again: files already holding a line are skipped (per file bookkeeping). */
    public void rebuild(String device) throws IOException {
        if (tree == null) return;
        store.kvSet(wmKey(), "0");
        sync(device);
    }

    public long pending() {
        long last = store.lastEventId();
        if (tree == null) return last;
        return Math.max(0, last - Long.parseLong(store.kvGet(wmKey(), "0")));
    }

    // ------------------------------------------------------------------ items sorted on other devices

    /**
     * Reads the other devices' events files in this folder (the desktop extension's events.jsonl, another
     * phone's events.tablet.jsonl, …) and returns what was sorted there: [key, action, ts]. Only new
     * complete lines are read each time.
     */
    public List<String[]> readOthersSorted(String device) throws IOException {
        List<String[]> out = new ArrayList<>();
        if (tree == null) return out;
        String folder = folderKey();
        String own = "events." + device + ".jsonl";
        String root = DocumentsContract.getTreeDocumentId(tree);
        for (Child month : children(root)) {
            if (!Document.MIME_TYPE_DIR.equals(month.mime) || !MONTH.matcher(month.name).matches()) continue;
            for (Child day : children(month.id)) {
                if (!Document.MIME_TYPE_DIR.equals(day.mime) || !DAY.matcher(day.name).matches()) continue;
                for (Child f : children(day.id)) {
                    if (f.name.equals(own) || !EVENTS.matcher(f.name).matches()) continue;
                    String rel = month.name + "/" + day.name + "/" + f.name;
                    long offset = store.importOffset(folder, rel);
                    if (f.size < offset) offset = 0; // the file was rewritten: read it again (repeats are ignored)
                    if (f.size == offset) continue;
                    long consumed = readLines(docUri(f.id), offset, out);
                    store.setImportOffset(folder, rel, offset + consumed);
                }
            }
        }
        return out;
    }

    private long readLines(Uri uri, long offset, List<String[]> out) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return 0;
            long skipped = 0;
            while (skipped < offset) {
                long s = in.skip(offset - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        }
        byte[] data = buf.toByteArray();
        int end = data.length;
        while (end > 0 && data[end - 1] != '\n') end--; // only complete lines
        String text = new String(data, 0, end, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(line);
                String action = o.optString("action");
                String key = o.optString("key");
                if (key.isEmpty() || !(action.equals("skipped") || action.equals("deferred") || action.equals("queued"))) continue;
                long ts = parseIso(o.optString("time"));
                out.add(new String[] {key, action, String.valueOf(ts)});
            } catch (JSONException ignored) {
                // a line another program is still writing, or not ours
            }
        }
        return end;
    }

    private static long parseIso(String s) {
        try {
            return java.time.Instant.parse(s).toEpochMilli();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }
}
