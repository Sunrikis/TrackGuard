"""Evaluate category+IoU matches and efficiency on annotated image samples."""
import argparse
import json
from pathlib import Path
import statistics
import time
from geometry import iou
from worker import run, write_json

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--annotations', type=Path, default=ROOT / 'data/evaluation/annotations.json')
    parser.add_argument('--model', type=Path, default=ROOT / 'ai-core/models/trackguard-world.pt')
    args = parser.parse_args()
    dataset = json.loads(args.annotations.read_text(encoding='utf-8'))
    output = ROOT / 'storage/evaluation' / time.strftime('%Y%m%d-%H%M%S')
    output.mkdir(parents=True, exist_ok=True)
    tp = fp = fn = 0
    elapsed, inference, rows = [], [], []
    for index, sample in enumerate(dataset['samples']):
        folder = output / str(index)
        folder.mkdir()
        before = time.perf_counter()
        result = run({'source': str(ROOT / sample['file']), 'model': str(args.model),
                      'confidence': .35, 'sampleSeconds': 1, 'roi': sample.get('roi', []), 'maxVideoSeconds': 120}, folder)
        elapsed.append((time.perf_counter() - before) * 1000)
        inference.append(result['inferenceMs'])
        remaining = set(range(len(sample['objects'])))
        sample_tp = sample_fp = 0
        for prediction in sorted(result['detections'], key=lambda value: value['confidence'], reverse=True):
            candidates = [(iou(prediction['box'], sample['objects'][i]['box']), i) for i in remaining
                          if prediction['category'] == sample['objects'][i]['category']]
            score, match = max(candidates, default=(0, -1))
            if score >= .5:
                sample_tp += 1
                remaining.remove(match)
            else:
                sample_fp += 1
        tp += sample_tp
        fp += sample_fp
        fn += len(remaining)
        rows.append({'file': sample['file'], 'tp': sample_tp, 'fp': sample_fp, 'fn': len(remaining)})
    if not rows:
        raise ValueError('Annotation file contains no samples')
    summary = {
        'samples': len(rows), 'iouThreshold': .5, 'confidenceThreshold': .35,
        'precision': tp / (tp + fp) if tp + fp else None,
        'recall': tp / (tp + fn) if tp + fn else None,
        'meanTotalMs': round(statistics.mean(elapsed), 2),
        'meanInferenceMs': round(statistics.mean(inference), 2),
        'tp': tp, 'fp': fp, 'fn': fn, 'details': rows,
        'limitation': dataset['description'],
    }
    write_json(output / 'metrics.json', summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print('Saved:', output / 'metrics.json')


if __name__ == '__main__':
    main()
