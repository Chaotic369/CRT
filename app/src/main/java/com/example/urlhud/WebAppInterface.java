package com.example.urlhud;

import android.content.Context;
import android.webkit.JavascriptInterface;

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