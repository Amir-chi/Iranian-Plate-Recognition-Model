# Android deployment — app and NCNN FP16 assets

Download [v0.2.0-android-ncnn-fp16.zip](https://github.com/Amir-chi/Iranian-Plate-Recognition-Model/releases/download/v0.2.0/v0.2.0-android-ncnn-fp16.zip) for the same released weights converted to native NCNN graphs with FP16 weight storage. This is an integration asset bundle, **not an APK or an on-phone performance certification**.

[Download checksum](https://github.com/Amir-chi/Iranian-Plate-Recognition-Model/releases/download/v0.2.0/v0.2.0-android-ncnn-fp16.zip.sha256) · [Aggregate validation/export evidence](../training_results/mobile-export-2026-11-04/summary.json)

NCNN supports native Android CPU deployment and Vulkan acceleration where available; see [Ultralytics NCNN integration](https://docs.ultralytics.com/integrations/ncnn/) and [Tencent's Android build instructions](https://github.com/Tencent/ncnn/wiki/how-to-build#build-for-android). A native app can use these models without shipping Python/PyTorch. The original export experiment did not build an app. A subsequent [native live-camera app](../android-app/README.md) now bundles these models; see its [Android build/test report](../training_results/android-camera-2026-11-04/REPORT.md).

## Model payload and accuracy preservation

| Component | Parameters | Original .pt | NCNN .param + .bin |
|---|---:|---:|---:|
| Character reader | 3,018,063 | 6.26 MB | 6.15 MB |
| Plate locator | 11,135,987 | 22.52 MB | 22.39 MB |
| Both | 14,154,050 | 28.77 MB | **28.54 MB** |

Sizes use decimal MB and exclude the app/runtime and activation memory. The download ZIP is about 26.28 MB. The original PyTorch checkpoints already store compact weights, so FP16 conversion does **not** halve their download size. This step provides a mobile runtime representation; pruning and INT8 were not performed. The plate locator is the larger component and the main model-size reduction opportunity.

The character NCNN export produced **the same text for all 995 HezarAI test images** as the PyTorch release: 865/995 correct (86.93%) and 28 net extra characters. This is exact-string parity on the measured desktop backend, not guaranteed bit-identical tensors or phone accuracy on unseen data. These previously inspected test images are not a fresh final holdout.

The full two-stage NCNN pipeline also matched the PyTorch texts on all five earlier full-frame demonstration images; detected-box IoU against PyTorch boxes ranged from 0.99966 to 0.99988. Those five include repeated views of the same vehicle and are not a detection accuracy benchmark.

## Smaller-input validation experiment

The rule was to retain a smaller input only if validation full-plate accuracy and net-extra-character counts did not regress. Shape candidates were evaluated on HezarAI validation, not chosen using test scores.

| Character input (height × width) | Correct / 996 | Net extra characters |
|---|---:|---:|
| **640 × 640 — retained** | **865** | 40 |
| 256 × 640 | 840 | 14 |
| 192 × 640 | 796 | 5 |
| 384 × 640 — additional validation candidate | 858 | 19 |
| 512 × 512 — additional validation candidate | 856 | 29 |

Smaller inputs reduced excess output partly alongside missed/incorrect characters; they did not preserve full-string accuracy. Therefore the distributed default remains 640 × 640 for both models. No faster-phone or no-loss claim is made for reduced inputs.

## Integration contract

The archive includes `pipeline.json`, exact UTF-8 `classes.json`, source-weight hashes, metadata notices and `SHA256SUMS.txt`.

1. Feed the full RGB frame to the plate locator using centered, aspect-ratio-preserving 640 × 640 letterboxing, padding 114, float32 CHW input divided by 255. Input/output names: `in0` / `out0`.
2. Decode YOLOv8 center-x, center-y, width, height and class probabilities; apply class-aware NMS at IoU 0.7 and plate confidence 0.5. Undo padding/scale when mapping coordinates to the source frame.
3. Expand each detected box by five pixels, clip to frame boundaries and crop clean pixels. Give that crop to the character reader with the same preprocessing and confidence 0.25.
4. Apply duplicate character suppression at IoU 0.5 and sort retained boxes by horizontal center. Preserve exact class IDs from `classes.json`, normalize text as in [plate_ocr.py](../plate_ocr.py), and mark unsupported standard shapes as unverified rather than guessing/truncating.

Raw unbatched output is `[5, 8400]` for the one-class locator and `[41, 8400]` for the 37-class reader (four box rows plus class rows). NMS and text decoding are outside the exported graphs. Mobile camera rotation, mirroring and preview coordinate transforms must be handled by the Android app.

## Desktop timing and remaining phone work

On the local Windows CPU, the NCNN character test took about 63.86 seconds for 995 crops including decoding, preprocessing and postprocessing. In the five-image full pipeline run, the four images after initial model loading took about 229–235 ms each. These small desktop measurements are **not Android FPS** and do not include camera capture, UI rendering or sustained thermal/battery measurements. A separate forced-thread timing experiment terminated and was excluded from the evidence.

Before choosing a phone performance target, measure on the actual Android device with its NCNN CPU/Vulkan backend, camera resolution, memory and sustained load. To reduce work further, detect the plate less frequently and track the ROI between detections, then OCR only changed/clear crops. Such scheduling has not been implemented or benchmarked here. A smaller locator trained/distilled on plate data, or carefully calibrated INT8, needs its own validation before replacing the released locator.

## Reproduce the format conversion

On the existing training environment, retain Ultralytics **8.4.8** and install `ncnn==1.0.20261020` and `pnnx==20261020`. Copy original weights to a separate output folder first, so exports cannot overwrite active weights:

```python
from ultralytics import YOLO

YOLO("separate-output/plate_detector.pt").export(
    format="ncnn", imgsz=640, half=True, device="cpu", batch=1)
YOLO("separate-output/chars37-best.pt").export(
    format="ncnn", imgsz=640, half=True, device="cpu", batch=1)
```

This uses the installed 8.4.8 `half=True` API; newer documentation uses `quantize=16`. Include the generated `.param`, `.bin`, metadata and class mapping in the native app. Retain upstream model/software notices; no new license is asserted for third-party assets.
