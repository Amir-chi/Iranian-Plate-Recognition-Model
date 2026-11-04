import cv2
import os
from pathlib import Path
from ultralytics import YOLO
from plate_pipeline import read_plates

# ==========================================
# Model settings
# ==========================================
# Model 1: plate detector (you need to download it)
ROOT = Path(__file__).resolve().parent
PLATE_MODEL_PATH = str(ROOT / 'models/plate_detector.pt')
# Model 2: character recognizer (the one you trained)
CHAR_MODEL_PATH = str(ROOT / 'models/chars37-best.pt')
if not os.path.isfile(CHAR_MODEL_PATH):
    CHAR_MODEL_PATH = str(ROOT / 'models/best.pt')
# Selected on HezarAI validation; increasing image size did not improve the winner.
CHAR_IMAGE_SIZE = 640
CHAR_CONFIDENCE = 0.25
CHAR_DUPLICATE_IOU = 0.5


def start_smart_detection():
    # Check if model files exist
    if not os.path.exists(PLATE_MODEL_PATH) or not os.path.exists(CHAR_MODEL_PATH):
        print("Error: model files not found!")
        print(f"Required weights: {PLATE_MODEL_PATH}, {CHAR_MODEL_PATH}")
        return

    print("⏳ Loading models...")
    plate_model = YOLO(PLATE_MODEL_PATH)
    char_model = YOLO(CHAR_MODEL_PATH)
    print(f"Character weights: {CHAR_MODEL_PATH}")
    print("System is ready.")

    cap = cv2.VideoCapture(0)  # laptop camera

    while True:
        ret, frame = cap.read()
        if not ret: break
        plates = read_plates(frame, plate_model, char_model,
                             char_imgsz=CHAR_IMAGE_SIZE, char_confidence=CHAR_CONFIDENCE,
                             duplicate_iou=CHAR_DUPLICATE_IOU)
        for plate in plates:
            px1, py1, px2, py2 = map(int, plate['plate_box'])
            cv2.rectangle(frame, (px1, py1), (px2, py2), (255, 0, 0), 3)
            if plate['text']:
                color = (0, 255, 0) if plate['standard_shape'] else (0, 165, 255)
                status = "standard layout" if plate['standard_shape'] else "unverified layout"
                cv2.putText(frame, plate['text'], (px1, py1 - 15),
                            cv2.FONT_HERSHEY_SIMPLEX, 1, color, 2)
                cv2.putText(frame, status, (px1, py2 + 22),
                            cv2.FONT_HERSHEY_SIMPLEX, .55, color, 1)
                print(f"Plate candidate ({status}): {plate['text']}")

        cv2.imshow('2-Stage LPR System', frame)

        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    start_smart_detection()
