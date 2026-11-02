package com.otaat.funnel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * The YouTube screen: YouTube's phone site in a WebView, with the One Thing At A Time engine injected
 * at document start, and the Next / Later / Queue bar drawn by the app below the page.
 */
public class MainActivity extends Activity implements Bridge.Host {
    private static final int REQ_FOLDER = 1;

    private final App app = App.get();
    private WebView web;
    private FrameLayout root;
    private LinearLayout bar, buttons;
    private TextView status, message, countChip, folderChip;
    private Button next, later, queue;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ScriptHandler script;
    private String scriptSource;
    private Bridge bridge;
    /** A new secret per screen: only the script the app injects knows it (see Bridge). */
    private final String token = java.util.UUID.randomUUID().toString();
    private JavaScriptReplyProxy page;
    private JSONObject uiState = new JSONObject();
    private final Runnable hideMessage = () -> message.setVisibility(View.GONE);

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Without these the feed can't be hidden before it is drawn: refuse rather than show it unlogged.
            TextView t = new TextView(this);
            t.setPadding(dp(24), dp(48), dp(24), dp(24));
            t.setTextSize(17);
            t.setText("Funnel needs a newer Android System WebView.\n\nUpdate \"Android System WebView\" (or Chrome) from the Play Store, then open Funnel again.");
            setContentView(t);
            return;
        }
        buildUi();
        setupWebView();
        app.youtube = this;
        Bundle crashState = getIntent() == null ? null : getIntent().getBundleExtra("webState");
        if (saved == null && crashState != null) saved = crashState;
        if (saved != null && web.restoreState(saved) != null) {
            // back where we were
        } else {
            String url = urlFromIntent(getIntent());
            web.loadUrl(url != null ? url : Urls.HOME);
        }
        if (!app.welcomed() && app.folder() == null) welcome();
        refreshChips();
        app.io.execute(app::importNow);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private boolean night() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int bg() {
        return night() ? Color.rgb(15, 15, 15) : Color.WHITE;
    }

    private int fg() {
        return night() ? Color.rgb(241, 241, 241) : Color.rgb(15, 15, 15);
    }

    private int muted() {
        return night() ? Color.rgb(170, 170, 170) : Color.rgb(96, 96, 96);
    }

    private Button bigButton(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(48));
        b.setStateListAnimator(null);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(24));
        d.setColor(primary ? (night() ? Color.rgb(241, 241, 241) : Color.rgb(15, 15, 15)) : (night() ? Color.rgb(39, 39, 39) : Color.rgb(242, 242, 242)));
        b.setBackground(d);
        b.setTextColor(primary ? bg() : fg());
        b.setOnClickListener(l);
        return b;
    }

    private TextView chip(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(l == null ? muted() : fg());
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinHeight(dp(40));
        if (l != null) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            t.setBackgroundResource(tv.resourceId);
            t.setOnClickListener(l);
        }
        return t;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(bg());
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        column.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // The bar's height only changes between feed pages (buttons) and other pages (no buttons). Messages
        // are drawn over the bar and the status goes in the link row, so pressing a button never resizes
        // the page (a page resizing mid-scroll could leave two Shorts half on screen).
        FrameLayout barFrame = new FrameLayout(this);
        column.addView(barFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(bg());
        bar.setPadding(dp(8), dp(4), dp(8), 0);
        barFrame.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        message = new TextView(this);
        message.setTextSize(14);
        message.setGravity(Gravity.CENTER_VERTICAL);
        message.setPadding(dp(14), dp(6), dp(14), dp(6));
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.BOTTOM);
        mlp.setMargins(dp(8), 0, dp(8), 0);
        barFrame.addView(message, mlp);

        status = new TextView(this); // (kept for the chip row's text; not shown on its own)

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setVisibility(View.GONE);
        next = bigButton("Next →", true, v -> command("next"));
        later = bigButton("Later", false, v -> command("later"));
        queue = bigButton("Queue", false, v -> command("queue"));
        LinearLayout.LayoutParams lp;
        lp = new LinearLayout.LayoutParams(0, dp(48), 2f);
        lp.setMargins(dp(2), dp(2), dp(4), dp(2));
        buttons.addView(next, lp);
        lp = new LinearLayout.LayoutParams(0, dp(48), 1.4f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        buttons.addView(later, lp);
        lp = new LinearLayout.LayoutParams(0, dp(48), 1.2f);
        lp.setMargins(dp(4), dp(2), dp(2), dp(2));
        buttons.addView(queue, lp);
        bar.addView(buttons);

        // Always there, one tap each: today's count, Logs, Watch later, Later list, Settings.
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        folderChip = chip("⚠ Choose log folder", v -> chooseFolder());
        folderChip.setTextColor(Color.rgb(230, 140, 0));
        links.addView(folderChip);
        countChip = chip("", null);
        countChip.setMaxWidth(dp(260));
        countChip.setSingleLine(true);
        countChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        links.addView(countChip);
        links.addView(chip("Logs", v -> openPage("logs", "")));
        links.addView(chip("Watch later", v -> web.loadUrl(Urls.WATCH_LATER)));
        links.addView(chip("Later list", v -> openPage("later", "")));
        links.addView(chip("Settings", v -> openPage("options", "")));
        scroll.addView(links);
        bar.addView(scroll);

        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (customView != null) {
                v.setPadding(0, 0, 0, 0);
            } else {
                android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            }
            return WindowInsets.CONSUMED;
        });
        applySystemBarColors();

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
    }

    private void applySystemBarColors() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        c.setSystemBarsAppearance(night() ? 0 : light, light);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(Urls.browserUserAgent(WebSettings.getDefaultUserAgent(this)));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.emptySet());
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        bridge = new Bridge(this, true);
        bridge.setToken(token);
        WebViewCompat.addWebMessageListener(web, "otaatNative", Urls.YOUTUBE_ORIGINS, bridge.listener(p -> page = p));
        registerScript();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                if (req.isRedirect() && Urls.isYoutubeHost(String.valueOf(req.getUrl().getHost()))) return false;
                return route(req.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // A whole new page: its own script will say what the bar shows.
                onUiUpdate(null);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.w(App.TAG, "WebView renderer gone; starting over");
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) parent.removeView(web);
                Bundle state = new Bundle();
                web.saveState(state);
                web.destroy();
                web = null;
                // Start again with the same history (the activity is not recreated by Android here).
                Intent again = new Intent(MainActivity.this, MainActivity.class).putExtra("webState", state);
                finish();
                startActivity(again);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });
    }

    /** The injected script, with the user's settings in it so the very first frame uses them. */
    private void registerScript() {
        if (scriptSource == null) scriptSource = asset("inject/youtube.js");
        String src = scriptSource.replace("/*@SETTINGS@*/null", app.settings().toString())
                .replace("'@TOKEN@'", "'" + token + "'");
        if (script != null) script.remove();
        script = WebViewCompat.addDocumentStartJavaScript(web, src, Urls.YOUTUBE_ORIGINS);
    }

    private String asset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("missing asset " + path, e);
        }
    }

    // ------------------------------------------------------------------ navigation

    /** true = handled here (not loaded in the WebView as it is). */
    private boolean route(Uri u) {
        Urls.Route r = Urls.route(u);
        switch (r.kind) {
            case LOAD:
                return false;
            case REWRITE:
                web.loadUrl(r.url);
                return true;
            case EXTERNAL:
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (RuntimeException e) { // no app for it, or an address Android refuses (file:)
                    onToast("No app can open that link.", "warn", 3000);
                }
                return true;
            default:
                return true; // blocked (e.g. "open in the YouTube app" links)
        }
    }

    private String urlFromIntent(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) return Urls.toMobile(intent.getData());
        if (Intent.ACTION_SEND.equals(intent.getAction())) return Urls.findYoutubeUrl(intent.getStringExtra(Intent.EXTRA_TEXT));
        return null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null && web != null) web.loadUrl(url);
    }

    private void goBack() {
        if (customView != null) {
            if (customCallback != null) customCallback.onCustomViewHidden();
            exitFullscreen();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        goBack(); // Android 11 and 12 (newer versions use the back callback)
    }

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customCallback = callback;
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        root.requestApplyInsets();
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        customCallback = null;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.show(WindowInsets.Type.systemBars());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        root.requestApplyInsets();
    }

    // ------------------------------------------------------------------ talking to the page

    private void push(JSONObject m) {
        if (page == null) return;
        try {
            page.postMessage(m.toString());
        } catch (IllegalStateException e) {
            page = null; // that page is gone
        }
    }

    private void command(String name) {
        try {
            JSONObject inner = new JSONObject().put("type", "command").put("name", name);
            push(new JSONObject().put("type", "message").put("message", inner));
        } catch (JSONException ignored) {
            // not possible
        }
    }

    /** Settings changed (or the sorted list was reset): new pages get them at once, this one is told. */
    void settingsChanged() {
        if (web == null) return;
        registerScript();
        try {
            JSONObject change = new JSONObject().put("settings", new JSONObject().put("newValue", app.settings()));
            push(new JSONObject().put("type", "storage").put("changes", change));
        } catch (JSONException ignored) {
            // not possible
        }
    }

    /** Items sorted on another device: they leave the feed on screen too (the extension's sortedPing). */
    void sortedElsewhere(List<String> keys) {
        try {
            JSONObject ping = new JSONObject().put("keys", new JSONArray(keys)).put("ts", System.currentTimeMillis());
            JSONObject change = new JSONObject().put("sortedPing", new JSONObject().put("newValue", ping));
            push(new JSONObject().put("type", "storage").put("changes", change));
        } catch (JSONException ignored) {
            // not possible
        }
    }

    // ------------------------------------------------------------------ Bridge.Host

    @Override
    public void onUiUpdate(JSONObject state) {
        uiState = state == null ? new JSONObject() : state;
        boolean visible = uiState.optBoolean("visible", false);
        buttons.setVisibility(visible ? View.VISIBLE : View.GONE);
        next.setEnabled(uiState.optBoolean("nextEnabled"));
        later.setEnabled(uiState.optBoolean("laterEnabled"));
        queue.setVisibility(uiState.optBoolean("showQueue") ? View.VISIBLE : View.GONE);
        queue.setEnabled(uiState.optBoolean("queueEnabled"));
        for (Button b : new Button[] {next, later, queue}) b.setAlpha(b.isEnabled() ? 1f : 0.4f);
        refreshChips();
    }

    private void refreshChips() {
        String count = uiState.optString("countText", "");
        String st = uiState.optBoolean("visible") ? uiState.optString("status", "") : "";
        String text = st.isEmpty() ? count : count.isEmpty() ? st : st + " · " + count;
        countChip.setText(text);
        countChip.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        folderChip.setVisibility(app.folder() == null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onToast(String text, String kind, int ms) {
        message.setText(text);
        int color = "err".equals(kind) ? Color.rgb(200, 40, 40) : "warn".equals(kind) ? Color.rgb(176, 96, 0) : night() ? Color.rgb(50, 50, 50) : Color.rgb(32, 32, 32);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setColor(color);
        message.setBackground(d);
        message.setTextColor(Color.WHITE);
        message.setVisibility(View.VISIBLE);
        message.removeCallbacks(hideMessage);
        message.postDelayed(hideMessage, Math.max(1500, Math.min(ms, 10000)));
    }

    @Override
    public void openPage(String page, String hash) {
        Intent i = new Intent(this, PagesActivity.class);
        i.putExtra("page", page);
        i.putExtra("hash", hash);
        startActivity(i);
    }

    @Override
    public void openUrl(String url) {
        Uri u = Bridge.parse(url);
        if (u != null && !route(u)) web.loadUrl(u.toString());
    }

    @Override
    public void exportFile(String filename, String text, String mime) {
        openPage("logs", ""); // exports are made from the Logs page
    }

    @Override
    public void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents"));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_FOLDER);
        } catch (ActivityNotFoundException e) {
            onToast("This phone has no folder picker.", "err", 4000);
        }
    }

    @Override
    public Context context() {
        return this;
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (PagesActivity.keepFolder(this, data.getData())) {
                refreshChips();
                onToast("Log files will be written to " + data.getData().getLastPathSegment(), "ok", 3500);
            }
        }
    }

    private void welcome() {
        new AlertDialog.Builder(this)
                .setTitle("Where should your logs go?")
                .setMessage("Everything shown to you is logged. Choose a folder (for example Documents/OTAAT) and the app "
                        + "writes the same Markdown and JSONL files as the Firefox extension there, with \".phone\" in "
                        + "their names.\n\nUntil you choose, everything is kept safely inside the app.")
                .setPositiveButton("Choose folder", (d, w) -> {
                    app.setWelcomed();
                    chooseFolder();
                })
                .setNegativeButton("Not now", (d, w) -> app.setWelcomed())
                .show();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;
        web.onResume();
        refreshChips();
        app.io.execute(app::importNow);
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            app.scheduleSync(0); // get everything into the files now
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (web == null) return;
        root.setBackgroundColor(bg());
        bar.setBackgroundColor(bg());
        applySystemBarColors();
    }

    @Override
    protected void onDestroy() {
        if (app.youtube == this) app.youtube = null;
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
