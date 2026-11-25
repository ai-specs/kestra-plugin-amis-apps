import hashlib
import os
from pathlib import Path
import subprocess
import sys
import zipfile

PREFIX = 'io/kestra/plugin/amis/apps/'
PLUGIN_SERVICE = 'META-INF/services/io.kestra.core.models.Plugin'
PROVIDERS = ['io.kestra.plugin.amis.apps.PageTrigger', 'io.kestra.plugin.amis.apps.ApiTrigger']
INDEX_PREFIX = 'META-INF/micronaut/io.micronaut.core.beans.BeanIntrospectionReference/'
INTROSPECTIONS = {INDEX_PREFIX + 'io.kestra.plugin.amis.apps.$' + name + '$Introspection'
                  for name in ('PageTrigger', 'ApiTrigger')}


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
        require(indices == INTROSPECTIONS, 'Unexpected or missing Micronaut introspection registration')
        resources = {name for name in entries if not name.endswith('/') and not name.endswith('.class')}
        require(resources == INTROSPECTIONS | {PLUGIN_SERVICE, 'META-INF/MANIFEST.MF'}, 'Unexpected jar resource')
        for index in indices:
            class_entry = index.removeprefix(INDEX_PREFIX).replace('.', '/') + '.class'
            require(class_entry in entries, 'Introspection index has no class')
    reports.mkdir(parents=True, exist_ok=True)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / 'java') if os.environ.get('JAVA_HOME') else 'java'
    classpath = os.pathsep.join([str(host), str(artifact), test_classes])
    result = subprocess.run([java, '-cp', classpath, 'io.kestra.plugin.amis.apps.testing.PackagedTriggerMain',
                             'metadata', str(reports / 'thin-jar-metadata'), str(artifact)], text=True, capture_output=True)
    (reports / 'thin-jar-metadata.log').write_text(result.stdout + result.stderr)
    require(result.returncode == 0, 'Invalid introspection metadata or plugin discovery; see thin-jar-metadata.log')
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    report = f'artifact={artifact.name}\nsha256={digest}\nclasses=amis-package-only\npluginProviders=2\nintrospections=2\nbusinessBeans=none\n'
    (reports / 'thin-jar.txt').write_text(report)
    print(report)


if __name__ == '__main__':
    verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], Path(sys.argv[4]))
