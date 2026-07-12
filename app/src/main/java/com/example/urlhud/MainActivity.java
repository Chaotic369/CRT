package com.example.urlhud;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Edge-to-edge, chrome-free browser: a tree of resizable split WebView panes
 * that fills the whole screen with zero visible UI of its own. The only
 * control surface is a floating popup (URL bar + bookmarks + split/close
 * buttons) that appears wherever you long-press.
 *
 * This is a native-Android port of the Electron "edge popup browser":
 *  - index.html's split/leaf tree      -> PaneManager
 *  - main.js's bookmarks.json          -> BookmarkStore
 *  - main.js's context-menu listener   -> setOnLongClickListener per WebView
 *  - urlbar.html + preload-urlbar.js   -> assets/urlbar.html + AndroidPopup bridge
 * Right-click (desktop) becomes long-press (touch) throughout.
 */
public class MainActivity extends AppCompatActivity {

    // Change this to whatever you want the browser to open with on launch.
    private static final String START_URL = "https://example.com";

    private static final int POPUP_WIDTH_DP = 320;
    private static final int POPUP_HEIGHT_DP = 240;
    private static final int POPUP_MARGIN_DP = 10;

    // Any pane whose URL contains one of these gets trinity_sync.js injected
    // once the page finishes loading — this is the userscript @match list,
    // reimplemented by hand since WebView has no extension/userscript host
    // to read @match off the file itself.
    private static final String[] TRINITY_SYNC_DOMAINS = { "olymptrade.com", "pocketoption.com" };

    private FrameLayout rootFrame;
    private FrameLayout paneSlot;
    private FrameLayout popupContainer;
    private View scrimView;
    private WebView popupWebView;
    private int popupWidthPx;
    private int popupHeightPx;

    private PaneManager paneManager;
    private BookmarkStore bookmarkStore;
    private SessionStore sessionStore;

    // trinity_sync.js loaded once at startup; injected into any pane whose
    // URL matches TRINITY_SYNC_DOMAINS once that pane finishes loading.
    private String trinitySyncScript;

    // Whichever pane was long-pressed last - every popup action targets this,
    // mirroring lastTargetWcId in main.js.
    private WebView lastTargetWebView;
    private float lastTouchRawX;
    private float lastTouchRawY;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        bookmarkStore = new BookmarkStore(this);
        sessionStore = new SessionStore(this);
        trinitySyncScript = loadAsset("trinity_sync.js");

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(0xFF0A0A0A);

        paneSlot = new FrameLayout(this);
        rootFrame.addView(paneSlot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Full-screen transparent scrim: tapping outside the popup dismisses it,
        // the touch equivalent of urlBarWindow's blur-to-close behavior.
        scrimView = new View(this);
        scrimView.setBackgroundColor(Color.TRANSPARENT);
        scrimView.setVisibility(View.GONE);
        scrimView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                hidePopup();
                return true;
            }
            return false;
        });
        rootFrame.addView(scrimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        popupWidthPx = Math.round(dpToPx(POPUP_WIDTH_DP));
        popupHeightPx = Math.round(dpToPx(POPUP_HEIGHT_DP));

        popupWebView = new WebView(this);
        popupWebView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings ps = popupWebView.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        popupWebView.setWebChromeClient(new WebChromeClient()); // needed for window.prompt()
        popupWebView.addJavascriptInterface(new PopupBridge(), "AndroidPopup");
        popupWebView.loadUrl("file:///android_asset/urlbar.html");

        popupContainer = new FrameLayout(this);
        popupContainer.setVisibility(View.GONE);
        popupContainer.addView(popupWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams popupLp = new FrameLayout.LayoutParams(popupWidthPx, popupHeightPx);
        popupLp.gravity = Gravity.TOP | Gravity.START;
        rootFrame.addView(popupContainer, popupLp);

        setContentView(rootFrame);

        paneManager = new PaneManager(this, paneSlot, this::createConfiguredWebView);
        lastTargetWebView = restoreSessionOrInit();

        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (popupContainer.getVisibility() == View.VISIBLE) {
            hidePopup();
            return;
        }
        if (lastTargetWebView != null && lastTargetWebView.canGoBack()) {
            lastTargetWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(flags);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    /** Reads a file from assets/ into a String. Used to load trinity_sync.js once at startup. */
    private String loadAsset(String filename) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException e) {
            Log.e("MainActivity", "Failed to load asset " + filename, e);
        }
        return sb.toString();
    }

    /**
     * Rebuilds the pane tree from whatever was saved last time (same split
     * layout, same URL in each pane), or falls back to a single pane at
     * START_URL if there's no saved session yet or it fails to parse.
     */
    private WebView restoreSessionOrInit() {
        String savedTree = sessionStore.load();
        if (savedTree != null) {
            try {
                return paneManager.restore(new JSONObject(savedTree));
            } catch (JSONException e) {
                Log.e("MainActivity", "Failed to restore saved session, starting fresh", e);
            }
        }
        return paneManager.init(START_URL);
    }

    /** Persists the current split layout + each pane's URL so the next launch can restore it. */
    private void saveSession() {
        JSONObject tree = paneManager.serialize();
        if (tree != null) sessionStore.save(tree.toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Safety net: the process can be killed any time after onStop with
        // no further callback, so make sure the latest layout/URLs are
        // written to disk as soon as the app leaves the foreground.
        saveSession();
    }


    @SuppressLint({"ClickableViewAccessibility", "SetJavaScriptEnabled"})
    private WebView createConfiguredWebView(String url) {
        WebView webView = new WebView(this);

        // Tracks this pane's last-opened URL for the popup's URL bar. Set
        // immediately (not just once WebView reports it back) so a
        // freshly-launched or freshly-split pane already has the right
        // value the instant it's long-pressed, rather than depending on
        // WebView.getUrl(), which lags behind loadUrl() until navigation
        // actually kicks off internally.
        webView.setTag(url);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new ClipboardBridge(), "AndroidClipboard");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String requestUrl) {
                if (requestUrl.startsWith("http://") || requestUrl.startsWith("https://")
                        || requestUrl.equals("about:blank")) {
                    return false; // let the WebView itself navigate
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl)));
                } catch (Exception ignored) {
                    // no app can handle it - just ignore
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String startedUrl, Bitmap favicon) {
                if (startedUrl != null) view.setTag(startedUrl);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null) view.setTag(url);
                saveSession();
                if (url == null || trinitySyncScript == null || trinitySyncScript.isEmpty()) return;
                for (String domain : TRINITY_SYNC_DOMAINS) {
                    if (url.contains(domain)) {
                        view.evaluateJavascript(trinitySyncScript, null);
                        break;
                    }
                }
            }
        });

        webView.setLongClickable(true);
        webView.setHapticFeedbackEnabled(false);

        // Track raw touch-down coordinates so the long-press callback (which
        // gets no coordinates of its own) knows where to anchor the popup.
        webView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastTouchRawX = event.getRawX();
                lastTouchRawY = event.getRawY();
            }
            return false; // never swallow - scrolling/zooming/taps still work normally
        });

        webView.setOnLongClickListener(v -> {
            lastTargetWebView = (WebView) v;
            showPopup(lastTouchRawX, lastTouchRawY);
            return true; // consume, so no native text-selection/context menu appears
        });

        webView.loadUrl(url);
        return webView;
    }

    private void showPopup(float rawX, float rawY) {
        int[] rootLoc = new int[2];
        rootFrame.getLocationOnScreen(rootLoc);
        float localX = rawX - rootLoc[0];
        float localY = rawY - rootLoc[1];

        float margin = dpToPx(POPUP_MARGIN_DP);
        float left = localX - popupWidthPx / 2f;
        float top = localY - popupHeightPx / 2f;

        float maxLeft = Math.max(margin, rootFrame.getWidth() - popupWidthPx - margin);
        float maxTop = Math.max(margin, rootFrame.getHeight() - popupHeightPx - margin);
        left = Math.max(margin, Math.min(left, maxLeft));
        top = Math.max(margin, Math.min(top, maxTop));

        popupContainer.setTranslationX(left);
        popupContainer.setTranslationY(top);
        popupContainer.setVisibility(View.VISIBLE);
        scrimView.setVisibility(View.VISIBLE);
        scrimView.bringToFront();
        popupContainer.bringToFront();

        Object tag = (lastTargetWebView != null) ? lastTargetWebView.getTag() : null;
        String currentUrl = (tag instanceof String) ? (String) tag : null;
        if (currentUrl == null || currentUrl.equals("about:blank")) currentUrl = "";

        popupWebView.evaluateJavascript(
                "window.resetAndFocus && window.resetAndFocus("
                        + jsStringLiteral(bookmarkStore.toJson()) + ", "
                        + jsStringLiteral(currentUrl) + ")",
                null);

        popupWebView.requestFocus();
        popupWebView.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(popupWebView, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void hidePopup() {
        popupContainer.setVisibility(View.GONE);
        scrimView.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(popupWebView.getWindowToken(), 0);
    }

    private void pushBookmarksUpdated() {
        popupWebView.evaluateJavascript(
                "window.onBookmarksUpdated && window.onBookmarksUpdated(" + jsStringLiteral(bookmarkStore.toJson()) + ")",
                null);
    }

    private String jsStringLiteral(String raw) {
        return JSONObject.quote(raw);
    }

    /** Matches normalizeUrl() in main.js. */
    private String normalizeUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.matches("(?i)^https?://.*")) return value;
        if (value.matches("^[\\w-]+(\\.[\\w-]+)+.*")) return "https://" + value;
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "https://www.google.com/search?q=" + value;
        }
    }

    /**
     * Native ClipboardManager bridge for trinity_sync.js and the pane hosting
     * the signal source. Exists because Android WebView's Web Clipboard API
     * (navigator.clipboard.readText/writeText) throws NotAllowedError with no
     * way to grant it — WebView has no permission-prompt surface for it and
     * navigator.permissions is undefined there. This bridge talks to
     * Android's OS-level clipboard directly instead, which any pane in a
     * focused, foregrounded app (this one) can always read and write.
     */
    private class ClipboardBridge {

        @JavascriptInterface
        public void write(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("trinity", text));
            });
        }

        @JavascriptInterface
        public String read() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(MainActivity.this);
            return text != null ? text.toString() : "";
        }
    }

    /**
     * JS bridge for the popup WebView (assets/urlbar.html). Mirrors
     * preload-urlbar.js / the ipcMain.on(...) handlers in main.js, one
     * method per IPC channel. Every method here runs on a background
     * thread by default, so state-mutating work is posted to the UI thread.
     */
    private class PopupBridge {

        @JavascriptInterface
        public void navigate(String rawUrl) {
            String target = normalizeUrl(rawUrl);
            runOnUiThread(() -> {
                if (target != null && lastTargetWebView != null) {
                    lastTargetWebView.setTag(target);
                    lastTargetWebView.loadUrl(target);
                    saveSession();
                }
                hidePopup();
            });
        }

        @JavascriptInterface
        public void navigateToBookmark(String url) {
            runOnUiThread(() -> {
                if (url != null && lastTargetWebView != null) {
                    lastTargetWebView.setTag(url);
                    lastTargetWebView.loadUrl(url);
                    saveSession();
                }
                hidePopup();
            });
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(MainActivity.this::hidePopup);
        }

        @JavascriptInterface
        public void splitPane(String direction) {
            runOnUiThread(() -> {
                paneManager.splitPane(lastTargetWebView, direction);
                saveSession();
                hidePopup();
            });
        }

        @JavascriptInterface
        public void closePane() {
            runOnUiThread(() -> {
                paneManager.closePane(lastTargetWebView);
                lastTargetWebView = null;
                saveSession();
                hidePopup();
            });
        }

        @JavascriptInterface
        public void addBookmark(String bookmarkJson) {
            runOnUiThread(() -> {
                try {
                    bookmarkStore.add(new JSONObject(bookmarkJson));
                } catch (JSONException ignored) {}
                pushBookmarksUpdated();
            });
        }

        @JavascriptInterface
        public void editBookmark(int index, String bookmarkJson) {
            runOnUiThread(() -> {
                try {
                    bookmarkStore.edit(index, new JSONObject(bookmarkJson));
                } catch (JSONException ignored) {}
                pushBookmarksUpdated();
            });
        }

        @JavascriptInterface
        public void deleteBookmark(int index) {
            runOnUiThread(() -> {
                bookmarkStore.delete(index);
                pushBookmarksUpdated();
            });
        }
    }
}
