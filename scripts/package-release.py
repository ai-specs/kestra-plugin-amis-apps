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
    payload = destination / 'release'
    payload.mkdir(parents=True)
    shutil.copy2(artifacts[0], payload / 'kestra-amis-apps.jar')
    for source, target in [(build / 'reports', 'java-reports'), (build / 'test-results', 'java-results'),
                           (ROOT / 'ui/reports', 'ui-reports')]:
        assert source.is_dir(), source
        shutil.copytree(source, payload / target)
    shutil.copy2(ROOT / '.integration/host-provenance.json', payload / 'host-provenance.json')
    shutil.copy2(ROOT / 'host-contract.properties', payload / 'host-contract.properties')
    archive = payload / 'source.tar'
    with archive.open('wb') as output:
        subprocess.run(['git', 'archive', '--format=tar', commit], cwd=ROOT, stdout=output, check=True)
    with tarfile.open(archive) as source:
        assert all(not entry.issym() and not entry.islnk() and not Path(entry.name).is_absolute()
                   and '..' not in Path(entry.name).parts for entry in source), 'Unsafe source snapshot'
    files = {str(path.relative_to(payload)): sha256(path) for path in sorted(payload.rglob('*')) if path.is_file()}
    provenance = {'schemaVersion': 1, 'repository': 'ai-specs/kestra-plugin-amis-apps', 'commit': commit,
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
