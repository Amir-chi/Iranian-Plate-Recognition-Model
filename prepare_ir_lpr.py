"""Convert the official IR-LPR real+dummy ZIPs without changing the original data."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import math
from pathlib import Path, PurePosixPath
import threading
import unicodedata
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
import yaml


ROOT = Path(__file__).resolve().parent
FILE_IDS = {"train": "1PBbW1I7Itdf83bH_p60vo3_jrGTS0yLM",
            "val": "1yZYdSNYBPXOoT_QySlH2RVTQS0AySA1z",
            "test": "1HzZ5vgP5XsmbFCE-n8F70qlRRUtABIq6"}
EXTRA_CLASSES = ["D", "S", "ف", "ک", "گ", "تشریفات"]


def normalize(name):
    return (unicodedata.normalize("NFKC", name).strip()
            .replace("\u200d", "").replace("\u200c", "")
            .replace("ي", "ی").replace("ك", "ک"))


def convert_annotation(xml, width, height, class_ids):
    rows, classes = [], Counter()
    for obj in ET.fromstring(xml).findall("object"):
        name = normalize(obj.findtext("name", ""))
        if name not in class_ids:
            raise ValueError(f"unsupported class: {name!a}")
        box = obj.find("bndbox")
        x1, y1, x2, y2 = [float(box.findtext(k)) for k in ("xmin", "ymin", "xmax", "ymax")]
        if not all(math.isfinite(v) for v in (x1, y1, x2, y2)):
            raise ValueError("non-finite coordinates")
        if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
            raise ValueError(f"box outside {width}x{height}: {[x1, y1, x2, y2]}")
        cls = class_ids[name]
        rows.append(f"{cls} {(x1+x2)/2/width:.9f} {(y1+y2)/2/height:.9f} "
                    f"{(x2-x1)/width:.9f} {(y2-y1)/height:.9f}")
        classes[cls] += 1
    if not rows:
        raise ValueError("empty annotation")
    return "\n".join(rows) + "\n", classes


def prepare(downloads, output, base_data):
    output = Path(output).resolve()
    if output.exists():
        raise FileExistsError(f"Choose a new output folder; refusing to overwrite {output}")
    base_data = Path(base_data).resolve(strict=True)
    base = yaml.safe_load(base_data.read_text(encoding="utf-8"))
    old_names = base["names"]
    if not isinstance(old_names, list):
        old_names = [old_names[i] for i in range(len(old_names))]
    names = old_names + [n for n in EXTRA_CLASSES if normalize(n) not in {normalize(x) for x in old_names}]
    class_ids = {normalize(n): i for i, n in enumerate(names)}
    # Some distinct images share a filename; the raw JPEG bytes identify them.
    original_owner = {}
    raw = base_data.parent.parent / "raw_dataset"
    if not raw.is_dir():
        raise FileNotFoundError(f"Original raw_dataset is needed to preserve split ownership: {raw}")
    def image_digest(image):
        return hashlib.sha256(image.read_bytes()).hexdigest()
    with ThreadPoolExecutor(max_workers=8) as pool:
        for split in ("test", "val", "train"):
            images = [p for p in (raw / split).iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"}]
            for digest in pool.map(image_digest, images):
                original_owner.setdefault(digest, split)
    archives = {s: Path(downloads) / f"plate_image_with_dummy-{s}.zip" for s in FILE_IDS}
    for archive in archives.values():
        if not archive.is_file():
            raise FileNotFoundError(archive)
    output.mkdir(parents=True)
    for split in FILE_IDS:
        (output / split / "images").mkdir(parents=True)
        (output / split / "labels").mkdir()

    seen, lock = set(), threading.Lock()
    report = {"source": "https://github.com/mut-deep/IR-LPR", "names": names,
              "archives_sha256": {}, "excluded": [], "duplicates": [], "splits": {}}
    manifests = {s: [] for s in FILE_IDS}
    real = {s: [] for s in FILE_IDS}
    stats = {s: Counter() for s in FILE_IDS}
    box_counts = {s: Counter() for s in FILE_IDS}

    # Priority protects held-out splits when byte-identical images appear twice.
    for source_split in ("test", "val", "train"):
        archive = archives[source_split]
        with archive.open("rb") as stream:
            report["archives_sha256"][source_split] = hashlib.file_digest(stream, "sha256").hexdigest()
        with ZipFile(archive) as zipped:
            members = set(zipped.namelist())
            annotations = sorted(n for n in members if n.lower().endswith(".xml"))

            def convert(member):
                stem = PurePosixPath(member).stem
                synthetic = stem.startswith("dummy_")
                image_member = next((str(PurePosixPath(member).with_suffix(ext))
                                     for ext in (".jpg", ".png", ".jpeg", ".JPG", ".PNG")
                                     if str(PurePosixPath(member).with_suffix(ext)) in members), None)
                try:
                    if image_member is None:
                        raise ValueError("missing image")
                    payload = zipped.read(image_member)
                    digest = hashlib.sha256(payload).hexdigest()
                    split = original_owner.get(digest, source_split)
                    with Image.open(io.BytesIO(payload)) as image:
                        width, height = image.size
                        image.verify()
                    label, counts = convert_annotation(zipped.read(member), width, height, class_ids)
                    with lock:
                        if digest in seen:
                            return "duplicate", {"archive": source_split, "xml": member, "sha256": digest}
                        seen.add(digest)
                    image_path = output / split / "images" / (digest + PurePosixPath(image_member).suffix.lower())
                    label_path = output / split / "labels" / (digest + ".txt")
                    image_path.write_bytes(payload)
                    label_path.write_text(label, encoding="utf-8")
                    is_original_real = (not synthetic and digest in original_owner
                                        and all(cls < len(old_names) for cls in counts))
                    return "ok", (split, str(image_path), synthetic, is_original_real, counts)
                except (ValueError, TypeError, AttributeError, ET.ParseError, OSError) as exc:
                    return "excluded", {"archive": source_split, "xml": member, "reason": str(exc)}

            with ThreadPoolExecutor(max_workers=8) as pool:
                # Limit pending image buffers on Python 3.11.
                for start in range(0, len(annotations), 256):
                    for status, result in pool.map(convert, annotations[start:start + 256]):
                        if status != "ok":
                            report["duplicates" if status == "duplicate" else "excluded"].append(result)
                            continue
                        split, image, synthetic, original_real, counts = result
                        manifests[split].append(image)
                        if original_real:
                            real[split].append(image)
                        stats[split]["synthetic" if synthetic else "real"] += 1
                        box_counts[split].update(counts)
        print(f"Converted {source_split}: {len(annotations)} XMLs", flush=True)

    for split in FILE_IDS:
        (output / f"{split}.txt").write_text("\n".join(sorted(manifests[split])) + "\n", encoding="utf-8")
        (output / f"real_{split}.txt").write_text("\n".join(sorted(real[split])) + "\n", encoding="utf-8")
        report["splits"][split] = {"images": len(manifests[split]), **stats[split],
                                   "original_real_eval": len(real[split]),
                                   "boxes_per_class": {names[i]: box_counts[split][i] for i in range(len(names))}}
    common = {"path": str(output), "names": names, "nc": len(names)}
    (output / "data.yaml").write_text(yaml.safe_dump({**common, **{s: f"{s}.txt" for s in FILE_IDS}},
                                                   allow_unicode=True), encoding="utf-8")
    (output / "real.yaml").write_text(yaml.safe_dump({**common, "names": old_names, "nc": len(old_names),
                                                    **{s: f"real_{s}.txt" for s in FILE_IDS}},
                                                   allow_unicode=True), encoding="utf-8")
    (output / "preparation_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output), "classes": len(names), "splits": report["splits"],
                      "excluded_images": len(report["excluded"]), "duplicate_images": len(report["duplicates"])},
                     ensure_ascii=True, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--downloads", type=Path, default=ROOT / "runs/datasets/ir-lpr/downloads")
    parser.add_argument("--output", type=Path, default=ROOT / "runs/datasets/ir-lpr/ready")
    parser.add_argument("--base-data", type=Path, default=ROOT.parent / "Iranian-Plate-Processing-Model-TRAIN_FOLDER/ready_dataset/data.yaml")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if args.download:
        import gdown
        args.downloads.mkdir(parents=True, exist_ok=True)
        for split, file_id in FILE_IDS.items():
            archive = args.downloads / f"plate_image_with_dummy-{split}.zip"
            if not archive.exists():
                gdown.download(id=file_id, output=str(archive), resume=True, use_cookies=False)
    prepare(args.downloads, args.output, args.base_data)
