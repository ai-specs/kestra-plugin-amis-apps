import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class GradleLauncherTest(unittest.TestCase):
    def setUp(self):
        self.area = Path(tempfile.mkdtemp(prefix='amis-gradle-launcher-'))
        self.addCleanup(shutil.rmtree, self.area)
        scripts = self.area / 'scripts'
        scripts.mkdir()
        shutil.copyfile(Path(__file__).with_name('gradle.sh'), scripts / 'gradle.sh')
        self.launcher = scripts / 'gradle.sh'
        self.cached = self.tool('.work/tools/gradle-9.8.0', '9.8.0', 'pinned-cache')
        self.wrong = self.tool('inherited-tool', '8.0', 'wrong-version')
        self.explicit = self.tool('explicit-tool', '9.8.0', 'explicit-tool')
        self.environment = {**os.environ, 'GRADLE_HOME': str(self.wrong)}
        self.environment.pop('AMIS_GRADLE_HOME', None)

    def tool(self, directory, version, marker):
        home = self.area / directory
        executable = home / 'bin' / 'gradle'
        executable.parent.mkdir(parents=True)
        executable.write_text(f'#!/bin/sh\nprintf "Gradle {version}\\n{marker}\\n"\n')
        executable.chmod(0o755)
        return home

    def run_launcher(self, environment):
        return subprocess.run(['bash', str(self.launcher), '--version'], cwd=self.area,
                              env=environment, text=True, capture_output=True)

    def test_inherited_gradle_home_does_not_replace_pinned_version(self):
        result = self.run_launcher(self.environment)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('pinned-cache', result.stdout)
        self.assertNotIn('wrong-version', result.stdout)

    def test_explicit_matching_tool_is_used(self):
        result = self.run_launcher({**self.environment, 'AMIS_GRADLE_HOME': str(self.explicit)})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('explicit-tool', result.stdout)
        self.assertNotIn('pinned-cache', result.stdout)

    def test_explicit_wrong_version_fails_instead_of_silently_switching(self):
        result = self.run_launcher({**self.environment, 'AMIS_GRADLE_HOME': str(self.wrong)})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('AMIS_GRADLE_HOME must provide pinned Gradle version', result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
