import com.otaat.funnel.Format;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;

/**
 * Test helper (tests/format.test.js): reads events from stdin, one per line, tab-separated base64 fields
 * ("-" = absent), and prints the app's Markdown line, JSONL line and file header for each, base64-encoded,
 * so the test can compare them byte for byte with the extension's shared/format.js.
 * Fields: id ts day action site key kind url title author snippet surface pageUrl thumb note extra
 * (extra: base64 key,value,key,value… or "-").
 */
public class FormatCheck {
    static String dec(String s) {
        return s.equals("-") ? null : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    static String enc(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        StringBuilder out = new StringBuilder();
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            Format.Event ev = new Format.Event();
            ev.id = Long.parseLong(dec(f[0]));
            ev.ts = Long.parseLong(dec(f[1]));
            ev.day = dec(f[2]);
            ev.action = dec(f[3]);
            ev.site = dec(f[4]);
            ev.key = dec(f[5]);
            ev.kind = dec(f[6]);
            ev.url = dec(f[7]);
            ev.title = dec(f[8]);
            ev.author = dec(f[9]);
            ev.snippet = dec(f[10]);
            ev.surface = dec(f[11]);
            ev.pageUrl = dec(f[12]);
            ev.thumb = dec(f[13]);
            ev.note = dec(f[14]);
            if (!f[15].equals("-")) {
                ev.extra = new LinkedHashMap<>();
                String[] kv = f[15].isEmpty() ? new String[0] : f[15].split(",");
                for (int i = 0; i + 1 < kv.length; i += 2) ev.extra.put(dec(kv[i]), dec(kv[i + 1]));
            }
            out.append(enc(Format.mdLine(ev))).append('\t').append(enc(Format.jsonlLine(ev))).append('\t')
                    .append(enc(Format.fileHeader(ev.action, Format.dayOf(ev)))).append('\t')
                    .append(enc(Format.mdPath(ev, "phone") + "|" + Format.jsonlPath(ev, "phone"))).append('\n');
        }
        System.out.print(out);
    }
}
