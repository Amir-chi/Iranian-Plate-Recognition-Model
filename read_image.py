"""Save plate localization, clean crops, character boxes and OCR for image files."""

import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from ultralytics import YOLO

from main import PLATE_MODEL_PATH, CHAR_MODEL_PATH
from plate_pipeline import read_plates
from plate_ocr import character_text


def font(size):
    for name in ("C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if Path(name).is_file():
            return ImageFont.truetype(name, size)
    return ImageFont.load_default()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="An image or a directory of images")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plates", type=Path, default=Path(PLATE_MODEL_PATH))
    parser.add_argument("--chars", type=Path, default=Path(CHAR_MODEL_PATH))
    args = parser.parse_args()
    source = args.input.resolve(strict=True)
    files = sorted(p for p in source.iterdir() if p.suffix.lower() in {'.jpg', '.jpeg', '.png', '.webp'}) if source.is_dir() else [source]
    if not files:
        parser.error("No input images")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    plate_model, char_model = YOLO(str(args.plates)), YOLO(str(args.chars))
    report = {"plate_weights": str(args.plates.resolve()), "character_weights": str(args.chars.resolve()),
              "weights_sha256": {kind: hashlib.sha256(path.read_bytes()).hexdigest()
                                  for kind, path in [('plate', args.plates), ('character', args.chars)]},
              "character_coordinate_space": "Pixels relative to the clean crop_box; add crop_box x/y offsets for full-frame coordinates.",
              "settings": {"plate_confidence": .5, "margin": 5, "imgsz": 640,
                           "character_confidence": .25, "duplicate_iou": .5}, "images": []}
    for index, path in enumerate(files):
        image = Image.open(path).convert('RGB')
        frame = cv2.cvtColor(np.asarray(image), cv2.COLOR_RGB2BGR)
        plates = read_plates(frame, plate_model, char_model)
        prefix = f'{index:03}_{path.stem}'
        full = image.copy()
        drawing = ImageDraw.Draw(full)
        for number, plate in enumerate(plates):
            x1, y1, x2, y2 = plate['crop_box']
            crop = image.crop((x1, y1, x2, y2))
            clean_path = f'{prefix}_plate{number}_clean.png'
            crop.save(output / clean_path)
            # Enlarge only the display; inference used the untouched crop above.
            factor = max(1., min(4., 900 / crop.width))
            annotated = crop.resize((round(crop.width * factor), round(crop.height * factor)), Image.Resampling.LANCZOS)
            canvas = Image.new('RGB', (max(600, annotated.width), annotated.height + 72), 'white')
            canvas.paste(annotated, (0, 0))
            draw = ImageDraw.Draw(canvas)
            for char in plate['characters']:
                a, b, c, d = char['box']
                draw.rectangle((a*factor, b*factor, c*factor, d*factor), outline='#087f80', width=2)
                draw.text((a*factor, b*factor), character_text(char['class']), font=font(19), fill='#ffe45e', stroke_width=1, stroke_fill='black')
            x = 12
            for char in plate['text']:
                draw.text((x, annotated.height + 8), char, font=font(25), fill='#193a54')
                x += max(22, draw.textlength(char, font=font(25)) + 3)
            draw.text((12, annotated.height + 42), 'Standard shape only' if plate['standard_shape'] else 'Unverified layout', font=font(18), fill='#8b5b00')
            char_path = f'{prefix}_plate{number}_characters.png'
            canvas.save(output / char_path)
            drawing.rectangle(tuple(plate['plate_box']), outline='#00c5c8', width=3)
            drawing.text((max(0, x1), max(0, y1-24)), f"Plate {number+1}: {plate['plate_confidence']:.2f}", font=font(18), fill='#00c5c8', stroke_width=1, stroke_fill='black')
            plate.update(clean_crop=clean_path, character_image=char_path)
        full_path = f'{prefix}_localization.png'
        full.save(output / full_path)
        report['images'].append({"source": str(path), "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                                 "localization_image": full_path, "plates": plates})
        print(f'{path.name}: {len(plates)} plate(s)', flush=True)
    (output / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
