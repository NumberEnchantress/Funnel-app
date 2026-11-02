package com.otaat.funnel;

import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which addresses stay in the app, which go to the browser, and YouTube links made into phone-site links. */
final class Urls {
    private Urls() {}

    static final String HOME = "https://m.youtube.com/";
    static final String WATCH_LATER = "https://m.youtube.com/playlist?list=WL";

    /** Where the engine runs and the message channel is open (YouTube itself, never other sites). */
    static final Set<String> YOUTUBE_ORIGINS = new HashSet<>(Arrays.asList("https://m.youtube.com", "https://www.youtube.com"));

    /**
     * The WebView's own user agent, minus the parts that mark it as an embedded WebView ("; wv" and
     * "Version/4.0"): YouTube then serves its normal phone site, as in Chrome on the same phone.
     */
    static String browserUserAgent(String ua) {
        return ua.replace("; wv)", ")").replaceAll(" Version/\\d+(\\.\\d+)*", "");
    }

    enum Kind { LOAD, REWRITE, EXTERNAL, BLOCK }

    static final class Route {
        final Kind kind;
        final String url;

        Route(Kind kind, String url) {
            this.kind = kind;
            this.url = url;
        }
    }

    static boolean isYoutubeHost(String host) {
        return host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com") || host.equals("youtu.be");
    }

    /** Google's sign-in and consent pages, which YouTube's own sign-in goes through. */
    static boolean isSignInHost(String host) {
        return host.equals("accounts.google.com") || host.equals("accounts.youtube.com") || host.equals("consent.youtube.com")
                || host.equals("consent.google.com") || host.equals("myaccount.google.com") || host.equals("www.google.com")
                || host.matches("accounts\\.google\\.[a-z.]+");
    }

    static Route route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            // intent:// and vnd.youtube: links ask to open the YouTube app; mailto etc. go to their apps.
            if (scheme.equals("intent") || scheme.startsWith("vnd.youtube") || scheme.equals("youtube")) return new Route(Kind.BLOCK, null);
            // mailto: and the like go to their apps; anything else (file:, content:, javascript:) is refused.
            if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("market")) return new Route(Kind.EXTERNAL, u.toString());
            return new Route(Kind.BLOCK, null);
        }
        if (host.equals("m.youtube.com")) {
            // Links out of YouTube (video descriptions): straight to the real address, in the browser.
            if ("/redirect".equals(u.getPath()) && u.getQueryParameter("q") != null) {
                Uri target = Uri.parse(u.getQueryParameter("q"));
                String ts = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
                if (!ts.equals("http") && !ts.equals("https")) return new Route(Kind.BLOCK, null);
                return route(target);
            }
            return new Route(Kind.LOAD, null);
        }
        if (isYoutubeHost(host)) return new Route(Kind.REWRITE, toMobile(u));
        if (isSignInHost(host)) return new Route(Kind.LOAD, null);
        return new Route(Kind.EXTERNAL, u.toString());
    }

    /** Any YouTube link as a phone-site link: youtu.be/ID → m.youtube.com/watch?v=ID, www → m. */
    static String toMobile(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("youtu.be")) {
            String id = u.getPath() == null ? "" : u.getPath().replaceAll("^/+", "").split("/")[0];
            Uri.Builder b = Uri.parse(HOME).buildUpon().path("/watch").appendQueryParameter("v", id);
            String t = u.getQueryParameter("t");
            if (t != null) b.appendQueryParameter("t", t);
            String list = u.getQueryParameter("list");
            if (list != null) b.appendQueryParameter("list", list);
            return b.build().toString();
        }
        if (!isYoutubeHost(host)) return HOME;
        return u.buildUpon().scheme("https").authority("m.youtube.com").build().toString();
    }

    private static final Pattern LINK = Pattern.compile("https?://(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)/\\S+", Pattern.CASE_INSENSITIVE);

    /** The first YouTube link in shared text. */
    static String findYoutubeUrl(String text) {
        if (text == null) return null;
        Matcher m = LINK.matcher(text);
        return m.find() ? toMobile(Uri.parse(m.group())) : null;
    }
}
