"""Locate plates in clean full frames, then recognize each detected crop."""

import math

from plate_ocr import recognize_plate_details, has_standard_plate_shape


def read_plates(frame, plate_model, char_model, plate_confidence=.5, margin=5,
                char_imgsz=640, char_confidence=.25, duplicate_iou=.5):
    if frame is None or frame.size == 0:
        raise ValueError("Expected a nonempty image")
    if margin < 0:
        raise ValueError("Crop margin must be nonnegative")
    height, width = frame.shape[:2]
    results = plate_model.predict(frame, imgsz=640, conf=plate_confidence,
                                  rect=False, half=False, verbose=False)
    plates = []
    for result in results:
        for box, score in zip(result.boxes.xyxy.cpu().tolist(), result.boxes.conf.cpu().tolist()):
            if score < plate_confidence or not all(math.isfinite(v) for v in box):
                continue
            x1, y1, x2, y2 = box
            crop_box = [max(0, math.floor(x1 - margin)), max(0, math.floor(y1 - margin)),
                        min(width, math.ceil(x2 + margin)), min(height, math.ceil(y2 + margin))]
            cx1, cy1, cx2, cy2 = crop_box
            if cx2 <= cx1 or cy2 <= cy1:
                continue
            # Neither model receives display overlays; each crop owns clean pixels.
            crop = frame[cy1:cy2, cx1:cx2].copy()
            reading = recognize_plate_details(char_model, crop, char_imgsz, char_confidence, duplicate_iou)
            plates.append({"plate_box": box, "plate_confidence": float(score),
                           "crop_box": crop_box, **reading,
                           "standard_shape": has_standard_plate_shape(reading["text"])})
    return plates
