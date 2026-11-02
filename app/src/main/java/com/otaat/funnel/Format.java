package com.otaat.funnel;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Log lines and file layout, written exactly like the Firefox extension's shared/format.js
 * (mdLine, fileHeader, jsonlLine, dayFolder), so the phone's files read the same as the desktop's.
 * No Android imports: tools/FormatCheck.java runs this on a PC and the tests compare it with format.js.
 */
public final class Format {
    private Format() {}

    public static final String[] ACTIONS = {"seen", "skipped", "deferred", "queued"};

    /** One log event, as stored (the extension's event shape). */
    public static final class Event {
        public long id;
        public long ts;
        public String day;
        public String action;
        public String site;
        public String key;
        public String kind;
        public String url;
        public String title;
        public String author;
        public String snippet;
        public String surface;
        public String pageUrl;
        public String thumb;
        public Map<String, String> extra; // null = none; empty map = {}
        public String note;
    }

    public static boolean isAction(String a) {
        for (String x : ACTIONS) if (x.equals(a)) return true;
        return false;
    }

    static String siteName(String site) {
        if ("youtube".equals(site)) return "YouTube";
        if ("reddit".equals(site)) return "Reddit";
        if ("x".equals(site)) return "X";
        return site == null ? "undefined" : site;
    }

    static String actionTitle(String action) {
        switch (action) {
            case "seen": return "Seen";
            case "skipped": return "Skipped (Next)";
            case "deferred": return "Deferred (Later)";
            case "queued": return "Queued (YouTube queue)";
            default: return action;
        }
    }

    // ------------------------------------------------------------------ dates (device's local time zone)

    static ZoneId zone = ZoneId.systemDefault();

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    // en-GB long date, as the extension writes it: "Wednesday, 14 October 2026"
    private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.UK);

    public static String localDate(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone).format(DATE);
    }

    public static String localStamp(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone).format(STAMP);
    }

    public static String isoTime(long ts) {
        return ISO.format(Instant.ofEpochMilli(ts));
    }

    public static String dayTitle(String day) {
        return LocalDate.parse(day, DATE).format(DAY_TITLE);
    }

    // ------------------------------------------------------------------ text

    /** Half of an emoji (a lone UTF-16 surrogate) can't be written as UTF-8; replace it. */
    public static String wellFormed(String s) {
        if (s == null) return "";
        StringBuilder out = null;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean bad = false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    if (out != null) out.append(c).append(s.charAt(i + 1));
                    i++;
                    continue;
                }
                bad = true;
            } else if (Character.isLowSurrogate(c)) {
                bad = true;
            }
            if (bad && out == null) {
                out = new StringBuilder(n);
                out.append(s, 0, i);
            }
            if (out != null) out.append(bad ? '�' : c);
        }
        return out == null ? s : out.toString();
    }

    /** At most n UTF-16 units, without cutting an emoji in half. */
    public static String cut(String text, int n) {
        String s = wellFormed(text == null ? "" : text);
        if (s.length() <= n) return s;
        char code = s.charAt(n - 1);
        return s.substring(0, Character.isHighSurrogate(code) ? n - 1 : n);
    }

    /** JavaScript's whitespace (\s and String.prototype.trim). */
    static boolean isJsSpace(char c) {
        switch (c) {
            case '\t': case '\n': case 0x0B: case '\f': case '\r': case ' ':
            case 0x00A0: case 0x1680: case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000: case 0xFEFF:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200A;
        }
    }

    static String collapseSpaces(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean inSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isJsSpace(c)) {
                if (!inSpace) b.append(' ');
                inSpace = true;
            } else {
                b.append(c);
                inSpace = false;
            }
        }
        return b.toString();
    }

    static String jsTrim(String s) {
        int a = 0, z = s.length();
        while (a < z && isJsSpace(s.charAt(a))) a++;
        while (z > a && isJsSpace(s.charAt(z - 1))) z--;
        return s.substring(a, z);
    }

    static String jsTrimEnd(String s) {
        int z = s.length();
        while (z > 0 && isJsSpace(s.charAt(z - 1))) z--;
        return s.substring(0, z);
    }

    /** One line of plain text: no newlines, collapsed whitespace, length-capped. */
    public static String clean(String text, int max) {
        if (text == null) return "";
        String s = jsTrim(collapseSpaces(wellFormed(text)));
        if (s.length() > max) s = jsTrimEnd(cut(s, max - 1)) + "…";
        return s;
    }

    public static String clean(String text) {
        return clean(text, 300);
    }

    private static final Pattern MD_ESCAPE = Pattern.compile("([\\\\\\[\\]])");

    static String mdText(String text, int max) {
        return MD_ESCAPE.matcher(clean(text, max)).replaceAll("\\\\$1");
    }

    static String mdUrl(String url) {
        return (url == null ? "" : url).replace(" ", "%20").replace("(", "%28").replace(")", "%29");
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    public static String titleOf(Event ev) {
        String t = clean(ev.title);
        if (!t.isEmpty()) return t;
        t = clean(ev.snippet, 120);
        if (!t.isEmpty()) return t;
        if (!empty(ev.url)) return ev.url;
        if (!empty(ev.key)) return ev.key;
        return "(untitled)";
    }

    /** "- 2026-10-14 14:03:22 · YouTube · [Title](url) · Channel · 12:34 · home" */
    public static String mdLine(Event ev) {
        List<String> parts = new ArrayList<>();
        parts.add(localStamp(ev.ts));
        parts.add(siteName(ev.site));
        String title = mdText(titleOf(ev), 200);
        parts.add(!empty(ev.url) ? "[" + title + "](" + mdUrl(ev.url) + ")" : title);
        if (!empty(ev.author)) parts.add(mdText(ev.author, 80));
        if (ev.extra != null && !empty(ev.extra.get("community"))) parts.add(mdText(ev.extra.get("community"), 80));
        if (ev.extra != null && !empty(ev.extra.get("duration"))) parts.add(mdText(ev.extra.get("duration"), 20));
        if (!empty(ev.kind) && !ev.kind.equals("video") && !ev.kind.equals("post") && !ev.kind.equals("tweet")) parts.add(ev.kind);
        if (!empty(ev.surface)) parts.add(mdText(ev.surface, 40));
        if (!empty(ev.note)) parts.add(mdText(ev.note, 120));
        StringBuilder line = new StringBuilder("- ").append(String.join(" · ", parts));
        if (!empty(ev.snippet) && !empty(ev.title) && !clean(ev.snippet).equals(clean(ev.title))) {
            line.append("\n  > ").append(mdText(ev.snippet, 280));
        }
        return line.append('\n').toString();
    }

    public static String fileHeader(String action, String day) {
        return "# " + actionTitle(action) + " — " + dayTitle(day) + "\n\n"
                + "Written by the Funnel (One Thing At A Time) Android app. Newest entries at the bottom.\n\n";
    }

    private static final Pattern DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public static String dayOf(Event ev) {
        return ev.day != null && DAY.matcher(ev.day).matches() ? ev.day : localDate(ev.ts);
    }

    /** One folder per month, one per day inside it: 2026-10/2026-10-14 */
    public static String dayFolder(String day) {
        return day.substring(0, 7) + "/" + day;
    }

    /** The device name goes in the file name (seen.phone.md) so phone and PC files can share a folder. */
    public static String mdPath(Event ev, String device) {
        return dayFolder(dayOf(ev)) + "/" + ev.action + "." + device + ".md";
    }

    public static String jsonlPath(Event ev, String device) {
        return dayFolder(dayOf(ev)) + "/events." + device + ".jsonl";
    }

    // ------------------------------------------------------------------ JSON (byte-for-byte like JSON.stringify)

    public static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        quote(b, wellFormed(s));
        return b.toString();
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    private static void field(StringBuilder b, String name, String value) {
        if (empty(value)) return;
        if (b.length() > 1) b.append(',');
        quote(b, name);
        b.append(':');
        quote(b, wellFormed(value));
    }

    /** The extension's exportRecord(): a fixed field order, empty fields left out. */
    public static String exportRecord(Event ev) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"id\":").append(ev.id);
        field(b, "time", isoTime(ev.ts));
        field(b, "action", ev.action);
        field(b, "site", ev.site);
        field(b, "key", ev.key);
        field(b, "kind", ev.kind);
        field(b, "url", ev.url);
        field(b, "title", ev.title);
        field(b, "author", ev.author);
        field(b, "snippet", ev.snippet);
        field(b, "surface", ev.surface);
        field(b, "pageUrl", ev.pageUrl);
        field(b, "thumb", ev.thumb);
        if (ev.extra != null) {
            b.append(",\"extra\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : ev.extra.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, wellFormed(e.getKey()));
                b.append(':');
                quote(b, wellFormed(e.getValue()));
            }
            b.append('}');
        }
        field(b, "note", ev.note);
        return b.append('}').toString();
    }

    public static String jsonlLine(Event ev) {
        return exportRecord(ev) + "\n";
    }

    // ------------------------------------------------------------------ grouping into file appends

    /** Lines for one file: [event id, text], in order. */
    public static final class FileAppend {
        public final String path;
        public final String header;
        public final List<Long> ids = new ArrayList<>();
        public final List<String> lines = new ArrayList<>();

        FileAppend(String path, String header) {
            this.path = path;
            this.header = header;
        }

        public byte[] bytes(boolean withHeader, long afterId) {
            StringBuilder b = new StringBuilder();
            if (withHeader) b.append(header);
            for (int i = 0; i < ids.size(); i++) if (ids.get(i) > afterId) b.append(lines.get(i));
            return b.toString().getBytes(StandardCharsets.UTF_8);
        }

        public long maxId() {
            long m = 0;
            for (long id : ids) m = Math.max(m, id);
            return m;
        }
    }

    public static List<FileAppend> appendsFor(List<Event> events, String device) {
        Map<String, FileAppend> files = new LinkedHashMap<>();
        for (Event ev : events) {
            String md = mdPath(ev, device);
            files.computeIfAbsent(md, p -> new FileAppend(p, fileHeader(ev.action, dayOf(ev))));
            files.get(md).ids.add(ev.id);
            files.get(md).lines.add(mdLine(ev));
            String jl = jsonlPath(ev, device);
            files.computeIfAbsent(jl, p -> new FileAppend(p, ""));
            files.get(jl).ids.add(ev.id);
            files.get(jl).lines.add(jsonlLine(ev));
        }
        return new ArrayList<>(files.values());
    }
}
