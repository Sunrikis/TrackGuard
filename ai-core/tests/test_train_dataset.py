import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('training_entry', Path(__file__).resolve().parents[1] / 'train/train.py')
training = importlib.util.module_from_spec(spec)
spec.loader.exec_module(training)


class TrainingDatasetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'data.yaml'
        self.config.write_text('path: .\ntrain: images/train\nval: images/val\nnames: [person]\n', encoding='utf-8')
        for split in ('train', 'val'):
            (self.root / 'images' / split).mkdir(parents=True)
            (self.root / 'labels' / split).mkdir(parents=True)
            (self.root / 'images' / split / 'one.jpg').touch()
            (self.root / 'labels' / split / 'one.txt').write_text('0 0.5 0.5 0.2 0.2\n', encoding='utf-8')

    def test_relative_dataset_resolves_from_configuration(self):
        data = training.read_dataset(self.config, ['train', 'val'])
        self.assertEqual(data['path'], str(self.root.resolve()))
        self.assertEqual(data['names'], {0: 'person'})

    def test_missing_annotation_is_not_silently_background(self):
        (self.root / 'labels/train/one.txt').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing label'):
            training.read_dataset(self.config, ['train', 'val'])

    def test_invalid_class_and_outside_box_rejected(self):
        for label in ('1 0.5 0.5 0.2 0.2', '0 0.95 0.5 0.2 0.2', '0 nan 0.5 0.2 0.2'):
            (self.root / 'labels/train/one.txt').write_text(label, encoding='utf-8')
            with self.assertRaises(ValueError):
                training.read_dataset(self.config, ['train'])
