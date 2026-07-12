# Edge Popup Browser (Android)

Fullscreen, edge-to-edge native browser. No titlebar, no toolbar, no visible
controls anywhere on screen — the *only* UI is a floating popup that appears
wherever you **long-press**.

This is a straight port of the desktop Electron version of this app, with
long-press standing in for right-click (touchscreens have no right-click).
Everything else — split panes, bookmarks, the popup itself — works the same
way.

## What's in the popup

- **URL bar** — pre-filled with whatever the pane you long-pressed in is
  currently showing (its last-opened website), selected so you can either
  hit Go to reload it or just start typing to replace it. Hit Go to
  navigate the pane you long-pressed in.
- **Bookmark bar** (its own labeled row, below the URL input) — tap a
  bookmark to jump to it, `＋` to add the current/typed URL as a bookmark,
  long-press a bookmark to edit or delete it. Bookmarks persist to Android
  `SharedPreferences`, so they survive restarts.
- **Split ⇔ / Split ⇕** — splits *whichever pane you long-pressed in* into
  two resizable panes (side-by-side or stacked). Drag the thin divider
  between panes to resize. You can split a pane again to keep nesting.
- **Remove split** — closes whichever pane you long-pressed in and gives its
  space back to its sibling pane.

Tapping outside the popup, or pressing the system Back button, closes it
without doing anything.

## Session persistence

The app always reopens exactly where you left off: every split you've made,
each divider's position, and whatever website is loaded in each individual
pane are all saved automatically (on every split/close/navigate, on regular
in-page navigation, and as a safety net whenever the app leaves the
foreground) and restored on the next launch. `START_URL` is only used the
very first time the app runs, before any session has been saved.

## How it works

- `MainActivity.java` — creates the fullscreen `Activity`, builds the very
  first pane, and owns the popup: it listens for a long-press on *every*
  pane's `WebView` (each split pane is its own real `WebView`), remembers
  which one was pressed, and positions the popup at the touch point. It also
  hosts the `AndroidPopup` JavaScript-interface bridge the popup talks to,
  and applies navigate/split/close actions to whichever pane was targeted.
- `PaneManager.java` — owns the tree of split/leaf panes and renders it as
  nested `LinearLayout`s with `WebView` leaves and draggable divider `View`s.
  This is a native-Android port of the split/leaf tree that Electron's
  `index.html` builds out of flexbox `<div>`s — same tree, same operations
  (`init` / `splitPane` / `closePane`), just real Views instead of DOM nodes,
  since Android's `WebView` has no equivalent of Electron's `<webview>` tag
  to nest inside HTML.
- `BookmarkStore.java` — persists bookmarks as a JSON array in
  `SharedPreferences`, the Android equivalent of `main.js`'s
  `bookmarks.json` file.
- `SessionStore.java` — persists the whole pane tree (split layout, divider
  ratios, and each pane's current URL) as a single JSON blob in
  `SharedPreferences`, so `MainActivity` can rebuild the exact same
  split-screen session on the next launch instead of starting over.
- `assets/urlbar.html` — the popup itself, hosted in its own small
  transparent `WebView`. This is close to line-for-line the same file as the
  Electron version's `urlbar.html`, just calling `window.AndroidPopup.*`
  instead of `window.electronAPI.*`.

## Build it (no Android Studio needed)

1. Create a new **empty** GitHub repository.
2. Push everything in this folder to it:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Go to the repo's **Actions** tab. The "Build APK" workflow runs
   automatically on push and produces `app-debug.apk`.
4. Open the finished workflow run → **Artifacts** → download
   `url-hud-debug-apk` (it's a zip containing the APK).

## Install it on your phone

1. Copy `app-debug.apk` to your Android device.
2. Tap it. Android will ask you to allow installs from that source
   (Settings → Apps → *the app you opened it with* → Install unknown apps)
   — this is normal for any APK not from the Play Store.
3. Open "Edge Popup Browser". You'll see the start page fill the whole
   screen with nothing on it. Long-press anywhere for the popup.

## Things to tweak

- `START_URL` in `MainActivity.java` — what loads in the first pane on
  launch.
- `POPUP_WIDTH_DP` / `POPUP_HEIGHT_DP` in `MainActivity.java` — size of the
  floating popup.
- `MIN_PERCENT` / `MAX_PERCENT` in `PaneManager.java` — how far a split
  divider can be dragged before it clamps.
- Bookmark storage lives in the `crt_bookmarks` SharedPreferences file, and
  the saved split-screen session lives in `crt_session` — clear the app's
  storage (Settings → Apps → Edge Popup Browser → Storage → Clear data) to
  reset both back to a single pane at `START_URL`.

## Notes

- This is a **debug-signed** build — fine to sideload on your own device,
  but not suitable for Play Store distribution (that needs a release
  signing key).
- `minSdk 21` / `compileSdk 34` — works on Android 5.0 and up.
- If you want to change the app name or package id, edit
  `AndroidManifest.xml` (`android:label`) and `app/build.gradle`
  (`namespace` / `applicationId`).
- Navigation currently isn't restricted to any domain list, same as the
  desktop version — add a `shouldOverrideUrlLoading` allowlist in
  `MainActivity.java` if you want to lock panes to specific sites.
- One known rough edge: on some pages, a long-press can momentarily trigger
  the WebView's own text-selection handles at the same time the popup opens,
  since Chromium detects long-press for text selection independently of the
  `View.OnLongClickListener` callback this app hooks into. It doesn't break
  anything — the popup still opens correctly — but you may see a brief
  selection highlight underneath it. Haven't found a clean way to fully
  suppress that at the WebView layer without disabling text selection
  everywhere, so it's left as-is.

## TRINITY_SYNC pair-sync bridge

Android WebView has no extension host, so a Tampermonkey userscript can't be
installed here the way it can on desktop. Two things were added to work
around that:

- **`assets/trinity_sync.js`** — an Android build of the `TRINITY_PAIR_SYNC`
  userscript. `MainActivity.java`'s `onPageFinished` hook injects it via
  `WebView.evaluateJavascript()` into any pane whose URL matches
  `TRINITY_SYNC_DOMAINS` (currently `olymptrade.com` / `pocketoption.com`) —
  this replaces the userscript's `@match` list, which nothing on Android
  reads automatically.
- **`ClipboardBridge`** (in `MainActivity.java`, exposed to JS as
  `window.AndroidClipboard`) — Android WebView's Web Clipboard API
  (`navigator.clipboard.readText`/`writeText`) throws `NotAllowedError` with
  no way to grant it; `navigator.permissions` doesn't even exist in WebView.
  This bridge reads/writes Android's native clipboard directly instead, and
  is registered on every pane, so the signal-source pane and the OT/PO pane
  can pass a pair name through it.

If your signal source is a page you also control (e.g. a page like
`888znq.github.io/FFT/`), have it call
`window.AndroidClipboard ? window.AndroidClipboard.write(pair) : navigator.clipboard.writeText(pair)`
instead of a bare `navigator.clipboard.writeText(pair)` — that one-line
change is enough for it to work both there and on desktop unmodified.

To point the injection at a different broker or add one, edit `PAIRS` and
`TRINITY_SYNC_DOMAINS` — `PAIRS` lives in `assets/trinity_sync.js`,
`TRINITY_SYNC_DOMAINS` in `MainActivity.java`.
