import base64
import sys
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from preview import extract


class PreviewTests(unittest.TestCase):
    def test_mpeg4_video_frames_and_end_clamping(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'preview.mp4'
            writer = cv2.VideoWriter(str(source), cv2.VideoWriter_fourcc(*'mp4v'), 10, (64, 48))
            self.assertTrue(writer.isOpened())
            for value in range(20):
                writer.write(np.full((48, 64, 3), value * 10, dtype=np.uint8))
            writer.release()
            first, middle, last = [extract(source, t) for t in (0, 1, 2)]
            self.assertAlmostEqual(first['duration'], 2)
            self.assertAlmostEqual(middle['time'], 1)
            self.assertAlmostEqual(last['time'], 1.9)
            frames = [cv2.imdecode(np.frombuffer(base64.b64decode(f['image'].split(',')[1]), np.uint8), cv2.IMREAD_COLOR) for f in (first, middle, last)]
            self.assertLess(frames[0].mean(), frames[1].mean())
            self.assertLess(frames[1].mean(), frames[2].mean())
            with self.assertRaises(ValueError): extract(source, 0, max_seconds=1)

    def test_invalid_input(self):
        for time in (-1, float('nan'), float('inf')):
            with self.assertRaises(ValueError): extract('missing.mp4', time)
        with self.assertRaises(ValueError): extract('missing.mp4')
