# Plate Lens — offline Android live camera prototype

A native Android app that opens the rear camera, locates plates, recognizes characters on clean crops, and displays Persian tokens in physical plate order. It bundles the released NCNN FP16 models and requires only camera permission. The application declares no Internet, microphone, location or shared-storage permission.

## First use

Install the debug APK produced by the **Android live camera APK** workflow, open **پلاک‌خوان**, and tap **شروع دوربین** to grant camera access. After the first grant, the app opens the camera automatically on later launches. Use **توقف دوربین** to unbind and release the camera, **چراغ** to toggle the rear light if available, and **کپی متن خام** to copy the raw reading.

The lower card shows the actual detected crop and character sequence. A standard layout is not confirmation of correctness. Nonstandard counts/layouts are explicitly marked uncertain; characters are not truncated, invented or silently replaced. Cropped/full-frame camera images are not saved, uploaded or added to a history database. Copying a plate writes only the user-selected text to the system clipboard.

## Pipeline and limits

- CameraX preview plus RGBA analysis, shared ViewPort, rear camera, portrait UI. Analysis crop and rotation are applied before native inference; the overlay uses the same fit-center presentation.
- Keep-only-latest backpressure and a 350 ms minimum interval between analysis starts. Model work runs on a single worker thread, not the UI thread. Actual FPS, thermal behavior and overlay alignment must be measured on the target phone.
- Native NCNN CPU inference, two threads, FP16 weight storage with FP16 arithmetic disabled. There is no CUDA/Python dependency on the phone and no Vulkan path in this initial app.
- Plate confidence 0.5; character confidence 0.25; class-aware NMS IoU 0.7; cross-class duplicate suppression IoU 0.5; letterbox 640 × 640; five-pixel clean crop margin.
- At most five highest-confidence plates are processed per frame to bound live OCR work. All their boxes are drawn; the detail card shows the highest-confidence plate.
- No temporal voting/tracking, automatic gate-opening, identification decision or production accuracy guarantee is included. Reading errors and glare/blur/angle sensitivity remain those of the underlying prototype.
- Contains arm64-v8a, armeabi-v7a and x86_64 native libraries. Minimum Android 8.0 (API 26). The x86_64 library permits Android-emulator testing; it is not evidence of performance on an ARM phone.

## Build

Use JDK 17, Gradle 8.9, Android SDK 35, NDK 27.0.12077973 and CMake 3.22.1. These are configured in CI; Android Studio can import this directory.

```sh
python scripts/prepare_assets.py --fixtures
gradle assembleDebug testDebugUnitTest lintDebug
gradle connectedDebugAndroidTest
```

`prepare_assets.py` downloads the released model bundle and the official NCNN 20261020 Android static SDK, verifies pinned SHA256 digests and creates normalized class tokens. SDK/model binaries and generated files are ignored by Git. Keep generated class IDs in their original order. Model notices and NCNN/AndroidX acknowledgements are bundled in assets.

The emulator tests exercise native full-frame inference on the five earlier public demonstration images and rear-camera start/stop. These are integration tests, not an independent OCR benchmark or a substitute for real-phone testing. Test fixtures come from the pinned ALPR_Dataset revision already documented under `training_results/cropped-output-2026-11-04/`.

## Distribution

This is a development-signed preview APK for sideload testing, not a Play Store release. A device may require allowing installation from the source used to open the APK. Signing/release management, Play deployment and testing on a real Android phone remain separate steps. Source and build evidence are available in this repository and its Actions artifacts.
