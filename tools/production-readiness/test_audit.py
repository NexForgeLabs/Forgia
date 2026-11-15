"""Fail-closed controls for the read-only audit and deployable configuration templates."""

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
spec = importlib.util.spec_from_file_location("production_audit", HERE / "audit.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class AuditFailureControls(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.source = self.repo / "src/main/example.java"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("certified production\n")
        for name, data in {
            audit.C6: {"finalProfilePair": {"productionFingerprint": "", "definitionHashes": {}}},
            audit.LIFECYCLE: {"productionFingerprintPreservedFromC6": ""},
        }.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(data))
        self.command("init", "-q")
        self.command("add", ".")
        self.command("-c", "user.name=Audit Fixture", "-c", "user.email=audit@example.invalid",
                     "commit", "-qm", "fixture")
        expected = audit.fingerprint(audit.inventory(self.repo))
        c6 = self.repo / audit.C6
        c6.write_text(json.dumps({"finalProfilePair": {"productionFingerprint": expected, "definitionHashes": {}}}))
        lifecycle = self.repo / audit.LIFECYCLE
        lifecycle.write_text(json.dumps({"productionFingerprintPreservedFromC6": expected}))
        self.command("add", ".")
        self.command("-c", "user.name=Audit Fixture", "-c", "user.email=audit@example.invalid",
                     "commit", "-qm", "pin receipts")
        revision = self.command("rev-parse", "HEAD").strip()
        self.command("update-ref", "refs/remotes/origin/main", revision)
        pins = {name: audit.sha256(self.repo / name) for name in (audit.C6, audit.LIFECYCLE)}
        self.constants = patch.multiple(audit, FINGERPRINT=expected, PRODUCTION_REVISION=revision,
                                        FROZEN_DOCUMENTS=pins, ARTIFACTS={"optional.so": "0" * 64})
        self.constants.start()
        self.addCleanup(self.constants.stop)

    def command(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True,
                                       stderr=subprocess.STDOUT)

    def test_pristine_source_does_not_claim_runtime_certification(self):
        result = audit.audit(self.repo)
        self.assertEqual(result["verdict"], "PASS")
        self.assertFalse(result["runtimeTestsExecuted"])
        self.assertFalse(result["deploymentPerformed"])
        self.assertEqual(result["localBuildArtifacts"][0]["status"], "MISSING")

    def test_modified_production_fails_fingerprint_and_preservation(self):
        self.source.write_text("changed production\n")
        result = audit.audit(self.repo)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertFalse(result["checks"]["certifiedProductionFingerprint"])
        self.assertFalse(result["checks"]["productionDiffEmpty"])

    def test_untracked_production_input_fails(self):
        (self.source.parent / "extra.java").write_text("untracked production\n")
        result = audit.audit(self.repo)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertFalse(result["checks"]["noUntrackedProductionInputs"])

    def test_changed_certificate_fails_even_with_same_fingerprint(self):
        path = self.repo / audit.C6
        path.write_text(path.read_text() + "\n")
        result = audit.audit(self.repo)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertFalse(result["checks"]["frozenCertificatesPreserved"])

    def test_required_missing_artifact_fails(self):
        result = audit.audit(self.repo, require_artifacts=True)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertFalse(result["checks"]["localBuildArtifactsMatchCertifiedPins"])

    def test_required_changed_artifact_fails(self):
        (self.repo / "optional.so").write_bytes(b"wrong binary")
        result = audit.audit(self.repo, require_artifacts=True)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertEqual(result["localBuildArtifacts"][0]["status"], "MISMATCH")

    def test_evidence_missing_tampered_and_wrong_size_are_failures(self):
        path = self.repo / "evidence.log"
        data = b"original evidence\n"
        path.write_bytes(data)
        reference = {"path": path.name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
        # Repeated references do not manufacture extra verified evidence.
        good = audit.verify_evidence(self.repo, [reference, reference])
        self.assertTrue(good["passed"])
        self.assertEqual(good["uniqueReferences"], 1)
        self.assertFalse(audit.verify_evidence(self.repo, {**reference, "bytes": len(data) + 1})["passed"])
        path.write_bytes(b"changed evidence\n")
        self.assertFalse(audit.verify_evidence(self.repo, reference)["passed"])
        path.unlink()
        self.assertFalse(audit.verify_evidence(self.repo, reference)["passed"])

    def test_receipt_cannot_follow_symlink_outside_checkout(self):
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / "private"
            target.write_bytes(b"private")
            (self.repo / "linked").symlink_to(target)
            result = audit.verify_evidence(self.repo, {"path": "linked", "sha256": audit.sha256(target)})
            self.assertFalse(result["passed"])
            self.assertEqual(result["failures"][0]["problem"], "outside repository")

    def test_cli_refuses_to_overwrite_evidence(self):
        path = self.repo / "retained.json"
        path.write_bytes(b"retain exactly\n")
        process = subprocess.run(["python3", str(HERE / "audit.py"), "--repo", str(self.repo),
                                  "--output", str(path)], capture_output=True, text=True)
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(path.read_bytes(), b"retain exactly\n")


class ConfigurationControls(unittest.TestCase):
    def test_profiles_match_frozen_c6_values_and_differ_only_in_pool(self):
        receipt = json.loads((REPO / audit.C6).read_text())
        profiles = {}
        for name, key in (("c6-fixed", "finalFixed"), ("c6-persistent", "finalPersistent")):
            actual = tomllib.loads((HERE / "config" / (name + ".toml")).read_text())
            expected = {key: tomllib.loads("value = " + value)["value"]
                        for key, value in receipt["fullProfiles"][key]["serverConfig"]["values"].items()}
            self.assertEqual(actual, expected)
            profiles[name] = actual
        self.assertEqual([key for key in profiles["c6-fixed"]
                          if profiles["c6-fixed"][key] != profiles["c6-persistent"][key]],
                         ["enableRegionThreadingPersistentWorkers"])

    def test_every_runtime_key_is_in_templates_and_configuration_matrix(self):
        source = (REPO / "src/main/java/net/neoforged/neoforge/common/ForgiaConfig.java").read_text()
        keys = set(re.findall(r'\.define(?:InRange|Enum)?\("([^"]+)"', source))
        self.assertEqual(len(keys), 24)
        matrix = (REPO / "region-threading/PRODUCTION-CONFIGURATION.md").read_text()
        for name in ("serial", "c6-fixed", "c6-persistent"):
            actual = tomllib.loads((HERE / "config" / (name + ".toml")).read_text())
            self.assertEqual(set(actual), keys)
        for key in keys:
            self.assertIn("| `" + key + "` |", matrix)

    def test_serial_template_disables_all_sixteen_switches(self):
        values = tomllib.loads((HERE / "config/serial.toml").read_text())
        switches = {key: value for key, value in values.items() if isinstance(value, bool)}
        self.assertEqual(len(switches), 16)
        self.assertTrue(all(value is False for value in switches.values()))


if __name__ == "__main__":
    unittest.main()
