package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the current pane tree (split layout + ratios + each pane's URL)
 * as a single JSON blob in SharedPreferences, so the app can reopen exactly
 * where you left off. Same storage pattern as BookmarkStore, just a
 * different key - this is what lets MainActivity restore the whole
 * split-screen session on launch instead of always starting from START_URL.
 */
public class SessionStore {

    private static final String PREFS_NAME = "crt_session";
    private static final String KEY_TREE = "pane_tree_json";

    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the saved tree JSON, or null if there's no saved session yet. */
    public synchronized String load() {
        return prefs.getString(KEY_TREE, null);
    }

    public synchronized void save(String treeJson) {
        prefs.edit().putString(KEY_TREE, treeJson).apply();
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_TREE).apply();
    }
}
