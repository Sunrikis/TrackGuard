"""Persistent local inference worker with private JSON-lines IPC; also supports one-shot CLI."""
from __future__ import annotations
import argparse
from collections import OrderedDict
from contextlib import redirect_stderr, redirect_stdout
from concurrent.futures import ThreadPoolExecutor, wait
import gc
import hashlib
import json
import os
from pathlib import Path
import sys
import threading
import queue
import time
import traceback

MODULE_STARTED = time.perf_counter()
# Keep stdout exclusively for the private protocol, including during third-party imports.
PROTOCOL_OUTPUT = sys.stdout
if '--serve' in sys.argv:
    sys.stdout = sys.stderr
os.environ['YOLO_AUTOINSTALL'] = 'false'
os.environ.setdefault('YOLO_CONFIG_DIR', str(Path(__file__).resolve().parents[1] / 'storage' / 'model-config'))
Path(os.environ['YOLO_CONFIG_DIR']).mkdir(parents=True, exist_ok=True)

import cv2
import numpy as np
from geometry import estimate_rails, validate_roi, in_danger, EventTracker, sample_frame_indices
from vision_advice import enrich_with_vision
MODULE_IMPORT_MS = (time.perf_counter() - MODULE_STARTED) * 1000

GROUPS = {
    'animal': ('animal', '动物'),
    'vehicle': ('vehicle', '车辆'),
    'person': ('person', '人员'),
    'motorcycle': ('motorcycle', '摩托车'),
    **{k: ('vehicle', '车辆') for k in ['car','truck','bus','bicycle']},
    **{k: ('animal', '动物') for k in ['dog','cat','horse','cow','sheep','bird','elephant','bear','zebra','giraffe']},
    'large rock': ('obstacle', '岩石'),
    **{k: ('obstacle', '大型障碍物') for k in ['fallen tree','large cardboard box','suitcase']},
}
ADVICE = {
    'person': '核实人员位置，通知现场值守人员劝离危险区，并按线路管理流程处置。',
    'vehicle': '核实车辆侵限情况，联系值守人员采取防护措施，协调移离轨道。',
    'motorcycle': '核实摩托车侵限情况，通知值守人员做好防护并协调移离轨道。',
    'animal': '通知巡检人员确认动物位置，采取安全驱离措施并持续观察。',
    'obstacle': '核实障碍物大小与侵限范围，安排人员防护并清理，确认线路恢复。',
}


class DetectionError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def check_cancelled(cancelled):
    if cancelled is not None and cancelled.is_set():
        raise DetectionError('CANCELLED', 'Task cancelled')


class InferenceRuntime:
    """The serial worker owns a bounded model cache and one CUDA context."""
    def __init__(self, capacity=2):
        before = time.perf_counter()
        import torch
        from ultralytics import YOLO
        self.torch, self.factory = torch, YOLO
        self.device = 0 if torch.cuda.is_available() else 'cpu'
        # Large default CPU thread pools add overhead to the small transforms around CUDA calls.
        threads = int(os.environ.get('RFOID_CPU_THREADS', '4'))
        self.image_size = int(os.environ.get('RFOID_IMAGE_SIZE', '640'))
        requested_batch = int(os.environ.get('RFOID_BATCH_SIZE', '8'))
        if not 1 <= threads <= 32 or not 1 <= requested_batch <= 8:
            raise ValueError('RFOID_CPU_THREADS must be 1..32 and RFOID_BATCH_SIZE must be 1..8')
        if not 320 <= self.image_size <= 1280 or self.image_size % 32:
            raise ValueError('RFOID_IMAGE_SIZE must be a multiple of 32 between 320 and 1280')
        torch.set_num_threads(threads)
        cv2.setNumThreads(min(threads, 2))
        self.half = self.device == 0 and os.environ.get('RFOID_HALF', 'true').lower() == 'true'
        self.batch_size = requested_batch if self.device == 0 else 1
        self.region_pool = ThreadPoolExecutor(max_workers=min(threads, 4), thread_name_prefix='trackguard-region')
        self.dependency_ms = (time.perf_counter() - before) * 1000
        self.capacity = capacity
        self.models = OrderedDict()

    def acquire(self, model_path, cancelled=None):
        check_cancelled(cancelled)
        path = Path(model_path).resolve()
        if not path.is_file() or path.stat().st_size < 1024:
            raise DetectionError('MODEL_NOT_READY', 'Model file missing or empty')
        stat = path.stat()
        signature = (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
        cached = self.models.get(path)
        timings = {'modelLoadMs': 0.0, 'modelHashMs': 0.0}
        if cached is not None and cached['signature'] == signature:
            self.models.move_to_end(path)
            return cached, True, timings
        if cached is not None:
            del self.models[path]
            del cached
            gc.collect()
            if self.device == 0:
                self.torch.cuda.empty_cache()
        if len(self.models) >= self.capacity:
            self.models.popitem(last=False)
            gc.collect()
            if self.device == 0:
                self.torch.cuda.empty_cache()
        before = time.perf_counter()
        model = self.factory(str(path))
        names = model.names
        if not any(name in GROUPS for name in names.values()):
            raise DetectionError('MODEL_NOT_READY', 'Model has no supported categories')
        timings['modelLoadMs'] = (time.perf_counter() - before) * 1000
        check_cancelled(cancelled)
        before = time.perf_counter()
        fingerprint = hashlib.sha256()
        with path.open('rb') as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b''):
                check_cancelled(cancelled)
                fingerprint.update(chunk)
        timings['modelHashMs'] = (time.perf_counter() - before) * 1000
        stat_after = path.stat()
        if signature != (stat_after.st_size, stat_after.st_mtime_ns, stat_after.st_ctime_ns):
            raise DetectionError('MODEL_NOT_READY', 'Model changed while being loaded; retry after the file copy finishes')
        cached = {'model': model, 'names': names, 'fingerprint': fingerprint.hexdigest(), 'signature': signature}
        self.models[path] = cached
        return cached, False, timings

    def predict_options(self):
        return dict(iou=.45, device=self.device, imgsz=self.image_size, half=self.half,
                    rect=True, max_det=50, verbose=False)

    def synchronize(self):
        if self.device == 0:
            self.torch.cuda.synchronize()

    def close(self):
        self.region_pool.shutdown(wait=True, cancel_futures=True)


    @staticmethod
    def estimate_region(frame):
        started = time.perf_counter()
        return estimate_rails(frame), (time.perf_counter()-started)*1000

    @staticmethod
    def release_inputs(cached):
        # Ultralytics retains its most recent input batch; do not keep full-resolution video frames idle.
        predictor = getattr(cached['model'], 'predictor', None)
        if predictor is not None:
            predictor.results = predictor.batch = predictor.dataset = None


def write_json(path, value):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False), encoding='utf-8')
    # Windows may briefly deny replacement while Spring Boot is polling progress.json.
    # Keep the atomic write, but wait for the reader to release its file handle instead
    # of treating a transient sharing violation as a fatal inference error.
    for attempt in range(10):
        try:
            os.replace(temp, path)
            return
        except PermissionError:
            if attempt == 9:
                raise
            time.sleep(.01 * (attempt + 1))


def save_image(path, frame):
    ok, data = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 88])
    if not ok:
        raise DetectionError('INVALID_MEDIA', 'Cannot encode screenshot')
    data.tofile(str(path))


def annotate(frame, roi, detections):
    h,w = frame.shape[:2]
    out=frame.copy()
    pts=(np.asarray(roi)*[w,h]).astype(np.int32)
    overlay=out.copy()
    cv2.fillPoly(overlay,[pts],(255,140,40))
    out=cv2.addWeighted(overlay,.16,out,.84,0)
    cv2.polylines(out,[pts],True,(255,175,60),max(2,round(w/500)))
    for d in detections:
        x1,y1,x2,y2=(np.asarray(d['box'])*[w,h,w,h]).astype(int)
        color=(85,75,245) if d['inDanger'] else (140,190,100)
        cv2.rectangle(out,(x1,y1),(x2,y2),color,max(2,round(w/450)))
        label=f"{d['category']} {d['confidence']:.0%}"+(' | ALERT' if d['inDanger'] else ' | outside')
        font_scale=max(.45,w/1700)
        (tw,th),_=cv2.getTextSize(label,cv2.FONT_HERSHEY_SIMPLEX,font_scale,1)
        top=max(0,y1-th-12)
        cv2.rectangle(out,(x1,top),(min(w,x1+tw+10),top+th+10),color,-1)
        cv2.putText(out,label,(x1+5,top+th+3),cv2.FONT_HERSHEY_SIMPLEX,font_scale,(255,255,255),1,cv2.LINE_AA)
    return out


def run(request, output, runtime=None, cancelled=None):
    started = time.perf_counter()
    persistent = runtime is not None
    module_ms = 0.0 if persistent else MODULE_IMPORT_MS
    timings = {key: 0.0 for key in ('decodeMs', 'regionMs', 'regionComputeMs', 'associationMs', 'annotationMs', 'snapshotMs', 'progressMs', 'adviceMs')}
    timings['startupMs'] = module_ms
    check_cancelled(cancelled)
    source, model_path = Path(request['source']), Path(request['model'])
    if not model_path.is_file() or model_path.stat().st_size < 1024:
        raise DetectionError('MODEL_NOT_READY', 'Model file missing or empty')
    manual = request.get('roi', [])
    if manual:
        manual=validate_roi(manual).tolist()
    confidence=float(request['confidence'])
    sample=float(request['sampleSeconds'])
    if not .1 <= confidence <= .95 or not (sample == 0 or .5 <= sample <= 5):
        raise ValueError('Invalid parameters')
    selected = request.get('targetCategories', ['person', 'vehicle', 'motorcycle', 'animal', 'obstacle'])
    if not isinstance(selected, list) or not selected or any(not isinstance(c, str) or c not in ADVICE for c in selected) or len(set(selected)) != len(selected):
        raise ValueError('Select at least one valid target category')
    is_image=source.suffix.lower() in {'.png','.jpg','.jpeg'}
    capture=None
    cached=None
    region_futures=[]
    try:
        before = time.perf_counter()
        if is_image:
            first=cv2.imdecode(np.fromfile(str(source),dtype=np.uint8),cv2.IMREAD_COLOR)
            duration=0.0
            fps, frames, frame_indices = 0.0, 1, range(1)
        else:
            capture=cv2.VideoCapture(str(source))
            fps,frames=capture.get(cv2.CAP_PROP_FPS),capture.get(cv2.CAP_PROP_FRAME_COUNT)
            if not capture.isOpened() or not np.isfinite([fps,frames]).all() or fps<=0 or frames<=0:
                raise DetectionError('INVALID_MEDIA','Invalid video metadata')
            duration=frames/fps
            if duration>min(float(request['maxVideoSeconds']),120)+.05:
                raise DetectionError('VIDEO_TOO_LONG','Video exceeds duration limit')
            ok,first=capture.read()
            if not ok: first=None
            frame_indices=sample_frame_indices(frames,fps,sample)
            if len(frame_indices) > 14400:
                raise DetectionError('TOO_MANY_FRAMES', 'At most 14400 frames per task; shorten the video or use sampling')
        if first is None or first.size==0 or first.shape[0]*first.shape[1]>24_000_000:
            raise DetectionError('INVALID_MEDIA','Cannot decode media or frame exceeds 24 megapixels')
        timings['decodeMs'] += (time.perf_counter() - before) * 1000
        before = time.perf_counter()
        roi=manual or estimate_rails(first)
        timings['regionMs'] += (time.perf_counter() - before) * 1000
        timings['regionComputeMs'] = timings['regionMs']
        if roi is None:
            raise DetectionError('RAIL_NOT_FOUND','Please calibrate a fixed-camera track region')
        write_json(output/'progress.json',{'progress':5})
        if runtime is None:
            runtime = InferenceRuntime()
            timings['startupMs'] += runtime.dependency_ms
        cached, reused, model_timings = runtime.acquire(model_path, cancelled)
        timings.update(model_timings)
        model, names, device = cached['model'], cached['names'], runtime.device
        # Embedded text embeddings permit fully offline inference. No set_classes/download at runtime.
        supported=[i for i,name in names.items() if name in GROUPS and GROUPS[name][0] in selected]
        if not supported:
            raise DetectionError('MODEL_NOT_READY','Model has no supported categories')
        capabilities=sorted({GROUPS[names[i]][0] for i in supported})
        if set(capabilities) != set(selected):
            raise DetectionError('MODEL_NOT_READY', 'Selected categories are not supported by these weights')
        write_json(output/'progress.json',{'progress':10})
        tracker=EventTracker(sample)
        region_fallback_frames=0
        # Bound raw input memory as well as GPU batch size, including very large source frames.
        batch_size=min(runtime.batch_size, max(1, (64 * 1024 * 1024) // first.nbytes), len(frame_indices))
        inference_ms=0.0
        preview='frame-000000.jpg'
        preview_score=-1
        current_frame = 0
        saved=set()
        last_progress=time.perf_counter()
        for offset in range(0, len(frame_indices), batch_size):
            check_cancelled(cancelled)
            batch=[]
            for index in range(offset, min(offset + batch_size, len(frame_indices))):
                before = time.perf_counter()
                if index==0:
                    frame=first
                else:
                    target_frame = frame_indices[index]
                    # Decode forward once; batch inference does not drop or reorder video frames.
                    for _ in range(target_frame - current_frame - 1):
                        check_cancelled(cancelled)
                        if not capture.grab():
                            raise DetectionError('INVALID_MEDIA', 'Cannot advance to sampled frame')
                    ok,frame=capture.read()
                    current_frame = target_frame
                    if not ok or frame is None or frame.shape[0] * frame.shape[1] > 24_000_000:
                        raise DetectionError('INVALID_MEDIA',f'Cannot decode frame {target_frame}')
                    timings['decodeMs'] += (time.perf_counter() - before) * 1000
                batch.append((index, frame_indices[index] / fps if fps else 0.0, frame))
            # Hough line extraction releases the GIL. Process independent frame regions
            # on a bounded pool while CUDA runs; every frame still gets its own estimate.
            before=time.perf_counter()
            region_futures=[runtime.region_pool.submit(runtime.estimate_region,item[2])
                            if item[0]!=0 and not manual else None for item in batch]
            timings['regionMs'] += (time.perf_counter()-before)*1000
            before=time.perf_counter()
            results=model.predict([item[2] for item in batch], conf=confidence, classes=supported,
                                  **runtime.predict_options())
            if len(results) != len(batch):
                raise ValueError('Model returned an unexpected batch size')
            # Transfer all box values once per frame, not separate synchronizations for coordinates/classes/confidence.
            boxes=[result.boxes.data.cpu().numpy() if result.boxes is not None else [] for result in results]
            runtime.synchronize()
            inference_ms+=(time.perf_counter()-before)*1000
            for (index,timestamp,frame), frame_boxes, region_future in zip(batch,boxes,region_futures):
                check_cancelled(cancelled)
                before = time.perf_counter()
                if region_future is not None:
                    current_roi, compute_ms=region_future.result()
                    timings['regionComputeMs'] += compute_ms
                else:
                    current_roi=roi
                timings['regionMs'] += (time.perf_counter() - before) * 1000
                if current_roi is None:
                    current_roi=roi
                    region_fallback_frames += 1
                before=time.perf_counter()
                h,w=frame.shape[:2]
                snapshot=f'frame-{index:06d}.jpg'
                detections=[]
                for values in frame_boxes:
                    box, conf, cls = values[:4], values[-2], values[-1]
                    name=names[int(cls)]
                    if name not in GROUPS: continue
                    category,label=GROUPS[name]
                    normalized=np.clip(box/[w,h,w,h],0,1).tolist()
                    if normalized[2]<=normalized[0] or normalized[3]<=normalized[1]:continue
                    danger=in_danger(normalized,current_roi)
                    detections.append({'category':category,'label':label,'modelLabel':name,
                        'confidence':round(float(conf),5),'box':normalized,'inDanger':danger,
                        'risk':('MEDIUM' if category=='animal' else 'HIGH') if danger else 'INFO',
                        'frameTime':round(timestamp,3),'snapshot':snapshot,
                        'advice':ADVICE[category] if danger else '目标位于危险区外，不生成预警；如轨道区域有偏差，请重新标定。',
                        'adviceSource':'LOCAL_RULE'})
                tracker.update(detections,timestamp)
                score=sum(d['inDanger'] for d in detections)*100+len(detections)
                if score>preview_score:
                    preview=snapshot;preview_score=score
                required=tracker.evidence_snapshots() | {preview}
                timings['associationMs'] += (time.perf_counter() - before) * 1000
                # Retain the overview and evidence referenced by each final event, not every video frame.
                if snapshot in required:
                    before=time.perf_counter()
                    annotated=annotate(frame,current_roi,detections)
                    timings['annotationMs'] += (time.perf_counter() - before) * 1000
                    before=time.perf_counter()
                    save_image(output/snapshot,annotated)
                    saved.add(snapshot)
                    timings['snapshotMs'] += (time.perf_counter() - before) * 1000
                before=time.perf_counter()
                for obsolete in saved-required:
                    (output/obsolete).unlink(missing_ok=True)
                saved.intersection_update(required)
                timings['snapshotMs'] += (time.perf_counter() - before) * 1000
            if time.perf_counter()-last_progress >= .25 or offset+batch_size >= len(frame_indices):
                before=time.perf_counter()
                write_json(output/'progress.json',{'progress':min(98,round(10+88*(offset+len(batch))/len(frame_indices)))})
                last_progress=time.perf_counter()
                timings['progressMs'] += (time.perf_counter() - before) * 1000
            del results, boxes, batch
            region_futures=[]
        detections=tracker.results()
        check_cancelled(cancelled)
        before=time.perf_counter()
        write_json(output/'progress.json',{'progress':99})
        timings['progressMs'] += (time.perf_counter() - before) * 1000
        before=time.perf_counter()
        vision_advice=enrich_with_vision(output,detections,not is_image)
        timings['adviceMs'] = (time.perf_counter() - before) * 1000
        check_cancelled(cancelled)
        timings['postprocessMs'] = sum(timings[key] for key in ('associationMs', 'annotationMs', 'snapshotMs'))
        timings['inferenceMs'] = inference_ms
        timings['workerMs'] = (time.perf_counter() - started) * 1000 + module_ms
        accounted = sum(timings[key] for key in ('startupMs', 'decodeMs', 'regionMs', 'modelLoadMs', 'modelHashMs', 'postprocessMs', 'inferenceMs', 'progressMs', 'adviceMs'))
        timings['otherMs'] = max(0, timings['workerMs'] - accounted)
        pipeline_ms=timings['workerMs']-sum(timings[key] for key in ('startupMs', 'modelLoadMs', 'modelHashMs', 'adviceMs'))
        region_source='MANUAL' if manual else 'AUTO_CURVE' if len(roi)>4 else 'AUTO_GEOMETRY'
        return {'ok':True,'detections':detections,'region':roi,'regionSource':region_source,
                'preview':preview,'sampledFrames':len(frame_indices),'durationSeconds':round(duration,3),
                'inferenceMs':round(inference_ms,2),'model':model_path.name,
                'modelSha256':cached['fingerprint'],'device':'CUDA' if device==0 else 'CPU',
                'workerMode':'PERSISTENT' if persistent else 'ONESHOT','workerPid':os.getpid(),'modelReused':reused,
                'performance':{'inferenceFps':round(len(frame_indices)*1000/max(inference_ms,.001),2),
                               'pipelineFps':round(len(frame_indices)*1000/max(pipeline_ms,.001),2),
                               'batchSize':batch_size,'imageSize':runtime.image_size,
                               'precision':'FP16' if runtime.half else 'FP32','savedSnapshots':len(saved),
                                'sourceFrames':int(frames),'sourceFps':round(fps,3),
                                'parallelRegions':not is_image and not manual,
                                'regionFallbackFrames':region_fallback_frames,
                                'samplingMode':'ALL_FRAMES' if not is_image and sample==0 else 'SAMPLED' if not is_image else 'IMAGE'},
                'targetCategories':selected,'timings':{k:round(v,2) for k,v in timings.items()},
                'visionAdvice':vision_advice,
                'capabilities':capabilities,'objectCount':len(detections),'warningCount':sum(d['inDanger'] for d in detections),
                'outsideCount':sum(not d['inDanger'] for d in detections)}
    finally:
        running=[future for future in region_futures if future is not None]
        for future in running:future.cancel()
        if running:wait(running)
        if capture is not None:capture.release()
        if cached is not None:runtime.release_inputs(cached)
        if not persistent and runtime is not None:runtime.close()


def serve():
    """stdin reader handles cancellation while the main thread performs serial inference."""
    commands = queue.Queue(maxsize=2)
    events = {}
    lock = threading.Lock()

    def emit(message):
        PROTOCOL_OUTPUT.write(json.dumps(message, ensure_ascii=False, allow_nan=False) + '\n')
        PROTOCOL_OUTPUT.flush()

    def read_commands():
        try:
            for line in sys.stdin:
                command = json.loads(line)
                identifier = command.get('id')
                if command.get('type') == 'cancel':
                    with lock:
                        event = events.get(identifier)
                        if event is not None:
                            event.set()
                elif command.get('type') == 'run' and isinstance(identifier, str):
                    with lock:
                        if identifier in events:
                            raise ValueError('Duplicate request ID')
                        event = threading.Event()
                        events[identifier] = event
                    commands.put_nowait((command, event))
                else:
                    raise ValueError('Invalid worker command')
        except Exception:
            traceback.print_exc()
        # A closed pipe means the owning backend exited. Exit even if native inference is stuck.
        os._exit(0)

    threading.Thread(target=read_commands, name='trackguard-control', daemon=True).start()
    try:
        runtime = InferenceRuntime()
        emit({'type': 'ready', 'protocol': 1, 'pid': os.getpid(),
              'startupMs': round((time.perf_counter() - MODULE_STARTED) * 1000, 2),
              'dependencyMs': round(MODULE_IMPORT_MS + runtime.dependency_ms, 2),
              'device': 'CUDA' if runtime.device == 0 else 'CPU',
              'batchSize':runtime.batch_size,'imageSize':runtime.image_size,'precision':'FP16' if runtime.half else 'FP32'})
    except Exception:
        traceback.print_exc()
        emit({'type': 'startup_error'})
        return 1
    while True:
        command, event = commands.get()
        output = Path(command['output'])
        output.mkdir(parents=True, exist_ok=True)
        fatal = False
        with (output / 'worker.log').open('a', encoding='utf-8', buffering=1) as log:
            with redirect_stdout(log), redirect_stderr(log):
                print(f"Persistent worker pid={os.getpid()} request={command['id']}")
                try:
                    request = json.loads(Path(command['request']).read_text(encoding='utf-8'))
                    result = run(request, output, runtime, event)
                except Exception as exc:
                    traceback.print_exc()
                    result = {'ok': False, 'code': getattr(exc, 'code', 'INFERENCE_ERROR'), 'message': str(exc)[:500]}
                    fatal = not isinstance(exc, (DetectionError, ValueError))
                write_json(output / 'result.json', result)
                print(f"Finished ok={result['ok']} code={result.get('code', 'OK')}")
        with lock:
            events.pop(command['id'], None)
        emit({'type': 'done', 'id': command['id'], 'restartRequired': fatal})
        if fatal:
            return 1


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--serve', action='store_true', help='Stay alive and accept private stdin commands from Spring Boot')
    parser.add_argument('--request',type=Path)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    if args.serve:
        if args.request is not None or args.output is not None:
            parser.error('--serve cannot be combined with --request/--output')
        return serve()
    if args.request is None or args.output is None:
        parser.error('one-shot mode requires --request and --output')
    args.output.mkdir(parents=True,exist_ok=True)
    try:
        result=run(json.loads(args.request.read_text(encoding='utf-8')),args.output)
        write_json(args.output/'result.json',result)
        return 0
    except Exception as exc:
        traceback.print_exc()
        write_json(args.output/'result.json',{'ok':False,'code':getattr(exc,'code','INFERENCE_ERROR'),'message':str(exc)[:500]})
        return 1


if __name__=='__main__':
    sys.exit(main())
