import android.webkit.JavascriptInterface;
import android.content.Context;

// Add this interface binding in your MainActivity onCreate:
// webView.addJavascriptInterface(new WebAppInterface(this), "AndroidAPI");

public class WebAppInterface {
    Context mContext;

    WebAppInterface(Context c) {
        mContext = c;
    }

    @JavascriptInterface
    public void navigate(String url) {
        // Implement navigation logic for the active pane
    }

    @JavascriptInterface
    public void zoomIn() {
        // Implement zoom in logic
    }

    @JavascriptInterface
    public void zoomOut() {
        // Implement zoom out logic
    }

    @JavascriptInterface
    public void toggleFullscreen() {
        // Implement fullscreen toggle for Android view
    }

    @JavascriptInterface
    public void splitPane(String direction) {
        // Implement native PaneManager splitting
    }

    @JavascriptInterface
    public void closePane() {
        // Implement native PaneManager closing
    }
}