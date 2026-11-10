# PProg Viewer for Android

A small **Kotlin + WebView** shell for the existing PProg Viewer at
https://fda1111.github.io/Pprogmobile.github.io/.

## Install / get APK

Open **GitHub Actions → Build Android APK → latest successful run → Artifacts →
PProgViewer-Android-debug**. If the workflow publishes a preview release,
you can instead download `PProgViewer-Android-debug.apk` from
https://github.com/fDa1111/Pprogmobile.github.io/releases.

APK builds automatically on changes to `android/**` or the workflow; HTML
changes do **not** require new APKs. The Android WebView loads GitHub Pages
each time, while the PWA service worker caches the app for subsequent offline
use. Use **Ещё → Проверить обновления** in the PWA to refresh its web assets.

## Build locally

Open the `android/` folder as a project in Android Studio with JDK 17.
Android Gradle Plugin: 8.7.3, Gradle: 8.9, Android SDK: 35.
Or run `gradle :app:assembleDebug` inside `android/` with those tools
installed. Result: `app/build/outputs/apk/debug/app-debug.apk`.

## Android integration

- File import: system document picker supports TXT files; no storage permission.
- Exports: page sends each exported Blob to the native bridge in 48 KiB base64
  chunks, up to 64 MiB, then opens Android's **Create document** picker.
- External navigation: web app stays within its exact GitHub Pages path;
  external links open in the system browser.
- Browser cache and PWA service worker support offline use *after* the first
  successful online load. First launch needs network access.
- TXT remains local to the app; this shell does not upload it.
- Android 15+ system-bar insets are respected.

## Important signing note

The workflow produces a **test/debug** APK. GitHub Actions runners may generate
different debug signing keys. Replacing one debug APK with another may fail due
to signature mismatch; uninstalling will clear local WebView data. Stable
in-place native APK upgrades require a privately stored permanent signing
keystore (GitHub Actions secrets) or an app store signing workflow. Web-only
updates through GitHub Pages do not have this limitation.

The project currently produces a preview build, not a Play Store release.
