import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parent.parent


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def verify_p1(kind, commit, jar_hash):
    report_root = ROOT / 'ui/reports'
    path = report_root / ('p1-' + kind + '.json')
    report = json.loads(path.read_text())
    manifest_path = ROOT / 'contracts/p1-cases.json'
    expected = json.loads(manifest_path.read_text())[kind]
    assert report['schemaVersion'] == 1 and report['passed'], 'P1 report did not pass: ' + kind
    assert report['scope'] == ('test-transport' if kind == 'transport' else 'production-execution'), 'P1 report scope differs'
    assert report['manifestSha256'] == sha256(manifest_path), 'P1 case manifest hash differs'
    assert report['sourceCommit'] == commit, 'P1 source commit differs'
    names = sorted(filter(None, subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')))
    source_files = {name: sha256(ROOT / name) for name in names}
    source_hash = hashlib.sha256(json.dumps(source_files, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    assert report['sourceSha256'] == source_hash, 'P1 source snapshot hash differs'
    if kind == 'production':
        assert report.get('sourceStable') is True, 'P1 production source was not stable'
        for boundary in ['sourceAtStart', 'sourceAtEnd']:
            snapshot = report.get(boundary)
            assert isinstance(snapshot, dict), 'P1 production source boundary missing'
            assert snapshot.get('sourceCommit') == commit and snapshot.get('sourceSha256') == source_hash, 'P1 production source boundary differs'
    cases = report['cases']
    assert sorted(item['id'] for item in cases) == sorted(expected), 'P1 exact case IDs differ'
    assert report['stats'] == {'expected': len(expected), 'passed': len(expected), 'failed': 0, 'skipped': 0, 'notRun': 0}, 'P1 incomplete matrix'
    host = json.loads((ROOT / '.integration/host-provenance.json').read_text())
    assert report['hostImage'] == host['image'] and report['hostExecutableSha256'] == host['sha256'], 'P1 formal host differs'
    valid_jar = report.get('jarSha256') == jar_hash if kind == 'production' else report.get('jarSha256') in (None, jar_hash)
    assert valid_jar, 'P1 runtime jar differs'
    for item in cases:
        assert item['status'] == 'passed' and item['evidence'], 'P1 case lacks passed evidence'
        for entry in item['evidence']:
            target = (report_root / entry['path']).resolve()
            assert target.is_relative_to(report_root.resolve()), 'P1 evidence path escapes reports'
            assert target.is_file() and sha256(target) == entry['sha256'], 'P1 evidence hash differs'
    return report


def stage(destination):
    destination = destination.resolve()
    assert destination.is_relative_to(ROOT / '.work') and not destination.exists(), 'Use a new owned .work release directory'
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True), 'Release requires a clean committed source tree'
    source_ci = None
    if os.environ.get('GITHUB_EVENT_NAME') == 'workflow_run':
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())['workflow_run']
        assert event['conclusion'] == 'success' and event['status'] == 'completed', 'Source CI must have completed successfully'
        assert event['event'] == 'push' and event['head_branch'] == 'main' and event['head_sha'] == commit, 'Source CI head differs'
        assert event['name'] == 'AMIS independent checks' and event['head_repository']['full_name'] == 'ai-specs/kestra-plugin-amis-apps', 'Unexpected source workflow'
        source_ci = {'runId': event['id'], 'headSha': commit, 'status': 'completed', 'conclusion': 'success', 'event': 'push', 'branch': 'main'}
    else:
        assert os.environ.get('CI') != 'true', 'Formal publication requires a completed workflow_run event'
        assert os.environ.get('GITHUB_SHA', commit) == commit, 'Release commit differs from CI head'
    build = ROOT / os.environ.get('AMIS_BUILD_DIR', 'build')
    artifacts = list((build / 'libs').glob('*.jar'))
    assert len(artifacts) == 1, 'Exactly one tested thin jar required'
    summary = json.loads((ROOT / 'ui/reports/summary.json').read_text())
    assert summary['acceptancePassed'] and not summary['failures'], 'Complete independent UI gate required'
    for name in ['unit.xml', 'inputs.json', 'translations.json', 'chromium.json', 'preferences-red.json']:
        assert (ROOT / 'ui/reports' / name).is_file(), 'Missing independent UI evidence: ' + name
    jar_hash = sha256(artifacts[0])
    p1_reports = {kind: verify_p1(kind, commit, jar_hash) for kind in ['transport', 'production']}
    assert p1_reports['transport']['sourceSha256'] == p1_reports['production']['sourceSha256'], 'P1 report sources differ'
    subprocess.check_call(['node', str(ROOT / 'scripts/check-p2-report.mjs'), str(ROOT), jar_hash], cwd=ROOT)
    payload = destination / 'release'
    payload.mkdir(parents=True)
    shutil.copy2(artifacts[0], payload / 'kestra-amis-apps.jar')
    for source, target in [(build / 'reports', 'java-reports'), (build / 'test-results', 'java-results'),
                           (ROOT / 'ui/reports', 'ui-reports')]:
        assert source.is_dir(), source
        shutil.copytree(source, payload / target)
    shutil.copytree(ROOT / 'examples/p1', payload / 'examples/p1')
    shutil.copytree(ROOT / 'examples/p2', payload / 'examples/p2')
    (payload / 'contracts').mkdir()
    shutil.copy2(ROOT / 'contracts/p1-cases.json', payload / 'contracts/p1-cases.json')
    shutil.copy2(ROOT / 'contracts/p2-cases.json', payload / 'contracts/p2-cases.json')
    transport_report = payload / 'ui-reports/p1-transport.json'
    transport = json.loads(transport_report.read_text())
    transport['jarSha256'] = jar_hash
    transport_report.write_text(json.dumps(transport, indent=2) + '\n')
    p2_transport = payload / 'ui-reports/p2-transport.json'
    p2_value = json.loads(p2_transport.read_text())
    p2_value['jarSha256'] = jar_hash
    p2_transport.write_text(json.dumps(p2_value, indent=2) + '\n')
    shutil.copy2(ROOT / '.integration/host-provenance.json', payload / 'host-provenance.json')
    shutil.copy2(ROOT / 'host-contract.properties', payload / 'host-contract.properties')
    archive = payload / 'source.tar'
    with archive.open('wb') as output:
        subprocess.run(['git', 'archive', '--format=tar', commit], cwd=ROOT, stdout=output, check=True)
    with tarfile.open(archive) as source:
        assert all(not entry.issym() and not entry.islnk() and not Path(entry.name).is_absolute()
                   and '..' not in Path(entry.name).parts for entry in source), 'Unsafe source snapshot'
    files = {str(path.relative_to(payload)): sha256(path) for path in sorted(payload.rglob('*')) if path.is_file()}
    provenance = {'schemaVersion': 1, 'stage': 'P2', 'repository': 'ai-specs/kestra-plugin-amis-apps', 'commit': commit,
                  'runId': os.environ.get('GITHUB_RUN_ID'), 'sourceCi': source_ci,
                  'jarSha256': sha256(payload / 'kestra-amis-apps.jar'),
                  'host': json.loads((payload / 'host-provenance.json').read_text()), 'files': files,
                  'scope': 'Independent artifact and reports; root production composition remains a separate gate'}
    (payload / 'release-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    (destination / 'Dockerfile').write_text('FROM scratch\nCOPY release /release\n')
    print(json.dumps({'commit': commit, 'jarSha256': provenance['jarSha256'], 'files': len(files)}))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python3 scripts/package-release.py NEW_OWNED_WORK_DIRECTORY')
    stage(Path(sys.argv[1]))
