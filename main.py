import cv2
import os
from pathlib import Path
from ultralytics import YOLO
from plate_ocr import recognize_plate

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
        clean_frame = frame.copy()

        # ---------------------------------------
        # Step 1: find plate location (Plate Detection)
        # ---------------------------------------
        plate_results = plate_model(frame, verbose=False)

        for p_res in plate_results:
            for p_box in p_res.boxes:
                # Skip if confidence is less than 50%
                if p_box.conf[0] < 0.5: continue

                # Plate coordinates
                px1, py1, px2, py2 = map(int, p_box.xyxy[0])

                # ---------------------------------------
                # Step 2: crop plate and recognize characters
                # ---------------------------------------
                # Crop with a small safety margin
                h, w, _ = frame.shape
                # Copy clean pixels before adding overlays to the displayed frame.
                crop = clean_frame[max(0, py1 - 5):min(h, py2 + 5), max(0, px1 - 5):min(w, px2 + 5)].copy()

                if crop.size > 0:
                    # Send cropped plate to character model
                    final_text = recognize_plate(char_model, crop, CHAR_IMAGE_SIZE,
                                                 CHAR_CONFIDENCE, CHAR_DUPLICATE_IOU)
                    cv2.rectangle(frame, (px1, py1), (px2, py2), (255, 0, 0), 3)

                    if final_text:

                        # Draw plate text on the image
                        cv2.putText(frame, final_text, (px1, py1 - 15),
                                    cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
                        print(f"Plate detected: {final_text}")

        cv2.imshow('2-Stage LPR System', frame)

        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    start_smart_detection()
