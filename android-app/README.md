# Plate Lens — offline Android live camera prototype

A native Android app that opens the rear camera, locates plates, recognizes characters on clean crops, and displays Persian tokens in physical plate order. It bundles the released NCNN FP16 models and requires only camera permission. The application declares no Internet, microphone, location or shared-storage permission.

[Download the installable preview APK](https://github.com/Amir-chi/Iranian-Plate-Recognition-Model/releases/download/android-v0.1.2-preview/PlateLens-0.1.2-preview.apk) · [Checksums and build evidence](../training_results/android-gpu-2026-11-04/REPORT.md)

## First use

Install the debug APK produced by the **Android live camera APK** workflow, open **پلاک‌خوان**, and tap **شروع دوربین** to grant camera access. After the first grant, the app opens the camera automatically on later launches. Use **توقف دوربین** to unbind and release the camera, **چراغ** to toggle the rear light if available, and **کپی متن خام** to copy the raw reading.

The lower card shows the actual detected crop and character sequence. A standard layout is not confirmation of correctness. Nonstandard counts/layouts are explicitly marked uncertain; characters are not truncated, invented or silently replaced. Cropped/full-frame camera images are not saved, uploaded or added to a history database. Copying a plate writes only the user-selected text to the system clipboard.

The timing card reports the actual backend, total analysis duration, detector time and cumulative character-reader time. The first GPU model load and inference may include shader setup; compare warmed-up readings on the same static scene. The processing-mode button is disabled during model changes. A backend change releases the camera and invalidates earlier queued results before reopening.

## Camera zoom

Spread/pinch two fingers on the live preview, or use **+ / −**. The middle button displays the observed zoom ratio; tap it to reset to 1×. Limits come from the attached camera, and unsupported/limit controls are disabled. The requested ratio is restored when restarting the camera or switching processing backend during the same activity.

Zoom uses CameraX `CameraControl.setZoomRatio`, affecting the shared preview and analysis camera outputs; no `View` scale transform or separate fake enlarged preview is used. While a zoom request is pending, analysis pauses and previous readings are cleared. Session generations reject older results. The analysis resumes after the latest camera request completes. Digital zoom is camera-dependent and cannot restore missing image detail; optical zoom or a guaranteed accuracy improvement is not claimed.

## Pipeline and limits

- CameraX preview plus RGBA analysis, shared ViewPort, rear camera, portrait UI. Analysis crop and rotation are applied before native inference; the overlay uses the same fit-center presentation.
- Keep-only-latest backpressure and a 100 ms minimum interval between analysis starts. Model work runs on a single worker thread, not the UI thread. Actual FPS, thermal behavior and overlay alignment must be measured on the target phone.
- Native NCNN Vulkan inference when a compatible GPU is available; CPU fallback on model-load/inference errors. The processing-mode button switches between requesting GPU and forcing CPU. CPU inference uses at most four reported big-core threads. FP16 arithmetic stays disabled; weights are unchanged. No CUDA/Python is needed on the phone.
- Plate confidence 0.5; character confidence 0.25; class-aware NMS IoU 0.7; cross-class duplicate suppression IoU 0.5; letterbox 640 × 640; five-pixel clean crop margin.
- At most five highest-confidence plates are processed per frame to bound live OCR work. All their boxes and recognized tokens are drawn; the detail card shows the highest-confidence plate. Completed plates are published before the remaining readers finish, with a completed/total indicator. This reduces time to first result, not the number of OCR inferences.
- No temporal voting/tracking, automatic gate-opening, identification decision or production accuracy guarantee is included. Reading errors and glare/blur/angle sensitivity remain those of the underlying prototype.
- Contains arm64-v8a, armeabi-v7a and x86_64 native libraries. Minimum Android 8.0 (API 26). The x86_64 library permits Android-emulator testing; it is not evidence of performance on an ARM phone.

## Build

Use JDK 17, Gradle 8.9, Android SDK 35, NDK 30.0.16248370 and CMake 3.22.1. These are configured in CI; Android Studio can import this directory.

```sh
python scripts/prepare_assets.py --fixtures
gradle assembleDebug testDebugUnitTest lintDebug
gradle connectedDebugAndroidTest
```

`prepare_assets.py` downloads the released model bundle and the official NCNN 20261020 Android Vulkan static SDK, verifies pinned SHA256 digests and creates normalized class tokens. SDK/model binaries and generated files are ignored by Git. Keep generated class IDs in their original order. Model notices and NCNN/AndroidX acknowledgements are bundled in assets.

The emulator tests exercise native full-frame inference on the five earlier public demonstration images on both requested backends, progressive delivery for a synthetic two-plate scene, and rear-camera start/stop. These are integration tests, not an independent OCR benchmark or a substitute for real-phone testing. Test fixtures come from the pinned ALPR_Dataset revision already documented under `training_results/cropped-output-2026-11-04/`.

## Distribution

This is a development-signed preview APK for sideload testing, not a Play Store release. A device may require allowing installation from the source used to open the APK. Signing/release management, Play deployment and testing on a real Android phone remain separate steps. Source and build evidence are available in this repository and its Actions artifacts.

Software/virtual Vulkan devices are excluded from accelerator selection. The first software-Vulkan emulator attempt lost the entire emulator after five successful CPU fixture readings, before completing the GPU pass; it is not a passed GPU test. The corrected selection uses integrated/discrete hardware devices and keeps CPU execution for the emulator. Hardware Vulkan predictions and speed still require physical-phone testing.
