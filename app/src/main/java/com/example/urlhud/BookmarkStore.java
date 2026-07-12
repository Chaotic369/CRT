package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists the bookmark list as a JSON array in SharedPreferences. This is
 * the Android equivalent of the bookmarks.json file main.js keeps in
 * Electron's userData folder (loadBookmarks / saveBookmarks).
 */
public class BookmarkStore {

    private static final String PREFS_NAME = "crt_bookmarks";
    private static final String KEY_BOOKMARKS = "bookmarks_json";

    private final SharedPreferences prefs;

    public BookmarkStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized JSONArray load() {
        String raw = prefs.getString(KEY_BOOKMARKS, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public synchronized String toJson() {
        return load().toString();
    }

    private synchronized void save(JSONArray arr) {
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply();
    }

    public synchronized void add(JSONObject bookmark) {
        JSONArray arr = load();
        arr.put(bookmark);
        save(arr);
    }

    public synchronized void edit(int index, JSONObject bookmark) {
        JSONArray arr = load();
        if (index >= 0 && index < arr.length()) {
            try {
                arr.put(index, bookmark);
                save(arr);
            } catch (JSONException ignored) {}
        }
    }

    public synchronized void delete(int index) {
        JSONArray arr = load();
        if (index >= 0 && index < arr.length()) {
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (i == index) continue;
                try {
                    next.put(arr.get(i));
                } catch (JSONException ignored) {}
            }
            save(next);
        }
    }
}
