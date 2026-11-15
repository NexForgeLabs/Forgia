#!/usr/bin/env python3
# Copyright (c) NeoForged and contributors
# SPDX-License-Identifier: LGPL-2.1-only
"""Execute bounded actual-JVM evidence/access controls; retain every command, source and failure."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New private evidence directory; never overwritten")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--command-timeout", type=int, default=30)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    if args.command_timeout < 1 or args.command_timeout > 60:
        parser.error("Each command timeout must be between 1 and 60 seconds")
    java_home = args.java_home
    if java_home is None:
        compiler = shutil.which("javac")
        if compiler is None:
            parser.error("Provide --java-home for JDK 21")
        java_home = Path(compiler).resolve().parents[1]
    java_home = java_home.resolve()
    native_sources = sorted((repo / "projects/runtime-proof/src/main/native").glob("*.c"))
    bridge_sources = sorted((repo / "projects/runtime-proof/src/main/java").rglob("*.java"))
    fixture = Path(__file__).resolve().parent / "fixtures"
    tests = sorted(fixture.glob("*.java"))
    control = fixture / "native_library_control.c"
    redefine_agent = fixture / "redefine_test_agent.c"
    jni_agent = fixture / "jni_access_test_agent.c"
    modified = fixture / "RedefinitionVictim.modified.java.txt"
    evidence_agent = fixture / "malformed_evidence_test_agent.c"
    sources = [Path(__file__).resolve(), *native_sources, *bridge_sources, *tests, control, redefine_agent, jni_agent, evidence_agent, modified]
    hashes = {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    for source in sources:
        retained = output / "sources" / source.relative_to(repo)
        retained.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, retained)
    library = output / "libforgia_runtime_proof.so"
    bridge = output / "runtime-proof.jar"
    native_control = output / "unknown-native.so"
    javac, jar, java = [str(java_home / "bin" / tool) for tool in ("javac", "jar", "java")]
    compile_native = ["gcc", "-std=c11", "-shared", "-fPIC", "-O2", "-pthread", "-Wall", "-Wextra",
                      "-I" + str(java_home / "include"), "-I" + str(java_home / "include/linux")]
    commands = [
        ("compile-service", [*compile_native, "-o", str(library), *map(str, native_sources), "-ldl"]),
        ("compile-bridge", [javac, "--release", "21", "-d", str(output / "classes"), *map(str, bridge_sources)]),
        ("bridge-jar", [jar, "cf", str(bridge), "-C", str(output / "classes"), "."]),
        ("compile-fixtures", [javac, "--release", "21", "-cp", str(bridge), "-d", str(output / "tests"), *map(str, tests)]),
    ]
    agent = "-agentpath:" + str(library) + "=" + str(bridge)
    profile = [java, "-XX:+DisableAttachMechanism", "-XX:+UnlockDiagnosticVMOptions", "-XX:-InlineUnsafeOps",
               "--add-opens=java.base/jdk.internal.foreign.abi=ALL-UNNAMED", agent, "-cp", str(output / "tests")]
    for name in ("InstalledDefinitionProbe", "ProductionAccessProbe", "PublicationRaceProbe", "ConcurrentRegistrationProbe", "NestedCallbackProbe", "FrozenValueProbe", "AccessorEntryProbe"):
        extra = [str(output / "tests/ProductionAccessProbe$HiddenWriter.class")] if name == "ProductionAccessProbe" else []
        commands.append((name, [*profile, name, *extra]))
    commands += [
        ("compile-unknown-native", [*compile_native, "-o", str(native_control), str(control)]),
        ("verified-environment-and-jmx-refusal", [*profile, "RuntimeEnvironmentProbe", "true"]),
        ("absent-service", [java, "-cp", str(output / "tests") + os.pathsep + str(bridge), "RuntimeEnvironmentProbe", "false"]),
        ("unsafe-intrinsics-refusal", [java, "-XX:+DisableAttachMechanism", agent, "-cp", str(output / "tests"), "RuntimeEnvironmentProbe", "false"]),
        ("attach-enabled-refusal", [java, "-XX:+UnlockDiagnosticVMOptions", "-XX:-InlineUnsafeOps", agent, "-cp", str(output / "tests"), "RuntimeEnvironmentProbe", "false"]),
        ("native-library-unprotected-control", [java, "-cp", str(output / "tests"), "RuntimeNativeLibraryProbe", str(native_control), "false"]),
        ("native-library-before-initialization-refusal", [*profile, "RuntimeNativeLibraryProbe", str(native_control), "true"]),
    ]
    test_library = output / "libforgia_redefinition_test.so"
    replacement_source = output / "replacement-source/RedefinitionVictim.java"
    replacement_source.parent.mkdir()
    replacement_source.write_bytes(modified.read_bytes())
    commands += [
        ("compile-redefinition-test-service", [*compile_native, "-I" + str(repo / "projects/runtime-proof/src/main/native"),
                                               "-o", str(test_library), str(redefine_agent),
                                               str(repo / "projects/runtime-proof/src/main/native/access_enforcement.c"), "-ldl"]),
        ("compile-alternate-body", [javac, "--release", "21", "-d", str(output / "replacement"), str(replacement_source)]),
        ("reject-actual-jvmti-redefinition", [java, "-XX:+DisableAttachMechanism", "-XX:+UnlockDiagnosticVMOptions", "-XX:-InlineUnsafeOps",
                                             "-agentpath:" + str(test_library) + "=" + str(bridge), "-cp", str(output / "tests"),
                                             "RedefinitionAttemptProbe", str(output / "replacement/RedefinitionVictim.class")]),
    ]
    jni_library = output / "libforgia_jni_test.so"
    commands += [
        ("compile-jni-test-service", [*compile_native, "-I" + str(repo / "projects/runtime-proof/src/main/native"),
                                      "-o", str(jni_library), str(jni_agent),
                                      str(repo / "projects/runtime-proof/src/main/native/access_enforcement.c"), "-ldl"]),
        ("reject-actual-jni-field-and-method-access", [java, "-XX:+DisableAttachMechanism", "-XX:+UnlockDiagnosticVMOptions", "-XX:-InlineUnsafeOps",
                                                      "-agentpath:" + str(jni_library) + "=" + str(bridge), "-cp", str(output / "tests"),
                                                      "JniAccessProbe"]),
    ]
    evidence_library = output / "libforgia_evidence_test.so"
    commands += [
        ("compile-evidence-fault-test-service", [*compile_native, "-I" + str(repo / "projects/runtime-proof/src/main/native"),
                                                "-o", str(evidence_library), str(evidence_agent),
                                                str(repo / "projects/runtime-proof/src/main/native/access_enforcement.c"), "-ldl"]),
        ("reject-malformed-vm-evidence", [java, "-XX:+DisableAttachMechanism", "-XX:+UnlockDiagnosticVMOptions", "-XX:-InlineUnsafeOps",
                                          "-agentpath:" + str(evidence_library) + "=" + str(bridge), "-cp", str(output / "tests"),
                                          "MalformedEvidenceProbe"]),
    ]
    record = {"startedAt": datetime.now(timezone.utc).isoformat(), "sourceHashes": hashes,
              "gitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
              "gitStatus": subprocess.check_output(["git", "status", "--short"], cwd=repo, text=True),
              "javaHome": str(java_home),
              "javaVersion": subprocess.run([java, "-version"], capture_output=True, text=True, check=True).stderr,
              "scope": "Actual VM component controls; these fixtures issue no production Core Ticket or Lease.", "commands": []}
    status = 0
    for index, (name, command) in enumerate(commands):
        try:
            completed = subprocess.run(command, capture_output=True, text=True, timeout=args.command_timeout, cwd=repo)
            text = completed.stdout + completed.stderr
            code = completed.returncode
        except subprocess.TimeoutExpired as failure:
            def decode(value):
                return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
            text = decode(failure.stdout) + decode(failure.stderr) + "\nCOMMAND_TIMEOUT\n"
            code = 124
        except OSError as failure:
            text, code = str(failure) + "\n", 127
        log = output / f"{index:02d}-{name}.log"
        log.write_text(text)
        item = {"name": name, "command": command, "exitCode": code, "log": str(log),
                "sha256": hashlib.sha256(log.read_bytes()).hexdigest()}
        record["commands"].append(item)
        if code:
            status = code
            break
        if name == "native-library-unprotected-control" and "PROBE_UNKNOWN_NATIVE_INITIALIZER_EXECUTED" not in text:
            status = 1
            item["validationError"] = "Unprotected control did not execute the actual native initializer"
            break
        if name == "native-library-before-initialization-refusal" and "PROBE_UNKNOWN_NATIVE_INITIALIZER_EXECUTED" in text:
            status = 1
            item["validationError"] = "Refused native initializer nevertheless executed"
            break
        if name == "reject-actual-jni-field-and-method-access" and "JNI_ACCESS_REFUSED value=0 earlierEffects=0" not in text:
            status = 1
            item["validationError"] = "JNI access control did not prove the original authority boundary"
            break
        print(name, "PASS", flush=True)
    record["finishedAt"] = datetime.now(timezone.utc).isoformat()
    record["outcome"] = "VM_COMPONENT_CONTROLS_PASS_NO_CORE_MOD_PERMISSION_ISSUED" if status == 0 else "VM_COMPONENT_CONTROL_FAILED"
    record["exitCode"] = status
    record["artifactHashes"] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (library, bridge, native_control, test_library, jni_library, evidence_library) if p.exists()}
    (output / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    print(record["outcome"], flush=True)
    return status


if __name__ == "__main__":
    sys.exit(main())
