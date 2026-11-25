import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / '.work' / 'gate-tests'


def rejects(name, command, expected):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    (REPORT / (name + '.log')).write_text(result.stdout + result.stderr)
    if result.returncode == 0:
        raise AssertionError('Gate accepted invalid input: ' + name)
    if expected not in result.stdout + result.stderr:
        raise AssertionError('Gate failed for an unexpected reason: ' + name)


def main():
    REPORT.mkdir(parents=True, exist_ok=True)
    (REPORT / 'result.json').unlink(missing_ok=True)
    provenance_path = ROOT / '.integration' / 'host-provenance.json'
    original = provenance_path.read_bytes()
    try:
        provenance = json.loads(original)
        provenance['sha256'] = '0' * 64
        provenance_path.write_text(json.dumps(provenance))
        rejects('host-hash-mismatch', [sys.executable, 'scripts/host-evidence.py', 'verify'],
                'Host input differs from fixed provenance')
    finally:
        provenance_path.write_bytes(original)
    artifact = ROOT / 'build' / 'libs' / 'kestra-plugin-amis-apps-0.0.1-SNAPSHOT.jar'
    original = artifact.read_bytes()
    for name, entry, expected in (
        ('third-party-class', 'com/example/Forbidden.class', 'Host or third-party classes in thin jar'),
        ('nested-jar', 'lib/forbidden.jar', 'Nested dependency jar'),
        ('unexpected-bean-index', 'META-INF/micronaut/example', 'Foundation must not register runtime beans or services'),
    ):
        try:
            with zipfile.ZipFile(artifact, 'a') as archive:
                archive.writestr(entry, b'negative gate test')
            rejects(name, ['bash', 'scripts/gradle.sh', 'verifyThinJar', '-x', 'jar'], expected)
        finally:
            artifact.write_bytes(original)
    (REPORT / 'result.json').write_text(json.dumps({
        'passed': 4,
        'checks': ['host-hash-mismatch', 'third-party-class', 'nested-jar', 'unexpected-bean-index'],
        'restored': True,
    }, indent=2) + '\n')
    print('4 negative build gates passed; original inputs restored')


if __name__ == '__main__':
    main()
