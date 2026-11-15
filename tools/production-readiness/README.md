# Production readiness re-audit

This package reads the checkout and frozen certificates. It does not start/install a server, alter live
configs/worlds, build binaries, load a native service or certify a deployment. Python **3.11+** and Git are
required; optional native build prerequisites are documented in the configuration matrix.

From the repository root, fetch first, then write to a **new** evidence file:

```sh
git fetch --prune origin
python3 tools/production-readiness/audit.py \
  --output /absolute/private/new-run/source-audit.json
```

`origin/main` in the result is the locally fetched reference, not a network freshness assertion. The tool
checks the current working production bytes against C6's exact tracked-file SHA256 JSON inventory encoding;
checks additional production/build paths against revision `0ede20061`; rejects untracked production inputs;
and verifies frozen report/receipt and canonical definition hashes. Documentation/tool changes are permitted
and recorded in `workingTree`; production changes make the source audit fail.

On the evidence-owning machine, require both private retained-evidence trees and local service artifacts:

```sh
python3 tools/production-readiness/audit.py \
  --verify-retained-evidence --require-build-artifacts \
  --output /absolute/private/new-run/full-preservation-audit.json
```

Evidence checks stream SHA256 and sizes from both receipts, deduplicating identical path/hash references.
Missing, changed or outside-checkout files fail when verification is requested. Without this flag private
raw evidence is **not verified**; absent private worlds/logs are never counted as passes. Artifact checks
always report status, but only affect the verdict with `--require-build-artifacts`. Native/verified-Core
pins identify the exact retained binaries, not a general rule that a rebuild can never work. A rebuilt or
installed combination needs its own validation and receipt, not a relabeled match.

Successful source preservation exits 0; a failed check or missing required input exits nonzero. `--output`
refuses an existing path, including a concurrent overwrite through exclusive creation. JSON stdout is also
available for piping. The tool writes only the explicit output path and never fetches or mutates the repo.
Checks do not establish that an installed artifact contains the source, that a native ABI is compatible,
that a pack is safe, or that a canary/recovery drill passed.

Configuration examples contain **all 24 keys**:

- [serial.toml](config/serial.toml): all sixteen Boolean switches false; explicit geometry/count are inert
  with bridge disabled. Use for same-build baseline/rollback after stopping and checking world overrides.
- [c6-fixed.toml](config/c6-fixed.toml): exact accepted Fixed config values; optional service/VM launch
  conditions are separate. Resources are ON.
- [c6-persistent.toml](config/c6-persistent.toml): exact accepted Persistent values; only pool flag differs.

These are opt-in examples, not product default changes or installed configuration. Tests compare both C6
examples to the frozen receipt, require complete schema/matrix coverage and verify source/evidence tamper,
missing artifacts and overwrite/outside-path refusals:

```sh
python3 -m unittest discover -s tools/production-readiness -p test_audit.py -v
```

[Readiness report](../../region-threading/PRODUCTION-READINESS.md),
[configuration matrix](../../region-threading/PRODUCTION-CONFIGURATION.md),
[canary/rollback procedure](../../region-threading/CONTROLLED-CANARY.md).
The original [runtime proof tools](../runtime-proof/README.md) retain VM, real-Mod and lifecycle reproduction;
all their server run directories must be new. Existing [Modpack load tests](../modpack-matrix/README.md)
expect all tested slices, including spawn, to advance. Their normal all-slice PASS gate does **not** match
C6's spawn-OFF profile, and they do not install the C6 native service. Reconcile the declared coverage and
launch inputs before using that harness for a canary; never silently turn an unexercised slice into a pass.
