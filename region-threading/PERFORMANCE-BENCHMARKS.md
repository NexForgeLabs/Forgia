# Performance benchmarks

Session base: clean `506e3707a80884140dd68b2bd51211e40aa879b4`; Minecraft1.21.1, Temurin21.0.13+9, Linux/WSL2, 4physical/8logical CPUs. All servers use disposable worlds under `tmp/performance-session`; no production world is opened. Measurements and tests run sequentially. Existing C6 and lifecycle records are historical acceptance evidence, not new performance measurements.

## Comparable installed-server fixture

The [controller](../tools/performance/README.md) uses unchanged vanilla furnace tickers, seed813584 flat terrain, 64already-burning furnaces across eight region neighborhoods, complete loaded3×3 neighborhoods, no players/mods/spawning/random ticks, 4configured region workers, fixed2GiB G1 heap and the same VM/JMX observer. Each fresh JVM has400warmup ticks and two400tick measured windows. Scheduled exact freezes prove tick counts; complete before/after NBT,128output items per window and inventory conservation match across **all12JVMs/24windows**. The exporter refuses any difference, failed run or profiled measurement.

Stock NeoForge21.1.249 provides the upstream baseline. Forgia `.98` and `.99` are local benchmark versions of the current21.1.250 development tree. They share the same Minecraft version, seed, VM, observer, configuration and completed workload. The stock patch-version difference is explicit; comparisons do not isolate every upstream code change. Forgia serial is an additional same-tree control. These are light stationary vanilla furnaces, not a whole modpack or multiplayer claim.

Round0 runs stock→Forgia serial→workers→monitored; round1 reverses that order. Optimized workers/monitored likewise reverse order in a second round. Two windows within a JVM are not independent replications; no pooled percentile or statistical significance is claimed. Every quantile below belongs to its own window, reconstructed from the native tick ring inside the proven-active inner interval. Freeze/control commands and diagnostic formatting are excluded. Observed TPS is subject to the documented equal-valued ring endpoint ambiguity.

The [machine result](PERFORMANCE-CONTROLLED-RESULTS.json) binds run/binary hashes, exact equivalence, own-interval CPU/allocation metrics and per-window values. Raw rings, NBT, commands, local JVM artifacts, exits and logs remain private under `tmp/performance-session`. Main/worker CPU seconds use the thread observation interval (roughly18–19s), not necessarily exactly the native-ring interval. Allocation likewise reports its own duration. CPU percentages must use those durations. GC collection count/time is not stop-the-world pause duration. Blocked-monitor entrance deltas are0 in these primary windows; other waits/contention remain unmeasured.

| Build/condition | JVM round/window | Mean | P50 | P95 | P99 | Observed TPS | Main CPU s | Worker CPU s | Alloc MiB/s | GC count/ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 21.1.250.98-beta workers sampled | r0/0 | 14.082 | 10.411 | 24.247 | 35.359 | 19.98 | 5.771 | 0.844 | 252.87 | 4/80 |
| 21.1.250.98-beta workers sampled | r0/1 | 10.569 | 10.273 | 11.240 | 28.995 | 20.03 | 4.732 | 0.820 | 253.49 | 4/76 |
| 21.1.250.98-beta serial unsampled | r0/0 | 0.268 | 0.246 | 0.325 | 0.710 | 19.98 | 1.093 | 0.000 | 0.42 | 0/0 |
| 21.1.250.98-beta serial unsampled | r0/1 | 0.248 | 0.232 | 0.313 | 0.685 | 19.98 | 1.144 | 0.000 | 0.40 | 0/0 |
| 21.1.250.98-beta workers unsampled | r0/0 | 10.376 | 10.130 | 10.869 | 25.050 | 19.98 | 4.423 | 0.790 | 252.82 | 4/58 |
| 21.1.250.98-beta workers unsampled | r0/1 | 10.432 | 10.110 | 11.492 | 24.181 | 19.98 | 4.685 | 0.818 | 253.40 | 4/61 |
| 21.1.249 stock unsampled | r0/0 | 0.217 | 0.199 | 0.268 | 0.528 | 20.03 | 1.056 | 0.000 | 0.40 | 0/0 |
| 21.1.249 stock unsampled | r0/1 | 0.193 | 0.182 | 0.239 | 0.570 | 19.77 | 1.097 | 0.000 | 0.37 | 0/0 |
| 21.1.250.98-beta workers sampled | r1/0 | 10.378 | 10.043 | 11.196 | 24.741 | 19.98 | 4.417 | 0.800 | 252.83 | 5/71 |
| 21.1.250.98-beta workers sampled | r1/1 | 10.380 | 10.018 | 11.317 | 25.257 | 19.98 | 4.680 | 0.827 | 253.47 | 4/60 |
| 21.1.250.98-beta serial unsampled | r1/0 | 0.267 | 0.253 | 0.296 | 0.478 | 19.92 | 1.084 | 0.000 | 0.46 | 0/0 |
| 21.1.250.98-beta serial unsampled | r1/1 | 0.264 | 0.249 | 0.320 | 0.673 | 19.98 | 1.135 | 0.000 | 0.44 | 0/0 |
| 21.1.250.98-beta workers unsampled | r1/0 | 10.347 | 10.112 | 11.247 | 17.239 | 19.98 | 4.456 | 0.803 | 253.16 | 4/26 |
| 21.1.250.98-beta workers unsampled | r1/1 | 10.283 | 10.090 | 11.112 | 17.309 | 20.03 | 4.673 | 0.826 | 252.95 | 4/28 |
| 21.1.249 stock unsampled | r1/0 | 0.260 | 0.240 | 0.313 | 0.531 | 19.87 | 1.083 | 0.000 | 0.40 | 0/0 |
| 21.1.249 stock unsampled | r1/1 | 0.246 | 0.231 | 0.321 | 0.615 | 19.98 | 1.133 | 0.000 | 0.38 | 0/0 |
| 21.1.250.99-beta workers sampled | r0/0 | 2.351 | 2.331 | 2.708 | 3.088 | 19.98 | 1.752 | 0.773 | 18.70 | 0/0 |
| 21.1.250.99-beta workers sampled | r0/1 | 2.949 | 2.942 | 3.629 | 4.141 | 19.98 | 2.048 | 0.823 | 18.67 | 1/15 |
| 21.1.250.99-beta workers unsampled | r0/0 | 2.375 | 2.363 | 2.731 | 3.580 | 20.03 | 1.750 | 0.780 | 18.72 | 0/0 |
| 21.1.250.99-beta workers unsampled | r0/1 | 3.090 | 3.051 | 3.633 | 4.659 | 19.98 | 2.091 | 0.828 | 18.64 | 1/14 |
| 21.1.250.99-beta workers sampled | r1/0 | 2.286 | 2.245 | 2.575 | 3.292 | 19.98 | 1.729 | 0.778 | 18.70 | 0/0 |
| 21.1.250.99-beta workers sampled | r1/1 | 2.298 | 2.199 | 2.682 | 3.005 | 19.98 | 1.806 | 0.796 | 18.72 | 1/21 |
| 21.1.250.99-beta workers unsampled | r1/0 | 2.335 | 2.297 | 2.682 | 3.297 | 19.98 | 1.761 | 0.772 | 18.70 | 0/0 |
| 21.1.250.99-beta workers unsampled | r1/1 | 2.981 | 2.931 | 3.593 | 4.234 | 20.03 | 2.061 | 0.825 | 18.72 | 1/24 |

`.98` includes runtime monitoring and the drain-deadline change, before furnace lookahead reuse. `.99` adds reuse strictly inside an unexecuted canonical prefix. On unsampled worker conditions, the four window means change from10.376/10.432/10.347/10.283ms to2.375/3.090/2.335/2.981ms. The arithmetic mean of these four **window means**, a descriptive comparison only, falls10.359→2.695ms (74.0%). Main-thread CPU/observation drops from roughly23–25% of one core to9–11%; worker CPU stays roughly4% of one core. Allocation falls253.08→18.70MiB/s (92.6%). This removes a real regression but still leaves the worker/resource path much slower than the stock0.19–0.26ms and Forgia-serial0.25–0.27ms windows. There is **no general faster-than-vanilla claim**.

## Profiling evidence, kept separate

Separate JFR runs use the same installed64-furnace fixture, another200discarded ticks after recorder startup, and one400tick window. They are excluded from the primary exporter. Before/after observed MSPT:10.940/2.594ms. Server thread CPU:4.582/1.844s over18.019/18.018s. The initial main-thread execution samples include227resource-identity/reservation/scope samples and39catalog-audit samples;147leaves hit ResourceTable.slot. After reuse only31total execution samples occur in the active interval, with11resource and6catalog categories. Sampling counts are not exact CPU duration or exact allocation bytes.

JFR measured active-window GC pauses before:4pauses, total74.903ms, maximum22.722ms. After:0observed pauses in that window, not a promise of no GC in later operation. No JavaMonitorEnter event above the JFR threshold appears in either measured window; short monitor contention and AQS/park waits are not ruled out. Full recordings/streamed summaries: `profiled-before/`, `profiled-after/` under the private session root. These are diagnostic measurements with profiler overhead, not substitutes for primary timing.

## Existing mixed normal-world soak

The unchanged full6000tick normal-preset production soak (200warmup, seed4242424242, redstone/POI/entities/spawn/random ticks) produced:

| Revision/work | Mean ms | P50 | P95 | P99 | Evidence |
|---|---:|---:|---:|---:|---|
| Initial clean HEAD, all slices on |8.274|7.836|11.631|16.456|`before/`|
| Only deadline admission optimization |8.108|7.610|11.561|17.234|`after-optimization/`|

Both completed all existing soak invariants and exited successfully. The ~2.0%mean change is one before/after pair with JIT/world-state variation; P99 increased4.7%. It establishes no broad tail benefit. The exact component test establishes avoided pre-deadline submissions without altering logical queue ticks or accepted-task joins.

A separate initial2400tick JFR soak failed the unchanged required cross-region-write invariant (0observed). Its log/failed exit and recording remain under `before-profile/`; it is not included in passing benchmark totals. The historical same-HEAD4GiB/ZGC/taskset result8.644ms threading-on vs7.820ms threading-off is a regression lead, not interchangeable with the new2GiB/G1 data. GameTest execution is accelerated; `1000/meanMSPT` is not a measured paced-server TPS result.

## Monitoring overhead and accuracy

The optimized installed pairs above compare `runtimeDiagnosticsEnabled/runtimeJvmSamplingEnabled` both false versus both true with identical NBT/work. Sampled means2.351/2.949/2.286/2.298ms and unsampled2.375/3.090/2.335/2.981ms overlap with JVM/JIT variation. Sampled allocation18.698MiB/s versus unsampled18.695MiB/s (mean of four per-window rates). These runs detect no overhead above the workload's between-JVM timing variation; they do not establish a negative cost or a tight whole-server upper bound.

The dedicated two-million-iteration harness, after its first warmup repetition, records history in5.134–5.256ns/tick versus0.645–0.882ns baseline-loop cost, with0allocated bytes. First-call JIT/class initialization allocates80bytes. Warmed region begin+record costs55.118ns/iteration,0allocated bytes. These are loop microbenchmarks, not server MSPT. Full output: `monitor-benchmark-final/output.log`. The benchmark is not counted as a correctness test.

Independent checks feed the collector a known native ring slot (123.456789ms), verify exact nearest-rank arithmetic against1..100ms, bracket per-worker CPU with independent ThreadMXBean reads, and gate real worker tasks to prove busy/queued/completed/failed/refused/joined counts. Actual installed console responses and external read-only JMX CPU/allocation samples agree in scope and magnitude; e.g. optimized sampled allocation18.691MiB/s in the command versus18.70MiB/s externally. Different sample intervals are explicit, so that is not claimed as exact interval equality. Region totals remain explicitly partial: omitted canonical BE/serial-fallback timers are N/A, never inferred from furnace task time.

Player-related main-thread cost, generation/load throughput and complete lock-wait durations are N/A for this fixture. Connected-player movement/packet/inventory/combat performance and new chunk-stage determinism are unprocessed coverage, not passing or zero-cost measurements. See the three subsystem audits for the exact retained serial boundaries.
