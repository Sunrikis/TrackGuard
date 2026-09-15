"""Train, fine-tune, resume or evaluate a YOLO-World detector."""
import argparse
from datetime import datetime
import math
from pathlib import Path

import yaml

AI_ROOT = Path(__file__).resolve().parents[1]
IMAGE_TYPES = {'.jpg', '.jpeg', '.png', '.bmp', '.webp'}


def read_dataset(path, splits):
    path = Path(path).resolve()
    data = yaml.safe_load(path.read_text(encoding='utf-8-sig'))
    if not isinstance(data, dict):
        raise ValueError('Dataset configuration must be a YAML mapping')
    names = data.get('names')
    if isinstance(names, list):
        names = dict(enumerate(names))
    if not isinstance(names, dict) or not names or set(names) != set(range(len(names))):
        raise ValueError('names must use consecutive class IDs starting at 0')
    if any(not isinstance(n, str) or not n.strip() for n in names.values()) or len(set(names.values())) != len(names):
        raise ValueError('Class names must be nonempty and unique')
    data['names'] = names
    root = (path.parent / data.get('path', '.')).resolve()
    data['path'] = str(root)
    seen = set()
    for split in splits:
        value = data.get(split)
        if not isinstance(value, str):
            raise ValueError(f'Configure {split}: images/{split} in {path}')
        folder = (root / value).resolve()
        # This scaffold deliberately supports one images/<split> directory per split.
        if folder.parent.name != 'images':
            raise ValueError(f'{split} must point to an images/<split> directory')
        images = sorted(p for p in folder.rglob('*') if p.suffix.lower() in IMAGE_TYPES and p.is_file())
        if not images:
            raise ValueError(f'No images in {folder}. Add your dataset before training.')
        boxes = 0
        for image in images:
            if image.resolve() in seen:
                raise ValueError(f'Image occurs in multiple splits: {image}')
            seen.add(image.resolve())
            label = folder.parent.parent / 'labels' / folder.name / image.relative_to(folder).with_suffix('.txt')
            if not label.is_file():
                raise ValueError(f'Missing label: {label}; use an empty file for a background image')
            for line_number, line in enumerate(label.read_text(encoding='utf-8-sig').splitlines(), 1):
                if not line.strip():
                    continue
                parts = line.split()
                if len(parts) != 5:
                    raise ValueError(f'{label}:{line_number}: expected class x y width height')
                cls, x, y, width, height = map(float, parts)
                if not all(math.isfinite(v) for v in (cls, x, y, width, height)):
                    raise ValueError(f'{label}:{line_number}: non-finite value')
                if cls != int(cls) or int(cls) not in names or not (0 <= x <= 1 and 0 <= y <= 1 and 0 < width <= 1 and 0 < height <= 1):
                    raise ValueError(f'{label}:{line_number}: invalid class or normalized coordinates')
                if x-width/2 < -1e-6 or x+width/2 > 1+1e-6 or y-height/2 < -1e-6 or y+height/2 > 1+1e-6:
                    raise ValueError(f'{label}:{line_number}: bounding box exceeds image')
                boxes += 1
        if not boxes:
            raise ValueError(f'{split} has no annotated objects')
        data[split] = str(folder)
        print(f'{split}: {len(images)} images, {boxes} boxes')
    return data


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=AI_ROOT / 'datasets/railway/data.yaml')
    parser.add_argument('--model', default=str(AI_ROOT / 'models/trackguard-world.pt'))
    parser.add_argument('--mode', choices=['train', 'check', 'val'], default='train')
    parser.add_argument('--scratch', action='store_true', help='Randomly initialize YOLO-World detector from built-in architecture')
    parser.add_argument('--resume', type=Path, help='Resume from an interrupted run weights/last.pt')
    parser.add_argument('--split', choices=['val', 'test'], default='val')
    parser.add_argument('--epochs', type=int, default=100)
    parser.add_argument('--batch', type=int, default=4)
    parser.add_argument('--imgsz', type=int, default=640)
    parser.add_argument('--device', default=None, help='0 for first GPU; cpu for CPU; omitted for automatic selection')
    parser.add_argument('--workers', type=int, default=0, help='0 is a conservative Windows default')
    parser.add_argument('--freeze', type=int, default=0, help='Freeze the first N backbone layers')
    parser.add_argument('--patience', type=int, default=30)
    args = parser.parse_args(argv)
    if args.resume and (args.scratch or args.mode != 'train'):
        parser.error('--resume cannot be combined with --scratch or non-training modes')
    if args.scratch and args.mode != 'train':
        parser.error('--scratch is only valid for training')
    if min(args.epochs, args.batch, args.imgsz) <= 0 or min(args.workers, args.freeze, args.patience) < 0:
        parser.error('Invalid training parameter range')
    if args.resume:
        if not args.resume.is_file():
            parser.error(f'Checkpoint not found: {args.resume}')
        from ultralytics import YOLOWorld
        options = {
            'resume': True,
            'workers': args.workers,
        }
        if args.device is not None:
            options['device'] = args.device
        YOLOWorld(str(args.resume.resolve())).train(**options)
        return
    try:
        splits = ['train', 'val'] if args.mode != 'val' else [args.split]
        if args.mode == 'check' and args.split == 'test':
            splits.append('test')
        data = read_dataset(args.data, splits)
    except (ValueError, OSError, yaml.YAMLError) as error:
        parser.error(str(error))
    if args.mode == 'check':
        print('Dataset structure and labels passed. No training started.')
        return
    model_path = Path(args.model).resolve()
    if not args.scratch and not model_path.is_file():
        parser.error(f'Model file not found: {model_path}')
    from ultralytics import YOLOWorld
    model = YOLOWorld('yolov8l-worldv2.yaml' if args.scratch else str(model_path))
    run = AI_ROOT / 'train/runs' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    run.mkdir(parents=True, exist_ok=False)
    resolved_data = run / 'data.resolved.yaml'
    resolved_data.write_text(yaml.safe_dump(data, allow_unicode=True), encoding='utf-8')
    options = dict(data=str(resolved_data), imgsz=args.imgsz, batch=args.batch,
                   workers=args.workers, project=str(run), name=args.mode, exist_ok=False)
    if args.device is not None:
        options['device'] = args.device
    if args.mode == 'val':
        model.val(split=args.split, **options)
    else:
        model.train(epochs=args.epochs, patience=args.patience, freeze=args.freeze,
                    pretrained=not args.scratch, seed=0, **options)
    print(f'Results saved under: {run}')


if __name__ == '__main__':
    main()
