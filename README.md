# AndroidScreenshotsOrganizer

Ask My Screenshots is a native Android app for local screenshot organization and search.

## What it does

- Lets the user choose a screenshot date range.
- Requests image access without all-files permission.
- Invokes the `:screenshot-skill` Android library module to organize screenshots.
- Uses Google Play Services unbundled ML Kit modules for OCR, language ID, image labels, barcode scanning, and face metadata; first setup may download local models.
- Stores only screenshot URI references plus extracted metadata in an encrypted SQLCipher/Room index.
- Does not copy screenshot bytes, face crops, or thumbnails into app storage.
- Answers local screenshot searches with deterministic FTS/entity/category matching.
- Builds a deterministic mind-map graph from local categories, entities, topics, and screenshot leaves.
- Exposes optional Gemini or custom remote query rewrite hooks that only accept redacted routing context; the app keeps local-only search as the default.

## Local build

The repo expects local Android tooling under ignored `.local/` paths:

- `.local/jdk`
- `.local/android-sdk`
- `.local/gradle`

For Gemini-assisted query planning or answer phrasing, provide
`ASK_SCREENSHOTS_GEMINI_API_KEY` as a Gradle property or environment variable
when building. Do not commit API keys.

Build:

```bash
scripts/build_debug.sh
```

Or use the checked-in Gradle wrapper directly:

```bash
./gradlew :app:assembleDebug
```

Install on a USB-debugging Android phone:

```bash
scripts/install_debug.sh
```

The shareable debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```
