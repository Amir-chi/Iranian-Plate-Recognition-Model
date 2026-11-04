import unittest
from types import SimpleNamespace
from unittest.mock import Mock

import numpy as np

from plate_pipeline import read_plates


class Values:
    def __init__(self, value):
        self.value = value

    def cpu(self):
        return self

    def tolist(self):
        return self.value


def result(boxes, confidences, names=None, classes=None):
    return SimpleNamespace(names=names, boxes=SimpleNamespace(xyxy=Values(boxes), conf=Values(confidences), cls=Values(classes)))


class PipelineTests(unittest.TestCase):
    def test_localization_crop_clips_to_frame_and_ocr_sees_clean_pixels(self):
        frame = np.full((30, 50, 3), 73, dtype=np.uint8)
        locator = Mock()
        locator.predict.return_value = [result([[-2, 3, 22, 40]], [.9])]
        reader = Mock()
        reader.predict.return_value = [result([[4, 0, 8, 10], [0, 0, 4, 10]], [.8, .9], {0: '1', 1: '2'}, [1, 0])]
        plates = read_plates(frame, locator, reader)
        self.assertEqual(plates[0]['crop_box'], [0, 0, 27, 30])
        self.assertEqual(plates[0]['text'], '۱۲')
        crop = reader.predict.call_args.args[0]
        self.assertEqual(crop.shape, (30, 27, 3))
        self.assertFalse(np.shares_memory(crop, frame))
        self.assertTrue(np.all(crop == 73))
        self.assertTrue(np.all(frame == 73))

    def test_no_plate_or_invalid_box_does_not_call_character_reader(self):
        locator, reader = Mock(), Mock()
        locator.predict.return_value = [result([[1, 1, 10, 10], [90, 90, 100, 100]], [.1, .9])]
        self.assertEqual(read_plates(np.zeros((20, 20, 3), dtype=np.uint8), locator, reader), [])
        reader.predict.assert_not_called()


if __name__ == '__main__':
    unittest.main()
