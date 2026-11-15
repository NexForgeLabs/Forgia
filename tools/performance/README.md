# Controlled installed-server performance tools

`controlled_server.py` reuses `tools/block-entity-resource-ab/server_ab.py`'s installed-server controller, disposable worlds, exact scheduled freeze, complete NBT collection and external native-ring/JMX observer. It supports stock NeoForge, Forgia serial, Forgia workers, and optional runtime diagnostics without a helper mod or new execution subsystem. Every invocation requires a new `--runs` directory, an explicitly pinned installed universal SHA256 and an already accepted local EULA. Never point it at a production world. The installed libraries are shared read-only; worlds/configs/logs are private. Run conditions sequentially and reverse their order in a second independent JVM round.

```sh
# JDK21; local compiled observer, outside benchmark timing:
javac --add-modules jdk.attach -d tmp/perf-observer tools/block-entity-resource-ab/JvmMetrics.java tools/block-entity-resource-ab/JfrEvents.java
python3 -u tools/performance/controlled_server.py \
  --runs /absolute/new/disposable/evidence \
  --installation /absolute/private/installation \
  --version 21.1.250.99-beta --universal-sha256 <exact-installed-core-hash> \
  --eula /path/to/already/accepted/eula.txt --java /path/to/jdk21/bin/java \
  --classes /absolute/path/to/tmp/perf-observer \
  --bridge --diagnostics --warmup-ticks 400 --window-ticks 400 --windows 2

python3 -m unittest discover -s tools/performance -p 'test_*.py'
python3 tools/performance/export_controlled.py --output /path/to/combined.json /path/to/run-a/result.json /path/to/run-b/result.json
```

Omit `--bridge` for serial Forgia; use `--stock` plus the stock version/hash for stock NeoForge. Omit `--diagnostics` to disable optional server history/JVM/task timings while preserving correctness counters. `--jfr /path/to/profile.jfc` starts a **separate diagnostic run** after warmup, then discards another 200 ticks. Profiled runs are refused by the primary exporter. Never pool profiled and unprofiled timings.

Fixture: seed813584, flat, no connected players/mods, 64 continuously burning vanilla furnaces across eight fixed-region neighborhoods, 4 workers, 2GiB fixed G1 heap, exactly 400 warmup ticks and two exactly 400-tick output/conservation windows. The default64 is configurable to1..256. Original body totals are checked when the worker bridge is active; all builds must produce exact equivalent full before/after NBT. `export_controlled.py` refuses a missing/failed/profiled run or any workload/state/output mismatch, and retains binary/evidence hashes. Consecutive windows are not independent JVM repeats.

The observer reconstructs the native100-slot tick ring only inside conservatively proven active intervals, excluding freeze, snapshot and command formatting. Endpoint equality can obscure a tick, so reported ring TPS is an observation, not an exact packet or scheduling trace. Main/worker CPU and allocation have their own observation intervals. GC MXBean time is not GC pause duration. Blocked-monitor entrance counts do not measure lock wait duration or other synchronization. No player cost, generation throughput or loading throughput is inferred from this stationary furnace fixture.

Raw command responses include console command validation of the new diagnostics; they are kept in private evidence. The published machine result contains only bounded summaries, exact NBT equivalence verdicts and file hashes, not RCON credentials/world data. Local evidence and session commands live under `tmp/performance-session`; retained failures must not be overwritten or relabeled.
