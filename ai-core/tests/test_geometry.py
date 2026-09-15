import sys
from pathlib import Path
import unittest
from unittest.mock import patch
import numpy as np
import cv2
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from geometry import validate_roi, in_danger, estimate_rails, EventTracker, sample_frame_indices

ROI=[[.3,.2],[.7,.2],[.8,.95],[.2,.95]]
def detection(box, danger=True, conf=.8, category='person', time=0):
    return dict(box=box,category=category,inDanger=danger,confidence=conf,frameTime=time,snapshot=f'frame-{time}.jpg')

class GeometryTests(unittest.TestCase):
    def test_known_rail_pair_preserves_geometry_and_margin(self):
        lines=np.array([[[164,64,110,388]],[[236,64,290,388]]], dtype=np.int32)
        with patch('geometry.cv2.HoughLinesP', return_value=lines):
            actual=estimate_rails(np.zeros((400,400,3), dtype=np.uint8))
        np.testing.assert_allclose(actual, [[.3848,.16],[.6152,.16],[.788,.97],[.212,.97]], atol=1e-6)
    def test_all_frames_and_invalid_metadata(self):
        self.assertEqual(list(sample_frame_indices(4, 29.97, 0)), [0,1,2,3])
        for count,fps,interval in [(1,30,-1),(1,float('nan'),0),(float('inf'),30,0),(1,30,float('nan'))]:
            with self.assertRaises(ValueError): sample_frame_indices(count,fps,interval)
    def test_fractional_video_fps_never_samples_beyond_last_frame(self):
        self.assertEqual(list(sample_frame_indices(120, 29.97, 1)), [0, 30, 60, 90])
        self.assertEqual(list(sample_frame_indices(1, 29.97, 1)), [0])
    def test_intrusion_and_outside(self):
        self.assertTrue(in_danger([.45,.3,.55,.8],ROI))
        self.assertFalse(in_danger([.85,.3,.95,.8],ROI))
    def test_upper_body_overlap_does_not_trigger(self):
        self.assertFalse(in_danger([.4,.4,.6,.99],[[.3,.2],[.7,.2],[.7,.7],[.3,.7]]))
    def test_edge_intrusion_is_kept(self):
        self.assertTrue(in_danger([.75,.5,.85,.9],ROI))
    def test_invalid_polygon_and_nonfinite_rejected(self):
        for p in [[[.1,.1],[.9,.9],[.1,.9],[.9,.1]], [[float('nan'),.1],[.9,.1],[.9,.9],[.1,.9]], [[.5,.5],[.501,.5],[.501,.501],[.5,.501]]]:
            with self.assertRaises(ValueError):validate_roi(p)
    def test_no_visual_evidence_never_assumes_safe_rails(self):
        self.assertIsNone(estimate_rails(np.zeros((300,400,3),dtype=np.uint8)))
    def test_curved_rails_use_multi_point_corridor(self):
        frame=np.zeros((500,600,3),dtype=np.uint8)
        ys=np.arange(80,486)
        centre=300+110*((ys-80)/405)**2
        half=35+145*((ys-80)/405)
        left=np.column_stack((centre-half,ys)).astype(np.int32)
        right=np.column_stack((centre+half,ys)).astype(np.int32)
        cv2.polylines(frame,[left],False,(245,245,245),7)
        cv2.polylines(frame,[right],False,(245,245,245),7)
        with patch('geometry.cv2.HoughLinesP',return_value=None):
            region=estimate_rails(frame)
        self.assertIsNotNone(region)
        self.assertGreater(len(region),4)
        self.assertGreater(np.mean(np.asarray(region)[-4:,0])-np.mean(np.asarray(region)[:4,0]),.08)
    def test_same_object_is_not_counted_per_frame(self):
        tracker=EventTracker()
        for t in range(4):tracker.update([detection([.4+t*.005,.3,.55+t*.005,.8],time=t)],t)
        self.assertEqual(len(tracker.results()),1)
    def test_two_objects_same_frame_never_merge(self):
        tracker=EventTracker()
        tracker.update([detection([.4,.3,.55,.8]),detection([.42,.3,.58,.8])],0)
        self.assertEqual(len(tracker.results()),2)
    def test_preserves_first_intrusion_over_high_confidence_outside(self):
        tracker=EventTracker()
        tracker.update([detection([.4,.3,.55,.8],False,.99)],0)
        tracker.update([detection([.41,.3,.56,.8],True,.7,time=1)],1)
        tracker.update([detection([.42,.3,.57,.8],False,.99,time=2)],2)
        result=tracker.results()[0]
        self.assertTrue(result['inDanger']);self.assertEqual(result['frameTime'],1)
    def test_category_and_elapsed_gap_create_distinct_events(self):
        tracker=EventTracker()
        tracker.update([detection([.4,.3,.55,.8])],0)
        tracker.update([detection([.4,.3,.55,.8],category='vehicle')],1)
        tracker.update([detection([.4,.3,.55,.8])],10)
        self.assertEqual(len(tracker.results()),3)

if __name__=='__main__':unittest.main()
