import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from telegram_logs import TrainingEvents, shutdown_choice, shutdown_windows, telegram_request, offer_shutdown


class TrainingTelemetryTest(unittest.TestCase):
    def test_epoch_is_written_once_and_status_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            events = TrainingEvents(directory)
            trainer = SimpleNamespace(epoch=0, epochs=60, epoch_time=120,
                                      metrics={"metrics/mAP50(B)": 0.9})
            events.epoch_end(trainer)
            events.epoch_end(trainer)
            lines = (Path(directory) / "events.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 1)
            event = json.loads(lines[0])
            self.assertEqual(event["epoch"], 1)
            self.assertEqual(event["metrics"]["metrics/mAP50(B)"], 0.9)
            self.assertEqual(json.loads((Path(directory) / "status.json").read_text(encoding="utf-8")), event)

    def test_shutdown_button_requires_exact_user_chat_message_and_nonce(self):
        prompt = {"nonce": "test-nonce", "message_id": 12}
        query = {"from": {"id": 123}, "message": {"message_id": 12,
                 "chat": {"id": 123, "type": "private"}}, "data": "off:test-nonce"}
        self.assertEqual(shutdown_choice(query, "123", prompt), "off")
        query["from"]["id"] = 999
        self.assertIsNone(shutdown_choice(query, "123", prompt))
        query["from"]["id"] = 123
        query["data"] = "off:old-nonce"
        self.assertIsNone(shutdown_choice(query, "123", prompt))
        query["data"] = "on:test-nonce"
        self.assertEqual(shutdown_choice(query, "123", prompt), "on")
        query["message"]["chat"]["type"] = "group"
        self.assertIsNone(shutdown_choice(query, "123", prompt))

    def test_shutdown_uses_normal_windows_shutdown_without_force(self):
        with patch("telegram_logs.sys.platform", "win32"), patch("telegram_logs.subprocess.run") as run:
            shutdown_windows()
        run.assert_called_once_with(["shutdown.exe", "/s", "/t", "0"], check=True)

    def test_transport_errors_do_not_print_token(self):
        import requests
        with patch("requests.post", side_effect=requests.ConnectionError("secret-token-in-url")), patch("builtins.print") as output:
            self.assertFalse(telegram_request("secret-token", "sendMessage", {}))
        self.assertNotIn("secret-token", str(output.call_args_list))

    def test_keep_on_choice_never_invokes_shutdown(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "shutdown_prompt.json").write_text(json.dumps({"nonce": "nonce", "message_id": 12}))
            queries = [{"update_id": 1, "callback_query": {"id": "a", "from": {"id": 999},
                       "message": {"message_id": 12, "chat": {"id": 123, "type": "private"}}, "data": "off:nonce"}},
                       {"update_id": 2, "callback_query": {"id": "b", "from": {"id": 123},
                       "message": {"message_id": 12, "chat": {"id": 123, "type": "private"}}, "data": "on:nonce"}}]
            def reply(token, method, data):
                return {"ok": True, "result": queries if method == "getUpdates" else True}
            with patch("telegram_logs.sys.platform", "test"), patch("telegram_logs.telegram_request", side_effect=reply), patch("telegram_logs.shutdown_windows") as shutdown:
                offer_shutdown(project, "test-token", "123")
            shutdown.assert_not_called()
            self.assertEqual(json.loads((project / "shutdown_prompt.json").read_text())["decision"], "keep_on")

    def test_authorized_shutdown_click_invokes_shutdown_once(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "shutdown_prompt.json").write_text(json.dumps({"nonce": "nonce", "message_id": 12}))
            query = {"update_id": 1, "callback_query": {"id": "a", "from": {"id": 123},
                     "message": {"message_id": 12, "chat": {"id": 123, "type": "private"}}, "data": "off:nonce"}}
            def reply(token, method, data):
                return {"ok": True, "result": [query] if method == "getUpdates" else True}
            with patch("telegram_logs.sys.platform", "test"), patch("telegram_logs.active_training", return_value=False), patch("telegram_logs.telegram_request", side_effect=reply), patch("telegram_logs.shutdown_windows") as shutdown:
                offer_shutdown(project, "test-token", "123")
            shutdown.assert_called_once_with()
            self.assertEqual(json.loads((project / "shutdown_prompt.json").read_text())["decision"], "shutdown_requested")


if __name__ == "__main__":
    unittest.main()
