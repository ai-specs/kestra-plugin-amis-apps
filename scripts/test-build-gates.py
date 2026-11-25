import json
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / os.environ.get('AMIS_BUILD_DIR', 'build')
REPORT = BUILD / 'reports' / 'gate-tests' if os.environ.get('AMIS_BUILD_DIR') else ROOT / '.work' / 'gate-tests'


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
    fixture = REPORT / 'host-hash-fixture'
    fixture.mkdir(exist_ok=True)
    provenance = json.loads((ROOT / '.integration' / 'host-provenance.json').read_bytes())
    provenance['sha256'] = '0' * 64
    (fixture / 'host-provenance.json').write_text(json.dumps(provenance))
    host_link = fixture / 'host-webserver.jar'
    if not host_link.exists():
        host_link.symlink_to(ROOT / '.integration' / 'host-webserver.jar')
    rejects('host-hash-mismatch', [sys.executable, 'scripts/host-evidence.py', 'verify', str(fixture)],
            'Host input differs from fixed provenance')
    artifact = BUILD / 'libs' / 'kestra-plugin-amis-apps-0.0.1-SNAPSHOT.jar'
    original = artifact.read_bytes()
    for name, entry, expected in (
        ('third-party-class', 'com/example/Forbidden.class', 'Host or third-party classes in thin jar'),
        ('nested-jar', 'lib/forbidden.jar', 'Nested dependency jar'),
        ('unexpected-bean-index', 'META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/example',
         'Unexpected or missing Micronaut introspection registration'),
        ('unexpected-service', 'META-INF/services/example', 'Unexpected or missing service registration'),
        ('unexpected-resource', 'unexpected-resource.txt', 'Unexpected jar resource'),
    ):
        try:
            with zipfile.ZipFile(artifact, 'a') as archive:
                archive.writestr(entry, b'negative gate test')
            rejects(name, ['bash', 'scripts/gradle.sh', 'verifyThinJar', '-x', 'jar'], expected)
        finally:
            artifact.write_bytes(original)
    for name, entry, replacement, expected in (
        ('missing-plugin-service', 'META-INF/services/io.kestra.core.models.Plugin', None, 'Unexpected or missing service registration'),
        ('extra-plugin-provider', 'META-INF/services/io.kestra.core.models.Plugin',
         b'io.kestra.plugin.amis.apps.PageTrigger\nio.kestra.plugin.amis.apps.ApiTrigger\ncom.example.Forbidden\n',
         'Plugin service must register exactly PageTrigger and ApiTrigger'),
        ('missing-introspection', 'META-INF/micronaut/io.micronaut.core.beans.BeanIntrospectionReference/io.kestra.plugin.amis.apps.$PageTrigger$Introspection',
         None, 'Unexpected or missing Micronaut introspection registration'),
        ('damaged-introspection', 'io/kestra/plugin/amis/apps/$PageTrigger$Introspection.class', b'not a valid class',
         'Invalid introspection metadata or plugin discovery'),
    ):
        try:
            with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(artifact, 'w') as target:
                for item in source.infolist():
                    if item.filename == entry:
                        if replacement is not None:
                            target.writestr(item, replacement)
                    else:
                        target.writestr(item, source.read(item.filename))
            rejects(name, ['bash', 'scripts/gradle.sh', 'verifyThinJar', '-x', 'jar'], expected)
        finally:
            artifact.write_bytes(original)
    shutil.rmtree(fixture)
    (REPORT / 'result.json').write_text(json.dumps({
        'passed': 10,
        'checks': ['host-hash-mismatch', 'third-party-class', 'nested-jar', 'unexpected-bean-index', 'unexpected-service', 'unexpected-resource',
                   'missing-plugin-service', 'extra-plugin-provider', 'missing-introspection', 'damaged-introspection'],
        'restored': True,
    }, indent=2) + '\n')
    print('10 negative build gates passed; original artifact restored; formal host input unchanged')


if __name__ == '__main__':
    main()
