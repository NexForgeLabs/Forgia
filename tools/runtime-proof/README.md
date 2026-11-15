# Optional VM evidence and original Mod integration

**Implementation checkpoint; final C6 acceptance is pending.** The latest nested-callback/frozen-value boundary has standalone controls, but its renewed real-Mod lifecycle tests and two full unfiltered GameTest profiles have not yet completed. Earlier complete real-Mod runs belong to earlier source revisions. See the [checkpoint design and evidence](../../region-threading/MOD-WORKER-RUNTIME-DESIGN.md).

This opt-in Linux/JDK 21 runtime supplies actual installed-method evidence and object-access checks for the existing Core invocation Kernel. It creates no scheduler, invocation Ticket, resource reservation or execution permission. Ordinary startup does not load it, and an absent or unsupported service leaves Mod invocations serial.

The first integration fixture uses the original 方块电玩 / `piq_fc_arcade` 0.31.0-alpha.61 artifact, SHA-256 `d4cb3a41cd3d98c99a0118b1e97475b5781106711ac1d4e321d87c4c2506c510`. Supply that jar locally. It is All Rights Reserved and is not redistributed here. The fixture pin establishes a reproducible test input; Core authorization uses the actual installed function's semantics, original storage and binding, enforced access and the original resource/Kernel authority. Neither a Mod id nor this hash grants Worker permission.

The standalone component controls need a C compiler and the JDK 21 headers:

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
python3 tools/runtime-proof/verify_runtime.py \
  --java-home "$JAVA_HOME" \
  --output tmp/runtime-proof-components-new
```

The output directory must be new. Every command, real exit code, raw log, own-source revision and built artifact hash is retained. Controls cover installed hidden bytecodes and handlers, defensive copies, foreign writers through direct/reflection/MethodHandle/VarHandle/Unsafe routes, actual JNI field writes and JNI method entry, method entry before earlier side effects, publication races, reentrant access callbacks, enforced instance-field immutability, native memory/header boundaries, normal XML/thread creation, absent/unsupported environments, unknown native-library refusal before initialization, FFI/JMX native-agent routes, and an actual JVMTI redefinition request refused before installation. The redefinition and JNI variants are test-only translation units; the production library exposes neither code replacement nor JNI mutation helpers. These component fixtures do not claim to acquire a Core Mod Permit or Lease.

The opt-in real Mod tests load the untouched upstream artifact through the normal Mod loader and run in a real ServerLevel. To execute the three integration batches on a fresh world:

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain \
  --max-workers=1 -PforgiaVersion=21.1.250.60-beta \
  -I tools/runtime-proof/real_mod.gradle \
  -PtrustedModJar=/local/game_console-0.31.0-alpha.61.jar \
  -PtrustedModRun=tmp/runtime-proof-real-mod-new/server \
  -PgameTestFilter=regionC6Worker,regionC6Writer,regionC6Failure \
  :tests:runCanonicalGameTestServer
```

The existing canonical GameTest profile enables its required slices and resource ownership. Keep that profile's resources enabled for positive Mod Worker tests. Omit `gameTestFilter` to run the entire required GameTest registration set, including these opt-in tests; use `-PcanonicalPersistent=false` to exercise the fixed worker pool. The init script builds and copies the effective native service and verified Core bootstrap bundle into the run's local artifact directory. World, properties, logs, raw XML and artifacts remain local. Default test runs do not load the upstream jar or these opt-in integration fixtures.

The production proof path accepts a small structurally verified counter-return closure. The entire original third-party function runs once; the periodic/unknown branch receives a complete serial invocation before body start. Core wrapper preconditions are verified on the coordinator and passed through the original wrapper, so this Mod Worker body reads no live Chunk. Only the actual source, wrapper/delegate and position storage it reaches become resource Claims. Exact Core BlockPos instance fields are permanently frozen by the VM, with writes refused even for an owner; mutable roots retain access/entry checks. Access-check callbacks cannot recursively enter other protected mutable roots and do not bypass native bounds. Existing main-thread light-completion tasks retain their original behavior.

The supported profile requires disabled attach, the single startup native service, disabled Unsafe intrinsic inlining, compressed object/class pointers, and no enabled JVMCI, WhiteBox or native FFI downcalls. Unknown JNI/JNA libraries are refused before loading. This deliberately restricts the runtime; it is not general Modpack certification or a hostile JVM/Core memory-corruption sandbox. A missing or invalid code snapshot, changed function/binding, uncontrolled access, unsupported dynamic closure or stale original Ticket cannot receive Worker authority. Physical join and original resource/Region cleanup still precede release.

The GameTest observers run only after production admission. They count complete original bodies, check actual storage deltas and function identity, use real latches for overlap, and inspect the actual original Kernel/Lease for lifecycle assertions. They never fabricate Claims, invoke a replacement ticker, call an unknown factory during preparation, skip a started body's effects or replay it after failure. Tests using the native profile can run substantially slower than ordinary startup; no MSPT or performance improvement is inferred from passing them.
