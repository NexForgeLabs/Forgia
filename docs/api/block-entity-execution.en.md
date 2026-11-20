# Original block-entity tick execution adapters

Experimental protocol 1: `BlockEntityExecutionAdapter`, negotiated with
`ParallelApi.Capability.BLOCK_ENTITY_EXECUTION_ADAPTERS`.

Register during `RegisterParallelAdaptersEvent` through
`registrar.registerBlockEntityExecutionAdapter(protocol, id, types, adapter)`. Registration shares the
existing server session, frozen transaction, duplicate id/type checks and 128-adapter budget. It never
enables disabled engine switches. Vanilla and third-party types use the same registration surface.

The interface extends `AuditedBlockEntityTickAdapter` to reuse its implementation manifest, complete
branch checks and backing-root footprint. This is an explicitly trusted adapter contract, not automatic
semantic verification of arbitrary Java. Core validates actual invocation identity, currentness,
resource exclusion and lifecycle; the adapter remains responsible for its complete call closure,
aliases and external writers.

| Method | Contract |
|---|---|
| `audit()` | Pinned exact source/function implementations, audited revision and allowed Mixin origins |
| `executionMode()` | Frozen `REGION_OWNER` or `RESOURCE_SCOPED` |
| `prepareCanonical(level)` | Read-only preparation in the published canonical server phase |
| `check(source)` | Complete current branch admission or a bounded `Decision` |
| `inspect(source, tickerClass)` | Complete READ/WRITE `Proof`, or null for an unsupported branch |
| `inspectExecution(source, tickerClass)` | Defaults to check + inspect; can additionally return dependency revisions |
| `phaseClosed(successful)` | Cleanup after physical join and required commits, also after failure or a throwing preparation |

The new canonical path runs preparation and complete fresh inspections on the coordinator before any
body starts. Inputs are borrowed, read-only and must not be retained. The historical explicit driver
retains the inherited audited/predicate callbacks and their original timing; prefer testing the actual
`Level.tickBlockEntities` entry. Old predicate and C5 registrations never acquire new authorization.

`REGION_OWNER` executes independent regions concurrently under coarse ownership. `RESOURCE_SCOPED`
keeps outer region exclusion on the coordinator while workers enter separate resource leases, allowing
independent sources in the same chunk/region to overlap. Resource-scoped branches may only modify their
declared local storage: no world/provider queries, RNG, events, notifications, packets or scheduling.

The first implementation reuses audited `Proof`: up to 64 `Storage` entries, including the exact source
as WRITE. Use actual backing-root reference identities, accounting for inventory/stack/behaviour
storage and wrapper aliases. Core rejects duplicate identities, missing source writes, whole-world,
server/chunk, entity and foreign-BE roots. Writable roots cannot switch logical home across phase
windows; transient empty RegionState retirement does not change a logical region coordinate.

An `Inspection` may include `Dependency(identity, revision)` entries. Each unique dependency root must
also appear in the footprint, so writers conflict with readers inside a wave. Fresh inspection must
preserve identities, access modes and dependency revisions. Dependency invalidation and control of
external writers are still the adapter's responsibility. Resource values never expose a Permit/Lease.

Canonical planning uses windows of at most 2048 candidates, with independent invocations grouped into
at most the worker count of tasks. Whole-phase binding and deferred-update budgets remain; exhausted
unstarted work keeps its complete serial tick. Membership/Ticket snapshots remain O(N), while work-graph
diagnostics still use bounded sampling. Use actual engine/adapter completions for execution totals.

Pre-body refusals run the entire original ticker once on the server. Inspection exceptions fail the
phase. Started failures physically join and clean up without serial replay. Existing
`blockEntityTickAdapters()` and `auditedBlockEntityTicks()` report checks, refusals, integrity and actual
successful worker completions. Checks include planning and revalidation, not just body starts.

Stateful ticker support, generic capability resolvers, multi-home transactions and general effect
profiles remain future extensions. Compile against the facade with `compileOnly`, never bundle it.

See the [independent executable integration](https://github.com/NexForgeLabs/Forgia/blob/main/tests/src/junit/java/net/neoforged/neoforge/unittest/BlockEntityExecutionAdapterTest.java)
and [Create adapter](https://github.com/NexForgeLabs/Forgia/blob/main/adapters/create/README.md) for examples and acceptance boundaries.
