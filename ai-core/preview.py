"""Extract a JPEG preview without loading the detection model."""
import argparse
import base64
import json
import math
from pathlib import Path

import cv2


def extract(source, seconds=0, max_seconds=120):
    if not math.isfinite(seconds) or seconds < 0:
        raise ValueError('Invalid timestamp')
    capture = cv2.VideoCapture(str(source))
    try:
        fps = capture.get(cv2.CAP_PROP_FPS)
        count = capture.get(cv2.CAP_PROP_FRAME_COUNT)
        if not capture.isOpened() or not math.isfinite(fps) or not math.isfinite(count) or fps <= 0 or count < 1:
            raise ValueError('Cannot read video metadata')
        duration = count / fps
        if duration > max_seconds + 0.05:
            raise ValueError('Video exceeds duration limit')
        index = min(int(seconds * fps), int(count) - 1)
        capture.set(cv2.CAP_PROP_POS_FRAMES, index)
        ok, frame = capture.read()
        if not ok or frame is None:
            raise ValueError('Cannot decode selected frame')
        height, width = frame.shape[:2]
        if max(height, width) > 1280:
            ratio = 1280 / max(height, width)
            frame = cv2.resize(frame, (round(width * ratio), round(height * ratio)))
        ok, encoded = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if not ok:
            raise ValueError('Cannot encode preview')
        return {'duration': duration, 'time': index / fps,
                'image': 'data:image/jpeg;base64,' + base64.b64encode(encoded).decode('ascii')}
    finally:
        capture.release()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--time', type=float, default=0)
    parser.add_argument('--max-seconds', type=int, default=120)
    args = parser.parse_args()
    Path(args.output).write_text(json.dumps(extract(args.source, args.time, args.max_seconds)), encoding='utf-8')
