#!/usr/bin/env python3
"""Validate and summarize the C7 all-vanilla GameTestServer comparison without inferring missing counters."""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path


def load(path, driver):
    data = json.loads(path.read_text())
    if data.get("driver") != driver:
        raise ValueError(f"{path}: expected driver {driver}")
    if data.get("ownershipViolations") != 0:
        raise ValueError(f"{path}: ownership violation")
    if data.get("containerItemCountAtStart", 0) != data.get("containerItemCountAtEnd"):
        raise ValueError(f"{path}: inventory conservation failed")
    if len(data.get("types", {})) != data.get("vanillaTypes"):
        raise ValueError(f"{path}: incomplete type coverage")
    return data


def summary(data):
    result = {key: data[key] for key in ("driver", "msptAverage", "msptP95", "msptP99", "parallelInvocations", "workerCpuUtilization", "allocationBytes", "ownershipViolations")}
    for key in ("serialInvocations", "fallbackRatio", "processCpuCores", "heapDeltaBytes"):
        if key in data:
            result[key] = data[key]
    result["allocationBytesPerTick"] = data["allocationBytes"] / data["measuredTicks"]
    result["serialCompletionsMeasured"] = data["driver"] != "DISABLED"
    if data["driver"] == "C7":
        for label, field in (("graph", "graphNanos"), ("proof", "proofNanos"), ("collection", "collectionNanos"), ("scheduling", "schedulingNanos"), ("prepare", "preparationNanos"), ("execution", "executionNanos"), ("commit", "commitNanos"), ("phase", "phaseNanos")):
            result[label + "MsPerTick"] = (data["c7After"][field] - data["c7Before"][field]) / 1e6 / data["measuredTicks"]
        result["observedMaxConcurrent"] = data["c7After"]["maxConcurrent"]
        for key in ("failed", "dynamicDiscoveries", "invalidated", "conflicts"):
            result[key] = data["c7After"][key] - data["c7Before"][key]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--c6", type=Path, required=True)
    parser.add_argument("--c7", type=Path, required=True)
    parser.add_argument("--serial", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New report directory (must not exist)")
    args = parser.parse_args()
    paths = {"C6": args.c6, "C7": args.c7, "DISABLED": args.serial}
    runs = {driver: load(path, driver) for driver, path in paths.items()}
    reference = runs["C7"]
    matched_fields = ("seed", "dayTime", "fixtureOrigin", "warmupTicks", "measuredTicks", "copiesPerTickingType", "vanillaTypes", "serverTickingTypes", "placed", "chunks", "workers", "mods")
    for driver, data in runs.items():
        for field in matched_fields:
            if field not in data or data[field] != reference[field]:
                raise ValueError(f"{driver}: unmatched {field}")
        for name, row in data["types"].items():
            other = reference["types"][name]
            for field in ("initialBlockState", "statesProbed", "serverTickingStates", "placed", "hasServerTicker"):
                if row.get(field) != other.get(field):
                    raise ValueError(f"{driver}/{name}: unmatched fixture {field}")
    reason_counts = collections.Counter()
    for name, row in reference["types"].items():
        if row.get("failed", 0):
            raise ValueError(f"{name}: C7 failure")
        if row["collected"] != sum(row[field] for field in ("parallel", "fallback", "skipped", "failed")):
            raise ValueError(f"{name}: unresolved C7 invocations")
        if row["fallback"] != sum(row["fallbackReasons"].values()):
            raise ValueError(f"{name}: missing or repeated fallback reason")
        reason_counts.update(row["fallbackReasons"])
    output = {
        "scope": "Single uncapped GameTestServer run per driver; mostly idle vanilla BEs plus playing jukeboxes; zero players; 4 workers. Probe every state, execute one representative state per type.",
        "limitations": ["No general mod compatibility claim", "Type policy refusals describe missing audited coverage, not automatic Java field tracking", "Heap endpoint deltas are not retained memory overhead", "DISABLED has no C6/C7 serial completion counters", "Harness BE ticks are included in measured totals and type counts"],
        "matchedFixture": {field: reference[field] for field in matched_fields},
        "runs": [summary(runs[driver]) for driver in ("C6", "C7", "DISABLED")],
        "c7VanillaFallbackReasons": dict(sorted(reason_counts.items())),
        "inputSha256": {driver: hashlib.sha256(path.read_bytes()).hexdigest() for driver, path in paths.items()},
    }
    args.output.mkdir(parents=True, exist_ok=False)
    for driver, path in paths.items():
        (args.output / (driver.lower() + ".json")).write_bytes(path.read_bytes())
    (args.output / "summary.json").write_text(json.dumps(output, indent=2) + "\n")
    with (args.output / "c7-types.csv").open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["type", "placed", "hasServerTicker", "survivingAtEnd", "statesProbed", "serverTickingStates", "collected", "parallel", "fallback", "skipped", "failed", "fallbackReasons"])
        for name, row in sorted(reference["types"].items()):
            writer.writerow([name] + [row.get(field, "") for field in ("placed", "hasServerTicker", "survivingAtEnd", "statesProbed", "serverTickingStates", "collected", "parallel", "fallback", "skipped", "failed")] + [json.dumps(row["fallbackReasons"], sort_keys=True)])
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
