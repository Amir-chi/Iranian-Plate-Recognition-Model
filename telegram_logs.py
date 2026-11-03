"""Local training events and an independent Telegram log sender.

The sender reads TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID from its environment.
Neither credential is needed by the GPU training process.
"""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from zipfile import ZipFile, ZIP_DEFLATED


class TrainingEvents:
    def __init__(self, project):
        self.project = Path(project).resolve()
        self.project.mkdir(parents=True, exist_ok=True)
        self.last_epoch = 0
        self.last_progress = time.monotonic()
        self.batch = 0

    def emit(self, kind, text, **details):
        event = {"time": datetime.now().astimezone().isoformat(), "kind": kind,
                 "text": text, **details}
        try:
            import psutil
            memory = psutil.virtual_memory()
            rss = psutil.Process().memory_info().rss
            event["memory"] = {"available_ram_gb": round(memory.available / 2**30, 2),
                               "process_ram_gb": round(rss / 2**30, 2),
                               "system_ram_percent": memory.percent}
            event["text"] += f"\nRAM آزاد: {memory.available / 2**30:.2f} GB | فرایند: {rss / 2**30:.2f} GB"
        except (ImportError, OSError):
            pass
        try:
            with (self.project / "events.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")
            temporary = self.project / "status.tmp"
            temporary.write_text(json.dumps(event, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
            temporary.replace(self.project / "status.json")
        except OSError as error:
            print(f"Training event could not be saved: {type(error).__name__}", flush=True)

    def epoch_start(self, trainer):
        self.batch = 0

    def batch_end(self, trainer):
        self.batch += 1
        if time.monotonic() - self.last_progress >= 300:
            self.last_progress = time.monotonic()
            self.emit("progress", f"در حال آموزش: دور {trainer.epoch + 1}/{trainer.epochs}\n"
                      f"Batch: {self.batch}/{len(trainer.train_loader)}")

    def epoch_end(self, trainer):
        epoch = trainer.epoch + 1
        if epoch <= self.last_epoch:
            return  # Ultralytics also fires this callback during final best-weight validation.
        self.last_epoch = epoch
        metrics = {key: float(value) for key, value in trainer.metrics.items()}
        duration = float(trainer.epoch_time or 0)
        eta = duration * max(0, trainer.epochs - epoch) / 3600
        self.emit("epoch", f"دور {epoch}/{trainer.epochs} تمام شد\n"
                  f"mAP50: {metrics.get('metrics/mAP50(B)', 0):.2%}\n"
                  f"mAP50-95: {metrics.get('metrics/mAP50-95(B)', 0):.2%}\n"
                  f"Precision: {metrics.get('metrics/precision(B)', 0):.2%}\n"
                  f"Recall: {metrics.get('metrics/recall(B)', 0):.2%}\n"
                  f"زمان دور: {duration / 60:.1f} دقیقه | باقی‌ماندهٔ تقریبی: {eta:.1f} ساعت",
                  epoch=epoch, metrics=metrics, epoch_seconds=duration)


def telegram_request(token, method, data, files=None):
    import requests
    try:
        response = requests.post(f"https://api.telegram.org/bot{token}/{method}",
                                 data=data, files=files, timeout=(5, 60 if files else 35))
        payload = response.json()
        if response.ok and payload.get("ok"):
            return payload
        # Never log request URLs or exception strings, which can contain the token.
        print(f"Telegram delivery failed: HTTP {response.status_code}, code {payload.get('error_code')}", flush=True)
    except (requests.RequestException, ValueError) as error:
        print(f"Telegram delivery failed: {type(error).__name__}", flush=True)
    return False


def shutdown_choice(query, chat_id, prompt):
    """Only the intended private-chat user and this run's exact button can decide."""
    message = query.get("message", {})
    chat = message.get("chat", {})
    if (chat.get("type") != "private" or str(chat.get("id")) != str(chat_id)
            or str(query.get("from", {}).get("id")) != str(chat_id)
            or message.get("message_id") != prompt["message_id"]):
        return None
    for choice in ("off", "on"):
        if query.get("data") == f"{choice}:{prompt['nonce']}":
            return choice
    return None


def active_training():
    import psutil
    workspace = Path(__file__).resolve().parent
    for process in psutil.process_iter(["cmdline", "cwd"]):
        try:
            if Path(process.info.get("cwd") or "") != workspace:
                continue
            command = process.info.get("cmdline") or []
            direct = any(Path(arg).name == "train.py" for arg in command)
            background = (any(Path(arg).name == "background_run.py" for arg in command)
                          and any(command[i:i + 2] == ["--mode", "training"] for i in range(len(command) - 1)))
            if direct or background:
                return True
        except (psutil.Error, OSError):
            continue
    return False


def shutdown_windows():
    if sys.platform != "win32":
        raise RuntimeError("This shutdown action supports Windows only")
    # No /f: let Windows handle applications through its normal shutdown flow.
    subprocess.run(["shutdown.exe", "/s", "/t", "0"], check=True)


def offer_shutdown(project, token, chat_id):
    state_file = project / "shutdown_prompt.json"
    prompt = json.loads(state_file.read_text()) if state_file.exists() else None
    if prompt and prompt.get("decision"):
        return
    if not prompt:
        nonce = secrets.token_hex(12)
        keyboard = {"inline_keyboard": [[
            {"text": "خاموش کردن سیستم", "callback_data": f"off:{nonce}"},
            {"text": "روشن بماند", "callback_data": f"on:{nonce}"}]]}
        response = telegram_request(token, "sendMessage", {"chat_id": chat_id,
                                    "text": "آموزش، ارزیابی و آماده‌سازی لاگ‌ها تمام شد.\nسیستم خاموش شود؟",
                                    "reply_markup": json.dumps(keyboard, ensure_ascii=False)})
        if not response:
            raise RuntimeError("Could not deliver the shutdown confirmation buttons")
        prompt = {"nonce": nonce, "message_id": response["result"]["message_id"]}
        state_file.write_text(json.dumps(prompt), encoding="utf-8")

    keep_awake = None
    if sys.platform == "win32":
        import ctypes
        keep_awake = ctypes.windll.kernel32.SetThreadExecutionState
        keep_awake.argtypes = [ctypes.c_uint32]
        keep_awake.restype = ctypes.c_uint32
        keep_awake(0x80000001)
    offset = 0
    try:
        while True:
            response = telegram_request(token, "getUpdates", {"offset": offset, "timeout": 20,
                                        "allowed_updates": json.dumps(["callback_query"])})
            if not response:
                time.sleep(10)
                continue
            for update in response["result"]:
                offset = max(offset, update["update_id"] + 1)
                query = update.get("callback_query", {})
                choice = shutdown_choice(query, chat_id, prompt)
                if not choice:
                    continue
                if choice == "off" and active_training():
                    telegram_request(token, "answerCallbackQuery", {"callback_query_id": query["id"],
                                     "text": "هنوز یک فرایند آموزش فعال است؛ خاموشی انجام نشد."})
                    continue
                telegram_request(token, "answerCallbackQuery", {"callback_query_id": query["id"],
                                 "text": "درخواست دریافت شد"})
                telegram_request(token, "editMessageReplyMarkup", {"chat_id": chat_id,
                                 "message_id": prompt["message_id"], "reply_markup": json.dumps({"inline_keyboard": []})})
                if choice == "on":
                    telegram_request(token, "sendMessage", {"chat_id": chat_id, "text": "انتخاب ثبت شد؛ فرمان خاموشی اجرا نمی‌شود."})
                    prompt["decision"] = "keep_on"
                    state_file.write_text(json.dumps(prompt), encoding="utf-8")
                    return
                telegram_request(token, "sendMessage", {"chat_id": chat_id, "text": "تأیید خودت دریافت شد؛ فرمان خاموشی ویندوز اکنون اجرا می‌شود."})
                prompt["decision"] = "shutdown_requested"
                state_file.write_text(json.dumps(prompt), encoding="utf-8")
                try:
                    shutdown_windows()
                except (OSError, subprocess.SubprocessError) as error:
                    prompt["decision"] = "shutdown_failed"
                    state_file.write_text(json.dumps(prompt), encoding="utf-8")
                    telegram_request(token, "sendMessage", {"chat_id": chat_id,
                                     "text": f"فرمان خاموشی با خطا مواجه شد: {type(error).__name__}. سیستم را بررسی کنید."})
                return
    finally:
        if keep_awake:
            keep_awake(0x80000000)


def watch(project, pid, token, chat_id):
    import psutil
    project = Path(project).resolve()
    if (project / "shutdown_prompt.json").exists():
        offer_shutdown(project, token, chat_id)
        return
    cursor_file = project / "telegram_cursor.json"
    cursor = 0
    if cursor_file.exists():
        saved = json.loads(cursor_file.read_text(encoding="utf-8"))
        if saved.get("chat_id") == chat_id:
            cursor = int(saved.get("line", 0))
    try:
        process = psutil.Process(pid)
    except psutil.NoSuchProcess:
        process = None
    stopped_at = None
    while True:
        path = project / "events.jsonl"
        lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
        for line in lines[cursor:]:
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                break  # Retry a partial append on the next pass.
            message = f"[{event['time'][11:19]}] {event['text']}"
            if not telegram_request(token, "sendMessage", {"chat_id": chat_id, "text": message[:4000]}):
                time.sleep(10)
                break
            cursor += 1
            cursor_file.write_text(json.dumps({"chat_id": chat_id, "line": cursor}), encoding="utf-8")
            if event["kind"] in {"completed", "failed"}:
                while process is not None and process.is_running():
                    time.sleep(1)
                archive = project / "training_logs.zip"
                with ZipFile(archive, "w", ZIP_DEFLATED) as zipped:
                    for relative in ("training.stdout.log", "training.stderr.log", "events.jsonl",
                                     "comparison.json", "chars37/results.csv", "chars37/args.yaml"):
                        file = project / relative
                        if file.is_file():
                            zipped.write(file, relative)
                if archive.stat().st_size < 45_000_000:
                    with archive.open("rb") as stream:
                        telegram_request(token, "sendDocument", {"chat_id": chat_id,
                                         "caption": "لاگ کامل اجرای آموزش"},
                                         files={"document": (archive.name, stream, "application/zip")})
                if event["kind"] == "completed":
                    offer_shutdown(project, token, chat_id)
                return
        alive = process is not None and process.is_running()
        if not alive:
            stopped_at = stopped_at or time.monotonic()
            if cursor >= len(lines):
                telegram_request(token, "sendMessage", {"chat_id": chat_id,
                                 "text": "فرایند آموزش متوقف شد و رویداد پایان ثبت نشده است. لاگ محلی و آخرین checkpoint را بررسی کنید."})
                return
            if time.monotonic() - stopped_at > 900:
                raise RuntimeError("Training ended, but pending Telegram messages could not be delivered")
        time.sleep(2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--pid", required=True, type=int)
    args = parser.parse_args()
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        parser.error("Set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in the sender's environment")
    watch(args.run, args.pid, token, chat)
