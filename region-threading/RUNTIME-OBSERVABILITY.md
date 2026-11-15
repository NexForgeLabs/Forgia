# Runtime observability

Forgia adds permission-level-2 administrative views over the **existing** engine and native server tick ring. No scheduler, pool, ticket, resource authority, effect queue, disk writer or external dependency is added by monitoring. `/neoforge regionthreading` remains compatible, including its existing raw diagnostics lines. `/forgia status` reuses its status renderer.

## Commands

| Command | Output |
|---|---|
| `/forgia` | Compact usage |
| `/forgia status` | Existing overall threading/admission/compatibility status |
| `/forgia regions [page]` | Ten regions per page, stable ID ordering; empty/invalid pages explained |
| `/forgia region <dimension> <x> <z>` | One region's timing, workload, lifecycle and coverage; x/z are **region** coordinates |
| `/forgia region top <metric> [count]` | Descending rank, stable ID tie break; default 10, maximum 100 |
| `/forgia workers` | Configured/alive/busy/idle, queued/submitted/completed/rejected/failed, sampled wall and CPU, shutdown/join; first 20 individual rows |
| `/forgia scheduler` | Existing pool/regionizer and canonical furnace/resource counters and timing |
| `/forgia performance` | Native 10-second server MSPT plus latest JVM sample |
| `/forgia performance history` | 1-, 10-, 60- and 300-second windows |
| `/forgia performance history <seconds>` | Requested 1..300-second window |
| `/forgia performance json` | Versioned compact JSON; unavailable numbers are JSON null |
| `/forgia diagnostics` | Performance, worker, scheduler and threshold warning summaries |

Examples: `/forgia region minecraft:overworld 2 -1`; `/forgia region top mspt 10`; `/forgia region top chunks 5`. Metric completion offers `mspt`, `avg`, `p50`, `p95`, `p99`, `entities`, `blockentities`, `chunks`, `pending`, `worker`, `serial`. Block-entity count is currently N/A: ranking it explains the missing measurement rather than ranking fictional zeros. Dimension IDs, metrics and common history durations have Brigadier completion. Permission is applied at the root and inherited by every child, including console/in-game and JSON routes. Output uses plain text and consistent ms/seconds/percent/MiB units, so it remains readable without chat color.

## Definitions and coverage

**Native server MSPT:** the exact completed native `MinecraftServer.tickTimesNanos[tickCount % 100]` value, observed at `ServerTickEvent.Post`. This matches Minecraft's tallying boundary, excluding the target-rate sleep and post-event callbacks. It is not a duration around an arbitrary event handler. History contains all observed server ticks, including paused/frozen server ticks; benchmark controllers exclude frozen/control intervals independently. TPS is `(samples - 1) / time between first and last sampled completions`, not `1000 / mean MSPT` and not automatically clamped to 20.

**History:** two preallocated long arrays, 12,000 entries (192,000 payload bytes plus headers) per active server. Recording is O(1), allocation-free, server-thread-owned and lock-free. Windows use monotonic **wall time**, with exact nearest-rank P50/P95/P99 over the retained raw ticks. There is no average-of-percentiles error. Queries filter/sort a bounded copy; no sorting or history formatting runs each tick. Empty windows use N/A. Requests report sample count, coverage and `capacityLimited`: accelerated servers can exhaust 12,000 entries before five wall minutes. Ordinary 20 TPS retains at least five minutes. History starts at collection startup, clears on disable/server stop, and is not a persisted time series.

History belongs to the owning server thread and holds only a weak server reference. A deterministic two-thread control verifies that another server's tick/clear cannot replace its samples. The original process-wide slot produced a reproducible cross-server sample mix-up in full-suite execution; thread confinement fixes it without a global lock. Stopped server threads release their local state naturally, and the stop event explicitly clears it.

**Region MSPT:** the pre-existing last-100-server-tick sum of instrumented leased-group durations, including zero-work ticks. Current means the latest **closed** region slot, not an in-progress extrapolation. P50 is added; existing P95/P99 semantics are preserved. Group tick count is not server tick count. Overruns count closed measured slots above 50ms. Main/worker time is cumulative **wall duration**, separated by actual pool worker scope; it includes blocking and is not CPU time. Multiple regions overlap, so their durations must not be summed to infer server MSPT.

**Region limitations:** the existing canonical Native/Furnace fine execution and complete serial fallback bodies do not all record these leased-group timers. Every new region listing/detail states this partial coverage. Thus this is useful region load evidence, **not complete per-region tick-cost attribution**. Canonical BE/serial fallback time, block-entity count, active ticking chunks, pending scheduled block/fluid ticks, per-region admission/refusal/commit/coordination/ownership-contention and CPU attribution are explicitly N/A. Complete coverage requires instrumentation at canonical prefix/body/commit boundaries with overlap-aware region accounting, and lifecycle-maintained workload counters; this implementation does not scan chunk contents to guess them.

Loaded chunks and tracked entities come from existing regionizer membership. Region membership and lifetime counters are weakly consistent while workers/lifecycle transitions run; quiescent controlled snapshots are exact. Closed-window quantiles use one consistent copied ring. Published player counts use the existing per-tick player snapshot and current identity mapping; they are not connected-session counts. Pending work counts region queue **entries**; cancelled scheduled entries remain counted until the queue's normal pruning pass. They do not include scheduled block/fluid ticks, pending chunk I/O or active tickets.

**Pool tasks:** submissions/completions count pool Runnables, including batch runners, not individual entity/BE bodies. Failed is a subset of completed: completed means the pool task returned or threw, not that every body it represented succeeded. Rejection is actual refusal by the original pool. Queue depth counts admitted tasks not yet started, including a transient submitting handoff. Observations are weakly consistent while tasks transition; quiescent snapshots are exact. Timed-task averages use only completed timed tasks; disabling timings preserves counters. Queue waiting starts at submission before enqueue and ends at wrapper entry; execution wall includes wrapper/body/blocking. Per-worker rows count that thread's lifetime; aggregate totals survive fixed-executor worker replacement. Physical pool close now explicitly joins fixed executor threads after executor termination, matching persistent workers.

**JVM:** optional one-wall-second sampling of standard local MXBeans; no per-tick thread/region scan. Main CPU is one logical core's percentage. Worker CPU is all observed pool thread CPU, including scheduler/spin time, and utilization divides it by configured workers. This is distinct from task wall time and WAITING/BLOCKED state. Thread membership changes invalidate worker CPU/allocation deltas until another stable interval. Allocation covers server + observed worker threads, not the entire JVM. Unsupported/disabled counters are N/A. GC collection count/time are MXBean deltas; **GC pause duration is N/A** and requires JFR or GC-log correlation. Lock contention duration is N/A; monitoring does not enable costly JVM contention tracking.

Canonical furnace preparation, execute+join and commit timers retain their original scope. Cumulative issued resource leases are **not active reservations**. Active tickets/resource leases/cancellations/commit wait are N/A until constant-time, lifecycle-balanced counters are available. Existing running region leases, submitted region markers and structural transitions are observable without exposing mutable objects.

## Configuration and warnings

All settings live in the existing `forgia.toml`, including normal base/world override precedence. Threading/admission switches remain default-off and are unchanged. Sampling can be changed without granting execution authority.

| Key | Default | Meaning |
|---|---:|---|
| `runtimeDiagnosticsEnabled` | true | Native history and optional task timing; false clears server history, essential counters/ownership instrumentation continue |
| `runtimeJvmSamplingEnabled` | true | One-second local CPU/allocation/GC observations; false displays N/A |
| `runtimeHighRegionMspt` | 50 | HIGH_REGION_MSPT, latest instrumented closed region tick |
| `runtimeWorkerSaturationPercent` | 90 | WORKER_SATURATION, sampled CPU/configured worker capacity |
| `runtimeQueuePerWorker` | 2 | QUEUE_CONGESTION, pending task snapshot/configured workers |
| `runtimeSerialFallbackPercent` | 50 | HIGH_SERIAL_FALLBACK, lifetime entity/BE fallback ratio after at least 100 bodies |
| `runtimeRegionImbalanceRatio` | 4 | REGION_LOAD_IMBALANCE, max/mean measured region average, max at least 1ms |
| `runtimeMainCpuPercent` | 90 | Main CPU threshold for MAIN_THREAD_BOTTLENECK |
| `runtimeHighServerMspt` | 50 | Mean server MSPT also required for MAIN_THREAD_BOTTLENECK |

Warnings identify threshold observations, not proven causes. A queued snapshot does not prove sustained congestion; high fallback may be correct conservative mod compatibility. Imbalance describes the measured group coverage, not omitted work. CPU samples need two stable observations; small tasks can finish between snapshots. No claim of a root cause is inferred from CPU/MSPT correlation.

## Validation and cost

Controlled unit checks cover known native-ring input, independent percentile arithmetic, wall-time expiry, capacity limits, invalid inputs, administrator permission, metric completion, region pagination/retirement/N/A and actual Fixed/Persistent queue/busy/failure/refusal/join controls. Existing metric allocation/retirement and lifecycle/ownership suites remain enabled. The initial fixed-pool join assertion failed and led to the physical-thread join fix; its original failure is retained.

Dedicated `RuntimeObservabilityBenchmark` is tagged `benchmark`, excluded from correctness totals, and runs through `:tests:junitBenchmark --tests '*RuntimeObservabilityBenchmark'`. It reports two million history enabled/disabled iterations and warmed region recording allocations; it has no hardware-dependent pass threshold. Raw timing and installed-server enabled/disabled measurements are recorded in [PERFORMANCE-BENCHMARKS.md](PERFORMANCE-BENCHMARKS.md), with exact validation totals and limitations in [PERFORMANCE-OPTIMIZATION-REPORT.md](PERFORMANCE-OPTIMIZATION-REPORT.md).

The warmed dedicated harness measured history recording at **5.134–5.256ns/tick**, with **0allocated bytes**, versus0.645–0.882ns for its baseline loop. Region begin+record measured55.118ns/iteration,0allocated bytes. In the equivalent installed64-furnace fixture, optional sampling ON recorded18.698MiB/s versus18.695MiB/s OFF; timing differences were smaller than between-JVM variation, so no negative overhead or tight whole-server bound is claimed. The full per-window values and separate JFR measurements are in [PERFORMANCE-BENCHMARKS.md](PERFORMANCE-BENCHMARKS.md).

Collection has no disk I/O, external profiler dependency, per-tick full-region scan or global metrics lock. Command-time region ranking is O(regions × bounded window + sort), and raw history query work is bounded at 12,000 ticks. Very large-region command latency is not certified; pagination bounds output, not the cost of taking every region snapshot. Server-thread formatting/aggregation remains a scaling limitation that requires an immutable snapshot/background formatting path before extreme-region deployments. Monitoring is therefore **CONDITIONAL**, not a claim that every requested metric or arbitrary-scale command cost has been validated.
