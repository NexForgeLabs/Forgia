#!/usr/bin/env python3
"""Read-only source/evidence re-audit; never starts, installs or changes a server."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys


C6 = "region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.json"
LIFECYCLE = "region-threading/LIFECYCLE-CERTIFICATION-2026-11-15.json"
FINGERPRINT = "f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787"
PRODUCTION_REVISION = "0ede200610867aea46d5473c7f6ec4fccdd063e0"
INVENTORY_PATHS = ("src/main", "patches", "projects/runtime-proof/src/main")
PRESERVATION_PATHS = (
    "src/main", "patches", "projects/runtime-proof/src", "coremods/src",
    "adapters/create/src", "adapters/mobspawncontroller/src", "build.gradle",
    "settings.gradle", "gradle.properties", "projects/runtime-proof/build.gradle",
    "adapters/create/build.gradle", "adapters/mobspawncontroller/build.gradle",
    "buildSrc",
)
FROZEN_DOCUMENTS = {
    "region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.md":
        "1797c48268e2110217770d9cd285546a3a09e8e41ebd340e39f388af35b8b221",
    C6: "374326a6eaa378e57760bfcfd64869110e0dbbc2f5e3745479cb62199696e336",
    "region-threading/LIFECYCLE-CERTIFICATION-2026-11-15.md":
        "fa4309a87af8c6405a1e048b954b2d64fb6b2a87f75c0227ce8e6461c5a284f7",
    LIFECYCLE: "5b08c2bf5f051a2e0d7242dfb06bea4499bf3cb3f1cb5f80425d24b71fa16f41",
}
ARTIFACTS = {
    "projects/runtime-proof/build/native/libforgia_runtime_proof.so":
        "4c59ec052f086ac1cbdc0c1919504e5870a4043bf563d1da275d5112a89da2df",
    "projects/runtime-proof/build/libs/runtime-proof-21.1.250.60-beta-verified-core.jar":
        "914f4715293ec3dbc24bfd6159edc17fc727c3f4e0b6fa2f435c358bad15d602",
}


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory(repo):
    output = subprocess.check_output([
        "git", "-C", str(repo), "ls-files", "-z", "--", *INVENTORY_PATHS,
    ])
    return {
        name: sha256(repo / name)
        for name in output.decode().split("\0") if name
    }


def fingerprint(sources):
    # Identical encoding to the frozen C6 profile collector, including default JSON separators.
    return hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest()


def pinned_files(repo, pins):
    checks = []
    for name, expected in pins.items():
        path = repo / name
        actual = sha256(path) if path.is_file() else None
        checks.append({
            "path": name, "expectedSha256": expected, "actualSha256": actual,
            "status": "MATCH" if actual == expected else "MISSING" if actual is None else "MISMATCH",
        })
    return checks


def evidence_references(value):
    references = {}

    def visit(item):
        if isinstance(item, dict):
            if isinstance(item.get("path"), str) and isinstance(item.get("sha256"), str):
                references[(item["path"], item["sha256"])] = item.get("bytes")
            for child in item.values():
                visit(child)
        elif isinstance(item, list):
            for child in item:
                visit(child)

    visit(value)
    return references


def verify_evidence(repo, receipt):
    references = evidence_references(receipt)
    failures = []
    for (name, expected), expected_size in references.items():
        path = repo / name
        # Receipts are repository-relative. Never follow a receipt outside the selected checkout.
        if Path(name).is_absolute() or not path.resolve().is_relative_to(repo):
            failures.append({"path": name, "problem": "outside repository"})
            continue
        if not path.is_file():
            failures.append({"path": name, "problem": "missing"})
            continue
        actual = sha256(path)
        if actual != expected or (expected_size is not None and path.stat().st_size != expected_size):
            failures.append({"path": name, "problem": "checksum or size mismatch", "actualSha256": actual})
    return {
        "uniqueReferences": len(references), "verified": len(references) - len(failures),
        "failures": failures, "passed": not failures,
    }


def audit(repo, retained_evidence=False, require_artifacts=False):
    c6 = json.loads((repo / C6).read_text())
    lifecycle = json.loads((repo / LIFECYCLE).read_text())
    sources = inventory(repo)
    production_fingerprint = fingerprint(sources)
    diff = git(repo, "diff", "--name-only", PRODUCTION_REVISION, "--", *PRESERVATION_PATHS)
    untracked = git(repo, "ls-files", "--others", "--exclude-standard", "--", *PRESERVATION_PATHS)
    definitions = pinned_files(repo, c6["finalProfilePair"]["definitionHashes"])
    frozen = pinned_files(repo, FROZEN_DOCUMENTS)
    artifacts = pinned_files(repo, ARTIFACTS)
    checks = {
        "certifiedProductionFingerprint": production_fingerprint == FINGERPRINT,
        "receiptFingerprintConsistent": c6["finalProfilePair"]["productionFingerprint"] == FINGERPRINT
            and lifecycle["productionFingerprintPreservedFromC6"] == FINGERPRINT,
        "productionDiffEmpty": not diff,
        "noUntrackedProductionInputs": not untracked,
        "canonicalDefinitionsPreserved": all(row["status"] == "MATCH" for row in definitions),
        "frozenCertificatesPreserved": all(row["status"] == "MATCH" for row in frozen),
    }
    if require_artifacts:
        checks["localBuildArtifactsMatchCertifiedPins"] = all(row["status"] == "MATCH" for row in artifacts)
    evidence = {}
    if retained_evidence:
        evidence = {C6: verify_evidence(repo, c6), LIFECYCLE: verify_evidence(repo, lifecycle)}
        checks["retainedEvidenceVerified"] = all(row["passed"] for row in evidence.values())
    return {
        "schemaVersion": 1,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "Repository/source preservation only; not installed-server, compatibility or deployment certification",
        "verdict": "PASS" if all(checks.values()) else "FAIL",
        "repository": str(repo), "head": git(repo, "rev-parse", "HEAD"),
        "branch": git(repo, "branch", "--show-current"),
        "originMain": git(repo, "rev-parse", "origin/main"),
        "fetchPerformedByThisTool": False,
        "workingTree": git(repo, "status", "--porcelain=v1"),
        "productionRevision": PRODUCTION_REVISION,
        "productionInventoryPaths": list(INVENTORY_PATHS),
        "productionFileCount": len(sources),
        "expectedProductionFingerprint": FINGERPRINT,
        "actualProductionFingerprint": production_fingerprint,
        "additionalPreservationPaths": list(PRESERVATION_PATHS),
        "changedProductionPaths": diff.splitlines(),
        "untrackedProductionInputs": untracked.splitlines(),
        "checks": checks, "canonicalDefinitions": definitions,
        "frozenCertificates": frozen, "localBuildArtifacts": artifacts,
        "localBuildArtifactsRequired": require_artifacts,
        "retainedEvidenceRequested": retained_evidence, "retainedEvidence": evidence,
        "runtimeTestsExecuted": False, "serverStarted": False, "deploymentPerformed": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--verify-retained-evidence", action="store_true",
                        help="Require private files referenced by both certificates; absent evidence fails")
    parser.add_argument("--require-build-artifacts", action="store_true",
                        help="Require local optional-service binaries to match the frozen certified pins")
    parser.add_argument("--output", type=Path, help="Write JSON to a new file; refuse to overwrite")
    args = parser.parse_args()
    # Refuse an existing evidence destination before doing any work.
    if args.output and args.output.exists():
        parser.error(f"output already exists: {args.output}")
    try:
        result = audit(args.repo.resolve(), args.verify_retained_evidence, args.require_build_artifacts)
        rendered = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x") as stream:
                stream.write(rendered)
        print(rendered, end="")
        return 0 if result["verdict"] == "PASS" else 1
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Re-audit failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
