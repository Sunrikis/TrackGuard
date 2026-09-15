"""Measure the real video pipeline using the same cached runtime as Spring Boot.

Reports actual processed frames per second, never source duration divided by task time.
Does not require SQL Server and does not modify the input video or model.
"""
from __future__ import annotations
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import statistics
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True, help='Local short video, at most 120 seconds')
    parser.add_argument('--roi', default='[]', help='Four normalized points as JSON; [] estimates rails every frame')
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--batch-size', type=int, default=8)
    parser.add_argument('--image-size', type=int, default=640)
    parser.add_argument('--precision', choices=['auto','fp16','fp32'], default='auto')
    parser.add_argument('--sample-seconds', type=float, default=1, help='0 checks every frame; .5..5 samples')
    parser.add_argument('--output', type=Path, help='New output directory; must not already exist')
    args = parser.parse_args()
    if not args.model.is_file() or not args.source.is_file():
        parser.error('Model and source must be existing local files')
    if args.source.suffix.lower() not in {'.mp4','.mov','.m4v'} or not 1 <= args.runs <= 20:
        parser.error('Use a video source and 1..20 runs')
    os.environ['RFOID_BATCH_SIZE'] = str(args.batch_size)
    os.environ['RFOID_IMAGE_SIZE'] = str(args.image_size)
    os.environ['RFOID_HALF'] = 'false' if args.precision == 'fp32' else 'true'
    output = args.output or Path(__file__).resolve().parents[1]/'storage/benchmarks'/datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    output.mkdir(parents=True, exist_ok=False)
    started = time.perf_counter()
    print('Loading dependencies and model...', flush=True)
    from worker import InferenceRuntime, run, write_json
    runtime = InferenceRuntime()
    runtime.acquire(args.model)
    startup_ms = (time.perf_counter()-started)*1000
    request = dict(model=str(args.model.resolve()), source=str(args.source.resolve()),
                   roi=json.loads(args.roi), confidence=.35, sampleSeconds=args.sample_seconds,
                   maxVideoSeconds=120, targetCategories=['person','vehicle','motorcycle','animal','obstacle'])
    rows = []
    for index in range(1, args.runs+1):
        folder = output/f'run-{index:02d}'
        folder.mkdir()
        result = run(request, folder, runtime)
        write_json(folder/'result.json', result)
        for name in {result['preview']} | {d['snapshot'] for d in result['detections']}:
            if not (folder/name).is_file():
                raise RuntimeError('A result references a missing evidence image')
        row = dict(result['performance'], frames=result['sampledFrames'], timings=result['timings'],
                   modelReused=result['modelReused'], workerPid=result['workerPid'],
                   objectCount=result['objectCount'], warningCount=result['warningCount'])
        print(json.dumps({'run': index, **row}, ensure_ascii=False), flush=True)
        rows.append(row)
    import torch
    import ultralytics
    frames = sum(row['frames'] for row in rows)
    inference_ms = sum(row['timings']['inferenceMs'] for row in rows)
    worker_ms = sum(row['timings']['workerMs'] for row in rows)
    summary = dict(model=args.model.name, source=args.source.name, runs=rows,
                   startupMs=round(startup_ms,2), torch=torch.__version__, ultralytics=ultralytics.__version__,
                   device=torch.cuda.get_device_name(0) if runtime.device==0 else 'CPU',
                   measuredFrames=frames, inferenceFps=round(frames*1000/inference_ms,2),
                   pipelineFps=round(frames*1000/worker_ms,2),
                   pipelineFpsMin=min(row['pipelineFps'] for row in rows),
                   pipelineFpsMax=max(row['pipelineFps'] for row in rows),
                   pipelineFpsMedian=statistics.median(row['pipelineFps'] for row in rows),
                   definition='Inference includes preprocessing, prediction, NMS and CPU box transfer. Pipeline also includes decoding, ROI, association and evidence writes; excludes startup, upload, queue, Java and SQL Server.')
    write_json(output/'summary.json', summary)
    print(f"Measured {frames} actual frames: inference {summary['inferenceFps']:.2f} FPS; pipeline {summary['pipelineFps']:.2f} FPS", flush=True)
    print(f'Results: {output.resolve()}', flush=True)


if __name__ == '__main__':
    main()
