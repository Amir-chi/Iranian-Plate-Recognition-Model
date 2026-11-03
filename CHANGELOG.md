# v0.2.0 — 37-class model and improved plate-text decoding

## Improvements

On the same 995 HezarAI test crops, full-plate exact-match accuracy improves:

| Version | Correct plates | Exact match |
|---|---:|---:|
| Previous 31-class model and decoder | 827/995 | 83.12% |
| New 37-class weights, original decoder | 849/995 | 85.33% |
| New weights and duplicate-suppression decoder | 865/995 | 86.93% |

The full pipeline change gains **38 additional correct plates / +3.82 percentage points** over the previous version. Decoder improvement alone adds 16 correct plates, with zero regressions on this test. Candidate test character edit rate decreases from 3.15% to 2.80% after decoding changes.

- Append six output classes while preserving the original 31 class IDs and head rows.
- Suppress competing overlapping character boxes at IoU >0.5 and confidence >=0.25, then read by horizontal center.
- Keep input size 640: validation sweeps at 800 and 960 did not improve the selected result.
- Extract clean crops before drawing display overlays.
- Prefer `models/chars37-best.pt` when available; retain the original weight as a fallback and print the selected file.
- Include training/evaluation scripts, unit tests, raw aggregate metrics, model card and detailed reports.

## Evidence and limits

The 37-class run completed **7 epochs**, selected epoch **2**, and stopped after five epochs without improvement. The adaptive target of 25 epochs was not the number executed.

HezarAI evaluation covered all 9,953 images; the table above refers only to its designated test split. All 63 decoder configurations were selected on validation; test did not choose a configuration. This test has now been inspected, so fresh camera data is needed for future tuning claims. Exact byte/pixel overlap with prepared IR-LPR images was not found, but near-duplicate independence is not proven. Results concern cropped plates, not whole-camera operation or all 37 classes equally.

Earlier IR-LPR real-only test mAP50 changed from 94.77% to 94.11%, so this release does **not** claim universal improvement. Detection mAP and full-text exact match are different metrics. Read the model card and linked reports for the complete evidence.

## Install the new weight

Download `chars37-best.pt` into `models/chars37-best.pt`, install an appropriate PyTorch build, then:

```bash
python -m pip install -r requirements-app.txt
python main.py
```

A graphical desktop, camera 0, and `models/plate_detector.pt` are required for the live demo. The plate locator remains the existing one. The older character weight is retained at `models/best.pt`.

Candidate SHA256: `5ab6da27dedafc06f6c69d29c297d9f270c24fa5f0130d1d15fff40ebe9d56c8`.

Upstream data and software terms continue to apply; this release does not introduce a new license for third-party data or weights. Images, dataset transcripts, credentials and SSH keys are not included as new release assets.
