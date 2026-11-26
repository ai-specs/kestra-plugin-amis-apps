import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('restore', Path(__file__).with_name('restore-ci-artifact.py'))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


class RestoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.target = self.root / 'checkout'
        self.artifact = self.root / 'artifact'
        self.target.mkdir()
        self.artifact.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.target)], check=True)
        self.put(self.target, '.gitignore', '/build/\n/ui/reports/\n/.integration/\n/.work/\n')
        for name, value in [('examples/p1/conditional.json', '{"type":"page"}'), ('contracts/p1-cases.json', '{"production":[]}'), ('host-contract.properties', 'overlay.image=formal@sha256:test\n')]:
            self.put(self.target, name, value)
        subprocess.run(['git', 'add', '.'], cwd=self.target, check=True)
        for name in ['examples/p1/conditional.json', 'contracts/p1-cases.json']:
            self.put(self.artifact, name, (self.target / name).read_text())
        for name in restore.GENERATED_TREES:
            self.put(self.artifact, name + '/evidence', 'tested bytes')
        self.jar = self.artifact / 'build/libs/test.jar'
        self.jar.write_bytes(b'original tested jar bytes')
        host = {'image': 'formal@sha256:test', 'sha256': 'a' * 64}
        for name, field in [('.integration/host-public-types.txt', 'publicTypesSha256'), ('.integration/host-flow-validation.txt', 'flowValidationSha256')]:
            self.put(self.artifact, name, 'formal public types')
            host[field] = restore.digest(self.artifact / name)
        self.put(self.artifact, '.integration/host-provenance.json', json.dumps(host))

    def put(self, base, name, value):
        path = base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def test_existing_tracked_examples_are_compared_never_overwritten(self):
        source = self.target / 'examples/p1/conditional.json'
        before = source.stat().st_mtime_ns
        jar_hash = restore.digest(self.jar)
        result = restore.restore(self.target, self.artifact)
        self.assertEqual(source.stat().st_mtime_ns, before)
        self.assertIn('examples/p1/conditional.json', result['sourceFilesVerified'])
        self.assertEqual(restore.digest(self.target / 'build/libs/test.jar'), jar_hash)
        self.assertEqual((self.target / 'ui/reports/evidence').read_bytes(), b'tested bytes')
        self.assertEqual(subprocess.check_output(['git', 'diff', '--name-only'], cwd=self.target), b'')

    def test_different_source_content_fails_before_any_restore(self):
        self.put(self.artifact, 'examples/p1/conditional.json', '{"type":"wrong"}')
        with self.assertRaisesRegex(AssertionError, 'source content differs'):
            restore.restore(self.target, self.artifact)
        self.assertFalse((self.target / 'build').exists())

    def test_symlink_artifact_and_target_escape_are_rejected(self):
        path = self.artifact / 'ui/reports/link'
        path.symlink_to(self.root)
        with self.assertRaisesRegex(AssertionError, 'Symlink'):
            restore.restore(self.target, self.artifact)
        path.unlink()
        (self.target / 'build').symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(AssertionError, 'escapes'):
            restore.restore(self.target, self.artifact)

    def test_unknown_top_level_and_unexpected_host_are_rejected(self):
        self.put(self.artifact, 'unknown/file', 'unexpected')
        with self.assertRaisesRegex(AssertionError, 'Unexpected artifact'):
            restore.restore(self.target, self.artifact)
        (self.artifact / 'unknown/file').unlink()
        (self.artifact / 'unknown').rmdir()
        path = self.artifact / '.integration/host-provenance.json'
        host = json.loads(path.read_text())
        host['image'] = 'candidate:latest'
        path.write_text(json.dumps(host))
        with self.assertRaisesRegex(AssertionError, 'Unexpected formal host'):
            restore.restore(self.target, self.artifact)

    def test_nonempty_target_fails_without_overwrite(self):
        self.put(self.target, 'build/libs/user.jar', 'preserve')
        with self.assertRaisesRegex(AssertionError, 'not empty'):
            restore.restore(self.target, self.artifact)
        self.assertEqual((self.target / 'build/libs/user.jar').read_text(), 'preserve')

    def test_host_evidence_tampering_fails_before_restore(self):
        self.put(self.artifact, '.integration/host-public-types.txt', 'tampered')
        with self.assertRaisesRegex(AssertionError, 'Host evidence hash'):
            restore.restore(self.target, self.artifact)
        self.assertFalse((self.target / 'build').exists())


    def test_target_root_or_artifact_ancestor_symlink_rejected_without_writes(self):
        alias = self.root / 'target-alias'
        alias.symlink_to(self.target, target_is_directory=True)
        with self.assertRaisesRegex(AssertionError, 'ancestor symlink'):
            restore.restore(alias, self.artifact)
        artifact_alias = self.root / 'artifact-alias'
        artifact_alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(AssertionError, 'ancestor symlink'):
            restore.restore(self.target, artifact_alias / 'artifact')
        self.assertFalse((self.target / 'build').exists())

    def test_source_parent_symlink_rejected_without_writes(self):
        original = self.target / 'examples'
        saved = self.root / 'saved-examples'
        original.rename(saved)
        original.symlink_to(saved, target_is_directory=True)
        with self.assertRaisesRegex(AssertionError, 'Source (target escapes|parent symlink)'):
            restore.restore(self.target, self.artifact)
        self.assertFalse((self.target / 'build').exists())

    def test_late_tracked_collision_is_preflighted_before_any_copy(self):
        collision = 'ui/reports/evidence'
        self.put(self.target, collision, 'temporarily tracked')
        subprocess.run(['git', 'add', '-f', collision], cwd=self.target, check=True)
        (self.target / collision).unlink()
        with self.assertRaisesRegex(AssertionError, 'collides with tracked'):
            restore.restore(self.target, self.artifact)
        self.assertFalse((self.target / 'build').exists())
        self.assertFalse((self.target / '.integration').exists())
        self.assertFalse((self.target / collision).exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
