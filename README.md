# Iranian Plate Processing Model

A two-stage camera demo for Iranian license plates: a plate detector locates the plate in a full frame, then a YOLO character detector identifies characters in the cropped plate.

## Latest release: v0.2.0

The new 37-class model plus validated OCR decoding reads **865/995 complete test plates correctly (86.93%)**, compared with **827/995 (83.12%)** for the previous model and decoder: **+3.82 percentage points / 38 additional plates** on HezarAI test. This is cropped-plate text accuracy, not character-box mAP or a live-camera result.

Download `chars37-best.pt` from the [v0.2.0 release](https://github.com/Amir-chi/Iranian-Plate-Recognition-Model/releases/tag/v0.2.0) and place it in `models/`. The original `models/best.pt` remains available for comparison. See the [model card](MODEL_CARD.md), [changelog](CHANGELOG.md) and experiment reports below, including the earlier IR-LPR test regression.

The project remains a research prototype for a broader weighbridge automation workflow, not a production certification.

## Current training result

The 31 October 2026 experiment expanded the character detector from 31 to 37 classes. It completed **7 epochs**, selected **epoch 2**, and stopped early after five epochs without improvement. Real-test mAP50 changed from **94.77% to 94.11%**. The original baseline is retained. The application now prefers the locally available candidate with the validated decoder described below; it falls back to the baseline when candidate weights are absent.

Read the [full training report](docs/TRAINING_REPORT.md) for data provenance, preparation, hyperparameters, learning curves, real-only comparisons, limitations, reproduction steps and the next experiment. Raw evidence is in [training_results/ferdowsi-2026-10-31](training_results/ferdowsi-2026-10-31). Operational training instructions are in [TRAINING.md](TRAINING.md).

## Local source-ZIP follow-up — 1 November 2026

A direct local evaluation of the supplied original test ZIP found candidate mAP50 **89.99% vs 85.38%** for the baseline on 5,534 identical common-class images. This differs from the former 5,499-image real-only comparison and includes different rare-class support. The candidate therefore has mixed evidence across evaluation subsets. See the [local test report](training_results/local-source-test-2026-11-01/REPORT.md) and raw per-class results before drawing a deployment conclusion.

## Full-plate OCR follow-up — HezarAI

Both frozen models were evaluated on all 9,953 cropped plates. On the designated 995-image test split, the new model reads **849 complete plates correctly (85.33%)**, versus **827 (83.12%)** for the previous model. These are text exact-match rates, not detection mAP. Read the [full report](training_results/hezarai-2026-11-01/REPORT.md) for all split scores, CER, paired uncertainty, overlap checks and limitations.

## Validated decoder improvement

Duplicate-character suppression selected on validation improves the candidate’s HezarAI test exact match from **85.33% to 86.93% (865/995)**. The input remains 640 pixels. See the [configuration sweep, changes and verification](training_results/ocr-improvement-2026-11-01/REPORT.md). `main.py` uses this decoder and clean plate crops. Place the candidate at `models/chars37-best.pt` to use it; otherwise the app loads the original `models/best.pt` and prints the chosen path. The candidate weights remain a separate local artifact and are not bundled in Git.

## Run the camera demo

Create a Python environment, install a suitable PyTorch build for your CPU/GPU, then install the application dependencies from `requirements-app.txt`. The original `requirements.txt` is a historical environment snapshot, including CUDA-specific pins and both GUI/headless OpenCV packages; it is not the recommended fresh installation path. Place the separately obtained plate locator at `models/plate_detector.pt` and the baseline character detector at `models/best.pt`. Review the weights' terms before redistribution.

```bash
python -m venv .venv
# Activate the environment using the command appropriate for your shell.
python -m pip install -r requirements-app.txt
python main.py
```

`main.py` opens camera index 0; a camera and graphical desktop are required. This demo has not been evaluated as an end-to-end plate-string recognition system. Character-box mAP in the report does not measure full-plate accuracy.

## Data

Source: [IR-LPR](https://github.com/mut-deep/IR-LPR). The original project's [prepared 31-class dataset](https://drive.google.com/file/d/1SH-YcGGjrxnEaOJoqu3_XKrHmVjv2xqI/view) differs from this experiment's prepared 37-class real/synthetic data. Full data lives outside this repository; sample files do not constitute the training dataset. See upstream usage terms before downloading or redistributing data.

## Training

Install a suitable PyTorch build first, then `requirements-train.txt`. Use `prepare_ir_lpr.py` to convert the official archives, and `train.py` to train/evaluate the character detector. See the [reproduction instructions](docs/TRAINING_REPORT.md#reproduction-and-evidence) for the exact recorded experiment and required original split data.

The candidate improves the reported HezarAI full-plate OCR benchmark, but regresses on one earlier IR-LPR real-only box-detection evaluation. Overall deployment superiority and production readiness are not established.
