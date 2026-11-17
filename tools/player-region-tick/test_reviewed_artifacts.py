#!/usr/bin/env python3
"""Artifact-gate controls, independent of expensive game-server corpora."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import reviewed_artifacts as gate


class ReviewedArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / 'interaction-run'
        self.run.mkdir()
        self.version = 'isolated-artifact-gate-test'
        self.manifest = {
            'schema': 1, 'id': 'isolated-test', 'version': self.version,
            'contract': 'unknown-extension-whole-call-serial',
            'review': {'kind': 'parent-agent-source-and-artifact-review'},
            'installerSha256': '1' * 64, 'oracleSha256': '2' * 64,
            'installedJars': {k: hashlib.sha256(k.encode()).hexdigest() for k in ['server', 'universal']}}
        self.path = self.root / 'reviewed.json'
        self.path.write_text(json.dumps(self.manifest))
        digest = hashlib.sha256(self.path.read_bytes()).hexdigest()
        self.registry = patch.dict(gate.REVIEWED_MANIFESTS, {'isolated-test': digest})
        self.registry.start()
        self.addCleanup(self.registry.stop)
        (self.root / 'artifact-manifest.json').write_bytes(self.path.read_bytes())
        self.install = {'artifactManifestSha256': digest, 'version': self.version,
                        'installerSha256': '1' * 64, 'oracleSha256': '2' * 64,
                        'playerPlanningRequested': True, 'persistent': False}
        (self.root / 'mods').mkdir()
        (self.root / 'mods/forgia-player-oracle-1.0.jar').touch()
        for kind in ['server', 'universal']:
            jar = self.root / f'libraries/net/neoforged/neoforge/{self.version}/neoforge-{self.version}-{kind}.jar'
            jar.parent.mkdir(parents=True, exist_ok=True)
            jar.write_text(kind)
        live = {'requested': True, 'persistent': False, 'activeBatches': 0, 'stateClaims': 0,
                'network': {'pending': 0, 'readers': 0, 'terminals': 0, 'frozen': False},
                'phases': {'owners': 2, 'pending': 0, 'running': 0,
                           'phases': {p: {'worker': 0, 'failed': 0} for p in ['ENTITY', 'CONNECTION']}},
                'players': [{}, {}], 'refusals': {p + ':UNKNOWN_MOD': 1 for p in ['ENTITY', 'CONNECTION']}}
        final = copy.deepcopy(live)
        final['players'] = []
        final['phases']['owners'] = 0
        self.states = [live, final]
        self.write_commands()

    def write_commands(self):
        (self.run / 'commands.ndjson').write_text(''.join(json.dumps({
            'command': 'neoforge regionthreading status players', 'response': json.dumps(state)}) + '\n' for state in self.states))

    def verify(self):
        return gate.verify_installation(self.root, self.run, self.install, self.path)

    def test_explicit_reviewed_artifact_and_unknown_mod_fallback_pass(self):
        self.assertEqual('isolated-test', self.verify()['id'])

    def test_unknown_manifest_and_edited_manifest_fail_closed(self):
        for change in ['unknown-id', 'altered-byte']:
            with self.subTest(change=change):
                data = dict(self.manifest)
                if change == 'unknown-id': data['id'] = change
                else: data['installerSha256'] = '9' * 64
                self.path.write_text(json.dumps(data))
                with self.assertRaisesRegex(AssertionError, 'unreviewed or altered'):
                    gate.read_manifest(self.path)

    def test_installed_core_bytes_cannot_be_forged_by_receipt(self):
        jar = self.root / f'libraries/net/neoforged/neoforge/{self.version}/neoforge-{self.version}-server.jar'
        jar.write_text('wrong core')
        with self.assertRaisesRegex(AssertionError, 'actual reviewed core mismatch'):
            self.verify()

    def test_archive_digest_version_or_extra_mod_mismatch_is_refused(self):
        original = copy.deepcopy(self.install)
        for key in ['artifactManifestSha256', 'version', 'installerSha256', 'oracleSha256']:
            with self.subTest(key=key):
                self.install = dict(original, **{key: 'forged'})
                with self.assertRaises(AssertionError): self.verify()
        self.install = original
        (self.root / 'mods/extra.jar').touch()
        with self.assertRaisesRegex(AssertionError, 'unexpected installed Mod set'): self.verify()

    def test_worker_permission_or_missing_phase_refusal_cannot_pass(self):
        for phase in ['ENTITY', 'CONNECTION']:
            with self.subTest(phase=phase):
                self.states[0]['phases']['phases'][phase]['worker'] = 1
                self.write_commands()
                with self.assertRaisesRegex(AssertionError, 'must never enter player workers'): self.verify()
                self.states[0]['phases']['phases'][phase]['worker'] = 0
                self.states[0]['refusals'][phase + ':UNKNOWN_MOD'] = 0
                self.write_commands()
                with self.assertRaisesRegex(AssertionError, 'unknown-Mod serial refusal'): self.verify()
                self.states[0]['refusals'][phase + ':UNKNOWN_MOD'] = 1

    def test_missing_logout_or_retained_queue_state_fails(self):
        for key in ['pending', 'readers', 'terminals']:
            with self.subTest(key=key):
                self.states[-1]['network'][key] = 1
                self.write_commands()
                with self.assertRaisesRegex(AssertionError, 'bookkeeping'): self.verify()
                self.states[-1]['network'][key] = 0
        self.states[-1]['players'] = [{}]
        self.write_commands()
        with self.assertRaisesRegex(AssertionError, 'login/logout boundary'): self.verify()


if __name__ == '__main__':
    unittest.main()
