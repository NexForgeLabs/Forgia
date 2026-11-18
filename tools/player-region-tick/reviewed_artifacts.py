#!/usr/bin/env python3
"""Explicit artifact review gate for later schema 3/4 compatibility runs.

The legacy .115 checks stay in their original verifiers. A new core requires an
explicit CLI manifest, archived at installation, and a source-reviewed digest in
this registry. This grants no runtime worker permission to the oracle Mod.
"""
import hashlib
import json
from pathlib import Path

REVIEWED_MANIFESTS = {
    'forgia-21.1.250.152-beta-schema3-4': 'fca333dd8eaffdee7d41500e70351694fafba3e8a16b9db76f883601812d02eb',
    'forgia-21.1.250.151-beta-schema3-4': '083c1dc8d640cafce83fd1b29bf368cc9a35e18bf81e6804f3fdc8c5a611a77f',
    'forgia-21.1.250.150-beta-schema3-4': '7a8943dc61d5c622ca71773e0d90dadfbb4e5f46322fc8d85ee1c897045a9409',
    'forgia-21.1.250.146-beta-schema3-4': '99671241fb4de384ded730a8bf458e3956cca7bc04c00f32d8f174a3ac3d00ac',
    'forgia-21.1.250.145-beta-schema3-4': '614e45a50a7cee55ccbbcf9994e61fdcd2192a3e2e9626decda550dbd1497e62',
    'forgia-21.1.250.144-beta-schema3-4': '51885dcfb4f94a1993b5755e2a16af2f58451f34db57ef57113f1d15639d85b2',
    'forgia-21.1.250.143-beta-schema3-4': '9d01da11f8877a25ba19ff562974bd343ec2e7eb6ababddbe483740370db0a75',
    'forgia-21.1.250.136-beta-schema3-4': 'ac35832382f2b3d9703660230424793e12d160476aac56e645fe40de6fef2c8f',
    'forgia-21.1.250.134-beta-schema3-4': '7b7a091e349fbe7b4509bb81a71047cb627f3eba6a34d6f67cb0ec6e562fb721',
    'forgia-21.1.250.133-beta-schema3-4': '3f09c8977a1b130d1ca177b43d8b4d3f03cc32460700104f7667b1f5e431acf2',
    'forgia-21.1.250.127-beta-schema3-4': 'fb5ab729a900f19b9d81e8582e29e662aaf0534d6e2e725352dd605fec55ec56',
    'forgia-21.1.250.126-beta-schema3-4': 'b43c22a51fd9bc5b6598a789202c82f740ed87d1fe85d8ff595f61039e3472d1',
    'forgia-21.1.250.125-beta-schema3-4': '6c99f7eba6d3fef2757a253c13e680224f87983c8cda38dc7cfa4aa5c4db07ed',
    'forgia-21.1.250.124-beta-schema3-4': '6e43b4b70c0047a5a2f0572e6dc6d1fecf56028217aa045238a841c7f6716d38',
    'forgia-21.1.250.123-beta-schema3-4': 'c9a440f0483374d1e202a5195c25df32f8d0b61bfbf8f7921fd470988e6d7ea5',
    'forgia-21.1.250.122-beta-schema3-4': '6760559fdba264b5835879881f39e80cd13ccf43243acc7033a11a3754233462',
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def read_manifest(path):
    data = Path(path).read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    manifest = json.loads(data)
    require(REVIEWED_MANIFESTS.get(manifest.get('id')) == digest, 'unreviewed or altered artifact manifest')
    require(manifest['schema'] == 1 and manifest['contract'] == 'unknown-extension-whole-call-serial', 'unsupported artifact contract')
    require(manifest['review']['kind'] == 'parent-agent-source-and-artifact-review', 'missing explicit review')
    require(set(manifest['installedJars']) == {'server', 'universal'}, 'incomplete installed artifact hashes')
    return manifest, digest


def verify_installation(root, run, install, path):
    manifest, digest = read_manifest(path)
    require((root / 'artifact-manifest.json').read_bytes() == Path(path).read_bytes(), 'installation manifest differs from reviewed manifest')
    require(install['artifactManifestSha256'] == digest, 'installation manifest digest mismatch')
    require(install['version'] == manifest['version'] and install['installerSha256'] == manifest['installerSha256'], 'wrong reviewed core installation')
    require(install['oracleSha256'] == manifest['oracleSha256'], 'unreviewed oracle')
    require([p.name for p in (root / 'mods').glob('*.jar')] == ['forgia-player-oracle-1.0.jar'], 'unexpected installed Mod set')
    for suffix, expected in manifest['installedJars'].items():
        version = manifest['version']
        jar = root / f'libraries/net/neoforged/neoforge/{version}/neoforge-{version}-{suffix}.jar'
        require(hashlib.sha256(jar.read_bytes()).hexdigest() == expected, 'actual reviewed core mismatch ' + suffix)
    # The response itself is read from the original command log, not a runner flag.
    commands = [json.loads(line) for line in (run / 'commands.ndjson').read_text().splitlines()]
    responses = [json.loads(row['response']) for row in commands if row['command'] == 'neoforge regionthreading status players']
    require(len(responses) == 2, 'missing before-logout/final original production diagnostics')
    live, final = responses
    for state in responses:
        require(state['requested'] == install['playerPlanningRequested'] and state['persistent'] == install['persistent'], 'actual reviewed mode mismatch')
        require(state['activeBatches'] == state['stateClaims'] == state['network']['pending'] == state['network']['readers'] == state['network']['terminals'] == 0, 'live reviewed runtime bookkeeping')
        require(not state['network']['frozen'], 'network boundary still frozen')
        require(all(p['worker'] == p['failed'] == 0 for p in state['phases']['phases'].values()), 'oracle must never enter player workers')
    require(len(live['players']) == 2 and not final['players'], 'missing actual login/logout boundary')
    require(all(final['phases'][k] == 0 for k in ['owners', 'pending', 'running']), 'live reviewed player tickets after logout')
    if install['playerPlanningRequested']:
        for phase in ['ENTITY', 'CONNECTION']:
            require(live['refusals'].get(phase + ':UNKNOWN_MOD', 0) > 0, 'oracle did not exercise unknown-Mod serial refusal ' + phase)
    return {'id': manifest['id'], 'sha256': digest, 'contract': manifest['contract'],
            'scope': f"{manifest['version']} schema 3/4 gameplay compatibility with oracle; unknown extensions serial; worker=0",
            'refusals': live['refusals']}
