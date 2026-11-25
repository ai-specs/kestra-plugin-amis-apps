import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('host_evidence', Path(__file__).with_name('host-evidence.py'))
HOST_EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST_EVIDENCE)


class ImageLabelEvidenceTest(unittest.TestCase):
    def test_raw_labels_have_explicit_names_without_an_overlay_commit_claim(self):
        labels = {
            'org.opencontainers.image.revision': 'base-revision',
            'org.opencontainers.image.source': 'upstream-source',
            'org.opencontainers.image.title': 'kestra-base',
            'org.opencontainers.image.version': 'nightly',
            'org.opencontainers.image.created': '2026-10-12T02:33:19.892Z',
        }
        report = HOST_EVIDENCE.image_label_evidence({'Config': {'Labels': labels}})
        self.assertEqual(report['imageLabels'], labels)
        for suffix in ['Revision', 'Source', 'Title', 'Version', 'Created']:
            self.assertEqual(report['imageLabel' + suffix], labels['org.opencontainers.image.' + suffix.lower()])
        self.assertNotIn('revision', report)
        self.assertNotIn('overlayCommit', report)
        self.assertEqual(report['overlaySourceProvenance']['status'], 'not-verified-by-this-script')

    def test_missing_and_null_labels_do_not_invent_source_evidence(self):
        for inspection in [{}, {'Config': None}, {'Config': {'Labels': None}}]:
            report = HOST_EVIDENCE.image_label_evidence(inspection)
            self.assertIsNone(report['imageLabelRevision'])
            self.assertIsNone(report['imageLabelSource'])
            self.assertEqual(report['imageLabels'], {})

    def test_even_overlay_named_labels_require_independent_ci_and_promotion_evidence(self):
        report = HOST_EVIDENCE.image_label_evidence({'Config': {'Labels': {
            'org.opencontainers.image.source': 'overlay-source',
            'org.opencontainers.image.revision': 'candidate-revision',
        }}})
        self.assertEqual(report['overlaySourceProvenance']['status'], 'not-verified-by-this-script')
        self.assertIn('GitHub CI head', report['overlaySourceProvenance']['requiredEvidence'])
        self.assertIn('promotion/attestation', report['overlaySourceProvenance']['requiredEvidence'])

    def test_evidence_does_not_mutate_the_original_label_map(self):
        labels = {'org.opencontainers.image.revision': 'base-revision'}
        report = HOST_EVIDENCE.image_label_evidence({'Config': {'Labels': labels}})
        report['imageLabels']['org.opencontainers.image.revision'] = 'changed-copy'
        self.assertEqual(labels['org.opencontainers.image.revision'], 'base-revision')


if __name__ == '__main__':
    unittest.main(verbosity=2)
