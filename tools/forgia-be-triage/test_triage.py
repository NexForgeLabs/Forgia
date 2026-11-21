#!/usr/bin/env python3
"""Unit tests for tools/forgia-be-triage/triage.py using synthetic JVM class files."""

import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

import triage


def build_synthetic_class(
    this_name: str,
    super_name: str,
    method_name: str,
    method_desc: str,
    called_owner: str,
    called_name: str,
    called_desc: str,
    putstatic_field: bool = False,
) -> bytes:
    """Build a minimal valid JVM class file with one method that invokes one target method."""
    utf8_pool = [
        this_name,               # 1
        super_name,              # 2
        method_name,             # 3
        method_desc,             # 4
        "Code",                  # 5
        called_owner,            # 6
        called_name,             # 7
        called_desc,             # 8
        "staticCounter",         # 9
        "I",                     # 10
    ]
    cp = bytearray()
    for s in utf8_pool:
        b = s.encode("utf-8")
        cp += struct.pack(">BH", 1, len(b)) + b
    # 11: Class this_name (1)
    cp += struct.pack(">BH", 7, 1)
    # 12: Class super_name (2)
    cp += struct.pack(">BH", 7, 2)
    # 13: Class called_owner (6)
    cp += struct.pack(">BH", 7, 6)
    # 14: NameAndType called (7, 8)
    cp += struct.pack(">BHH", 12, 7, 8)
    # 15: Methodref (13, 14)
    cp += struct.pack(">BHH", 10, 13, 14)
    # 16: NameAndType field (9, 10)
    cp += struct.pack(">BHH", 12, 9, 10)
    # 17: Fieldref (11, 16)
    cp += struct.pack(">BHH", 9, 11, 16)

    cp_count = 18
    if putstatic_field:
        # iconst_1 (0x04), putstatic #17 (0xB3 0x00 0x11), return (0xB1)
        code = bytes([0x04, 0xB3, 0x00, 17, 0xB1])
    else:
        # invokestatic #15 (0xB8 0x00 0x0F), return (0xB1)
        code = bytes([0xB8, 0x00, 15, 0xB1])

    code_attr_body = struct.pack(">HHI", 2, 2, len(code)) + code + struct.pack(">HH", 0, 0)
    code_attr = struct.pack(">HI", 5, len(code_attr_body)) + code_attr_body

    header = struct.pack(">IHHH", 0xCAFEBABE, 0, 65, cp_count) + bytes(cp)
    body = struct.pack(">HHHH", 0x0021, 11, 12, 0)  # public class, 0 interfaces
    body += struct.pack(">H", 0)  # 0 fields
    body += struct.pack(">H", 1)  # 1 method
    body += struct.pack(">HHHH", 0x0001, 3, 4, 1) + code_attr
    body += struct.pack(">H", 0)  # 0 class attributes
    return header + body


class TriageTest(unittest.TestCase):
    def _make_jar(self, dir_path: Path, mod_id: str, classes: dict) -> Path:
        jar_path = dir_path / f"{mod_id}.jar"
        with zipfile.ZipFile(jar_path, "w") as zf:
            zf.writestr("META-INF/neoforge.mods.toml", f'[[mods]]\nmodId = "{mod_id}"\n')
            for cls_name, raw in classes.items():
                zf.writestr(f"{cls_name}.class", raw)
        return jar_path

    def test_classifies_serial_local_and_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            # 1. DEFINITELY_SERIAL: calls Level.addFreshEntity
            serial_cls = build_synthetic_class(
                "com/example/SerialSpawnerBlockEntity",
                triage.BLOCK_ENTITY_INTERNAL,
                "serverTick",
                "()V",
                "net/minecraft/world/level/Level",
                "addFreshEntity",
                "(Lnet/minecraft/world/entity/Entity;)Z",
            )
            # 2. LOCAL_CANDIDATE (RESOURCE_SCOPED): only calls Mth.clamp
            local_cls = build_synthetic_class(
                "com/example/LocalCounterBlockEntity",
                triage.BLOCK_ENTITY_INTERNAL,
                "serverTick",
                "()V",
                "net/minecraft/util/Mth",
                "clamp",
                "(III)I",
            )
            # 3. LOCAL_CANDIDATE (REGION_OWNER): calls BlockEntity.setChanged
            owner_cls = build_synthetic_class(
                "com/example/DirtyMachineBlockEntity",
                triage.BLOCK_ENTITY_INTERNAL,
                "tick",
                "()V",
                triage.BLOCK_ENTITY_INTERNAL,
                "setChanged",
                "()V",
            )
            # 4. UNKNOWN: calls Level.getCapability
            unknown_cls = build_synthetic_class(
                "com/example/PipeBlockEntity",
                triage.BLOCK_ENTITY_INTERNAL,
                "tick",
                "()V",
                "net/minecraft/world/level/Level",
                "getCapability",
                "()Ljava/lang/Object;",
            )

            jar = self._make_jar(
                tmp_path,
                "examplemod",
                {
                    "com/example/SerialSpawnerBlockEntity": serial_cls,
                    "com/example/LocalCounterBlockEntity": local_cls,
                    "com/example/DirtyMachineBlockEntity": owner_cls,
                    "com/example/PipeBlockEntity": unknown_cls,
                },
            )

            _, results = triage.analyze_paths([jar])
            by_name = {r.class_name: r for r in results}

            self.assertEqual("DEFINITELY_SERIAL", by_name["com.example.SerialSpawnerBlockEntity"].classification)
            self.assertEqual("LOCAL_CANDIDATE", by_name["com.example.LocalCounterBlockEntity"].classification)
            self.assertEqual("RESOURCE_SCOPED", by_name["com.example.LocalCounterBlockEntity"].recommended_mode)
            self.assertEqual("LOCAL_CANDIDATE", by_name["com.example.DirtyMachineBlockEntity"].classification)
            self.assertEqual("REGION_OWNER", by_name["com.example.DirtyMachineBlockEntity"].recommended_mode)
            self.assertEqual("UNKNOWN", by_name["com.example.PipeBlockEntity"].classification)

            decl = triage.build_declarations_json(results)
            self.assertEqual(1, len(decl["block_entities"]))
            self.assertEqual("examplemod:serial_spawner", decl["block_entities"][0]["type"])

            skel = triage.generate_adapter_skeleton("examplemod", results, tmp_path / "out")
            self.assertIsNotNone(skel)
            text = skel.read_text(encoding="utf-8")
            self.assertIn("implements BlockEntityExecutionAdapter", text)
            self.assertIn("com.example.LocalCounterBlockEntity", text)
            self.assertIn("com.example.DirtyMachineBlockEntity", text)


if __name__ == "__main__":
    unittest.main()
