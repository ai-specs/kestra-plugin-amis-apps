from copy import deepcopy
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
NAMESPACE = {'v': 'https://schema.gradle.org/dependency-verification'}


def validate(document, version):
    setting = document.find('v:configuration/v:verify-metadata', NAMESPACE)
    if setting is None or setting.text != 'true':
        raise ValueError('Metadata verification must remain enabled')
    components = document.findall(f'v:components/v:component[@group="org.junit"][@name="junit-bom"][@version="{version}"]', NAMESPACE)
    if len(components) != 1:
        raise ValueError('Locked JUnit BOM verification component required')
    for extension in ['pom', 'module']:
        artifacts = components[0].findall(f'v:artifact[@name="junit-bom-{version}.{extension}"]', NAMESPACE)
        if len(artifacts) != 1 or not any(re.fullmatch('[a-f0-9]{64}', item.get('value', ''))
                                          for item in artifacts[0].findall('v:sha256', NAMESPACE)):
            raise ValueError('Locked platform requires both POM and module SHA-256 checksums')


class DependencyMetadataTest(unittest.TestCase):
    def setUp(self):
        versions = re.findall(r'^org\.junit:junit-bom:([^=\s]+)=', (ROOT / 'gradle.lockfile').read_text(), re.MULTILINE)
        self.assertEqual(len(versions), 1)
        self.version = versions[0]
        self.document = ET.parse(ROOT / 'gradle/verification-metadata.xml').getroot()

    def test_locked_platform_metadata_is_complete(self):
        validate(self.document, self.version)

    def test_missing_pom_is_rejected_even_with_warm_dependency_cache(self):
        document = deepcopy(self.document)
        component = document.find(f'v:components/v:component[@name="junit-bom"][@version="{self.version}"]', NAMESPACE)
        component.remove(component.find(f'v:artifact[@name="junit-bom-{self.version}.pom"]', NAMESPACE))
        with self.assertRaises(ValueError):
            validate(document, self.version)

    def test_disabled_metadata_verification_is_rejected(self):
        document = deepcopy(self.document)
        document.find('v:configuration/v:verify-metadata', NAMESPACE).text = 'false'
        with self.assertRaises(ValueError):
            validate(document, self.version)

    def test_wrong_platform_version_is_rejected(self):
        with self.assertRaises(ValueError):
            validate(self.document, '0.0.0')


if __name__ == '__main__':
    unittest.main(verbosity=2)
