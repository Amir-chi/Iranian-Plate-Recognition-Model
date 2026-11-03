"""Run with: python -m unittest test_train"""

import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from pathlib import Path

import yaml

from train import main, prepare_data, extend_character_head, ROOT
from prepare_ir_lpr import convert_annotation, normalize


class DatasetPathsTest(unittest.TestCase):
    def test_server_budget_and_checkpoint_settings_reach_trainer(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            weights = root / 'weights.pt'
            weights.touch()
            model = SimpleNamespace(names={0: '0'}, task='detect', add_callback=Mock(),
                                    train=Mock(), trainer=SimpleNamespace(best=str(weights)))
            arguments = ['train.py', '--data', str(root / 'data.yaml'), '--weights', str(weights),
                         '--project', str(root / 'out'), '--batch', '32', '--workers', '4',
                         '--time-hours', '1.5', '--patience', '5', '--save-period', '1']
            with patch('sys.argv', arguments), patch('train.prepare_data', return_value=(root / 'resolved.yaml', ['0'], {'train': 1})), patch.dict('sys.modules', {'ultralytics': SimpleNamespace(YOLO=lambda _: model)}):
                main()
            settings = model.train.call_args.kwargs
            self.assertEqual((settings['time'], settings['patience'], settings['save_period']), (1.5, 5, 1))
            self.assertEqual((settings['batch'], settings['workers']), (32, 4))

    def test_moved_dataset_and_original_yaml_are_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for split in ("train", "val"):
                (root / split / "images").mkdir(parents=True)
                (root / split / "labels").mkdir()
                (root / split / "images/sample.jpg").touch()
                (root / split / "labels/sample.txt").write_text("0 0.5 0.5 0.1 0.2")
            source = root / "data.yaml"
            original = yaml.safe_dump(dict(path="/old/computer/dataset", names=["0"],
                                           nc=1, train="train/images", val="val/images"))
            source.write_text(original, encoding="utf-8")
            target, names, counts = prepare_data(source, root / "outputs")
            resolved = yaml.safe_load(target.read_text(encoding="utf-8"))
            self.assertEqual(counts, {"train": 1, "val": 1})
            self.assertEqual(names, ["0"])
            self.assertEqual(Path(resolved["train"]), root / "train/images")
            self.assertEqual(source.read_text(encoding="utf-8"), original)
            (root / "val/labels/sample.txt").unlink()
            with self.assertRaisesRegex(ValueError, "missing labels"):
                prepare_data(source, root / "outputs")

    def test_manifest_resolves_paths_relative_to_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "images").mkdir()
            (root / "labels").mkdir()
            (root / "images/a.jpg").touch()
            (root / "labels/a.txt").write_text("0 0.5 0.5 0.1 0.1")
            (root / "images.txt").write_text("images/a.jpg\n")
            source = root / "data.yaml"
            source.write_text(yaml.safe_dump(dict(names=["0"], train="images.txt", val="images.txt")))
            target, _, counts = prepare_data(source, root / "out")
            data = yaml.safe_load(target.read_text())
            self.assertEqual(counts, {"train": 1, "val": 1})
            self.assertEqual(Path(data["train"]).read_text().strip(), str(root / "images/a.jpg"))

    def test_annotations_normalize_aliases_and_reject_bad_coordinates(self):
        xml = '<annotation><object><name>ه\u200d</name><bndbox><xmin>10</xmin><ymin>5</ymin><xmax>30</xmax><ymax>15</ymax></bndbox></object></annotation>'
        rows, counts = convert_annotation(xml, 100, 20, {normalize("ه"): 26})
        self.assertEqual(rows.split(), ["26", "0.200000000", "0.500000000", "0.200000000", "0.500000000"])
        self.assertEqual(counts, {26: 1})
        with self.assertRaisesRegex(ValueError, "outside"):
            convert_annotation(xml.replace("<xmax>30", "<xmax>130"), 100, 20, {"ه": 26})

    def test_new_classes_preserve_existing_head_rows(self):
        import torch
        from ultralytics import YOLO
        model = YOLO(str(ROOT / "models/best.pt"))
        names = [model.names[i] for i in range(len(model.names))]
        old_rows = [(branch[-1].weight.clone(), branch[-1].bias.clone()) for branch in model.model.model[-1].cv3]
        extend_character_head(model, names + ["D", "S", "ف", "ک", "گ", "تشریفات"])
        self.assertEqual(len(model.model.names), 37)
        for branch, (weights, bias) in zip(model.model.model[-1].cv3, old_rows):
            self.assertTrue(torch.equal(branch[-1].weight[:31], weights))
            self.assertTrue(torch.equal(branch[-1].bias[:31], bias))
        with self.assertRaisesRegex(ValueError, "original order"):
            extend_character_head(model, list(reversed(names)))


if __name__ == "__main__":
    unittest.main()
