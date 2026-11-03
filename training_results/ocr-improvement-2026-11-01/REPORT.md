# OCR decoding improvement — 1 November 2026

## Outcome

The frozen 37-class weights are unchanged. Removing competing overlapping character boxes increases exact full-plate accuracy on the 995-image HezarAI test split from **849/995 (85.33%) to 865/995 (86.93%)**: +16 plates, +1.61 percentage points. The original 31-class model scored 827/995 (83.12%) with the original decoder; the combined weight-and-decoder improvement over that historical reference is +38 plates, +3.82 percentage points. These are cropped-plate text scores, not box mAP or a live-camera benchmark.

On validation, correct full plates increase from **851/996 (85.44%) to 865/996 (86.85%)**. Test character edit rate decreases from 251/7959 (**3.15%**) to 223/7959 (**2.80%**).

## Selection protocol

Only the 996-image validation split selected the configuration. The fixed grid tested input sizes 640/800/960; confidence 0.10/0.15/0.20/0.25/0.35/0.45/0.55; and cross-class duplicate IoU suppression disabled/0.5/0.7 (63 configurations). Predictions at confidence 0.1 were cached once per size, then the confidence/suppression grid was scored on those cached boxes. Both runs used ordinary model class-aware NMS IoU 0.7, FP32, GPU 0 and batch 8.

The winner maximizes exact match, then minimizes edit distance, then prefers smaller input size, confidence closer to 0.25, and disabled extra suppression on remaining ties. It was written to `selection.json` before test inference. The selected settings are **640, confidence 0.25, duplicate IoU 0.5**. Maximum validation correct counts by size: **{640: 865, 800: 855, 960: 857}**. Bigger input did not improve the selected result.

The initial discovery used automatic letterboxing. To make individual camera crops and evaluation batches use identical preprocessing, prediction now explicitly sets `rect=False`. The full validation grid and the frozen-config test were repeated with square letterboxing; the winner and headline counts were unchanged. Test results were not used to select a different configuration. The test split has been inspected in earlier project work, so it is no longer a pristine holdout for future iterative tuning.

## Implementation

`plate_ocr.py` is shared by evaluation and `main.py`. After model NMS it filters by confidence, processes boxes in descending confidence, removes a box if IoU with an already-kept box exceeds 0.5 regardless of predicted class, then reads boxes by horizontal center. It normalizes digits/letter spelling consistently with the previous OCR evaluation. It never forces an eight-character output, guesses missing characters, or uses the reference label. Adjacent repeated digits must remain distinct.

`main.py` now copies the clean frame and extracts every plate crop from it before display overlays. Previously a blue rectangle was drawn before cropping and could contaminate the recognition input; this correction is logical and has not been independently quantified on a live camera.

The app prefers `models/chars37-best.pt` when present and falls back to `models/best.pt` otherwise. It prints which weights were loaded. The candidate was copied locally and its SHA256 verified: `5ab6da27dedafc06f6c69d29c297d9f270c24fa5f0130d1d15fff40ebe9d56c8`. The original checkpoint remains byte-identical (`dc8e83b4442d6d88a47083c69cf9c65c99fb0fc37162e64e941088e35821ffa5`). Candidate weights are not automatically published in Git.

## Full-dataset cached follow-up

After freezing the configuration, the existing 9,953-row candidate prediction cache was decoded again with duplicate suppression. This does not retrain the network or choose another threshold. The cached test count agrees with the fresh square-letterbox test inference. Other split counts below are cached follow-up measurements rather than fresh camera or dataset inference.

| Split | Images | Correct full plates | Exact match | Newly correct | Regressions |
|---|---:|---:|---:|---:|---:|
| test | 995 | 865 | 86.93% | 16 | 0 |
| validation | 996 | 865 | 86.85% | 14 | 0 |
| train | 7962 | 6850 | 86.03% | 104 | 0 |
| all | 9953 | 8580 | 86.21% | 134 | 0 |

## Verification and limits

Three decoder unit tests cover competing class boxes, preservation of adjacent repeated digits, empty output and normalization. The actual camera recognition helper matched the calibrated batch decoder on 20 real test crops. Python compilation checks passed for the modified modules. No webcam session, whole-image plate-locator quality measurement or new training was performed. The unit/integration checks validate code behavior; they do not establish production robustness.

The same HezarAI label ambiguity, data provenance and exact-only overlap audit from [the prior report](../hezarai-2026-11-01/REPORT.md) apply. In particular, 29 labels containing +/# have unverified semantics and remain conservatively included; near-duplicate overlap and all 37-class coverage are not proven. This remains a fixed-camera deployment candidate requiring fresh camera data for a final decision.

## Evidence and reproduction

- `validation_grid.json`: all 63 validation configurations and counts.
- `selection.json`: selected weights hash, settings and validation reference.
- `test_result.json`: fresh selected-config test result and historical candidate reference.
- `cached_all_results.json`: aggregate cached results for every upstream split.
- `plate_ocr.py` and `test_plate_ocr.py` at the repository root: deployed decoder and its checks.

The exact tuning script and raw detection caches remain locally under `D:/PlateLocalTest/ocr-tuning-20261101/`. Raw images and transcripts are not redistributed here. Run `python -m unittest test_plate_ocr` for the decoder checks; run `python main.py` for the camera app after installing the weights and dependencies described in the project README. Application constants expose input size, confidence and duplicate IoU without modifying the checkpoint. Calibration used the local RTX 4060 Laptop GPU and PyTorch 2.7.1+cu118.
