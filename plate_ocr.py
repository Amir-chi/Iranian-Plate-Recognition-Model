"""Shared text decoding for character-detector evaluation and the camera app."""

import unicodedata
import re


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


def has_standard_plate_shape(text):
    """Check the supported 2 digits + one letter + 5 digits layout only.

    A matching shape does not establish correct recognition. Other plate types
    and incomplete crops need separate validation; never trim or guess text.
    """
    return re.fullmatch(r"[۰-۹]{2}[ابپتثجحدزژسشصطعفقکلمنوهیDS][۰-۹]{5}", normalize_text(text)) is not None


def box_iou(a, b):
    intersection = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection
    return intersection / union if union > 0 else 0.0


def select_characters(detections, confidence=0.25, duplicate_iou=None):
    """Retain confident, distinct boxes in physical left-to-right order."""
    selected = []
    for item in sorted(detections, key=lambda d: d["confidence"], reverse=True):
        if item["confidence"] < confidence:
            continue
        if duplicate_iou is not None and any(box_iou(item["box"], other["box"]) > duplicate_iou for other in selected):
            continue
        selected.append(item)
    selected.sort(key=lambda d: (d["box"][0] + d["box"][2]) / 2)
    return selected


def decode_characters(detections, confidence=0.25, duplicate_iou=None):
    """Read retained detections without forcing a length or guessing characters."""
    return "".join(character_text(d["class"]) for d in select_characters(detections, confidence, duplicate_iou))


def result_detections(result):
    boxes = result.boxes
    return [{"box": box, "confidence": float(conf), "class": result.names[int(cls)]}
            for box, conf, cls in zip(boxes.xyxy.cpu().tolist(), boxes.conf.cpu().tolist(), boxes.cls.cpu().tolist())]


def recognize_plate(model, crop, imgsz=640, confidence=0.25, duplicate_iou=0.5):
    return recognize_plate_details(model, crop, imgsz, confidence, duplicate_iou)["text"]


def recognize_plate_details(model, crop, imgsz=640, confidence=0.25, duplicate_iou=0.5):
    # Use the same square letterboxing for individual camera crops and evaluation batches.
    results = model.predict(crop, imgsz=imgsz, conf=confidence, iou=0.7, half=False, rect=False, verbose=False)
    detections = [item for result in results for item in result_detections(result)]
    selected = select_characters(detections, confidence, duplicate_iou)
    return {"text": "".join(character_text(d["class"]) for d in selected),
            "characters": selected}
