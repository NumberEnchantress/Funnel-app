package com.otaat.funnel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * The extension's own Logs and Later pages (and the app's Settings page), served from the app's assets
 * at https://appassets.androidplatform.net/app/… and talking to the app over a channel only they get.
 */
public class PagesActivity extends Activity implements Bridge.Host {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int REQ_EXPORT = 1;
    private static final int REQ_FOLDER = 2;

    private WebView web;
    private String exportText;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        web = new WebView(this);
        // A WebView ignores its own padding, so the system-bar insets go on a frame around it.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(web);
        setContentView(frame);
        getWindow().setDecorFitsSystemWindows(false);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return WindowInsets.CONSUMED;
        });
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        web.setBackgroundColor(night ? 0xff111113 : 0xfffafafa);
        frame.setBackgroundColor(night ? 0xff111113 : 0xfffafafa);
        if (getWindow().getInsetsController() != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : light, light);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/app/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "otaatPages", Collections.singleton(ORIGIN), new Bridge(this, false).listener(null));
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                finish(); // back to the YouTube screen instead of taking the whole app down
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (ORIGIN.equals(u.getScheme() + "://" + u.getHost())) return false;
                openUrl(u.toString());
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
        load(getIntent());
    }

    private void load(Intent i) {
        String page = i.getStringExtra("page");
        if (page == null || !page.matches("logs|later|options")) page = "logs";
        String hash = i.getStringExtra("hash");
        web.loadUrl(ORIGIN + "/app/pages/" + page + ".html" + (hash == null || hash.isEmpty() ? "" : "#" + hash));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        load(intent);
    }

    private void goBack() {
        if (web.canGoBack()) web.goBack();
        else finish();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        goBack();
    }

    // ------------------------------------------------------------------ Bridge.Host

    @Override
    public void onUiUpdate(JSONObject state) {}

    @Override
    public void onToast(String text, String kind, int ms) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void openPage(String page, String hash) {
        web.loadUrl(ORIGIN + "/app/pages/" + page + ".html" + (hash.isEmpty() ? "" : "#" + hash));
    }

    /** YouTube links open on the YouTube screen; anything else in the browser. */
    @Override
    public void openUrl(String url) {
        Uri u = Bridge.parse(url);
        if (u == null) return;
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (Urls.isYoutubeHost(host)) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Urls.toMobile(u)), this, MainActivity.class));
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException e) {
            Toast.makeText(this, "No app can open that link.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void exportFile(String filename, String text, String mime) {
        exportText = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "This phone has no \"save as\" screen. Use Copy instead.", Toast.LENGTH_LONG).show();
        }
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
            Toast.makeText(this, "This phone has no folder picker.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public Context context() {
        return this;
    }

    /** Keep access to the chosen folder across reboots, and start writing to it. */
    static boolean keepFolder(Context c, Uri tree) {
        try {
            c.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Toast.makeText(c, "That folder can't be kept across restarts. Choose a folder on the phone's own storage.", Toast.LENGTH_LONG).show();
            return false;
        }
        App.get().setFolder(tree);
        return true;
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_FOLDER) {
            keepFolder(this, data.getData());
            web.postDelayed(() -> web.evaluateJavascript("window.dispatchEvent(new Event('focus'))", null), 800);
        } else if (requestCode == REQ_EXPORT && exportText != null) {
            Uri uri = data.getData();
            String text = exportText;
            exportText = null;
            App.get().io.execute(() -> {
                String msg;
                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new java.io.IOException("couldn't open the file");
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    msg = "Saved.";
                } catch (Exception e) {
                    msg = "Couldn't save: " + e.getMessage();
                }
                String m = msg;
                App.get().main.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
