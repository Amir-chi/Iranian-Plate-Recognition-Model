"""Shared text decoding for character-detector evaluation and the camera app."""

import unicodedata


def normalize_text(text):
    text = unicodedata.normalize("NFKC", text).replace("ي", "ی").replace("ك", "ک").replace("آ", "ا")
    text = "".join(c for c in text if not c.isspace() and c not in "\u200c\u200d\u200e\u200f")
    return text.translate(str.maketrans("0123456789٠١٢٣٤٥٦٧٨٩", "۰۱۲۳۴۵۶۷۸۹۰۱۲۳۴۵۶۷۸۹"))


def character_text(name):
    if name == "الف":
        return "ا"
    if name.startswith("ژ "):
        return "ژ"
    return normalize_text(name)


def box_iou(a, b):
    intersection = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection
    return intersection / union if union > 0 else 0.0


def decode_characters(detections, confidence=0.25, duplicate_iou=None):
    """Keep confident boxes, optionally suppress overlapping class alternatives, and read left to right.

    Detections contain ``box`` (xyxy), ``confidence``, and ``class`` (name).
    No expected string length or ground truth is used.
    """
    selected = []
    for item in sorted(detections, key=lambda d: d["confidence"], reverse=True):
        if item["confidence"] < confidence:
            continue
        if duplicate_iou is not None and any(box_iou(item["box"], other["box"]) > duplicate_iou for other in selected):
            continue
        selected.append(item)
    selected.sort(key=lambda d: (d["box"][0] + d["box"][2]) / 2)
    return "".join(character_text(d["class"]) for d in selected)


def result_detections(result):
    boxes = result.boxes
    return [{"box": box, "confidence": float(conf), "class": result.names[int(cls)]}
            for box, conf, cls in zip(boxes.xyxy.cpu().tolist(), boxes.conf.cpu().tolist(), boxes.cls.cpu().tolist())]


def recognize_plate(model, crop, imgsz=640, confidence=0.25, duplicate_iou=0.5):
    # Use the same square letterboxing for individual camera crops and evaluation batches.
    results = model.predict(crop, imgsz=imgsz, conf=confidence, iou=0.7, half=False, rect=False, verbose=False)
    detections = [item for result in results for item in result_detections(result)]
    return decode_characters(detections, confidence, duplicate_iou)
