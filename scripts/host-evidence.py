import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
IMAGE = (ROOT / 'host-contract.properties').read_text().strip().removeprefix('overlay.image=')
TYPES = (
    'io.kestra.core.models.flows.Flow',
    'io.kestra.core.models.executions.Execution',
    'io.kestra.webserver.rbac.TypedAuthorization',
    'io.kestra.webserver.oidc.TrustedIdentityV2',
)


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def image_label_evidence(inspection):
    labels = dict((inspection.get('Config') or {}).get('Labels') or {})
    return {
        'imageLabelRevision': labels.get('org.opencontainers.image.revision'),
        'imageLabelSource': labels.get('org.opencontainers.image.source'),
        'imageLabelTitle': labels.get('org.opencontainers.image.title'),
        'imageLabelVersion': labels.get('org.opencontainers.image.version'),
        'imageLabelCreated': labels.get('org.opencontainers.image.created'),
        'imageLabels': labels,
        'imageLabelScope': 'Raw image configuration labels; may be inherited from the base image; not overlay source commit evidence',
        'overlaySourceProvenance': {
            'status': 'not-verified-by-this-script',
            'requiredEvidence': 'Successful GitHub CI head and promotion/attestation chain binding the overlay source commit to the formal image digest',
        },
    }


def prepare(area):
    artifact = area / 'host-webserver.jar'
    inspection = json.loads((area / 'image-inspect.json').read_text())
    if IMAGE not in inspection['RepoDigests']:
        raise ValueError('Pulled image digest does not match the fixed input')
    with zipfile.ZipFile(artifact) as archive:
        entries = archive.namelist()
        for public_type in TYPES:
            if entries.count(public_type.replace('.', '/') + '.class') != 1:
                raise ValueError('Missing or duplicated published type: ' + public_type)
        jackson = {}
        for component in ('jackson-databind', 'jackson-core', 'jackson-annotations'):
            properties = archive.read('META-INF/maven/com.fasterxml.jackson.core/' + component + '/pom.properties').decode()
            versions = [line.removeprefix('version=') for line in properties.splitlines() if line.startswith('version=')]
            if len(versions) != 1:
                raise ValueError('Cannot determine published Jackson version: ' + component)
            jackson[component] = versions[0]
    signatures = []
    for public_type in TYPES:
        signatures.append(subprocess.check_output(
            ['javap', '-public', '-classpath', str(artifact), public_type], text=True))
    signature_file = area / 'host-public-types.txt'
    signature_file.write_text('\n'.join(signatures))
    validation_file = area / 'host-flow-validation.txt'
    validation_file.write_text(subprocess.check_output(
        ['javap', '-p', '-v', '-classpath', str(artifact), 'io.kestra.core.models.flows.AbstractFlow'], text=True))
    provenance = {
        'image': IMAGE,
        'imageId': inspection['Id'],
        'platform': inspection['Os'] + '/' + inspection['Architecture'],
        **image_label_evidence(inspection),
        'artifactPath': '/app/kestra',
        'sha256': sha256(artifact),
        'publicTypes': list(TYPES),
        'publicTypesSha256': sha256(signature_file),
        'flowValidationSha256': sha256(validation_file),
        'jacksonVersions': jackson,
        'scope': 'published type inventory only; no AMIS entry SPI acceptance or CI attestation',
    }
    (area / 'host-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')


def verify(area=None):
    area = ROOT / '.integration' if area is None else area
    provenance = json.loads((area / 'host-provenance.json').read_text())
    if provenance['image'] != IMAGE or provenance['sha256'] != sha256(area / 'host-webserver.jar'):
        raise ValueError('Host input differs from fixed provenance')
    if provenance['publicTypesSha256'] != sha256(area / 'host-public-types.txt'):
        raise ValueError('Host public type inventory differs from provenance')
    if provenance['flowValidationSha256'] != sha256(area / 'host-flow-validation.txt'):
        raise ValueError('Host Flow validation inventory differs from provenance')
    print('Fixed host digest and artifact SHA-256 verified')


if __name__ == '__main__':
    if len(sys.argv) in (2, 3) and sys.argv[1] == 'verify':
        verify(None if len(sys.argv) == 2 else Path(sys.argv[2]))
    elif len(sys.argv) == 3 and sys.argv[1] == 'prepare':
        prepare(Path(sys.argv[2]))
    else:
        raise SystemExit('Usage: host-evidence.py verify | prepare EXTRACT_DIRECTORY')
