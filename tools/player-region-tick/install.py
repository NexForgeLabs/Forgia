#!/usr/bin/env python3
"""Prepare a NEW private installation; never reads a world or starts/stops an existing server."""
import argparse
import hashlib
import json
import os
import secrets
from pathlib import Path
import shutil
import socket
import subprocess
from reviewed_artifacts import read_manifest


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--libraries', type=Path, required=True)
    parser.add_argument('--link-library-dependencies', action='store_true', help='After successful private installation, share verified identical dependency jars; never expose linked jars to installer writes')
    parser.add_argument('--eula', type=Path, required=True)
    parser.add_argument('--installer', type=Path, required=True)
    fixture = parser.add_mutually_exclusive_group(required=True)
    fixture.add_argument('--oracle', type=Path)
    fixture.add_argument('--without-oracle', action='store_true', help='No test Mod or transformation: exercise actual production admission')
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--artifact-manifest', type=Path, help='Explicit reviewed schema 3/4 artifact; preserves the legacy .115 gates')
    parser.add_argument('--persistent', action='store_true')
    parser.add_argument('--player-planning', action='store_true', help='Enable the default-off player tick branch; unknown extensions remain serial')
    parser.add_argument('--criterion-proof', action='store_true', help='Install a test datapack that rewards an actual TICK criterion')
    args = parser.parse_args()
    reviewed = read_manifest(args.artifact_manifest) if args.artifact_manifest else None
    if reviewed:
        manifest, manifest_digest = reviewed
        if args.version != manifest['version'] or hashlib.sha256(args.installer.read_bytes()).hexdigest() != manifest['installerSha256']:
            raise ValueError('Installer differs from reviewed artifact')
        if not args.oracle or hashlib.sha256(args.oracle.read_bytes()).hexdigest() != manifest['oracleSha256']:
            raise ValueError('Reviewed schema 3/4 artifact requires the pinned serial-only oracle')
    root = args.output.resolve()
    if 'eula=true' not in args.eula.read_text().replace(' ', '').lower():
        raise ValueError('Supply an existing accepted eula.txt')
    root.mkdir(parents=True, exist_ok=False)
    if reviewed:
        shutil.copy2(args.artifact_manifest, root / 'artifact-manifest.json')
    library_source = args.libraries.resolve()
    # Every installer input must have a private inode. Vanilla BUNDLER_EXTRACT and mapping
    # processors can rewrite jars even when the resulting bytes are identical; linking before
    # those processors finish can corrupt class loading in another already-running test server.
    shutil.copytree(library_source, root / 'libraries')
    shutil.copy2(args.eula.resolve(), root / 'eula.txt')
    command = [str(args.java.resolve()), '-jar', str(args.installer.resolve()), '--installServer', str(root)]
    (root / 'install-command.json').write_text(json.dumps(command, indent=2) + '\n')
    with (root / 'installer.log').open('wb') as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    (root / 'installer.exit').write_text(str(result.returncode) + '\n')
    if result.returncode:
        raise RuntimeError('Installer failed; preserved log and exit')
    linked_dependencies = 0
    if args.link_library_dependencies:
        for source in library_source.rglob('*.jar'):
            relative = source.relative_to(library_source)
            # Keep Minecraft's generated jars and this core's complete outputs private. Only a
            # completed, byte-identical third-party dependency is shared; the new root is never
            # reinstalled and no server has started in it while this deduplication takes place.
            if relative.parts[:2] == ('net', 'minecraft') or relative.parts[:3] == ('net', 'neoforged', 'neoforge'):
                continue
            destination = root / 'libraries' / relative
            if not destination.is_file() or source.is_symlink() or destination.is_symlink():
                continue
            if hashlib.sha256(source.read_bytes()).digest() != hashlib.sha256(destination.read_bytes()).digest():
                continue
            destination.unlink()
            try:
                os.link(source, destination)
                linked_dependencies += 1
            except OSError:
                shutil.copy2(source, destination)
    (root / 'mods').mkdir(exist_ok=True)
    if args.oracle:
        shutil.copy2(args.oracle.resolve(), root / 'mods' / args.oracle.name)
    (root / 'config').mkdir(exist_ok=True)
    (root / 'config/forgia.toml').write_text(f'''enableRegionThreadingBridge = true
enableRegionThreadingPlayerTicks = {str(args.player_planning).lower()}
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingBlockEntityTicks = false
enableRegionThreadingRandomTicks = false
enableRegionThreadingSpawning = false
enableRegionThreadingPoi = false
enableRegionThreadingPersistentWorkers = {str(args.persistent).lower()}
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 2
''')
    port, rcon_port = free_port(), free_port()
    while rcon_port == port:
        rcon_port = free_port()
    password = secrets.token_hex(24)
    (root / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
level-name=world
level-seed=813584
generate-structures=false
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:dirt","height":2}},{{"block":"minecraft:grass_block","height":1}}],"biome":"minecraft:plains"}}
view-distance=3
simulation-distance=3
spawn-protection=0
max-tick-time=120000
gamemode=survival
difficulty=peaceful
pvp=true
allow-flight=true
enable-rcon=true
broadcast-rcon-to-ops=false
rcon.port={rcon_port}
rcon.password={password}
''')
    (root / 'server.properties').chmod(0o600)
    # RCON is bound in this private, loopback-only instance. Never record its password.
    launch = [str(args.java.resolve()), '-Xms512m', '-Xmx2g',
              '@' + str(root / f'libraries/net/neoforged/neoforge/{args.version}/unix_args.txt'), 'nogui']
    if args.criterion_proof:
        pack = root / 'world/datapacks/forgia-player-criterion'
        (pack / 'data/forgia_player/advancement').mkdir(parents=True)
        (pack / 'data/forgia_player/function').mkdir(parents=True)
        (pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'pack_format': 48, 'description': 'Isolated player criterion ownership proof'}}) + '\n')
        (pack / 'data/forgia_player/advancement/tick.json').write_text(json.dumps({
            'criteria': {'tick': {'trigger': 'minecraft:tick'}},
            'rewards': {'experience': 3, 'function': 'forgia_player:reward'}
        }) + '\n')
        (pack / 'data/forgia_player/function/reward.mcfunction').write_text('scoreboard players add @s forgia_criterion 1\n')
    receipt = {'root': str(root), 'version': args.version, 'port': port, 'criterionProof': args.criterion_proof,
               'libraryDependencyLinksRequested': args.link_library_dependencies,
               'linkedLibraryDependencies': linked_dependencies,
               'installerInputsPrivate': True,
               'persistent': args.persistent, 'playerPlanningRequested': args.player_planning, 'rconPort': rcon_port, 'launch': launch,
               'installerSha256': hashlib.sha256(args.installer.read_bytes()).hexdigest(),
               'oracleSha256': hashlib.sha256(args.oracle.read_bytes()).hexdigest() if args.oracle else None,
               'playerWorkersVerified': False}
    if reviewed:
        receipt['artifactManifestSha256'] = manifest_digest
    (root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
