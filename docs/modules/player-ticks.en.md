# Experimental player ticks

English | [中文](player-ticks.zh.md) · [Module map](README.en.md)

Player ticks use the server thread by default. The restart-only `enableRegionThreadingPlayerTicks` switch permits bounded worker dispatch when the region engine, entity worker slice, supported layout and region-owned entity storage are active. Under `FIXED_3X3`, the TOML switch alone is sufficient; under `DYNAMIC_CLUSTER`, **you must also pass the JVM flag `-Dforgia.dynamicPlayerSoak=true`** (as the 32-player GameTest fixture does), otherwise player ticks stay serial. [Configuration & tuning](../configuration.en.md) · [Diagnostics](observability.en.md)

## Execution order

| Phase | Original work and thread |
|---|---|
| `ENTITY` | Admitted entity-tick calls can run in region batches. The original `ServerPlayer.tick()` owns menu synchronization and advancement ticking. |
| `CONNECTION` | The server thread traverses connections and runs the complete `Connection.tick()`, including its queue, listener and channel tail. |
| `CONNECTION_BODY` | At the original listener call site, a region worker can execute `ServerPlayer.doTick()`. The server thread joins it before position correction, floating checks, keepalive and idle handling. A refused call runs at the original site. |

The phase coordinator, spatial leases, local state scopes and network gate apply through worker return and publisher cleanup. While waiting, the publisher services the admitted body's synchronous health, experience and cooldown sends through the existing batch. The body can start at most once per invocation. Failures after start propagate through the original connection error handling after the join. [Listener patch](https://github.com/NexForgeLabs/Forgia/blob/main/patches/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch) · [Batch implementation](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionTickBatch.java)

Admission checks the current player and connection, inventory and menu identities, reachable callbacks and criteria, and the loaded chunk footprint. Unbounded movement, reachable unknown callbacks, equipment changes requiring tracking broadcasts, live touch targets, lifecycle transitions and other unsupported state retain the serial path. Diagnostics distinguish capture refusals from prepared calls cancelled before start.

The production connection traversal joins each body before it continues to the next connection. The batch fixture verifies overlap across independent regions and serialization within a region. Production connection-body overlap remains open under this ordering constraint.

## Verification

Run from the repository root using JDK 21. The receipt used JDK 21.0.13+9 and recorded exit code 0 for each command.

```bash
export JAVA_TOOL_OPTIONS=-Dforgia.playerPrefixTiming=true
timeout 120s ./gradlew :neoforge:setup --offline --console=plain
timeout 180s ./gradlew :tests:junitTest --tests '*RegionPlayer*' --offline --console=plain
timeout 360s ./gradlew :tests:runFullParallelSoakGameTestServer -PfullSoakSeconds=20 --offline --console=plain
```

The player JUnit suite passed all 433 cases. The GameTest finished with all 32 players online, zero ownership violations, zero active batches and state claims, and zero pending network callbacks or readers. The connection stage test exercises queue flush, the single worker call, the synchronous outbound handoff, position correction, idle disconnect and channel flush in order. [JUnit fixture](https://github.com/NexForgeLabs/Forgia/blob/main/tests/src/junit/java/net/neoforged/neoforge/unittest/RegionPlayerSpatialLeaseTest.java)

| Phase | Worker | Server thread | Worker coverage |
|---|---:|---:|---:|
| `ENTITY` | 328 | 20 | 94.3% |
| `CONNECTION` | 0 | 576 | 0% |
| `CONNECTION_BODY` | 9 | 567 | 1.6% |

Historical ENTITY coverage was 71.8–71.9%. Mean MSPT was 528.9 ms over 17 measured ticks, compared with 167.3 and 232.8 ms in the two earlier runs. The 29.1 ms cumulative body submission-and-join interval includes 17.1 ms of worker body time. The Overworld chunk flush at shutdown took 158.4 s, outside the measurement window. The cause of the higher MSPT remains unresolved. [Machine-readable receipt](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/evidence/player-connection-body/summary.json)

Use `/neoforge regionthreading status players` for phase counts, refusal details and cleanup diagnostics. With the JVM timing flag active, this command also reports per-phase timings. Read body counters independently of the enclosing connection counters.
