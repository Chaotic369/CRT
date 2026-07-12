package com.example.urlhud;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates the fullscreen, frameless Activity: a native WebView per split
 * pane (via PaneManager) stacked above a persistent bottom-bar WebView
 * (assets/bar.html), the Android equivalent of Electron's main.js +
 * index.html + preload-index.js all at once.
 *
 * Panes are real android.webkit.WebView instances - not <iframe>s inside a
 * single WebView - the same way Electron's <webview> tag hosts a fully
 * independent guest page. This matters: a lot of real sites refuse to be
 * framed (X-Frame-Options / frame-ancestors), which would silently break an
 * iframe-based version but doesn't affect a real top-level WebView.
 *
 * Bookmarks, downloads, zoom, fullscreen, split control, and back
 * navigation are all handled here natively and pushed to / invoked from
 * bar.html through WebAppInterface (window.AndroidAPI), mirroring the
 * ipcMain handlers + splitAPI/barAPI bridges main.js and preload-index.js
 * provide on desktop.
 *
 * NOTE: This build intentionally omits TRINITY_SYNC (trinity_sync.js) and
 * the automatic domain-based script injection that referenced it. See the
 * project README section "About TRINITY_SYNC" for why.
 */
public class MainActivity extends AppCompatActivity implements DownloadsController.Listener {

    // Change this to whatever you want the browser to open with on launch -
    // same idea as START_URL in main.js.
    private static final String START_URL = "https://example.com";

    private static final float ZOOM_MIN = -6f; // ~25%, matches ZOOM_MIN in main.js
    private static final float ZOOM_MAX = 9f;  // ~400%, matches ZOOM_MAX in main.js
    private static final float ZOOM_STEP = 0.5f;

    private static final int FILE_CHOOSER_REQUEST_CODE = 51426;

    private FrameLayout paneSlot;
    private WebView barWebView;
    private PaneManager paneManager;
    private BookmarkStore bookmarkStore;
    private SessionStore sessionStore;
    private DownloadsController downloadsController;

    private WebView activePane;
    private final Map<WebView, Float> zoomLevels = new HashMap<>();
    private boolean fullscreenActive = false;

    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        paneSlot = findViewById(R.id.pane_slot);
        barWebView = findViewById(R.id.bar_webview);

        bookmarkStore = new BookmarkStore(this);
        sessionStore = new SessionStore(this);
        downloadsController = new DownloadsController(this, this);

        setupBarWebView();
        paneManager = new PaneManager(this, paneSlot, this::createPaneWebView);

        WebView firstPane = null;
        String savedTree = sessionStore.load();
        if (savedTree != null) {
            try {
                firstPane = paneManager.restore(new JSONObject(savedTree));
            } catch (JSONException e) {
                firstPane = null;
            }
        }
        if (firstPane == null) firstPane = paneManager.init(START_URL);

        setActivePane(firstPane);
    }

    // -------------------------------------------------------------------
    // Bottom bar
    // -------------------------------------------------------------------
    private void setupBarWebView() {
        WebSettings s = barWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        barWebView.addJavascriptInterface(new WebAppInterface(this), "AndroidAPI");
        barWebView.setBackgroundColor(0x00000000);
        barWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Bar just (re)loaded - resync it with current state, the way
                // did-finish-load -> init-pane works on desktop. Bookmarks /
                // downloads are pulled by bar.js itself on load via the
                // synchronous getBookmarksJson()/getDownloadsJson() calls.
                pushActivePaneState();
            }
        });
        barWebView.loadUrl("file:///android_asset/bar.html");
    }

    // -------------------------------------------------------------------
    // Pane creation - this is the WebViewFactory PaneManager calls for
    // every leaf, the native equivalent of renderLeaf() in index.html.
    // -------------------------------------------------------------------
    private WebView createPaneWebView(String url) {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.setTag(url);

        wv.setWebViewClient(new PaneWebViewClient());
        wv.setWebChromeClient(new PaneWebChromeClient());

        // Mirrors session.defaultSession.on('will-download') in main.js -
        // WebView has no built-in download interception, so this is the
        // hook that stands in for it.
        wv.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            String cookie = CookieManager.getInstance().getCookie(downloadUrl);
            downloadsController.startDownload(downloadUrl, userAgent, contentDisposition, mimeType, contentLength, cookie);
        });

        // Touch-to-focus - the primary source of truth for "active pane",
        // the same role the DOM 'focus' listener on each <webview> plays in
        // renderLeaf() (index.html). Doesn't consume the event, so the
        // WebView still handles the touch normally underneath.
        wv.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && v != activePane) {
                setActivePane((WebView) v);
            }
            return false;
        });

        zoomLevels.put(wv, 0f);
        wv.loadUrl(url);
        return wv;
    }

    private class PaneWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            zoomLevels.put(view, 0f); // reset zoom bookkeeping, matches contents.setZoomLevel(0) on did-navigate
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.setTag(url);
            if (view == activePane) pushActivePaneState();
            saveSession();
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            view.setTag(url);
            if (view == activePane) pushActivePaneState();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false; // no domain lock, same as the desktop version (see README)
        }
    }

    private class PaneWebChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            // Android has no lightweight equivalent of Electron opening a
            // second BrowserWindow for window.open()/target="_blank" -
            // resolve the URL and load it in this same pane instead.
            WebView transportView = new WebView(MainActivity.this);
            transportView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    view.loadUrl(request.getUrl().toString());
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(transportView);
            resultMsg.sendToTarget();
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    // -------------------------------------------------------------------
    // Active pane tracking
    // -------------------------------------------------------------------
    private void setActivePane(WebView pane) {
        activePane = pane;
        paneManager.setActivePane(pane);
        if (pane != null) pane.requestFocus();
        pushActivePaneState();
    }

    private void pushActivePaneState() {
        JSONObject state = new JSONObject();
        try {
            if (activePane != null) {
                String url = activePane.getUrl();
                state.put("url", url != null ? url : "");
                state.put("canGoBack", activePane.canGoBack());
                state.put("canGoForward", activePane.canGoForward());
            } else {
                state.put("url", "");
                state.put("canGoBack", false);
                state.put("canGoForward", false);
            }
            state.put("hasSplit", paneManager.hasSplit());
        } catch (JSONException ignored) {}
        runJs("window.onActivePaneState && window.onActivePaneState(" + state.toString() + ")");
    }

    // -------------------------------------------------------------------
    // Handlers called from WebAppInterface (window.AndroidAPI on bar.html) -
    // these mirror the ipcMain.on(...) handlers in main.js one-for-one.
    // -------------------------------------------------------------------
    public void handleNavigate(String rawUrl) {
        String target = normalizeUrl(rawUrl);
        if (target == null || activePane == null) return;
        activePane.loadUrl(target);
        activePane.setTag(target);
        pushActivePaneState();
        saveSession();
    }

    public void handleNavigateToBookmark(String url) {
        if (activePane == null || url == null) return;
        activePane.loadUrl(url);
        activePane.setTag(url);
        pushActivePaneState();
        saveSession();
    }

    public void handleZoomIn() {
        applyZoomStep(ZOOM_STEP);
    }

    public void handleZoomOut() {
        applyZoomStep(-ZOOM_STEP);
    }

    private void applyZoomStep(float step) {
        if (activePane == null) return;
        float current = zoomLevels.containsKey(activePane) ? zoomLevels.get(activePane) : 0f;
        float next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, current + step));
        if (next == current) return;
        zoomLevels.put(activePane, next);
        float factor = (float) Math.pow(1.2, next - current);
        activePane.zoomBy(factor);
    }

    public void handleToggleFullscreen() {
        fullscreenActive = !fullscreenActive;
        applySystemBars(fullscreenActive);
        barWebView.setVisibility(fullscreenActive ? View.GONE : View.VISIBLE);
        runJs("window.onFullscreenChanged && window.onFullscreenChanged(" + fullscreenActive + ")");
    }

    private void applySystemBars(boolean hidden) {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                if (hidden) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            if (hidden) {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            } else {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
    }

    public void handleSplitPane(String direction) {
        if (activePane == null) return;
        WebView newPane = paneManager.splitPane(activePane, direction);
        if (newPane != null) {
            setActivePane(newPane);
            saveSession();
        }
    }

    public void handleClosePane() {
        if (activePane == null) return;
        WebView newActive = paneManager.closePane(activePane);
        if (newActive != null) {
            zoomLevels.remove(activePane);
            setActivePane(newActive);
            saveSession();
        }
    }

    public String handleGetBookmarksJson() {
        return bookmarkStore.toJson();
    }

    public void handleAddBookmark(String bookmarkJson) {
        try {
            bookmarkStore.add(new JSONObject(bookmarkJson));
            pushBookmarks();
        } catch (JSONException ignored) {}
    }

    public void handleEditBookmark(int index, String bookmarkJson) {
        try {
            bookmarkStore.edit(index, new JSONObject(bookmarkJson));
            pushBookmarks();
        } catch (JSONException ignored) {}
    }

    public void handleDeleteBookmark(int index) {
        bookmarkStore.delete(index);
        pushBookmarks();
    }

    private void pushBookmarks() {
        runJs("window.onBookmarksUpdated && window.onBookmarksUpdated(" + bookmarkStore.toJson() + ")");
    }

    public String handleGetDownloadsJson() {
        return downloadsController.serialize().toString();
    }

    public void handleOpenDownload(String id) {
        String uriString = downloadsController.openUri(id);
        if (uriString == null || uriString.isEmpty()) return;
        try {
            Uri uri = Uri.parse(uriString);
            String mime = getContentResolver().getType(uri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime != null ? mime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    public void handleShowDownloadInFolder(String id) {
        // Android has no "reveal in folder" concept - the closest equivalent
        // is the system Downloads app, same destination every download in
        // this app lands in.
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No Downloads app found", Toast.LENGTH_SHORT).show();
        }
    }

    public void handleCancelDownload(String id) {
        downloadsController.cancel(id);
    }

    public void handleRemoveDownload(String id) {
        downloadsController.remove(id);
    }

    public void handleClearCompletedDownloads() {
        downloadsController.clearCompleted();
    }

    @Override
    public void onDownloadsChanged(org.json.JSONArray list) {
        runJs("window.onDownloadsUpdated && window.onDownloadsUpdated(" + list.toString() + ")");
    }

    // -------------------------------------------------------------------
    // Session persistence - same trigger points as documented in the
    // README: split / close / navigate, in-page navigation, and onPause as
    // a safety net.
    // -------------------------------------------------------------------
    private void saveSession() {
        JSONObject tree = paneManager.serialize();
        if (tree != null) sessionStore.save(tree.toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSession();
    }

    @Override
    protected void onDestroy() {
        downloadsController.stop();
        super.onDestroy();
    }

    // -------------------------------------------------------------------
    // Back button: exits fullscreen first (mirrors Esc in main.js), then
    // goes back in the active pane if it has history, matching Backspace's
    // role on desktop. Falls through to the default (finish) only when
    // neither applies, since there's no window chrome to close otherwise.
    // -------------------------------------------------------------------
    @Override
    public void onBackPressed() {
        if (fullscreenActive) {
            handleToggleFullscreen();
            return;
        }
        if (activePane != null && activePane.canGoBack()) {
            activePane.goBack();
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------------
    // URL normalization - same rules as normalizeUrl() in main.js.
    // -------------------------------------------------------------------
    private static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.matches("(?i)^https?://.*")) return value;
        if (value.matches("^[\\w-]+(\\.[\\w-]+)+.*")) return "https://" + value;
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + value;
        }
    }

    private void runJs(String script) {
        barWebView.post(() -> {
            if (!isFinishing() && !isDestroyed()) barWebView.evaluateJavascript(script, null);
        });
    }
}
