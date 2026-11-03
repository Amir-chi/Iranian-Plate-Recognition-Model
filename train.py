"""Fine-tune the existing character detector locally or on a GPU server."""

import argparse
from copy import deepcopy
import json
import math
from pathlib import Path

import yaml
from telegram_logs import TrainingEvents


ROOT = Path(__file__).resolve().parent
ACTIVE_REPORTER = None


def prepare_data(source, project):
    """Resolve this project's split directories beside data.yaml, ignoring old paths."""
    source = Path(source).resolve(strict=True)
    data = yaml.safe_load(source.read_text(encoding="utf-8"))
    names = data.get("names")
    if not isinstance(names, (list, dict)) or not names:
        raise ValueError("data.yaml must contain class names")
    if data.get("nc", len(names)) != len(names):
        raise ValueError("nc does not match the class names")
    counts = {}
    for split in ("train", "val", "test"):
        if split not in data:
            if split == "test":
                continue
            raise ValueError(f"Missing {split} split")
        location = Path(data[split])
        if not location.is_absolute():
            location = source.parent / location
        location = location.resolve(strict=True)
        if location.is_file():
            images = [Path(line.strip()) for line in location.read_text(encoding="utf-8").splitlines() if line.strip()]
            images = [(p if p.is_absolute() else location.parent / p).resolve(strict=True) for p in images]
        else:
            images = [p for p in location.iterdir()
                      if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}]
        if not images:
            raise ValueError(f"No images in {location}")
        missing = [p.name for p in images
                   if not (p.parent.parent / "labels" / f"{p.stem}.txt").is_file()]
        if missing:
            raise ValueError(f"{split}: missing labels, e.g. {missing[:3]}")
        if location.is_file():
            # Canonical absolute entries keep manifests portable within this host.
            manifest = Path(project).resolve() / f"resolved_{split}.txt"
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text("\n".join(map(str, images)) + "\n", encoding="utf-8")
            data[split] = str(manifest)
        else:
            data[split] = str(location)
        counts[split] = len(images)
    data["path"] = str(source.parent)
    project = Path(project).resolve()
    project.mkdir(parents=True, exist_ok=True)
    target = project / "resolved_data.yaml"
    target.write_text(yaml.safe_dump(data, allow_unicode=True), encoding="utf-8")
    return target, names, counts


def extend_character_head(model, names):
    """Append classes to this YOLOv8 head while preserving every existing class row."""
    import torch
    from ultralytics.nn.tasks import DetectionModel

    old_names = [model.names[i] for i in range(len(model.names))]
    if names[:len(old_names)] != old_names or len(names) <= len(old_names):
        raise ValueError("New classes must be appended after all existing classes, in their original order")
    previous = model.model
    torch.manual_seed(0)
    expanded = DetectionModel(deepcopy(previous.yaml), nc=len(names), verbose=False)
    expanded.load(previous, verbose=False)
    old_head, new_head = previous.model[-1], expanded.model[-1]
    if getattr(old_head, "end2end", False):
        raise ValueError("Class extension here supports the project's YOLOv8 character head")
    with torch.no_grad():
        for old_branch, new_branch in zip(old_head.cv3, new_head.cv3, strict=True):
            old, new = old_branch[-1], new_branch[-1]
            if old.weight.shape[1:] != new.weight.shape[1:]:
                raise ValueError("Classification feature width changed; cannot preserve class rows")
            new.weight[:len(old_names)].copy_(old.weight)
            new.bias[:len(old_names)].copy_(old.bias)
    expanded.names = dict(enumerate(names))
    model.model = expanded
    print(f"Extended character head: {len(old_names)} -> {len(names)} classes; existing rows preserved")


def evaluate(model, data, split, args, name):
    metrics = model.val(data=str(data), split=split, imgsz=args.imgsz,
                        batch=args.batch, device=args.device, workers=args.workers,
                        project=str(args.project.resolve()), name=name)
    result = {key: float(value) for key, value in metrics.results_dict.items()}
    (args.project / f"{name}_metrics.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


def main():
    global ACTIVE_REPORTER
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, type=Path)
    parser.add_argument("--weights", type=Path, default=ROOT / "models/best.pt")
    parser.add_argument("--real-data", type=Path, help="Original real-only dataset for before/after evaluation")
    parser.add_argument("--project", type=Path, default=ROOT / "runs/finetune")
    parser.add_argument("--name", default="persian_chars")
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--device", default="0")
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--time-hours", type=float, help="Training time budget in hours; evaluation is additional")
    parser.add_argument("--patience", type=int, default=15)
    parser.add_argument("--save-period", type=int, default=5, help="Save an epoch checkpoint every N epochs")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--resume", type=Path, help="Interrupted run's last.pt")
    action.add_argument("--evaluate", choices=("val", "test"))
    action.add_argument("--check-data", action="store_true")
    args = parser.parse_args()
    if args.epochs < 1 or args.batch < 1 or args.imgsz < 32 or args.workers < 0:
        parser.error("Invalid epochs, batch, imgsz or workers")
    if args.patience < 1 or args.save_period < 1:
        parser.error("Patience and save period must be positive")
    if args.time_hours is not None and (not math.isfinite(args.time_hours) or args.time_hours <= 0):
        parser.error("Training time budget must be finite and positive")
    resolved, names, counts = prepare_data(args.data, args.project)
    print(json.dumps({"images": counts, "classes": len(names), "yaml": str(resolved)},
                     ensure_ascii=False, indent=2))
    if args.check_data:
        return

    reporter = ACTIVE_REPORTER = TrainingEvents(args.project)
    reporter.emit("started", f"اجرای آموزش مدل کاراکتر شروع شد\n"
                  f"کلاس‌ها: {len(names)} | train: {counts.get('train', 0):,}\n"
                  f"حداکثر دور: {args.epochs} | batch: {args.batch} | imgsz: {args.imgsz}\n"
                  f"DataLoader workers: {args.workers} | image cache: off")

    # Keep data checks usable before installing the GPU runtime.
    from ultralytics import YOLO

    weights = (args.resume or args.weights).resolve(strict=True)
    model = YOLO(str(weights))
    names = names if isinstance(names, list) else [names[i] for i in range(len(names))]
    current = [model.names[i] for i in range(len(model.names))]
    if model.task != "detect":
        raise ValueError("Expected a character detection checkpoint")
    if current != names:
        if args.evaluate and current[:len(names)] == names:
            pass  # Expanded models can also be evaluated on the original real-only classes.
        elif not args.evaluate and not args.resume and names[:len(current)] == current:
            extend_character_head(model, names)
        else:
            raise ValueError("Checkpoint class names/order must match; new classes may only be appended")
    if args.evaluate:
        if args.evaluate not in counts:
            parser.error(f"No {args.evaluate} split in data.yaml")
        evaluate(model, resolved, args.evaluate, args, args.name)
        return

    comparison = {}
    if args.real_data:
        real_data, real_names, real_counts = prepare_data(args.real_data, args.project / "real_evaluation")
        real_names = real_names if isinstance(real_names, list) else [real_names[i] for i in range(len(real_names))]
        baseline = YOLO(str(args.weights.resolve(strict=True)))
        baseline_names = [baseline.names[i] for i in range(len(baseline.names))]
        if names[:len(real_names)] != real_names or baseline_names[:len(real_names)] != real_names:
            raise ValueError("Real-only evaluation must use the unchanged class IDs shared with the baseline")
        reporter.emit("stage", "در حال ثبت دقت پایه روی پلاک‌های واقعی...")
        comparison["baseline_val"] = evaluate(baseline, real_data, "val", args, "baseline_real")
        reporter.emit("baseline", f"دقت پایه روی دادهٔ واقعی ثبت شد\n"
                      f"mAP50: {comparison['baseline_val']['metrics/mAP50(B)']:.2%}\n"
                      f"mAP50-95: {comparison['baseline_val']['metrics/mAP50-95(B)']:.2%}")
        del baseline
    model.add_callback("on_train_epoch_start", reporter.epoch_start)
    model.add_callback("on_train_batch_end", reporter.batch_end)
    model.add_callback("on_fit_epoch_end", reporter.epoch_end)
    model.add_callback("on_train_start", lambda trainer: reporter.emit("stage", "داده‌ها بارگذاری شدند؛ آموزش روی GPU در حال اجراست."))
    reporter.emit("stage", "در حال آماده‌سازی مدل ۳۷کلاسه و بارگذاری دیتاست...")
    if args.resume:
        model.train(resume=True, data=str(resolved), device=args.device,
                    batch=args.batch, workers=args.workers, cache=False,
                    time=args.time_hours, patience=args.patience, save_period=args.save_period)
    else:
        model.train(
            data=str(resolved), epochs=args.epochs, imgsz=args.imgsz,
            batch=args.batch, device=args.device, workers=args.workers,
            project=str(args.project.resolve()), name=args.name,
            optimizer="AdamW", lr0=0.0001, cos_lr=True, patience=args.patience, time=args.time_hours,
            warmup_bias_lr=0.0, warmup_epochs=1.0,
            fliplr=0.0, flipud=0.0, mosaic=0.0, mixup=0.0,
            amp=True, seed=0, save=True, save_period=args.save_period,
            cache=False,
        )
    if args.real_data:
        reporter.emit("stage", "آموزش تمام شد؛ مقایسهٔ نهایی مدل قبلی و جدید روی پلاک‌های واقعی در حال اجراست...")
        comparison["finetuned_val"] = evaluate(model, real_data, "val", args, "finetuned_real")
        if "test" in real_counts:
            # Test is evaluated after training has finished, outside model selection.
            comparison["finetuned_test"] = evaluate(model, real_data, "test", args, "finetuned_real_test")
            comparison["baseline_test"] = evaluate(YOLO(str(args.weights.resolve())), real_data, "test", args, "baseline_real_test")
        comparison["best_weights"] = str(model.trainer.best)
        (args.project / "comparison.json").write_text(json.dumps(comparison, indent=2), encoding="utf-8")
        old = comparison.get("baseline_test", comparison["baseline_val"])
        new = comparison.get("finetuned_test", comparison["finetuned_val"])
        reporter.emit("completed", f"آموزش و ارزیابی تمام شد\n"
                      f"mAP50 واقعی — مدل قبلی: {old['metrics/mAP50(B)']:.2%}\n"
                      f"مدل جدید: {new['metrics/mAP50(B)']:.2%}\n"
                      f"تغییر: {(new['metrics/mAP50(B)'] - old['metrics/mAP50(B)']) * 100:+.2f} واحد درصد\n"
                      f"وزن منتخب: {comparison['best_weights']}", comparison=comparison)
    else:
        reporter.emit("completed", f"آموزش تمام شد. وزن منتخب: {model.trainer.best}")


if __name__ == "__main__":
    import sys
    keep_awake = None
    if sys.platform == "win32":
        import ctypes
        keep_awake = ctypes.windll.kernel32.SetThreadExecutionState
        keep_awake.argtypes = [ctypes.c_uint32]
        keep_awake.restype = ctypes.c_uint32
        if not keep_awake(0x80000001):  # continuous + system required; display may sleep
            print("Warning: unable to prevent automatic system sleep")
    try:
        main()
    except BaseException as error:
        if ACTIVE_REPORTER:
            ACTIVE_REPORTER.emit("failed", f"آموزش متوقف شد: {type(error).__name__}\n{str(error)[-1800:]}")
        raise
    finally:
        if keep_awake:
            keep_awake(0x80000000)
