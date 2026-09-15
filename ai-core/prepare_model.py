"""One-time preparation: embed prompts covering five business categories in YOLO-World."""
import argparse
import os
from pathlib import Path
os.environ['YOLO_AUTOINSTALL']='false'
root=Path(__file__).resolve().parents[1]
os.environ.setdefault('YOLO_CONFIG_DIR',str(root/'storage'/'model-config'))
os.environ.setdefault('TORCH_HOME',str(root/'storage'/'torch-cache'))
os.environ.setdefault('XDG_CACHE_HOME',str(root/'storage'/'cache'))
Path(os.environ['YOLO_CONFIG_DIR']).mkdir(parents=True,exist_ok=True)
parser=argparse.ArgumentParser()
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parent/'models'/'yolov8l-worldv2.pt')
parser.add_argument('--output',type=Path,default=Path(__file__).resolve().parent/'models'/'trackguard-world.pt')
args=parser.parse_args()
if args.output.exists():
    raise SystemExit('Output already exists; choose a new --output path to preserve the current model.')
from ultralytics import YOLO
import clip
from functools import partial
clip.load=partial(clip.load,download_root=str(root/'storage'/'cache'/'clip'))
model=YOLO(str(args.source))
labels=['person','car','truck','bus','motorcycle','bicycle','dog','cat','horse','cow','sheep','large rock','fallen tree','large cardboard box','suitcase']
model.set_classes(labels)
model.model.clip_model=None
model.save(str(args.output))
print(f'Saved offline model: {args.output}')
print('Classes:',model.names)
