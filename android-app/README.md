# Plate Lens — offline Android camera app

[Download 0.1.3 preview APK](https://github.com/Amir-chi/Iranian-Plate-Recognition-Model/releases/download/android-v0.1.3-preview/PlateLens-0.1.3-preview.apk) · [Build and test evidence](../training_results/android-logs-2026-11-05/REPORT.md)

All application controls, messages, accessibility labels and number formatting are English, including on a phone with a Persian system language. The actual recognized Persian plate characters are preserved in physical plate order. Android system dialogs use the system's own settings.

## Camera and zoom

Open **Plate Lens**, press **Start camera**, and grant camera access. Use **Stop camera**, **Light**, **Copy text**, or the processing-mode button as needed. The app requests hardware Vulkan acceleration and falls back to CPU when unavailable or when model load/inference returns an error. The timing card shows the actual backend and per-stage times.

Spread/pinch two fingers on the live preview, or use **+ / −**. Tap the displayed ratio to reset to **1×**. CameraX zoom applies to both preview and image analysis; camera limits are read from ZoomState. Pending zoom clears old readings and pauses analysis until the latest camera request completes. Digital zoom does not recover missing detail or guarantee better OCR.

## Automatic local records

**Auto-save is on by default at a minimum confidence of 80%.** Tap **Auto-save: 80%** to change the threshold (1–100%) or turn saving off. The setting persists between launches.

The save score is the **minimum of the plate locator score and every retained character score**. Empty/incomplete readings without character confidences are not saved. These are model scores, not calibrated probabilities or confirmation that the plate is correct. Extra characters are not truncated, and a standard layout does not prove a correct reading.

Each eligible detected plate is stored separately with its clean JPEG crop, exact raw text/tokens, timestamp, locator/character scores, selected threshold and layout status. Partial multi-plate results can be saved before all plates finish. The same text is saved at most once per 30 seconds, including after restarting the app. This is text-based duplicate suppression, not vehicle tracking.

JPEG encoding and SQLite writes run on a separate worker. Its bounded queue holds eight pending crops; saturation or storage failures produce a visible error. Crops are limited to 640 pixels on the longer side. Image and metadata are inserted as one SQLite row so a failed write cannot leave an unmatched image file. No automatic retention deletion is performed.

## History and export

**History** stops the camera and opens a paginated list of saved crop/text/score records (50 per page). Use **Refresh**, **Previous**, **Next**, or tap a row for a larger crop and **Copy text**. Press **Export ZIP** and select a destination using Android's file picker.

The ZIP contains `plates/<record-id>.jpg` and UTF-8 `records.json`. Each JSON row names its corresponding image and includes `saved_at_epoch_ms`, raw `text`, `tokens`, `confidence`, `plate_confidence`, `character_confidences`, `threshold_percent`, crop dimensions and `standard_layout`. History displays timestamps in device-local time with English digits; exported epoch timestamps are unambiguous.

Records are private app data on this phone. There is no Internet permission, background upload or new shared-storage permission. **Export before uninstalling or clearing app data:** those actions remove the private history. A failed export may leave an incomplete destination file; retry from History. Export is explicitly user initiated and includes only records present when it starts.

## Install and update

Android 8.0/API 26 minimum; arm64-v8a, armeabi-v7a and x86_64 libraries are included. This remains a sideload preview, not a Play Store release.

0.1.3 introduces a dedicated persistent preview signing key in CI. Updating older 0.1.0–0.1.2 builds may require uninstalling them once because their development keys differed. Those older versions did not have plate history. Subsequent builds signed with this same preview key can update 0.1.3 without clearing its data. Preserve the signing key; do not uninstall a logging version without exporting its records.

## Pipeline and build

CameraX RGBA analysis and preview share a ViewPort. Analysis uses latest-frame backpressure and a 100 ms minimum start interval. The locator and character reader retain their released v0.2.0 NCNN FP16 weights, 640 × 640 letterboxing, confidences 0.5 / 0.25, class-aware NMS 0.7, duplicate suppression 0.5 and five-pixel crop margin. Up to five plates are read sequentially and published incrementally. There is no temporal voting, retraining, identification decision or production accuracy guarantee.

Use JDK 17, Gradle 8.9, SDK 35, NDK 30.0.16248370 and CMake 3.22.1:

```sh
python scripts/prepare_assets.py --fixtures
gradle assembleDebug testDebugUnitTest lintDebug
gradle connectedDebugAndroidTest
```

The preparation script verifies pinned model and NCNN Vulkan SDK hashes. Generated models/SDKs are ignored by Git; upstream notices are bundled. Without signing environment variables, a local debug build uses the developer's ordinary debug key. CI requires `ANDROID_PREVIEW_KEYSTORE_B64` and `ANDROID_PREVIEW_STORE_PASSWORD` repository secrets, decodes the PKCS12 file outside the project, and passes `PLATE_KEYSTORE_PATH` / `PLATE_KEYSTORE_PASSWORD` to Gradle. Private key material is never committed or uploaded as an artifact.

Tests cover fixture OCR, per-character confidence output, two-plate delivery, zoom/camera lifecycle, threshold boundaries, persistence, duplicate cooldown, independent background crops, ZIP image/text matching and English history/settings. Emulator checks do not establish A52s GPU speed, camera quality or physical-phone reliability. Previously inspected fixtures are integration examples, not a fresh accuracy benchmark.
