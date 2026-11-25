import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('thin', Path(__file__).with_name('verify-thin-jar.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class RuntimeAssetGateTest(unittest.TestCase):
    def archive(self, mutation=None):
        template = b'__AMIS_BASE__ __AMIS_CSRF__ __AMIS_BOOTSTRAP__'
        script = b'export const marker = true;'
        assets = {'index.html': {'sha256': hashlib.sha256(template).hexdigest(), 'contentType': 'text/html; charset=utf-8'},
                  'assets/app-123.js': {'sha256': hashlib.sha256(script).hexdigest(), 'contentType': 'text/javascript; charset=utf-8'}}
        manifest = {'version': 1, 'entry': 'index.html', 'assets': assets}
        entries = {gate.RUNTIME_PREFIX + 'index.html': template, gate.RUNTIME_PREFIX + 'assets/app-123.js': script}
        if mutation == 'wrong-hash':
            assets['assets/app-123.js']['sha256'] = '0' * 64
        elif mutation == 'unsafe-path':
            assets['../secret'] = assets.pop('assets/app-123.js')
            entries[gate.RUNTIME_PREFIX + '../secret'] = entries.pop(gate.RUNTIME_PREFIX + 'assets/app-123.js')
        elif mutation == 'missing-asset':
            entries.pop(gate.RUNTIME_PREFIX + 'assets/app-123.js')
        elif mutation == 'extra-asset':
            entries[gate.RUNTIME_PREFIX + 'assets/unknown.js'] = b'unlisted'
        elif mutation == 'wrong-version':
            manifest['version'] = True
        elif mutation == 'missing-placeholder':
            entries[gate.RUNTIME_PREFIX + 'index.html'] = b'not a bootstrap'
            assets['index.html']['sha256'] = hashlib.sha256(b'not a bootstrap').hexdigest()
        elif mutation == 'external-entry':
            entries[gate.RUNTIME_PREFIX + 'index.html'] = template + b'<script src="https://example.invalid/main.js"></script>'
            assets['index.html']['sha256'] = hashlib.sha256(entries[gate.RUNTIME_PREFIX + 'index.html']).hexdigest()
        encoded = json.dumps(manifest).encode()
        if mutation == 'duplicate-key':
            encoded = encoded.replace(b'"version": 1', b'"version": 1, "version": 1')
        entries[gate.RUNTIME_PREFIX + 'runtime-manifest.json'] = encoded
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        return zipfile.ZipFile(io.BytesIO(output.getvalue()))

    def test_real_manifest_and_asset_bytes_agree(self):
        with self.archive() as archive:
            self.assertEqual(len(gate.verify_runtime(archive)), 3)

    def test_bad_manifest_and_asset_bytes_fail_closed(self):
        for mutation in ['wrong-hash', 'unsafe-path', 'missing-asset', 'extra-asset', 'wrong-version',
                         'missing-placeholder', 'external-entry', 'duplicate-key']:
            with self.subTest(mutation=mutation), self.archive(mutation) as archive:
                with self.assertRaises(ValueError):
                    gate.verify_runtime(archive)


if __name__ == '__main__':
    unittest.main(verbosity=2)
