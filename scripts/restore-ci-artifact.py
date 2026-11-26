"""Restore generated CI evidence without overwriting any committed source."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

GENERATED_TREES = ('build/libs', 'build/reports', 'build/test-results', 'ui/reports')
GENERATED_FILES = ('.integration/host-provenance.json', '.integration/host-public-types.txt', '.integration/host-flow-validation.txt')
# Uploaded diagnostics are validated as regular paths but are not packaging inputs.
DIAGNOSTICS = ('ui/test-results', 'ui/test-results-preferences-red', 'ui/test-results-p1', '.work/gate-tests', '.work/host-evidence-tests')
SOURCE_TREES = ('examples/p1',)
SOURCE_FILES = ('contracts/p1-cases.json',)


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def inside(name, roots):
    return any(name == root or name.startswith(root + '/') for root in roots)


def inventory(directory):
    assert directory.is_dir() and not directory.is_symlink(), 'Artifact root must be a regular directory'
    files = {}
    allowed = GENERATED_TREES + GENERATED_FILES + DIAGNOSTICS + SOURCE_TREES + SOURCE_FILES
    for path in directory.rglob('*'):
        name = path.relative_to(directory).as_posix()
        assert not path.is_symlink(), 'Symlink in artifact: ' + name
        assert path.resolve().is_relative_to(directory.resolve()), 'Artifact path escapes root'
        assert inside(name, allowed) or any(root.startswith(name + '/') for root in allowed), 'Unexpected artifact path: ' + name
        if path.is_file():
            assert inside(name, GENERATED_TREES + DIAGNOSTICS + SOURCE_TREES) or name in GENERATED_FILES + SOURCE_FILES, 'Unexpected artifact file: ' + name
            files[name] = path
        else:
            assert path.is_dir(), 'Non-regular artifact entry: ' + name
    return files


def restore(target, artifact):
    for path in (target, artifact):
        absolute = Path(os.path.abspath(path))
        assert absolute == absolute.resolve(), 'Root or ancestor symlink rejected'
    target = target.resolve()
    # Reject a root symlink before resolving it.
    assert not artifact.is_symlink(), 'Artifact root symlink rejected'
    artifact = artifact.resolve()
    assert target.is_dir() and target != artifact, 'Distinct target checkout required'
    tracked = set(filter(None, subprocess.check_output(['git', 'ls-files', '-z'], cwd=target).decode().split('\0')))
    files = inventory(artifact)
    for name, path in files.items():
        if inside(name, SOURCE_TREES) or name in SOURCE_FILES:
            destination = target / name
            assert destination.resolve().is_relative_to(target), 'Source target escapes checkout: ' + name
            assert destination.absolute() == destination.resolve(), 'Source parent symlink rejected: ' + name
            assert name in tracked and destination.is_file() and not destination.is_symlink(), 'Artifact source absent from fixed checkout: ' + name
            assert digest(path) == digest(destination), 'Artifact source content differs: ' + name
    for name in GENERATED_TREES + GENERATED_FILES:
        destination = target / name
        assert not destination.is_symlink(), 'Target symlink rejected: ' + name
        assert destination.resolve().is_relative_to(target), 'Target path escapes checkout'
        assert not destination.exists() or destination.is_dir() and not any(destination.iterdir()), 'Generated target is not empty: ' + name
    for name in GENERATED_TREES:
        assert any(path.startswith(name + '/') for path in files), 'Missing generated tree: ' + name
    for name in GENERATED_FILES:
        assert name in files, 'Missing host evidence: ' + name
    host = json.loads(files['.integration/host-provenance.json'].read_text())
    properties = dict(line.split('=', 1) for line in (target / 'host-contract.properties').read_text().splitlines() if line and not line.startswith('#'))
    assert host['image'] == properties['overlay.image'], 'Unexpected formal host input'
    assert len(host['sha256']) == 64 and all(c in '0123456789abcdef' for c in host['sha256']), 'Invalid executable hash'
    for name, field in [('.integration/host-public-types.txt', 'publicTypesSha256'), ('.integration/host-flow-validation.txt', 'flowValidationSha256')]:
        assert digest(files[name]) == host[field], 'Host evidence hash differs: ' + name
    restore_files = {name: path for name, path in files.items() if inside(name, GENERATED_TREES) or name in GENERATED_FILES}
    for name in restore_files:
        assert name not in tracked, 'Generated artifact collides with tracked source: ' + name
    copied = {}
    for name, path in restore_files.items():
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        assert digest(destination) == digest(path), 'Restored bytes differ'
        copied[name] = digest(destination)
    return {'schemaVersion': 1, 'restored': copied, 'sourceFilesVerified': sorted(name for name in files if inside(name, SOURCE_TREES) or name in SOURCE_FILES), 'hostImage': host['image']}


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: python3 restore-ci-artifact.py TARGET_CHECKOUT ARTIFACT_DIRECTORY')
    print(json.dumps(restore(Path(sys.argv[1]), Path(sys.argv[2])), indent=2))
