"""Create a balanced, validated YOLO dataset from the local SynRailObs download.

Source images/labels are read only. Output must be empty (except scaffold files).
Each accepted image has exactly one complete object annotation, so image and box
counts are both balanced without silently discarding other objects.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import random
import shutil

from PIL import Image
import yaml

AI_ROOT = Path(__file__).resolve().parents[1]
SOURCE_NAMES = ['animals', 'motos', 'persons', 'rocks', 'vehicles']
NAMES = ['animal', 'motorcycle', 'person', 'large rock', 'vehicle']
EXTENSIONS = {'.jpg', '.jpeg', '.png', '.bmp', '.webp'}


def scan(source, split):
    directory = source / split
    images = sorted(p for p in (directory / 'images').iterdir() if p.is_file() and p.suffix.lower() in EXTENSIONS)
    labels = list((directory / 'labels').glob('*.txt'))
    buckets = {i: [] for i in range(len(NAMES))}
    reasons = Counter()
    image_stems = Counter(p.stem for p in images)
    for image in images:
        if image_stems[image.stem] != 1:
            reasons['ambiguous_image_stem'] += 1
            continue
        label = directory / 'labels' / (image.stem + '.txt')
        if not label.is_file():
            reasons['missing_label'] += 1
            continue
        try:
            rows = [line.split() for line in label.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
            if len(rows) != 1 or len(rows[0]) != 5:
                reasons['not_single_object'] += 1
                continue
            cls, x, y, width, height = map(float, rows[0])
            if not all(math.isfinite(v) for v in (cls, x, y, width, height)) or cls != int(cls) or int(cls) not in buckets:
                raise ValueError('invalid class')
            if not (0 <= x <= 1 and 0 <= y <= 1 and 0 < width <= 1 and 0 < height <= 1):
                raise ValueError('invalid coordinates')
            if x-width/2 < -1e-6 or x+width/2 > 1+1e-6 or y-height/2 < -1e-6 or y+height/2 > 1+1e-6:
                raise ValueError('box outside image')
            buckets[int(cls)].append((image, label, ' '.join(rows[0]) + '\n'))
        except (ValueError, OSError, UnicodeError):
            reasons['invalid_label'] += 1
    report = dict(images=len(images), labels=len(labels), imageBytes=sum(p.stat().st_size for p in images),
                  orphanLabels=sum(p.stem not in image_stems for p in labels), excluded=dict(reasons),
                  eligiblePerClass={NAMES[i]: len(rows) for i, rows in buckets.items()})
    return buckets, report


def prepare(source, output, train_count=1000, val_count=200, seed=20260917):
    source, output = Path(source).resolve(), Path(output).resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError('Source and output must be separate, non-nested directories')
    if min(train_count, val_count) < 1:
        raise ValueError('Per-class counts must be positive')
    config = yaml.safe_load((source / 'data.yaml').read_text(encoding='utf-8-sig'))
    names = config['names']
    if isinstance(names, dict): names = [names[i] for i in range(len(names))]
    if names != SOURCE_NAMES:
        raise ValueError(f'Unexpected source classes: {names}; mapping must be reviewed')
    if output.exists():
        allowed = {'data.yaml', '.gitkeep'}
        existing = [p for p in output.rglob('*') if p.is_file() and p.name not in allowed]
        if existing:
            raise ValueError(f'Output already contains data: {existing[0]}; choose a new output directory')
    report = {'seed': seed, 'mapping': dict(enumerate(NAMES)), 'source': {}, 'output': {}, 'selected': []}
    candidates = {}
    for split in ('train', 'val'):
        candidates[split], report['source'][split] = scan(source, split)
        print(f'{split} eligible: {report["source"][split]["eligiblePerClass"]}', flush=True)
    selected = []
    known = set()
    rng = random.Random(seed)
    for split, count in (('train', train_count), ('val', val_count)):
        skipped = Counter()
        class_counts = {}
        for cls, rows in candidates[split].items():
            rng.shuffle(rows)
            kept = 0
            for image, label, text in rows:
                digest = hashlib.sha256(image.read_bytes()).hexdigest()
                if digest in known:
                    skipped['duplicate_image'] += 1
                    continue
                try:
                    with Image.open(image) as opened:
                        opened.load()
                        if min(opened.size) < 16: raise ValueError('Image too small')
                except (OSError, ValueError):
                    skipped['invalid_image'] += 1
                    continue
                known.add(digest)
                selected.append((split, cls, image, label, text, digest))
                kept += 1
                if kept == count: break
            if kept != count:
                raise ValueError(f'{split}/{NAMES[cls]} has only {kept} valid unique images; requested {count}. Output not written.')
            class_counts[NAMES[cls]] = kept
            print(f'Selected {split}/{NAMES[cls]}: {kept}', flush=True)
        report['output'][split] = {'imagesPerClass': class_counts, 'boxesPerClass': dict(class_counts), 'selectionExclusions': dict(skipped)}
    output.mkdir(parents=True, exist_ok=True)
    marker = output / '.preparing'
    marker.write_text('Preparation in progress; do not train until preparation.json is present.', encoding='utf-8')
    total_bytes = 0
    for split, cls, image, label, text, digest in selected:
        image_dir, label_dir = output / 'images' / split, output / 'labels' / split
        image_dir.mkdir(parents=True, exist_ok=True)
        label_dir.mkdir(parents=True, exist_ok=True)
        filename = image.name
        destination = image_dir / filename
        if destination.exists(): raise ValueError(f'Output collision: {destination}')
        shutil.copy2(image, destination)
        (label_dir / (image.stem + '.txt')).write_text(text, encoding='utf-8')
        total_bytes += image.stat().st_size
        report['selected'].append({'split': split, 'classId': cls, 'sourceImage': image.relative_to(source).as_posix(),
                                   'sourceLabel': label.relative_to(source).as_posix(), 'sha256': digest})
    (output / 'data.yaml').write_text(yaml.safe_dump({'path': '.', 'train': 'images/train', 'val': 'images/val',
        'nc': len(NAMES), 'names': dict(enumerate(NAMES))}, sort_keys=False, allow_unicode=True), encoding='utf-8')
    report['imageBytes'] = total_bytes
    (output / 'preparation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    marker.unlink()
    print(f'Prepared {len(selected)} image/label pairs, {total_bytes / 1024**3:.2f} GiB: {output}', flush=True)
    return report


def add_test(source, output, count=100, seed=20260918):
    """Add an independent balanced holdout without changing train/val files."""
    source, output = Path(source).resolve(), Path(output).resolve()
    if count < 1 or source == output or source in output.parents or output in source.parents:
        raise ValueError('Invalid count or overlapping directories')
    manifest = output / 'preparation.json'
    report = json.loads(manifest.read_text(encoding='utf-8'))
    config = yaml.safe_load((output / 'data.yaml').read_text(encoding='utf-8'))
    source_config = yaml.safe_load((source / 'data.yaml').read_text(encoding='utf-8-sig'))
    source_names = source_config['names']
    if isinstance(source_names, dict): source_names = [source_names[i] for i in range(len(source_names))]
    if source_names != SOURCE_NAMES or config['names'] != dict(enumerate(NAMES)):
        raise ValueError('Unexpected dataset classes')
    if 'test' in report['output'] or (output / '.preparing').exists():
        raise ValueError('Test split already prepared or incomplete preparation exists')
    for directory in (output / 'images/test', output / 'labels/test'):
        if directory.exists() and any(p.is_file() and p.name != '.gitkeep' for p in directory.rglob('*')):
            raise ValueError(f'Test directory already contains data: {directory}')
    known = set()
    for split in ('train', 'val'):
        for image in (output / 'images' / split).rglob('*'):
            if image.is_file() and image.suffix.lower() in EXTENSIONS:
                known.add(hashlib.sha256(image.read_bytes()).hexdigest())
    # Use the original validation pool; never move or relabel current train/val samples.
    buckets, audit = scan(source, 'val')
    rng = random.Random(seed)
    selected, skipped = [], Counter()
    used_sources = {row['sourceImage'] for row in report['selected']}
    for cls, rows in buckets.items():
        rng.shuffle(rows)
        kept = 0
        for image, label, text in rows:
            if image.relative_to(source).as_posix() in used_sources:
                continue
            digest = hashlib.sha256(image.read_bytes()).hexdigest()
            if digest in known:
                skipped['duplicate_image'] += 1
                continue
            try:
                with Image.open(image) as opened:
                    opened.load()
                    if min(opened.size) < 16: raise ValueError('Image too small')
            except (OSError, ValueError):
                skipped['invalid_image'] += 1
                continue
            selected.append((cls, image, label, text, digest))
            known.add(digest)
            kept += 1
            if kept == count: break
        if kept != count:
            raise ValueError(f'Not enough independent test images for {NAMES[cls]}: {kept}/{count}')
        print(f'Selected test/{NAMES[cls]}: {kept}', flush=True)
    marker = output / '.preparing'
    marker.write_text('Test preparation in progress', encoding='utf-8')
    for directory in (output / 'images/test', output / 'labels/test'):
        directory.mkdir(parents=True, exist_ok=True)
    for cls, image, label, text, digest in selected:
        shutil.copy2(image, output / 'images/test' / image.name)
        (output / 'labels/test' / (image.stem + '.txt')).write_text(text, encoding='utf-8')
        report['imageBytes'] += image.stat().st_size
        report['selected'].append({'split': 'test', 'classId': cls,
            'sourceImage': image.relative_to(source).as_posix(),
            'sourceLabel': label.relative_to(source).as_posix(), 'sha256': digest})
    counts = dict.fromkeys(NAMES, count)
    report['output']['test'] = {'imagesPerClass': counts, 'boxesPerClass': counts,
        'selectionExclusions': dict(skipped), 'seed': seed, 'sourceSplit': 'val', 'sourceAudit': audit}
    config['test'] = 'images/test'
    (output / 'data.yaml').write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding='utf-8')
    manifest.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    marker.unlink()
    print(f'Added {len(selected)} independent test pairs: {output}', flush=True)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=AI_ROOT / 'datasets/SynRailObs')
    parser.add_argument('--output', type=Path, default=AI_ROOT / 'datasets/railway')
    parser.add_argument('--train-per-class', type=int, default=1000)
    parser.add_argument('--val-per-class', type=int, default=200)
    parser.add_argument('--seed', type=int, default=20260917)
    parser.add_argument('--add-test', action='store_true', help='Add a holdout to an already prepared dataset')
    parser.add_argument('--test-per-class', type=int, default=100)
    args = parser.parse_args()
    if args.add_test:
        add_test(args.source, args.output, args.test_per_class, args.seed)
    else:
        prepare(args.source, args.output, args.train_per_class, args.val_per_class, args.seed)
