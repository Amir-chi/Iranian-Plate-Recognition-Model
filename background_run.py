"""Run one training or Telegram worker with durable, append-only local logs."""

import argparse
import os
from pathlib import Path
import runpy
import sys


ROOT = Path(__file__).resolve().parent


def worker_arguments(mode, run, resume=False, pid=None, batch=8, workers=0):
    run = Path(run).resolve()
    if mode == "telegram":
        if not pid or pid <= 0:
            raise ValueError("A training PID is required for the Telegram worker")
        return [str(ROOT / "telegram_logs.py"), "--run", str(run), "--pid", str(pid)]
    if mode != "training":
        raise ValueError("Unknown worker mode")
    if batch < 1 or workers < 0:
        raise ValueError("Batch must be positive and workers cannot be negative")
    data = ROOT / "runs/datasets/ir-lpr/ready"
    args = [str(ROOT / "train.py"), "--data", str(data / "data.yaml"),
            "--real-data", str(data / "real.yaml"), "--project", str(run),
            "--epochs", "60", "--batch", str(batch), "--workers", str(workers), "--name", "chars37"]
    if resume:
        checkpoint = run / "chars37/weights/last.pt"
        checkpoint.resolve(strict=True)
        args.extend(["--resume", str(checkpoint)])
    return args


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("training", "telegram"))
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--pid", type=int)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--workers", type=int, default=0)
    args = parser.parse_args()
    args.run = args.run.resolve()
    args.run.mkdir(parents=True, exist_ok=True)
    os.chdir(ROOT)
    sys.argv = worker_arguments(args.mode, args.run, args.resume, args.pid, args.batch, args.workers)
    prefix = "training" if args.mode == "training" else "telegram"
    sys.stdout = (args.run / f"{prefix}.stdout.log").open("a", encoding="utf-8", buffering=1)
    sys.stderr = (args.run / f"{prefix}.stderr.log").open("a", encoding="utf-8", buffering=1)
    os.dup2(sys.stdout.fileno(), 1)
    os.dup2(sys.stderr.fileno(), 2)
    runpy.run_path(sys.argv[0], run_name="__main__")
