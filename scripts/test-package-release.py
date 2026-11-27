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
        (self.root / 'contracts').mkdir()
        (self.root / 'contracts/p1-cases.json').write_text(json.dumps({'schemaVersion': 1, 'transport': ['P1 transport'], 'production': ['P1 production']}))
        (self.root / 'examples/p1').mkdir(parents=True)
        (self.root / 'examples/p1/scenarios.json').write_text('{"schemaVersion":1,"scenarios":[]}')
        # Explicit synthetic P2 package data: never production acceptance evidence.
        (self.root / 'examples/p2').mkdir(parents=True)
        (self.root / 'examples/p2/scenarios.json').write_text('{"schemaVersion":1,"synthetic":true,"scenarios":[]}')
        (self.root / 'contracts/p2-cases.json').write_text(json.dumps({'schemaVersion':1,'stage':'P2','transport':['synthetic transport'],'production':['synthetic production'],'productionPlan':{'synthetic production':{'namespace':'synthetic.ns','flowId':'synthetic-flow','apis':{'alpha':{'triggerId':'invokeAlpha','resultOutput':'alphaResponse','expectedNewExecutions':0}}}}}))
        (self.root / 'scripts').mkdir()
        for name in ['p2-report.mjs','p2-diagnostics.mjs','p2-collector-binding.mjs','p2-case-facts.mjs','p2-abandoned.mjs','check-p2-report.mjs']:
            (self.root / 'scripts' / name).write_bytes((Path(__file__).parent / name).read_bytes())
        self.git('add', '.')
        self.git('commit', '-qm', 'Test packaging source')
        self.commit = self.git('rev-parse', 'HEAD')
        for path in ['build/libs', 'build/reports', 'build/test-results', 'ui/reports', '.integration']:
            (self.root / path).mkdir(parents=True)
        (self.root / 'build/libs/test.jar').write_bytes(b'tested-jar-placeholder')
        (self.root / 'build/reports/unit.txt').write_text('isolated test data\n')
        (self.root / 'build/test-results/results.xml').write_text('<testsuite tests="1"/>')
        (self.root / 'ui/reports/summary.json').write_text(json.dumps({'acceptancePassed': True, 'failures': []}))
        for name in ['unit.xml', 'inputs.json', 'translations.json', 'chromium.json', 'preferences-red.json']:
            (self.root / 'ui/reports' / name).write_text('isolated packaging test evidence\n')
        (self.root / '.integration/host-provenance.json').write_text(json.dumps({'image': 'formal-test-input', 'sha256': 'b' * 64}))

        for kind in ['transport', 'production']:
            evidence = self.root / 'ui/reports' / (kind + '-evidence.json')
            evidence.write_text('public test evidence')
            report = {'schemaVersion': 1, 'scope': 'test-transport' if kind == 'transport' else 'production-execution',
                      'manifestSha256': release.sha256(self.root / 'contracts/p1-cases.json'),
                      'sourceCommit': self.commit, 'sourceSha256': self.source_hash(),
                      'hostImage': 'formal-test-input', 'hostExecutableSha256': 'b' * 64,
                      'jarSha256': release.sha256(self.root / 'build/libs/test.jar') if kind == 'production' else None,
                      'passed': True, 'stats': {'expected': 1, 'passed': 1, 'failed': 0, 'skipped': 0, 'notRun': 0},
                      'cases': [{'id': 'P1 ' + kind, 'status': 'passed', 'evidence': [{'path': evidence.name, 'sha256': release.sha256(evidence)}]}]}
            if kind == 'production':
                boundary = {'sourceCommit': self.commit, 'sourceSha256': self.source_hash()}
                report.update(sourceStable=True, sourceAtStart=boundary, sourceAtEnd=boundary)
            (self.root / 'ui/reports' / ('p1-' + kind + '.json')).write_text(json.dumps(report))

        for kind in ['transport', 'production']:
            name = 'synthetic ' + kind
            value = {'schemaVersion':1,'scope':'production-execution','caseId':name,'synthetic':False,'caller':'synthetic-user','identity':{'subject':'synthetic-user','signatureVerified':True},'consoleErrors':[],'diagnostics':{'errors':[],'requests':[],'responses':[]},'diagnosticClassifications':[],'executionIds':[],'collectorEvents':[], 'flows':[{'namespace':'synthetic.ns','expectedFlowId':'synthetic-flow','executionIds':[],'apis':[{'apiId':'alpha','triggerId':'invokeAlpha','resultOutput':'alphaResponse','caller':'synthetic-user','expectedNewExecutions':0,'beforeExecutionIds':[],'afterExecutionIds':[],'executionIds':[],'executions':[],'requests':[],'responses':[]}]}]}
            evidence = self.root / 'ui/reports' / ('p2-' + kind + '-evidence.json')
            collector = self.root / ('ui/reports/synthetic-' + kind + '-collector.json')
            collector.write_text(json.dumps({'schemaVersion':1,'caseId':name,'source':'chromium-cdp','integrity':[],'events':[]}))
            value['immutableDiagnostics'] = {'path':collector.name,'sha256':release.sha256(collector)}
            evidence.write_text(json.dumps(value))
            report = {'schemaVersion':1,'stage':'P2','scope':'test-transport' if kind == 'transport' else 'production-execution','manifestSha256':release.sha256(self.root / 'contracts/p2-cases.json'),'sourceCommit':self.commit,'sourceSha256':self.source_hash(),'hostImage':'formal-test-input','hostExecutableSha256':'b'*64,'jarSha256':release.sha256(self.root / 'build/libs/test.jar') if kind == 'production' else None,'passed':True,'stats':{'expected':1,'passed':1,'failed':0,'skipped':0,'notRun':0},'cases':[{'id':name,'status':'passed','evidence':[{'path':evidence.name,'sha256':release.sha256(evidence)}]}]}
            if kind == 'production':
                boundary = {'sourceCommit':self.commit,'sourceSha256':self.source_hash()}
                report.update(sourceStable=True,sourceAtStart=boundary,sourceAtEnd=boundary)
            (self.root / 'ui/reports' / ('p2-' + kind + '.json')).write_text(json.dumps(report))

    def source_hash(self):
        import hashlib
        names = sorted(filter(None, subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=self.root).decode().split('\0')))
        files = {name: release.sha256(self.root / name) for name in names}
        return hashlib.sha256(json.dumps(files, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()

    def git(self, *arguments):
        return subprocess.check_output(['git', *arguments], cwd=self.root, text=True).strip()

    def test_p2_missing_report_or_manifest_binding_is_rejected(self):
        path = self.root / 'ui/reports/p2-production.json'
        original = path.read_text()
        path.unlink()
        with self.assertRaises(subprocess.CalledProcessError):
            release.stage(self.root / '.work/p2-missing')
        report = json.loads(original)
        report['manifestSha256'] = '0' * 64
        path.write_text(json.dumps(report))
        with self.assertRaises(subprocess.CalledProcessError):
            release.stage(self.root / '.work/p2-manifest')

    def test_p2_semantic_evidence_tamper_and_source_drift_rejected(self):
        path = self.root / 'ui/reports/p2-production.json'
        report = json.loads(path.read_text())
        report['sourceAtEnd']['sourceSha256'] = '0' * 64
        path.write_text(json.dumps(report))
        with self.assertRaises(subprocess.CalledProcessError):
            release.stage(self.root / '.work/p2-drift')

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


    def test_p1_missing_or_incomplete_production_report_rejected(self):
        path = self.root / 'ui/reports/p1-production.json'
        report = json.loads(path.read_text())
        report['stats']['notRun'] = 1
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(AssertionError, 'incomplete matrix'):
            release.stage(self.root / '.work/p1-incomplete')
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            release.stage(self.root / '.work/p1-missing')

    def test_p1_case_identity_and_actual_execution_jar_rejected(self):
        path = self.root / 'ui/reports/p1-production.json'
        report = json.loads(path.read_text())
        original_id = report['cases'][0]['id']
        report['cases'][0]['id'] = 'invented passing test'
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(AssertionError, 'exact case IDs'):
            release.stage(self.root / '.work/p1-wrong-id')
        report['cases'][0]['id'] = original_id
        report['jarSha256'] = '0' * 64
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(AssertionError, 'runtime jar'):
            release.stage(self.root / '.work/p1-wrong-jar')

    def test_p1_evidence_tampering_or_path_escape_rejected(self):
        path = self.root / 'ui/reports/p1-production.json'
        report = json.loads(path.read_text())
        report['cases'][0]['evidence'][0]['sha256'] = '0' * 64
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(AssertionError, 'evidence hash'):
            release.stage(self.root / '.work/p1-tampered')
        report['cases'][0]['evidence'][0]['path'] = '../../README.md'
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(AssertionError, 'path escapes'):
            release.stage(self.root / '.work/p1-escape')


    def test_production_source_freeze_fields_are_required_and_exact(self):
        path = self.root / 'ui/reports/p1-production.json'
        original = json.loads(path.read_text())
        for index, change in enumerate([
            {'sourceStable': False}, {'sourceStable': None}, {'sourceAtStart': None},
            {'sourceAtEnd': {'sourceCommit': self.commit, 'sourceSha256': '0' * 64}},
            {'sourceAtStart': {'sourceCommit': '0' * 40, 'sourceSha256': self.source_hash()}}
        ]):
            path.write_text(json.dumps({**original, **change}))
            with self.assertRaisesRegex(AssertionError, 'source (was not stable|boundary)'):
                release.stage(self.root / ('.work/p1-source-' + str(index)))


if __name__ == '__main__':
    unittest.main(verbosity=2)
