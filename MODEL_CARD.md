# Model card — chars37-best.pt (v0.2.0)

## Task and identity

YOLOv8 nano character detector for cropped Iranian license plates, approximately 3.01 million parameters. Input: plate crop. Output: character boxes, class IDs and confidences; `plate_ocr.py` converts detections into normalized plate text. This is not the full-image plate locator.

Candidate SHA256: `5ab6da27dedafc06f6c69d29c297d9f270c24fa5f0130d1d15fff40ebe9d56c8`.
Baseline SHA256: `dc8e83b4442d6d88a47083c69cf9c65c99fb0fc37162e64e941088e35821ffa5`.

## Training

Initialized from the repository's 31-class `models/best.pt`. Six classes appended: D, S, ف, ک, گ, تشریفات. Original class IDs and classification head rows were preserved. Official IR-LPR real+dummy images were validated and deduplicated: 45,273 train, 5,363 validation, 5,542 test. Training contained 19,273 real and 26,000 synthetic images. The original baseline's full training history is not independently reconstructed here.

Training: 31 October 2026, RTX 3080 Ti, Python 3.10.12, torch 2.5.1+cu121, Ultralytics 8.4.8. AdamW, lr0=0.0001, batch 32, imgsz 640, patience 5, seed 0, time budget 1.4252h, four loader workers. Seven epochs completed; best checkpoint from epoch 2. See [training report](docs/TRAINING_REPORT.md) and [recorded args](training_results/ferdowsi-2026-10-31/chars37/args.yaml).

## Evaluation

- Earlier original-real IR-LPR test: mAP50 94.11% vs baseline 94.77%; mAP50–95 75.20% vs 77.71%.
- Direct source-ZIP shared-class evaluation: mAP50 89.99% vs 85.38%, on 5,534 identical images with different class support from the earlier test.
- HezarAI test full-string exact match: 85.33% for candidate weights vs 83.12% baseline, before decoder improvement.
- Candidate with validation-selected decoder: 86.93% (865/995); text CER 2.80%. The gain over candidate weights alone is a decoding gain, not additional network training.

All rows and split details are in the [HezarAI report](training_results/hezarai-2026-11-01/REPORT.md) and [decoder report](training_results/ocr-improvement-2026-11-01/REPORT.md). Do not compare mAP directly with exact string accuracy. Rare classes have limited real support; 37-class coverage is not proven by the aggregate score. HezarAI labels containing +/# have unverified semantics and remain conservatively included in the headline results. Near-duplicate independence is not proven by byte/pixel checks.

## Use and limitations

Research/prototype use and local comparisons. Test on the target camera before deployment. Cropped-plate recognition improvements do not establish performance of plate localization, full-frame video, blur/night conditions, or nonstandard layouts. No production readiness or universal superiority is claimed. OpenCV's existing text overlay is not a full Persian GUI renderer.

Weights are distributed as a separate release asset. Inspect upstream [IR-LPR](https://github.com/mut-deep/IR-LPR), [HezarAI](https://huggingface.co/datasets/hezarai/persian-license-plate-v1) and Ultralytics terms for your intended use. No new license is asserted for upstream data/weights. Citation information and reproducibility details are in the linked reports.
