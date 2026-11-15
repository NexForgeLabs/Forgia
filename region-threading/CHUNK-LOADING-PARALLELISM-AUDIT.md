# Chunk loading and preparation parallelism audit

This audit follows the actual Minecraft 1.21.1/NeoForge loading path, not a generic async-loading diagram. `ChunkMap.scheduleChunkLoad` reads/upgrades NBT asynchronously, then explicitly invokes `ChunkSerializer.read` with `mainThreadExecutor`. No new loading scheduler or pool is introduced, and this session does not move reconstruction or callbacks to workers.

| Pipeline operation | Current thread / ownership | Safe expansion and exact limitation |
|---|---|---|
| Region-file disk read | Existing `IOWorker`/storage mailbox | Already off the server thread. Keep per-file ordering, pending-write/read semantics and storage close futures |
| Decompression and NBT parse | Existing storage read pipeline | Already part of the asynchronous read result. NBT remains mutable and must have one consumer after transfer |
| Data-version upgrade | `ChunkMap.readChunk`: `thenApplyAsync(...upgradeChunkTag..., Util.backgroundExecutor())` | Already background work. DataFixer/legacy structure upgrade accesses dimension saved data and generator metadata; do not assume arbitrary upgrades are pure |
| Missing/invalid chunk detection | Read-result filtering, then main-thread create/failure path | Preserve error reporting and replaceable-position cache semantics |
| Block-state and biome palette decode | Inside main-thread `ChunkSerializer.read` | Candidate for isolated preparation; codecs and registry bindings must be audited/frozen, preserve partial-error reporting and original section order |
| Section/ProtoChunk construction | Main-thread serializer | Could consume a private prepared palette result, but must not expose writable palette objects before transfer |
| POI consistency | `PoiManager.checkConsistencyWithBlocks` for each section during read | Mutates loaded POI state; existing region POI publication and unload/write ordering must participate. Whole serializer is not pure |
| Lighting reconstruction | `retainData`, `queueSectionData`, light correctness and ProtoChunk engine attachment inside read | Calls the live light engine; preserve existing queue/status ordering. Do not publish incomplete light data |
| Upgrade/blending/retrogen | Serializer construction and status-dependent setup | Needs exact immutable inputs and ownership of the target chunk |
| Block/entity tick containers | Reconstructed in serializer, registered later | Preserve saved deadlines and registration order; no ticking before the original FULL path |
| Structure-start reconstruction | `unpackStructureStart(StructurePieceSerializationContext.fromLevel(...))` | Real template manager, structure piece constructors and mod factories; main-thread hot path in historical online stalls, but not a certified immutable computation |
| Structure references and heightmaps | Serializer, center chunk state | Potential private preparation after exact registry/array ownership proof; missing maps must preserve original prime behavior |
| Attachments and auxiliary light data | NeoForge deserialization inside serializer | User/mod serialization callbacks, unknown threading assumptions; retain serialized calls |
| Saved entities and block-entity tags | Proto tag storage / LevelChunk postLoad callback | Copying tags is different from constructing entities. Preserve original lifecycle and callbacks |
| Entity/block-entity reconstruction | Original post-load/registration paths on main-thread FULL mailbox | Factories, capabilities, attachments and Level access are not worker-safe by default |
| ChunkDataEvent.Load | Posted from `ChunkSerializer.read` for both full and proto chunks | Must run exactly once with original chunk/NBT and ordering; never silently skipped |
| FULL / world registration | Main-thread `ChunkStatusTasks.full` | Preserve currentlyLoading bypass, postLoad, loaded flag, BE/tick-container registration and ChunkEvent.Load |
| Ticking preparation | `prepareTickingChunk`: range future, main mailbox, postProcessGeneration/startTickingChunk/send notification | Serialized authoritative visibility boundary; nearby chunks and lighting futures must already satisfy the original contract |
| Unload/save/failure | Existing save-sync futures, unload queue, POI close/unload hooks and light-engine updates | Physically finish accepted preparation before resource release; preserve original error/empty-chunk policy |

Implementation plan, in dependency order:

1. Capture separate main-thread palette/heightmap/structure reconstruction timing on a saved-world loading fixture, including total load throughput and DataFixer version. Do not mix disk-cold and disk-warm runs.
2. Extract an internal value-only input containing pinned registry views, height bounds, owned NBT/primitive arrays, requested chunk coordinate and original status. No ServerLevel, PoiManager, live light engine, factory or callback should be reachable from the pure decoder.
3. Produce privately owned section palette/heightmap data on the existing loading executor, leaving all unknown codecs/attachments/structures on the original serialized path. Do not decode twice or replay a callback on fallback.
4. Consume the prepared result once in the original main-thread serializer continuation. Validate holder identity/status/lifecycle before registration; keep POI, lighting, entities/BEs, events and markPosition at their original boundary. Reuse holder cancellation/save-sync futures, with physically joined work before retirement.
5. Compare complete NBT, palettes, block entities, entity UUIDs, POI tickets, lighting and callbacks against a serial oracle across cancellation, corrupt data, unload, failures and save/new-JVM reload. Only then benchmark the same chunk set/cache state.

The exact blocker is the absence of this extracted pure preparation representation and an authority/lifecycle contract for transferring it into the original holder. Moving `ChunkSerializer.read` wholesale to `Util.backgroundExecutor` or the C6 pool would concurrently mutate POI/light/world state and invoke unknown callbacks. Adding a second loader or independent chunk locks is not an acceptable shortcut.

This session's existing disposable production soaks exercise generation, chunk ticking and normal shutdown, not a new load-preparation implementation. New reconstruction/cancellation/POI/light/callback parity tests for expanded loading are **NOT YET**, not passed. Existing accepted C6 physical save/reload evidence remains historical and is not relabeled as validation of new worker loading. Newly moved work: **none**. New load throughput benefit: **N/A**. Assessment: **NOT YET** for added worker loading/preparation; original asynchronous disk/NBT/upgrade work remains in place.
