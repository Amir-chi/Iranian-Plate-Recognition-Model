import cv2
import os
from ultralytics import YOLO

# ==========================================
# Model settings
# ==========================================
# Model 1: plate detector (you need to download it)
PLATE_MODEL_PATH = 'models/plate_detector.pt'
# Model 2: character recognizer (the one you trained)
CHAR_MODEL_PATH = 'models/best.pt'


def start_smart_detection():
    # Check if model files exist
    if not os.path.exists(PLATE_MODEL_PATH) or not os.path.exists(CHAR_MODEL_PATH):
        print("Error: model files not found!")
        print("Make sure plate_detector.pt and best.pt are inside the models folder.")
        return

    print("⏳ Loading models...")
    plate_model = YOLO(PLATE_MODEL_PATH)
    char_model = YOLO(CHAR_MODEL_PATH)
    print("System is ready.")

    cap = cv2.VideoCapture(0)  # laptop camera

    while True:
        ret, frame = cap.read()
        if not ret: break

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

                # Draw rectangle around detected plate
                cv2.rectangle(frame, (px1, py1), (px2, py2), (255, 0, 0), 3)

                # ---------------------------------------
                # Step 2: crop plate and recognize characters
                # ---------------------------------------
                # Crop with a small safety margin
                h, w, _ = frame.shape
                crop = frame[max(0, py1 - 5):min(h, py2 + 5), max(0, px1 - 5):min(w, px2 + 5)]

                if crop.size > 0:
                    # Send cropped plate to character model
                    char_results = char_model(crop, verbose=False)

                    detected_chars = []
                    for c_res in char_results:
                        for c_box in c_res.boxes:
                            # Save (X coordinate, class name)
                            # We need X to sort characters left to right
                            cls_id = int(c_box.cls[0])
                            char_name = char_model.names[cls_id]
                            cx1 = int(c_box.xyxy[0][0])

                            detected_chars.append((cx1, char_name))

                    # Important: sort characters from left to right
                    if len(detected_chars) > 2:
                        detected_chars.sort(key=lambda x: x[0])
                        final_text = "".join([c[1] for c in detected_chars])

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
