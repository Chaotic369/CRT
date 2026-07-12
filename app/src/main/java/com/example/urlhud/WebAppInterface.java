package com.example.urlhud;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;

public class WebAppInterface {
    Context mContext;
    private boolean systemBarsHidden = false;

    WebAppInterface(Context c) {
        mContext = c;
    }

    /**
     * Toggles the Android status bar / navigation bar. Everything else the
     * bottom bar does (navigate, zoom, split, close) is handled entirely in
     * JS on the hud.js side, since the panes are plain <iframe>s living in
     * the same page as the toolbar - no native call is needed for those.
     */
    @JavascriptInterface
    public void toggleFullscreen() {
        if (!(mContext instanceof Activity)) return;
        final Activity activity = (Activity) mContext;
        activity.runOnUiThread(() -> {
            systemBarsHidden = !systemBarsHidden;
            View decorView = activity.getWindow().getDecorView();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = decorView.getWindowInsetsController();
                if (controller != null) {
                    if (systemBarsHidden) {
                        controller.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        controller.hide(WindowInsets.Type.systemBars());
                    } else {
                        controller.show(WindowInsets.Type.systemBars());
                    }
                }
            } else {
                if (systemBarsHidden) {
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
        });
    }
}
