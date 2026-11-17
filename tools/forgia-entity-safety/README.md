# Forgia Entity Safety 0.1.0

[中文](README.zh.md) | English

A server-side, declaration-only compatibility mod for Forgia 1.21.1. It pins exactly
`cataclysm:the_leviathan` to the server thread before its tick starts. Cataclysm 3.33's
`TentacleHoldattack` can call `ServerPlayer.startRiding`, which changes the player's pose
and position while the Leviathan owns a region-worker tick. Forgia correctly rejects
that foreign entity mutation. Running the entire boss tick on the server preserves the
attack, synchronous return values and exactly-once effects.

Other entity types keep their existing scheduling, including other Cataclysm entities.
The riding-stack policy also keeps a vehicle carrying a declared entity on the server.
This mod does not disable entity workers, relax ownership guards, swallow failures, or
retry partially executed ticks. Its cost is the complete Leviathan tick plus any riding
stack that already contains it; installed-server performance must still be measured.

## Install

1. Put `forgia-entity-safety-0.1.0.jar` in the server's `mods` directory. Clients do not need it.
2. Restart the server. Keep `enableRegionThreadingEntityTicks = true`.
3. Run `/neoforge regionthreading compat` and check for
   `cataclysm:the_leviathan`, reason `forgia_entity_safety:leviathan_player_grab`,
   source `forgia_entity_safety:forgia-entity-safety.json`.
4. Reproduce the grab attack in a test encounter. Check
   `/neoforge regionthreading status`: ticks under that reason should be serial, while
   other eligible entity ticks continue on workers.

The mod uses Forgia's `regionThreadingDeclarations` metadata, verified in
`21.1.250.101-beta`. It contains no Java classes or Mixins and does not require a
Cataclysm compile dependency. The optional Cataclysm dependency accepts 3.33 and later;
only 3.33 is the diagnosed version, so later gameplay changes still need verification.
Without Cataclysm the unknown entity declaration is reported and ignored. Plain
NeoForge and old Forgia versions without declaration support cannot apply this fix.

## Extend only for another confirmed unsafe entity

Merge the additional registry id into `config/regionthreading-declarations.json`:

```json
{
  "entities": [
    {
      "type": "anothermod:confirmed_unsafe_entity",
      "reason": "pack:confirmed_foreign_entity_mutation"
    }
  ]
}
```

Replace the example id with the actual entity id and restart. The instance declarations
are combined with the mod's built-in Leviathan declaration. Avoid namespace-wide or
base-class declarations: they would unnecessarily serialize unrelated entities.

An arbitrary already-started Java tick cannot be transparently moved to another thread.
A general future fix needs whole-operation admission before side effects, or a precise
pre-tick declaration such as this one. This mod only covers its listed entity; it does
not promise recovery from every mod event, AI callback, chunk tick or storage violation.

## Validation

- The 19 declaration/policy JUnit tests pass. The new regression runs one declared
  entity plus 99 other mod-type entities using the same Java class: the declared body
  runs once on the server, all 99 others run once on workers, and ownership violations
  stay zero. This is a scope check, not a production parallel-percentage measurement.
- An isolated Forgia `21.1.250.101-beta` server with Cataclysm `3.33`, Lionfish API
  `3.1`, Curios `9.5.1+1.21.1` and Java `21.0.13` loads the packaged low-code mod and
  reports exactly one declaration with zero ignored entries.
- With one Leviathan, one Deepling and one cow (all NoAI), the runtime reports
  **706 worker ticks, 353 serial ticks**, all 353 attributed to
  `forgia_entity_safety:leviathan_player_grab`; health is clean and shutdown exits 0.
- This smoke run verifies dispatch and normal boss ticking, not a real player's grab
  encounter or compatibility with the complete installed pack.

Local verification artifacts are in `build/verification/` when supplied with this build.

## Build

Python 3.11 or later, no network or Java compilation required:

```bash
python3 tools/forgia-entity-safety/build.py --install-dir /path/to/forgia-server
```

Output: `tools/forgia-entity-safety/build/libs/forgia-entity-safety-0.1.0.jar`,
with a SHA-256 sidecar. The install check only reads the supplied libraries; it does
not install the mod or change the server.
