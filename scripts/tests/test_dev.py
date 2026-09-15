"""Verify development preparation without launching services or loading AI models."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('dev_launcher', Path(__file__).resolve().parents[1] / 'dev.py')
dev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev)


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('pom.xml', 'backend/pom.xml', 'backend/src/main/App.java',
                     'frontend/node_modules/vite/bin/vite.js'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture', encoding='utf-8')
        self.jar = self.root / 'backend/target/trackguard-2.0.0.jar'
        self.commands = []
        def build(command, *_args):
            self.commands.append(command)
            if command[0] == 'mvn':
                self.jar.parent.mkdir(parents=True, exist_ok=True)
                self.jar.write_bytes(b'jar fixture')
        for mock in (patch.object(dev, 'ROOT', self.root),
                     patch.object(dev, 'executable', side_effect=lambda name, _env: name),
                     patch.object(dev, 'run_build', side_effect=build)):
            mock.start()
            self.addCleanup(mock.stop)

    def test_default_build_skips_tests_then_reuses_jar(self):
        dev.prepare({})
        self.assertEqual(self.commands, [['mvn', '-B', '-Dmaven.test.skip=true', 'package']])
        self.commands.clear()
        dev.prepare({})
        self.assertEqual(self.commands, [])

    def test_add_edit_remove_and_replaced_jar_invalidate_cache(self):
        dev.prepare({})
        added = self.root / 'backend/src/main/Other.java'
        for action in (lambda: added.write_text('one'), lambda: added.write_text('two'),
                       added.unlink, lambda: self.jar.write_bytes(b'externally replaced')):
            self.commands.clear()
            action()
            dev.prepare({})
            self.assertEqual(len(self.commands), 1)

    def test_full_build_runs_tests_and_production_build_even_with_cache(self):
        dev.prepare({})
        self.commands.clear()
        dev.prepare({}, full_build=True)
        self.assertEqual(self.commands, [['mvn', '-B', 'package'], ['npm', 'test'], ['npm', 'run', 'build']])

    def test_skip_build_requires_artifacts_and_never_builds(self):
        with self.assertRaises(RuntimeError): dev.prepare({}, skip_build=True)
        dev.prepare({})
        self.commands.clear()
        dev.prepare({}, skip_build=True)
        self.assertEqual(self.commands, [])


if __name__ == '__main__':
    unittest.main()
