# URL HUD (Android)

A tiny native Android app: a fullscreen, blank WebView with nothing on it
except the floating draggable button from your userscript. Tap it to open
a URL bar, type a URL or search term, hit Go.

## Why not just "convert" the userscript directly?

A Tampermonkey userscript only runs inside Tampermonkey inside a real
browser — it can't be packaged into an APK as-is. This project instead:

- Wraps a plain Android `WebView` in a single fullscreen Activity (no
  address bar, no tabs, no browser UI at all — just your HUD).
- Loads `about:blank` on start, so the screen is empty except the button.
- Injects `app/src/main/assets/hud.js` (your script, lightly adapted)
  into the page every time it finishes loading.
- Replaces `GM_setValue`/`GM_getValue` (Tampermonkey-only APIs) with a
  small `AndroidPersist` JS bridge backed by `SharedPreferences`, so the
  button's position is still remembered between launches.

Everything else — the shadow-DOM isolation, dragging, the Go button,
search fallback — is unchanged from your script.

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
3. Open "URL HUD". You'll see a blank screen with a blue circular button
   bottom-right. Drag it anywhere; tap it for the URL bar.

## Notes

- This is a **debug-signed** build — fine to sideload on your own device,
  but not suitable for Play Store distribution (that needs a release
  signing key).
- `minSdk 21` / `compileSdk 34` — works on Android 5.0 and up.
- If you want to change the app name or package id, edit
  `AndroidManifest.xml` (`android:label`) and `app/build.gradle`
  (`namespace` / `applicationId`).
- This hasn't been run through an actual Android build locally (no
  Android SDK available in the environment that generated it) — the
  GitHub Actions run is the real first build/compile check. If it fails,
  paste the Actions log back and I'll fix it.
