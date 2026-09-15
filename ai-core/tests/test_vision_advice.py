"""Verify the Python-owned whole-task image understanding boundary."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vision_advice import VisionConfig, enrich_with_vision


class FakeResponse:
    status = 200

    def __init__(self, value):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, _limit=-1):
        return self.value


class VisionAdviceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = VisionConfig(True, "secret", "https://example.test/chat/completions",
                                   "deepseek-flash", 2, 2)

    def detection(self, index, category="person", risk="HIGH"):
        snapshot = f"frame-{index:06d}.jpg"
        (self.root / snapshot).write_bytes(bytes((index + 1, 2, 3, 4)))
        return {
            "trackKey": f"object-{index}", "snapshot": snapshot, "frameTime": index * 4.0,
            "category": category, "label": "人员", "risk": risk, "inDanger": True,
            "confidence": .8 + index / 100, "box": [.1, .2, .3, .5],
            "advice": "本地处置建议", "adviceSource": "LOCAL_RULE",
        }

    def response(self, content):
        return FakeResponse(json.dumps({
            "choices": [{"message": {"content": content}}]
        }, ensure_ascii=False).encode("utf-8"))

    def test_documented_base_url_is_expanded_to_chat_completions(self):
        with patch.dict("os.environ", {
            "DEEPSEEK_API_URL": "https://api.deepseek.com",
            "DEEPSEEK_API_KEY": "secret",
        }, clear=False):
            config = VisionConfig.from_env()
        self.assertEqual(config.api_url, "https://api.deepseek.com/chat/completions")

    def test_one_video_request_contains_all_results_and_only_selected_key_frames(self):
        detections = [
            self.detection(0), self.detection(1, "animal", "MEDIUM"),
            self.detection(2, "vehicle"), self.detection(3, "obstacle"),
        ]
        captured = []

        def open_request(request, timeout):
            captured.append((request, timeout))
            advice = json.dumps({
                "overallAdvice": "核实整段视频目标变化，立即联系值守人员防护，确认线路清空后恢复。",
                "events": [{"trackKey": "object-0", "advice": "立即核实人员位置并实施现场防护。"}],
            }, ensure_ascii=False)
            return self.response(advice)

        with patch("vision_advice.urlopen", side_effect=open_request):
            summary = enrich_with_vision(self.root, detections, True, self.config)

        self.assertEqual(len(captured), 1)
        request, timeout = captured[0]
        body = request.data.decode("utf-8")
        self.assertEqual(timeout, 2)
        self.assertEqual(request.get_header("Authorization"), "Bearer secret")
        self.assertIn('"response_format":{"type":"json_object"}', body)
        self.assertIn("整段视频的完整检测结果", body)
        self.assertIn("不得臆测未提供时段", body)
        for index in range(4):
            self.assertIn(f"object-{index}", body)
        self.assertEqual(body.count("data:image/jpeg;base64,"), 2)
        self.assertEqual(summary["requestedSnapshots"], 4)
        self.assertEqual(summary["generatedSnapshots"], 2)
        self.assertEqual(summary["skippedSnapshots"], 2)
        self.assertEqual(detections[0]["adviceSource"], "DEEPSEEK")
        self.assertEqual(detections[0]["advice"], "立即核实人员位置并实施现场防护。")
        self.assertEqual(detections[1]["advice"], summary["overallAdvice"])

    def test_missing_key_keeps_local_advice_without_http_request(self):
        detection = self.detection(0)
        disabled = VisionConfig(True, "", self.config.api_url, self.config.model, 2, 8)
        with patch("vision_advice.urlopen") as open_request:
            summary = enrich_with_vision(self.root, [detection], False, disabled)
        open_request.assert_not_called()
        self.assertFalse(summary["configured"])
        self.assertEqual(detection["adviceSource"], "LOCAL_RULE")
        self.assertEqual(detection["advice"], "本地处置建议")

    def test_invalid_json_falls_back_without_failing_detection(self):
        detection = self.detection(0)
        with patch("vision_advice.urlopen", return_value=self.response(b"not-json".decode())):
            summary = enrich_with_vision(self.root, [detection], False, self.config)
        self.assertEqual(summary["generatedSnapshots"], 0)
        self.assertEqual(summary["failedSnapshots"], 1)
        self.assertEqual(summary["errorMessage"], "DeepSeek 返回内容不是有效的结构化 JSON")
        self.assertEqual(detection["adviceSource"], "LOCAL_RULE")

    def test_snapshot_path_cannot_escape_task_directory(self):
        outside = self.root.parent / "outside-evidence.jpg"
        outside.write_bytes(b"outside")
        self.addCleanup(outside.unlink, missing_ok=True)
        detection = self.detection(0)
        detection["snapshot"] = "../outside-evidence.jpg"
        with patch("vision_advice.urlopen") as open_request:
            summary = enrich_with_vision(self.root, [detection], False, self.config)
        open_request.assert_not_called()
        self.assertEqual(summary["failedSnapshots"], 1)
        self.assertIn("无法读取任务证据帧", summary["errorMessage"])
        self.assertEqual(detection["adviceSource"], "LOCAL_RULE")


if __name__ == "__main__":
    unittest.main()
