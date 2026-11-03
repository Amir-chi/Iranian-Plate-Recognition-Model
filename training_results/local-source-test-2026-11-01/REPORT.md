# Local evaluation of the original test ZIP — 1 November 2026

Source ZIP SHA256: `7d3318431d41f44ce1324bbf273816f530580ff7b8463af14d0a09b43ee3c0fa`. This matches the source test archive used previously; it is not a new independent holdout.

The supplied ZIP was converted directly using the same class normalization and bounding-box validation as `prepare_ir_lpr.py`. Prepared train/validation image hashes were checked and no overlapping images were accepted. Of 5,602 annotations, 5,541 unique valid images were accepted: 18 duplicate images, 42 invalid annotations and one image below the 10-pixel minimum were excluded. No synthetic images were found despite the archive name. Full exclusions are in `source_audit.json`.

The common-class comparison uses the same 5,534 images for both models; all labels are within the original 31 class IDs. The candidate was additionally evaluated on all 5,541 accepted images with 37 configured classes. These are not the former original-real-only 5,499-image comparison manifests, and class support differs. Do not compare the aggregate numbers directly with that earlier report.

Local hardware: NVIDIA GeForce RTX 4060 Laptop GPU; PyTorch 2.7.1+cu118. Settings shared across all three evaluations: split=test, imgsz=640, batch=8, workers=0, device=0, conf=0.001, NMS IoU=0.7, half=False, plots=False. Each model was loaded from its existing checkpoint; no training or weight modification occurred.

| Evaluation | Images | Precision % | Recall % | mAP50 % | mAP50–95 % |
|---|---:|---:|---:|---:|---:|
| baseline_common31 | 5534 | 91.48 | 82.94 | 85.38 | 69.83 |
| candidate_common31 | 5534 | 92.81 | 86.05 | 89.99 | 71.84 |
| candidate_all37 | 5541 | 93.88 | 74.55 | 83.36 | 64.79 |

On the identical common-class image set, the candidate improves mAP50 by **4.61 percentage points** and mAP50–95 by **2.01 points**. This is positive evidence for this particular source-archive subset; it does not erase the previous real-only test regression or establish superiority in deployment. The earlier blanket conclusion that the candidate is worse must be limited to that previous evaluation.

Several rare classes strongly affect the mean across classes. In the baseline common-class run, ث, ز, ش and پ have AP50=0; the candidate improves some of these. Supports are tiny (the entire accepted set has 4 ث boxes, 2 ز boxes, 2 ش boxes and 5 پ boxes), so do not treat a few detections as a stable estimate. The per-class AP arrays and checkpoint hashes are recorded in `comparison.json`.

The all-37 candidate score is a different comparison scope. Some new classes have only one or two examples; absent classes are not evidence of successful recognition. A fresh, adequately supported real holdout and end-to-end plate-string evaluation are still required before choosing the deployment model.

Class support (boxes in the complete accepted ZIP, not necessarily the common-class subset):

| Class | Boxes |
|---|---:|
| 9 | 4084 |
| 5 | 3614 |
| ت | 182 |
| 3 | 3782 |
| 8 | 4286 |
| 6 | 4159 |
| 4 | 4069 |
| ه‍ | 328 |
| 7 | 3831 |
| ث | 4 |
| 1 | 5117 |
| 2 | 4254 |
| ج | 421 |
| ژ (معلولین و جانبازان) | 7 |
| س | 392 |
| ی | 300 |
| م | 436 |
| 0 | 553 |
| ن | 327 |
| و | 407 |
| الف | 16 |
| ق | 449 |
| ل | 345 |
| ب | 426 |
| د | 507 |
| ع | 119 |
| ط | 389 |
| پ | 5 |
| ز | 2 |
| ش | 2 |
| ف | 2 |
| D | 1 |
| S | 1 |
| تشریفات | 1 |
| گ | 1 |
| ک | 1 |
| ص | 486 |

Execution log and the exact script used are retained locally at `D:/PlateLocalTest/20261101/`. The ZIP, extracted images, model weights and local execution log are not bundled in this GitHub evidence directory. Image/annotation licensing remains upstream.
