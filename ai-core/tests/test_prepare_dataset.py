import importlib.util
import tempfile
import unittest
from pathlib import Path

from PIL import Image
import yaml

spec = importlib.util.spec_from_file_location('dataset_preparer', Path(__file__).resolve().parents[1] / 'train/prepare_dataset.py')
preparer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preparer)


class DatasetPreparationTests(unittest.TestCase):
    def test_balancing_preserves_source_and_prevents_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / 'source', root / 'output'
            source.mkdir()
            (source / 'data.yaml').write_text(yaml.safe_dump({'names': preparer.SOURCE_NAMES}), encoding='utf-8')
            for split_index, split in enumerate(('train', 'val')):
                (source / split / 'images').mkdir(parents=True)
                (source / split / 'labels').mkdir()
                for cls in range(5):
                    for index in range(2):
                        name = f'{cls}_{index}'
                        Image.new('RGB', (32, 32), (cls * 40, index * 80, split_index * 100)).save(source / split / 'images' / (name + '.png'))
                        (source / split / 'labels' / (name + '.txt')).write_text(f'{cls} 0.5 0.5 0.2 0.2\n')
            before = {p.relative_to(source): p.read_bytes() for p in source.rglob('*') if p.is_file()}
            report = preparer.prepare(source, output, 1, 1)
            self.assertEqual(len(report['selected']), 10)
            self.assertEqual(len({row['sha256'] for row in report['selected']}), 10)
            for split in ('train', 'val'):
                self.assertEqual(list(report['output'][split]['imagesPerClass'].values()), [1] * 5)
                self.assertEqual(len(list((output / 'labels' / split).glob('*.txt'))), 5)
            self.assertEqual(before, {p.relative_to(source): p.read_bytes() for p in source.rglob('*') if p.is_file()})
            with self.assertRaisesRegex(ValueError, 'already contains'):
                preparer.prepare(source, output, 1, 1)
            old_files = {p.relative_to(output): p.read_bytes() for split in ('train', 'val')
                         for folder in ('images', 'labels') for p in (output / folder / split).glob('*')}
            report = preparer.add_test(source, output, 1)
            self.assertEqual(len(report['selected']), 15)
            self.assertEqual(len({row['sha256'] for row in report['selected']}), 15)
            self.assertEqual(list(report['output']['test']['imagesPerClass'].values()), [1] * 5)
            for name, content in old_files.items():
                self.assertEqual((output / name).read_bytes(), content)
            self.assertEqual(before, {p.relative_to(source): p.read_bytes() for p in source.rglob('*') if p.is_file()})
            with self.assertRaisesRegex(ValueError, 'already prepared'):
                preparer.add_test(source, output, 1)
