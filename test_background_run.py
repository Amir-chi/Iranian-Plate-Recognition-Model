from pathlib import Path
import tempfile
import unittest

from background_run import worker_arguments


class BackgroundWorkerTest(unittest.TestCase):
    def test_resume_requires_checkpoint_and_preserves_training_configuration(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(FileNotFoundError):
                worker_arguments('training', folder, resume=True)
            checkpoint = Path(folder) / 'chars37/weights/last.pt'
            checkpoint.parent.mkdir(parents=True)
            checkpoint.touch()
            args = worker_arguments('training', folder, resume=True)
            self.assertEqual(args[args.index('--resume') + 1], str(checkpoint))
            self.assertEqual(args[args.index('--batch') + 1], '8')
            self.assertEqual(args[args.index('--workers') + 1], '0')
            self.assertEqual(args[args.index('--epochs') + 1], '60')
            self.assertFalse(any('TELEGRAM' in arg for arg in args))

    def test_memory_settings_are_explicit_and_validate_values(self):
        args = worker_arguments('training', '.', batch=4, workers=1)
        self.assertEqual(args[args.index('--batch') + 1], '4')
        self.assertEqual(args[args.index('--workers') + 1], '1')
        with self.assertRaises(ValueError):
            worker_arguments('training', '.', batch=0)
        with self.assertRaises(ValueError):
            worker_arguments('training', '.', workers=-1)

    def test_telegram_requires_a_pid_and_uses_the_same_run(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                worker_arguments('telegram', folder)
            args = worker_arguments('telegram', folder, pid=123)
            self.assertEqual(args[args.index('--pid') + 1], '123')
            self.assertEqual(args[args.index('--run') + 1], str(Path(folder).resolve()))


if __name__ == '__main__':
    unittest.main()
