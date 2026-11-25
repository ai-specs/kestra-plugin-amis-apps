import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release', Path(__file__).with_name('package-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.root_patch = patch.object(release, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.environment = patch.dict(os.environ)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        os.environ.pop('AMIS_BUILD_DIR', None)
        os.environ.pop('GITHUB_SHA', None)
        os.environ.pop('GITHUB_EVENT_NAME', None)
        os.environ.pop('GITHUB_EVENT_PATH', None)
        os.environ['CI'] = 'false'
        self.git('init', '-q')
        self.git('config', 'user.name', 'Release test')
        self.git('config', 'user.email', 'release-test@example.invalid')
        (self.root / '.gitignore').write_text('/build/\n/.integration/\n/ui/reports/\n/.work/\n')
        (self.root / 'README.md').write_text('Test source snapshot\n')
        (self.root / 'host-contract.properties').write_text('overlay.image=formal-test-input\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'Test packaging source')
        self.commit = self.git('rev-parse', 'HEAD')
        for path in ['build/libs', 'build/reports', 'build/test-results', 'ui/reports', '.integration']:
            (self.root / path).mkdir(parents=True)
        (self.root / 'build/libs/test.jar').write_bytes(b'tested-jar-placeholder')
        (self.root / 'build/reports/unit.txt').write_text('isolated test data\n')
        (self.root / 'build/test-results/results.xml').write_text('<testsuite tests="1"/>')
        (self.root / 'ui/reports/summary.json').write_text(json.dumps({'acceptancePassed': True, 'failures': []}))
        (self.root / '.integration/host-provenance.json').write_text(json.dumps({'image': 'formal-test-input'}))

    def git(self, *arguments):
        return subprocess.check_output(['git', *arguments], cwd=self.root, text=True).strip()

    def test_clean_snapshot_binds_jar_and_exact_committed_source(self):
        destination = self.root / '.work/release-image'
        release.stage(destination)
        provenance = json.loads((destination / 'release/release-provenance.json').read_text())
        self.assertEqual(provenance['commit'], self.commit)
        self.assertEqual(provenance['jarSha256'], release.sha256(destination / 'release/kestra-amis-apps.jar'))
        self.assertIn('source.tar', provenance['files'])
        for name, digest in provenance['files'].items():
            self.assertEqual(digest, release.sha256(destination / 'release' / name))
        self.assertEqual((destination / 'Dockerfile').read_text(), 'FROM scratch\nCOPY release /release\n')

    def test_dirty_source_or_wrong_ci_head_rejected(self):
        (self.root / 'README.md').write_text('uncommitted change\n')
        with self.assertRaisesRegex(AssertionError, 'clean committed'):
            release.stage(self.root / '.work/dirty')
        self.git('checkout', '--', 'README.md')
        os.environ['GITHUB_SHA'] = '0' * 40
        with self.assertRaisesRegex(AssertionError, 'CI head'):
            release.stage(self.root / '.work/wrong-head')

    def test_outside_or_existing_destination_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'owned .work'):
            release.stage(self.root / 'outside')
        (self.root / '.work/existing').mkdir(parents=True)
        with self.assertRaisesRegex(AssertionError, 'owned .work'):
            release.stage(self.root / '.work/existing')

    def test_incomplete_ui_or_ambiguous_jar_rejected(self):
        (self.root / 'ui/reports/summary.json').write_text(json.dumps({'acceptancePassed': False, 'failures': ['not run']}))
        with self.assertRaisesRegex(AssertionError, 'UI gate'):
            release.stage(self.root / '.work/failed-ui')
        (self.root / 'build/libs/other.jar').write_bytes(b'not-selected')
        with self.assertRaisesRegex(AssertionError, 'Exactly one'):
            release.stage(self.root / '.work/multiple-jars')

    def test_formal_publication_requires_a_completed_successful_source_run(self):
        os.environ['CI'] = 'true'
        with self.assertRaisesRegex(AssertionError, 'completed workflow_run'):
            release.stage(self.root / '.work/not-a-publication-event')
        event_path = self.root / '.work/source-event.json'
        event_path.parent.mkdir(exist_ok=True)
        event = {'id': 12345, 'conclusion': 'failure', 'status': 'completed', 'event': 'push',
                 'head_branch': 'main', 'head_sha': self.commit, 'name': 'AMIS independent checks',
                 'head_repository': {'full_name': 'ai-specs/kestra-plugin-amis-apps'}}
        os.environ['GITHUB_EVENT_NAME'] = 'workflow_run'
        os.environ['GITHUB_EVENT_PATH'] = str(event_path)
        event_path.write_text(json.dumps({'workflow_run': event}))
        with self.assertRaisesRegex(AssertionError, 'completed successfully'):
            release.stage(self.root / '.work/failed-source')
        event['conclusion'] = 'success'
        event_path.write_text(json.dumps({'workflow_run': event}))
        destination = self.root / '.work/verified-source'
        release.stage(destination)
        proof = json.loads((destination / 'release/release-provenance.json').read_text())['sourceCi']
        self.assertEqual(proof['runId'], 12345)
        self.assertEqual(proof['headSha'], self.commit)
        self.assertEqual(proof['conclusion'], 'success')


if __name__ == '__main__':
    unittest.main(verbosity=2)
