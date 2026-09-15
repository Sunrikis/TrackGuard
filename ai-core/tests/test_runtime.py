"""Exercise cache invalidation, class isolation and cancellation without loading CUDA."""
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import numpy as np
import cv2

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from worker import DetectionError, InferenceRuntime, run, save_image, write_json


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.loaded = []
        self.predictions = []
        self.cancel_on_predict = None
        self.box_factory = None
        self.seen_frames = []

        owner = self

        class FakeModel:
            names = {0: 'person', 1: 'car', 2: 'dog', 3: 'suitcase', 4: 'motorcycle', 5: 'large rock'}

            def __init__(self, filename):
                owner.loaded.append(filename)

            def predict(self, frame, **kwargs):
                owner.predictions.append(kwargs.get('classes'))
                if kwargs.get('classes') is not None and owner.cancel_on_predict is not None:
                    owner.cancel_on_predict.set()
                results = []
                for item in frame:
                    if kwargs.get('classes') is not None:
                        owner.seen_frames.append(int(item[0,0,0]))
                    values = owner.box_factory(item) if owner.box_factory and kwargs.get('classes') is not None else None
                    tensor = SimpleNamespace(numpy=lambda values=values: values)
                    results.append(SimpleNamespace(boxes=SimpleNamespace(data=SimpleNamespace(cpu=lambda tensor=tensor: tensor)) if values is not None else None))
                return results

        fake_torch = SimpleNamespace(cuda=SimpleNamespace(is_available=lambda: False), set_num_threads=lambda n: None)
        options = patch.dict(os.environ, {'RFOID_BATCH_SIZE':'8', 'RFOID_IMAGE_SIZE':'640', 'RFOID_CPU_THREADS':'4',
                                          'RFOID_HALF':'true', 'DEEPSEEK_VISION_ENABLED':'false'})
        options.start()
        self.addCleanup(options.stop)
        modules = patch.dict(sys.modules, {'torch': fake_torch, 'ultralytics': SimpleNamespace(YOLO=FakeModel)})
        modules.start()
        self.addCleanup(modules.stop)
        self.runtime = InferenceRuntime()
        self.addCleanup(self.runtime.close)
        self.model = self.weights('model.pt')

    def weights(self, name):
        path = self.root / name
        path.write_bytes(name.encode() * 1024)
        return path

    def test_atomic_json_write_retries_a_temporary_windows_sharing_violation(self):
        path = self.root / 'progress.json'
        original_replace = os.replace
        attempts = 0

        def flaky_replace(source, target):
            nonlocal attempts
            attempts += 1
            if attempts < 3:
                raise PermissionError(5, 'temporary sharing violation')
            return original_replace(source, target)

        with patch('worker.os.replace', side_effect=flaky_replace), patch('worker.time.sleep') as pause:
            write_json(path, {'progress': 10})
        self.assertEqual(attempts, 3)
        self.assertEqual(path.read_text(encoding='utf-8'), '{"progress": 10}')
        self.assertEqual(pause.call_count, 2)

    def request(self, targets):
        source = self.root / 'sample.jpg'
        save_image(source, np.zeros((32, 32, 3), dtype=np.uint8))
        return {'source': str(source), 'model': str(self.model), 'confidence': .35, 'sampleSeconds': 1,
                'roi': [[.1, .1], [.9, .1], [.9, .9], [.1, .9]], 'maxVideoSeconds': 120, 'targetCategories': targets}

    def test_repeated_acquire_reuses_model_and_fingerprint(self):
        first, reused, _ = self.runtime.acquire(self.model)
        second, reused_again, timings = self.runtime.acquire(self.model)
        self.assertFalse(reused)
        self.assertTrue(reused_again)
        self.assertIs(first, second)
        self.assertEqual(first['fingerprint'], hashlib.sha256(self.model.read_bytes()).hexdigest())
        self.assertEqual(len(self.loaded), 1)
        self.assertEqual(self.predictions, [])
        self.assertTrue(all(v == 0 for v in timings.values()))

    def test_replacing_weights_invalidates_model_and_fingerprint(self):
        first, _, _ = self.runtime.acquire(self.model)
        self.model.write_bytes(b'new-weights' * 1024)
        second, reused, _ = self.runtime.acquire(self.model)
        self.assertFalse(reused)
        self.assertNotEqual(first['fingerprint'], second['fingerprint'])
        self.assertEqual(len(self.loaded), 2)

    def test_cache_is_bounded_and_recent_models_survive_switching(self):
        other = self.weights('other.pt')
        third = self.weights('third.pt')
        self.runtime.acquire(self.model)
        self.runtime.acquire(other)
        self.assertTrue(self.runtime.acquire(self.model)[1])
        self.runtime.acquire(third)
        self.assertEqual(len(self.runtime.models), 2)
        self.assertTrue(self.runtime.acquire(self.model)[1])
        self.assertFalse(self.runtime.acquire(other)[1])

    def test_targets_and_task_timings_are_not_carried_between_jobs(self):
        self.runtime.acquire(self.model)
        for category, expected in [('person', [0]), ('vehicle', [1]), ('motorcycle', [4]), ('obstacle', [3, 5]), ('person', [0])]:
            output = self.root / f'run-{len(self.predictions)}'
            output.mkdir()
            result = run(self.request([category]), output, self.runtime)
            self.assertEqual(self.predictions[-1], expected)
            self.assertTrue(result['modelReused'])
            self.assertEqual(result['workerMode'], 'PERSISTENT')
            for field in ('startupMs', 'modelLoadMs', 'modelHashMs'):
                self.assertEqual(result['timings'][field], 0)
            parts = ['startupMs', 'modelLoadMs', 'modelHashMs', 'decodeMs', 'regionMs', 'inferenceMs',
                     'postprocessMs', 'progressMs', 'adviceMs', 'otherMs']
            self.assertAlmostEqual(sum(result['timings'][p] for p in parts), result['timings']['workerMs'], delta=.1)
        self.assertEqual(len(self.loaded), 1)

    def video(self, count=6):
        frames = [np.full((32,32,3), index, np.uint8) for index in range(count)]
        class Capture:
            position = 0
            def isOpened(self): return True
            def get(self, key): return 30 if key == cv2.CAP_PROP_FPS else count
            def read(self):
                if self.position == count: return False, None
                frame = frames[self.position]
                self.position += 1
                return True, frame
            def grab(self):
                self.position += 1
                return self.position <= count
            def release(self): pass
        return Capture()

    def test_all_frames_batches_include_tail_and_keep_final_evidence(self):
        self.runtime.acquire(self.model)
        self.runtime.batch_size = 4
        request = self.request(['person'])
        request.update(source=str(self.root/'video.mp4'), sampleSeconds=0)
        confidence = [.9,.7,.71,.85,.6,.65]
        def boxes(frame):
            index = int(frame[0,0,0])
            return np.array([[30 if index==0 else 27,8,32,28,confidence[index],0]])
        self.box_factory = boxes
        output = self.root/'video-output'
        output.mkdir()
        with patch('worker.cv2.VideoCapture', return_value=self.video()):
            result = run(request, output, self.runtime)
        self.assertEqual(self.seen_frames, list(range(6)))
        self.assertEqual(result['sampledFrames'], 6)
        self.assertEqual(result['warningCount'], 1)
        self.assertEqual(result['detections'][0]['frameTime'], .1)
        references = {result['preview']} | {d['snapshot'] for d in result['detections']}
        self.assertEqual({p.name for p in output.glob('*.jpg')}, references)
        self.assertLess(result['performance']['savedSnapshots'], 6)
        self.assertEqual(result['performance']['samplingMode'], 'ALL_FRAMES')
        pipeline_ms = result['timings']['workerMs'] - result['timings']['adviceMs']
        self.assertAlmostEqual(result['performance']['pipelineFps'], 6000/pipeline_ms, delta=1)

    def test_sampling_skips_only_requested_frames_and_empty_video_keeps_preview(self):
        self.runtime.acquire(self.model)
        self.runtime.batch_size = 4
        request = self.request(['person'])
        request.update(source=str(self.root/'video.mp4'), sampleSeconds=.5)
        output = self.root/'sampled-output'
        output.mkdir()
        with patch('worker.cv2.VideoCapture', return_value=self.video(46)):
            result = run(request, output, self.runtime)
        self.assertEqual(self.seen_frames, [0,15,30,45])
        self.assertEqual(result['sampledFrames'], 4)
        self.assertEqual(result['performance']['sourceFrames'], 46)
        self.assertEqual(result['performance']['savedSnapshots'], 1)
        self.assertTrue((output/result['preview']).is_file())
        self.assertFalse(self.runtime.half)  # CPU must never receive FP16.

    def test_parallel_automatic_regions_still_inspect_every_frame(self):
        self.runtime.acquire(self.model)
        self.runtime.batch_size=4
        request=self.request(['person'])
        request.update(source=str(self.root/'video.mp4'), roi=[], sampleSeconds=0)
        region_calls=[]
        def region(frame):
            region_calls.append(int(frame[0,0,0]))
            return [[.1,.1],[.9,.1],[.9,.9],[.1,.9]]
        output=self.root/'auto-output'
        output.mkdir()
        with patch('worker.cv2.VideoCapture', return_value=self.video()), patch('worker.estimate_rails', side_effect=region):
            result=run(request, output, self.runtime)
        self.assertEqual(sorted(region_calls), list(range(6)))
        self.assertEqual(self.seen_frames, list(range(6)))
        self.assertTrue(result['performance']['parallelRegions'])
        self.assertEqual(result['regionSource'], 'AUTO_GEOMETRY')

    def test_video_reuses_last_region_when_a_later_frame_is_unclear(self):
        self.runtime.acquire(self.model)
        self.runtime.batch_size=4
        request=self.request(['person'])
        request.update(source=str(self.root/'video.mp4'),roi=[],sampleSeconds=0)
        region=[[.1,.1],[.9,.1],[.9,.9],[.1,.9]]
        output=self.root/'region-fallback-output'
        output.mkdir()
        with patch('worker.cv2.VideoCapture',return_value=self.video()), patch('worker.estimate_rails',side_effect=[region,region,None,region,region,region,region]):
            result=run(request,output,self.runtime)
        self.assertEqual(result['performance']['regionFallbackFrames'],1)

    def test_cancellation_during_inference_preserves_model_for_next_job(self):
        self.runtime.acquire(self.model)
        cancelled = threading.Event()
        self.cancel_on_predict = cancelled
        output = self.root / 'cancelled'
        output.mkdir()
        with self.assertRaises(DetectionError) as failure:
            run(self.request(['person']), output, self.runtime, cancelled)
        self.assertEqual(failure.exception.code, 'CANCELLED')
        self.assertFalse((output / 'frame-000000.jpg').exists())
        self.cancel_on_predict = None
        next_output = self.root / 'next'
        next_output.mkdir()
        self.assertTrue(run(self.request(['vehicle']), next_output, self.runtime)['ok'])
        self.assertEqual(len(self.loaded), 1)


if __name__ == '__main__':
    unittest.main()
