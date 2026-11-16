# Audited original block-entity tick API

`AUDITED_BLOCK_ENTITY_TICKS` is an experimental additive capability of ParallelApi major 1. It lets a
separately installed, reviewed adapter describe a complete original ticker branch through
`AuditedBlockEntityTickAdapter`. Core remains independent of target mod names, classes and versions.
The first consumer is Create adapter 0.2.0; the core tests contain a separate counter integration with
no Create dependency.

This is an explicit trusted-adapter contract. The adapter is responsible for the semantic completeness
of its branch/resource/effect/external-writer audit. Hashes establish declared implementation integrity;
they do not infer program safety. The interface is not a sandbox against arbitrary Java, reflection,
ASM/coremods, JNI or asynchronous writers. Installing an unreviewed implementation cannot be described
as automatic compatibility certification.

Current additive execution protocol: [BlockEntityExecutionAdapter](../docs/api/block-entity-execution.en.md)
adds coordinator-only fresh inspections and same-region resource scopes. Create 0.3.0 uses that explicit
registration. The older audited protocol described here retains its own region-owner semantics.

## Registration and compatibility

Implement `AuditedBlockEntityTickAdapter`, check the capability during `RegisterParallelAdaptersEvent`,
and register through the existing `registerBlockEntityTicks(ParallelApi.MAJOR_VERSION, id, types, adapter)`.
This shares the existing server session, frozen registration, duplicate id/type rules and total adapter
budget. Audit callbacks are included in that transaction: caught nested registration failures and
reentrant duplicate registration cannot leave a published partial service.

The old `BlockEntityTickAdapter` remains binary compatible. Its predicate alone does not gain the new
canonical execution contract. The Java-only C5 general/dedicated request API keeps its current serial
semantics; audit reference text and descriptive resource validators do not silently become grants.
Existing vanilla native and Furnace proofs retain their own paths.

All original engine switches, compatibility refusals, server-only declarations, interior layout rules
and budgets still apply. In particular the existing canonical Coordinator keeps a phase larger than
2048 candidates per planning window. The current coordinator uses bounded windows for larger phases;
whole-phase resource/update budgets still apply. This extension does not raise or reset those budgets.
Audited adapters may use prepareCanonical alongside Furnace Waves; old v1 adapters keep their original
prepare=0 and complete serial behavior in that mode. No adapter creates a pool, schedules an alternate BE loop, issues a Permit,
commits arbitrary callbacks or replaces the original body.

## Adapter methods

| Method | Thread and lifetime | Required result |
|---|---|---|
| `audit()` | Registration server thread, once per registration | Frozen implementation/source/function manifest with bounded reference/revision |
| `prepareCanonical(level)` | Quiescent server in the original published phase; default delegates to old prepare | Bounded read-only preparation; no async scheduling/wait, chunk load or stale success cache |
| `inspect(source, tickerImplementation)` | Actual server cursor after original batch publication, then held-region prefix checks; can run on a worker before bodies | `null` for an unclosed branch; otherwise current complete backing-root READ/WRITE footprint |
| `check(source)` | Original v1 region-owned refusal gate immediately before a body | Original ADMITTED/refusal result; Core rechecks binding/footprint afterwards |

`inspect` may read only the supplied source's owned state and immutable metadata. It may not query
world/provider/capability objects, consume RNG, mutate world state, block, schedule or retain the source.
Every inherited tick, behaviour/lazy tick, callback, notification and RNG branch must be accounted for.
Unknown operations require the entire original ticker to stay serial.

A whole-level preparation is not a certificate that survives a serial fence. A candidate whose safety
depends on mutable global topology must have a valid complete currentness argument without querying that
live topology from a worker; otherwise `inspect` must return null. Snapshot computation and multi-owner
world transactions remain different protocols.

## Manifest and integrity

`Audit` contains a bounded reference/revision, exact source/function classes and `Implementation` entries.
Each implementation specifies a Class, SHA-256 of its packaged class bytes and explicitly reviewed merged
Mixin origins. Core checks all declared fingerprints/merged-method provenance, exact source/function
membership, and required adapter/source/function ancestry. Function classes with mutable instance
storage are not accepted by this first contract; that requires a richer bound-function inspection API.
Missing metadata, changed bytes and unknown
transforms leave the target serial.

The semantic call closure and completeness of this manifest are the adapter author's responsibility.
Class/resource hashes cannot automatically discover every possible callback, alias or foreign writer.
An adapter can pin its own reviewed packaged implementation while its target-mod hashes remain fixed
version facts. This is not a hardcoded target-mod verifier in Core.

## Footprint and original binding

`Proof` lists up to 64 `Storage(identity, READ/WRITE)` entries. Identities are compared by reference;
Core never calls a mod object's equals/hashCode to assign ownership. Include the exact source as WRITE.
Every mutable backing root touched by the branch needs its appropriate access; different wrappers around
one backing storage must identify the actual shared root. Missing source writes, duplicate identities,
whole-world/server/chunk claims, entity roots and foreign BE roots do not authorize a region-local body.

Core captures the original wrapper/delegate/source/function/chunk/frame, retains registration identity
and checks the live source, position, block state, chunk membership and original binding. Collection
uses frozen candidate metadata only; adapter inspections start after the original batch is published.
A same-position
replacement, function with the same name, new registration with an equal descriptor, or unknown subclass
cannot inherit the old candidate.

A fresh inspection must return the same root identities and access modes. Before any body starts, all
held-region checks finish. READ/WRITE overlap prevents those units from sharing a prefix, including
same-region units whose earlier writes could invalidate the later branch. READ/READ can remain parallel.
Units in one region preserve their original order.
This conflict analysis does not discover roots the adapter omitted or control external writers by itself.

## Execution and failure

The bridge creates no independent authority. It uses the original canonical phase, Native prefix,
InvocationTicket, RegionGuard/Permit, region leases and task pool. Mailbox delivery precedes fresh checks.
The original complete ticker runs once after authorization. Workers physically join and required effects
commit before an unknown serial fence; its suffix is inspected again afterwards.

A pre-body refusal leaves the original serial invocation available. An inspection/check exception is a
failure, not permission or a successful refusal. A started body failure is reported after join/cleanup;
it is never repaired by replaying the whole ticker. Shutdown/unload keeps the original physical completion
boundary. No adapter receives the engine, Ticket, RegionState or resource lease constructor.

## Diagnostics

`ParallelApi.Server.auditedBlockEntityTicks()` returns immutable integrity statuses. The existing v1
per-adapter checks/refusals/native-completions remain the execution counters. Console
`/neoforge regionthreading compat` reports both. An integrity status is not a current branch or completed
body; cumulative submissions and root counts are not speedup measurements.

## Executable examples and acceptance

- [`AuditedBlockEntityTickAdapterTest`](../tests/src/junit/java/net/neoforged/neoforge/unittest/AuditedBlockEntityTickAdapterTest.java)
  is an independent integration through the real Level entry, original bindings and real region workers.
- [`CreateTickAdmission`](../adapters/create/src/main/java/net/neoforged/forgia/compat/create/CreateTickAdmission.java)
  keeps all Create-specific branch and footprint logic outside Core.
- [`CreateAudit`](../adapters/create/src/main/java/net/neoforged/forgia/compat/create/CreateAudit.java)
  owns the target implementation facts and reviewed transformation set.

Acceptance must include serial equivalence, real different-region overlap, wrong functions/subclasses,
changed roots, aliases, unknown transformations, reentrant registration, complete fences, lifecycle and
failure without replay. A green fixture is not a substitute for the target modpack's real installed-server
validation or paired performance measurements.
