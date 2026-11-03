import unittest

from plate_ocr import decode_characters, normalize_text


class DecodeTests(unittest.TestCase):
    def test_competing_classes_on_same_character_keep_more_confident_box(self):
        boxes = [
            {"box": [20, 0, 30, 20], "confidence": .8, "class": "2"},
            {"box": [0, 0, 10, 20], "confidence": .9, "class": "1"},
            {"box": [20, 0, 30, 20], "confidence": .7, "class": "3"},
            {"box": [40, 0, 50, 20], "confidence": .1, "class": "4"},
        ]
        self.assertEqual(decode_characters(boxes, .25, .5), "۱۲")
        self.assertEqual(decode_characters(boxes, .25, None), "۱۲۳")

    def test_adjacent_identical_characters_are_preserved(self):
        boxes = [{"box": [x, 0, x + 10, 20], "confidence": .9, "class": "5"} for x in (0, 9)]
        self.assertEqual(decode_characters(boxes, .25, .5), "۵۵")
        self.assertEqual(decode_characters([], .25, .5), "")

    def test_equivalent_persian_forms(self):
        self.assertEqual(normalize_text("1٢ي ك ه\u200d"), "۱۲یکه")
        self.assertEqual(decode_characters([{"box": [0, 0, 10, 20], "confidence": .9, "class": "الف"}]), "ا")


if __name__ == "__main__":
    unittest.main()
