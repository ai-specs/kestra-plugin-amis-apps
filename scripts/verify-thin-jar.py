import hashlib
import os
from pathlib import Path
import subprocess
import sys
import zipfile
import re
import json

PREFIX = 'io/kestra/plugin/amis/apps/'
PLUGIN_SERVICE = 'META-INF/services/io.kestra.core.models.Plugin'
PROVIDERS = ['io.kestra.plugin.amis.apps.PageTrigger', 'io.kestra.plugin.amis.apps.ApiTrigger']
INDEX_PREFIX = 'META-INF/micronaut/io.micronaut.core.beans.BeanIntrospectionReference/'
INTROSPECTIONS = {INDEX_PREFIX + 'io.kestra.plugin.amis.apps.$' + name + '$Introspection'
                  for name in ('PageTrigger', 'ApiTrigger')}
BEAN_PREFIX = 'META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/'
BUSINESS_BEANS = {BEAN_PREFIX + 'io.kestra.plugin.amis.apps.runtime.$AmisHttpRouteProvider$Definition'}
RUNTIME_PREFIX = 'META-INF/kestra/amis-apps/runtime/'


def verify_runtime(archive):
    manifest_path = RUNTIME_PREFIX + 'runtime-manifest.json'
    require(manifest_path in archive.namelist(), 'Missing packaged runtime manifest')
    def unique_object(pairs):
        values = {}
        for key, value in pairs:
            require(key not in values, 'Duplicate runtime manifest key')
            values[key] = value
        return values
    require(archive.getinfo(manifest_path).file_size <= 1024 * 1024, 'Packaged runtime manifest exceeds size limit')
    manifest = json.loads(archive.read(manifest_path), object_pairs_hook=unique_object)
    require(set(manifest) == {'version', 'entry', 'assets'} and type(manifest['version']) is int
            and manifest['version'] == 1 and manifest['entry'] == 'index.html'
            and isinstance(manifest['assets'], dict) and 1 <= len(manifest['assets']) <= 2048,
            'Invalid packaged runtime manifest')
    runtime_entries = {name for name in archive.namelist() if name.startswith(RUNTIME_PREFIX) and not name.endswith('/')}
    require(runtime_entries == {manifest_path} | {RUNTIME_PREFIX + name for name in manifest['assets']},
            'Packaged runtime differs from manifest whitelist')
    for name, descriptor in manifest['assets'].items():
        require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_/-]+(?:[.][A-Za-z0-9_-]+)*', name)
                and not name.startswith('/') and not name.endswith('/') and '//' not in name and '/.' not in name
                and not name.startswith('.') and name != 'runtime-manifest.json', 'Unsafe runtime manifest path')
        require(isinstance(descriptor, dict) and set(descriptor) == {'sha256', 'contentType'}
                and isinstance(descriptor['sha256'], str) and re.fullmatch(r'[a-f0-9]{64}', descriptor['sha256']),
                'Invalid runtime asset descriptor')
        require(descriptor['contentType'] in {
            'text/html; charset=utf-8', 'text/javascript; charset=utf-8', 'text/css; charset=utf-8',
            'application/json; charset=utf-8', 'font/woff', 'font/woff2', 'font/ttf', 'font/otf',
            'application/vnd.ms-fontobject', 'font/vnd.ms-fontobject', 'image/svg+xml', 'image/png',
            'image/jpeg', 'image/gif', 'image/webp', 'image/x-icon', 'application/octet-stream'},
            'Invalid runtime asset content type')
        content = archive.read(RUNTIME_PREFIX + name)
        require(len(content) <= 32 * 1024 * 1024 and hashlib.sha256(content).hexdigest() == descriptor['sha256'],
                'Packaged runtime asset hash mismatch')
    require('index.html' in manifest['assets'], 'Missing packaged runtime entry')
    template = archive.read(RUNTIME_PREFIX + 'index.html').decode('utf-8')
    require(all(placeholder in template for placeholder in ['__AMIS_BASE__', '__AMIS_CSRF__', '__AMIS_BOOTSTRAP__']),
            'Incomplete packaged runtime template')
    require(not re.search(r'(?:src|href)=["\'](?:https?:|//|/assets)', template), 'Nonrelative packaged runtime entry')
    return runtime_entries


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def verify(artifact, host, test_classes, reports):
    with zipfile.ZipFile(artifact) as archive:
        entries = archive.namelist()
        require(len(entries) == len(set(entries)), 'Duplicate jar entry')
        classes = [name for name in entries if name.endswith('.class')]
        require(classes, 'Empty jar')
        require(all(name.startswith(PREFIX) for name in classes), 'Host or third-party classes in thin jar')
        require(not any(name.endswith('.jar') for name in entries), 'Nested dependency jar')
        services = {name for name in entries if name.startswith('META-INF/services/') and not name.endswith('/')}
        require(services == {PLUGIN_SERVICE}, 'Unexpected or missing service registration')
        require(archive.read(PLUGIN_SERVICE).decode().splitlines() == PROVIDERS, 'Plugin service must register exactly PageTrigger and ApiTrigger')
        indices = {name for name in entries if name.startswith('META-INF/micronaut/') and not name.endswith('/')}
        require(indices == INTROSPECTIONS | BUSINESS_BEANS, 'Unexpected or missing Micronaut introspection registration')
        runtime_entries = verify_runtime(archive)
        resources = {name for name in entries if not name.endswith('/') and not name.endswith('.class')}
        require(resources == INTROSPECTIONS | BUSINESS_BEANS | runtime_entries | {PLUGIN_SERVICE, 'META-INF/MANIFEST.MF'}, 'Unexpected jar resource')
        for index in indices:
            class_entry = index.removeprefix(INDEX_PREFIX).removeprefix(BEAN_PREFIX).replace('.', '/') + '.class'
            require(class_entry in entries, 'Introspection index has no class')
    reports.mkdir(parents=True, exist_ok=True)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / 'java') if os.environ.get('JAVA_HOME') else 'java'
    classpath = os.pathsep.join([str(host), str(artifact), test_classes])
    result = subprocess.run([java, '-cp', classpath, 'io.kestra.plugin.amis.apps.testing.PackagedTriggerMain',
                             'metadata', str(reports / 'thin-jar-metadata'), str(artifact)], text=True, capture_output=True)
    (reports / 'thin-jar-metadata.log').write_text(result.stdout + result.stderr)
    require(result.returncode == 0, 'Invalid introspection metadata or plugin discovery; see thin-jar-metadata.log')
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    report = f'artifact={artifact.name}\nsha256={digest}\nclasses=amis-package-only\npluginProviders=2\nintrospections=2\nbusinessBeans=1\nruntimeResources={len(runtime_entries)}\n'
    (reports / 'thin-jar.txt').write_text(report)
    print(report)


if __name__ == '__main__':
    verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], Path(sys.argv[4]))
