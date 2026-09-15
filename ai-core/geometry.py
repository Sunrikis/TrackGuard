"""Track-region estimation and intrusion geometry, independent of model weights."""
from __future__ import annotations
import math
import cv2
import numpy as np


def _orientation(a, b, c):
    return float((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]))


def _segments_intersect(a, b, c, d):
    eps = 1e-7
    values = (_orientation(a, b, c), _orientation(a, b, d),
              _orientation(c, d, a), _orientation(c, d, b))
    if any(abs(value) <= eps for value in values):
        # Collinear contact between non-neighbouring polygon edges is invalid too.
        def within(p, x, y):
            return (min(x[0], y[0])-eps <= p[0] <= max(x[0], y[0])+eps and
                    min(x[1], y[1])-eps <= p[1] <= max(x[1], y[1])+eps)
        if abs(values[0]) <= eps and within(c, a, b): return True
        if abs(values[1]) <= eps and within(d, a, b): return True
        if abs(values[2]) <= eps and within(a, c, d): return True
        if abs(values[3]) <= eps and within(b, c, d): return True
    return values[0] * values[1] < 0 and values[2] * values[3] < 0


def validate_roi(points):
    """Validate a normalized, simple polygon used as the danger corridor."""
    p = np.asarray(points, dtype=np.float32)
    if p.ndim != 2 or p.shape[1:] != (2,) or not 4 <= len(p) <= 24:
        raise ValueError("Danger area must contain 4 to 24 ordered points")
    if not np.isfinite(p).all() or (p < 0).any() or (p > 1).any():
        raise ValueError("Danger area points must be normalized")
    if abs(cv2.contourArea(p, oriented=True)) < .005:
        raise ValueError("Danger area is too small")
    count = len(p)
    for i in range(count):
        a, b = p[i], p[(i+1) % count]
        if np.linalg.norm(a-b) <= 1e-5:
            raise ValueError("Danger area contains duplicate points")
        for j in range(i+1, count):
            if j in (i, i+1) or (i == 0 and j == count-1):
                continue
            c, d = p[j], p[(j+1) % count]
            if _segments_intersect(a, b, c, d):
                raise ValueError("Danger area must not cross itself")
    return p


def _curve_peaks(score, width, limit=14):
    if not np.any(score > 0):
        return []
    threshold = max(float(np.percentile(score, 80)), float(score.max()) * .18)
    chosen = []
    separation = max(3, round(width * .025))
    for x in np.argsort(score)[::-1]:
        if score[x] < threshold:
            break
        if all(abs(int(x)-other) >= separation for other in chosen):
            chosen.append(int(x))
            if len(chosen) == limit:
                break
    return sorted(chosen)


def _estimate_curved_rails(edges, gray):
    """Trace two rail-like edge paths through horizontal bands."""
    h, w = gray.shape
    gx = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    gy = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3))
    rail_edges = ((edges > 0) & (gx >= gy * .55)).astype(np.float32)
    anchors = np.linspace(round(h*.96), round(h*.18), 9).astype(int)
    rows = []
    radius = max(3, round(h*.018))
    kernel = max(5, round(w*.025) | 1)
    for y in anchors:
        band = rail_edges[max(0, y-radius):min(h, y+radius+1)].sum(axis=0)
        score = cv2.GaussianBlur(band.reshape(1, -1), (kernel, 1), 0).ravel()
        peaks = _curve_peaks(score, w)
        if len(peaks) < 2:
            return None
        pairs = []
        for left_index, left in enumerate(peaks):
            for right in peaks[left_index+1:]:
                gap = right-left
                if .035*w <= gap <= .82*w:
                    strength = float(score[left]+score[right]) / max(float(score.max()), 1)
                    pairs.append((left, right, strength))
        if not pairs:
            return None
        rows.append(pairs)

    # Trace from the nearest band upward. Perspective narrows the pair while a
    # smooth bend may move its centre sideways.
    states = []
    for left, right, strength in rows[0]:
        gap, centre = right-left, (left+right)/2
        if .16*w <= gap <= .78*w and .16*w <= centre <= .84*w:
            states.append((strength, [(left, right)]))
    states = sorted(states, reverse=True)[:80]
    for pairs in rows[1:]:
        next_states = []
        for score, path in states:
            prev_left, prev_right = path[-1]
            prev_gap = prev_right-prev_left
            prev_centre = (prev_left+prev_right)/2
            for left, right, strength in pairs:
                gap, centre = right-left, (left+right)/2
                if gap > prev_gap*1.16 or gap < prev_gap*.35:
                    continue
                if abs(centre-prev_centre) > .18*w or max(abs(left-prev_left), abs(right-prev_right)) > .24*w:
                    continue
                motion = (abs(left-prev_left)+abs(right-prev_right))/(.12*w)
                width_change = abs(gap-prev_gap)/max(prev_gap, 1)
                next_states.append((score+strength-motion*.45-width_change*.7, path+[(left, right)]))
        states = sorted(next_states, reverse=True)[:80]
        if not states:
            return None

    score, path = states[0]
    bottom_gap = path[0][1]-path[0][0]
    top_gap = path[-1][1]-path[-1][0]
    if score < len(anchors)*.45 or bottom_gap < top_gap*1.08:
        return None
    y_values = anchors.astype(np.float64)
    left_values = np.asarray([item[0] for item in path], dtype=np.float64)
    right_values = np.asarray([item[1] for item in path], dtype=np.float64)
    left_fit = np.polyval(np.polyfit(y_values, left_values, 2), y_values)
    right_fit = np.polyval(np.polyfit(y_values, right_values, 2), y_values)
    gaps = right_fit-left_fit
    if np.any(gaps <= .025*w):
        return None
    margins = gaps*.14
    left_path = np.column_stack(((left_fit-margins)/w, y_values/h))[::-1]
    right_path = np.column_stack(((right_fit+margins)/w, y_values/h))
    try:
        return validate_roi(np.clip(np.vstack((left_path, right_path)), 0, 1)).tolist()
    except ValueError:
        return None


def estimate_rails(frame):
    """Find a straight rail pair, then fall back to a smooth curved corridor."""
    h, w = frame.shape[:2]
    scale = min(1.0, 960 / w)
    small = cv2.resize(frame, (round(w * scale), round(h * scale)))
    sh, sw = small.shape[:2]
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(cv2.GaussianBlur(gray, (5, 5), 0), 60, 160)
    edges[:round(sh * .10)] = 0
    lines = cv2.HoughLinesP(edges, 1, np.pi / 720, threshold=55,
                           minLineLength=sh * .22, maxLineGap=sh * .08)
    if lines is None:
        return _estimate_curved_rails(edges, gray)
    candidates = []
    top, bottom = sh * .16, sh * .97
    for x1, y1, x2, y2 in lines[:, 0]:
        if abs(int(y2) - int(y1)) < sh * .20:
            continue
        slope = (float(x2) - x1) / (float(y2) - y1)
        intercept = x1 - slope * y1
        xb, xt = slope * bottom + intercept, slope * top + intercept
        if 0.05 * sw < xb < .95 * sw and -.02 * sw < xt < 1.02 * sw and abs(slope) < 1.8:
            candidates.append((xt, xb, math.hypot(x2-x1, y2-y1)))
    if not candidates:
        return _estimate_curved_rails(edges, gray)
    # Evaluate the same pairs and thresholds in NumPy. Blocks bound temporary memory;
    # row-major argmax preserves the previous first-match behavior when scores tie.
    values = np.asarray(candidates, dtype=np.float64)
    best_pair, best_score = None, -1.0
    for start in range(0, len(values), 64):
        left = values[start:start+64]
        top_gap = values[None,:,0] - left[:,None,0]
        bottom_gap = values[None,:,1] - left[:,None,1]
        mid = (values[None,:,1] + left[:,None,1]) / 2
        valid = ((top_gap > .05*sw) & (top_gap < .50*sw) & (bottom_gap > .20*sw) &
                 (bottom_gap < .80*sw) & (bottom_gap >= top_gap*1.12) & (mid > .25*sw) & (mid < .75*sw))
        scores = (left[:,None,2]+values[None,:,2])/sh - np.abs(mid/sw-.5)*2 - np.abs(bottom_gap/sw-.38)
        scores[~valid] = -np.inf
        i,j = np.unravel_index(np.argmax(scores), scores.shape)
        if scores[i,j] > best_score:
            best_pair, best_score = (left[i], values[j]), float(scores[i,j])
    if best_pair is None or best_score < .8:
        return _estimate_curved_rails(edges, gray)
    left, right = best_pair
    margin_top, margin_bottom = (right[0]-left[0])*.14, (right[1]-left[1])*.14
    best = [[(left[0]-margin_top)/sw, top/sh], [(right[0]+margin_top)/sw, top/sh],
            [(right[1]+margin_bottom)/sw, bottom/sh], [(left[1]-margin_bottom)/sw, bottom/sh]]
    return validate_roi(np.clip(best, 0, 1)).tolist()


def in_danger(box, roi):
    """Use the object's ground-contact strip, not its upper-body overlap."""
    x1, y1, x2, y2 = box
    polygon = np.asarray(roi, dtype=np.float32)
    foot = ((x1+x2)/2, y2)
    if cv2.pointPolygonTest(polygon, foot, False) >= 0:
        return True
    strip_top = y2-(y2-y1)*.12
    samples = [(float(x), float(y)) for x in np.linspace(x1, x2, 5)
               for y in np.linspace(strip_top, y2, 3)]
    inside = sum(cv2.pointPolygonTest(polygon, point, False) >= 0 for point in samples)
    return inside / len(samples) >= .2


def iou(a, b):
    intersection = max(0, min(a[2], b[2])-max(a[0], b[0])) * max(0, min(a[3], b[3])-max(a[1], b[1]))
    return intersection / max((a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection, 1e-9)


def sample_frame_indices(frame_count, fps, sample_seconds):
    """Sample actual frame indices; rounded duration may point beyond the last frame."""
    if not all(math.isfinite(v) for v in (frame_count, fps, sample_seconds)) or frame_count < 1 or fps <= 0 or sample_seconds < 0:
        raise ValueError('Invalid sampling metadata')
    return range(0, int(frame_count), max(1, round(fps * sample_seconds)))


class EventTracker:
    """Greedy category+IoU association with one-to-one matching per sampled frame."""
    def __init__(self, sample_seconds=1):
        self.tracks = {}
        self.next_id = 1
        self.max_gap = max(3.0, sample_seconds * 2.5)

    def update(self, detections, timestamp):
        used = set()
        for d in sorted(detections, key=lambda x: x['confidence'], reverse=True):
            candidates = [(iou(d['box'], t['lastBox']), key) for key, t in self.tracks.items()
                          if key not in used and t['category'] == d['category'] and timestamp-t['lastSeen'] <= self.max_gap]
            score, key = max(candidates, default=(0, ''))
            if score < .20:
                key = f"object-{self.next_id}"
                self.next_id += 1
                self.tracks[key] = {**d, 'trackKey': key, 'lastBox': d['box'], 'lastSeen': timestamp}
            else:
                t = self.tracks[key]
                # Preserve evidence of an intrusion even when a later frame is outside.
                if (d['inDanger'] and not t['inDanger']) or (d['inDanger'] == t['inDanger'] and d['confidence'] > t['confidence']):
                    self.tracks[key] = {**d, 'trackKey': key}
                self.tracks[key].update(lastBox=d['box'], lastSeen=timestamp)
            d['trackKey'] = key
            used.add(key)
        if len(self.tracks) > 200:
            raise ValueError("Too many objects; split this video into shorter clips")

    def results(self):
        return [{k:v for k,v in t.items() if k not in ('lastBox','lastSeen')} for t in self.tracks.values()]

    def evidence_snapshots(self):
        return {track['snapshot'] for track in self.tracks.values()}
