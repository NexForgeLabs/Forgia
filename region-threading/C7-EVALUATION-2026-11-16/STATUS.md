# Paused experimental checkpoint

The operator paused further C7 development after the initial evaluation. C6 remains present and is the default driver. C7 remains default off; its default execution mode is SHADOW. No production migration or C6 removal is approved by these results.

This comparison uses 44 vanilla BE types and probes every block state of every valid vanilla block variant. 25 types can supply a server ticker. There are 419 placed BEs, including 16 playing jukeboxes; moving pistons naturally finish during warmup. All three runs use fixed fixture coordinates, the same flat seed, zero players, 4 workers, 200 warmup ticks and 600 measured ticks. GameTest harness BEs are included in aggregate counters. The primary C6 profile retains its optional furnace-wave/resource switches off.

| Driver | Mean MSPT | P95 | P99 | Recorded serial fallback |
| --- | ---: | ---: | ---: | ---: |
| C6 | 1.797 | 3.281 | 3.845 | 100% in this profile |
| C7 | 1.722 | 2.601 | 3.182 | 87.532% |
| Original serial | 0.701 | 1.302 | 1.773 | No parallel path; completion counters unavailable |

C7 completed 28,800 worker computations with canonical commit and 202,200 serial invocations. Supported operations are empty-input furnace-family clocks. Most types still lack an audited detached operation. Refusal categories identify missing proof coverage, not observed access traces on each idle invocation. This single run per driver does not establish a repeatable advantage over C6, and C7 is materially slower than the serial baseline. All measured runs preserved the initial container item count (16 discs), reported zero ownership violations, and C7 reported no failed invocations.

`summary.json` contains matched settings, input SHA-256 hashes, CPU/allocation observations and C7 phase timings. `c7-types.csv` contains per-type completion counts and one reason per completed fallback. Raw driver reports are retained alongside it.

Validation reached 370 passing focused C6/C7, lifecycle, ownership, worker-pool and configuration regressions, plus a successfully built installer. Subsequently the worker kernel was separated from the live validation/commit object: the final worker runner receives a detached computation and opaque resource identities. That final separation compiled successfully and passed 18 transformed Create integration tests, including independent/shared kinetic-network serial fallback without touching C6 adapter counters. The full 370-test suite, final formatting check and these benchmark runs were **not rerun after that last separation**, because the operator stopped development. These are checkpoint limitations, not a production certification.

Create validation uses actual transformed Create 6.0.10 classes and original tick bodies with mocked world/chunk services; it is not mixed-mod real-server certification. AE2/Mekanism gameplay and long-running mixed-mod workloads remain unvalidated. Metadata was fetched for possible future isolated tests, but no such new test run was started after the pause.

Baseline preservation branch: `checkpoint/c6-before-c7-20261116` at `88566baafe74b92e95701cdd0a4aa6c4e7e39202`. Experimental work: `feature/c7-dependency-graph`. Retain C6 regardless of this prototype's future.
