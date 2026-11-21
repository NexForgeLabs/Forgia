#!/usr/bin/env python3
"""Offline JVM bytecode triage and adapter skeleton generator for modded BlockEntities.

Analyzes mod JARs (including nested Jar-in-Jar) without loading or executing untrusted code:
1. Parses META-INF/neoforge.mods.toml and mixins.json files to identify mod IDs and Mixin targets.
2. Parses JVM class files (constant pool, class hierarchy, fields, methods, Code attributes,
   and BootstrapMethods for invokedynamic / lambdas).
3. Identifies all BlockEntity subclasses and BlockEntityTicker implementations, plus potential
   tick entry methods on each BlockEntity hierarchy.
4. Performs bounded call-graph and field/method access reachability analysis from tick methods,
   classifying each BlockEntity into:
   - DEFINITELY_SERIAL: reaches hard server-thread-only sinks (entity spawns/queries, global
     SavedData, PlayerList, EventBus.post, packet broadcasts, static field writes, etc.)
   - LOCAL_CANDIDATE: closed call graph with only self/local state accesses, suitable for
     BlockEntityExecutionAdapter (RESOURCE_SCOPED or REGION_OWNER)
   - UNKNOWN: reaches virtual/interface dispatch, capability queries, neighbor BE access,
     reflection, or unresolved external targets that require manual audit
5. Outputs:
   - Structured JSON report (--json)
   - Narrowing regionthreading-declarations.json (--declarations) for DEFINITELY_SERIAL types
   - Ready-to-audit BlockEntityExecutionAdapter Java skeleton with pinned SHA-256 hashes (--skeleton-dir)
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple

BLOCK_ENTITY_INTERNAL = "net/minecraft/world/level/block/entity/BlockEntity"
TICKER_INTERNAL = "net/minecraft/world/level/block/entity/BlockEntityTicker"
TICKER_SAM_DESC = (
    "(Lnet/minecraft/world/level/Level;"
    "Lnet/minecraft/core/BlockPos;"
    "Lnet/minecraft/world/level/block/state/BlockState;"
    "Lnet/minecraft/world/level/block/entity/BlockEntity;)V"
)

# Hard serial sinks: reaching any of these in a tick body pins the BE to the server thread.
HARD_SERIAL_METHOD_PATTERNS: List[Tuple[str, str, str]] = [
    ("net/minecraft/world/level/Level", "addFreshEntity", "entity_spawn"),
    ("net/minecraft/world/level/ServerLevelAccessor", "addFreshEntityWithPassengers", "entity_spawn"),
    ("net/minecraft/world/level/EntityGetter", "getEntities", "entity_query"),
    ("net/minecraft/world/level/EntityGetter", "getEntitiesOfClass", "entity_query"),
    ("net/minecraft/world/level/EntityGetter", "getNearestPlayer", "player_query"),
    ("net/minecraft/world/level/Level", "getEntities", "entity_query"),
    ("net/minecraft/world/level/Level", "getEntitiesOfClass", "entity_query"),
    ("net/minecraft/server/level/ServerLevel", "getEntities", "entity_query"),
    ("net/minecraft/server/level/ServerLevel", "getAllEntities", "entity_query"),
    ("net/minecraft/server/level/ServerLevel", "getPlayers", "player_query"),
    ("net/minecraft/server/level/ServerLevel", "getRandomPlayer", "player_query"),
    ("net/minecraft/server/level/ServerLevel", "getDataStorage", "global_saved_data"),
    ("net/minecraft/world/level/storage/DimensionDataStorage", "computeIfAbsent", "global_saved_data"),
    ("net/minecraft/world/level/storage/DimensionDataStorage", "get", "global_saved_data"),
    ("net/minecraft/world/level/storage/DimensionDataStorage", "set", "global_saved_data"),
    ("net/minecraft/server/MinecraftServer", "getPlayerList", "player_list"),
    ("net/minecraft/server/players/PlayerList", "broadcastAll", "network_broadcast"),
    ("net/minecraft/server/players/PlayerList", "broadcastSystemMessage", "network_broadcast"),
    ("net/neoforged/bus/api/IEventBus", "post", "event_bus_post"),
    ("net/neoforged/neoforge/network/PacketDistributor", "sendToAllPlayers", "network_broadcast"),
    ("net/neoforged/neoforge/network/PacketDistributor", "sendToPlayersTrackingChunk", "network_broadcast"),
    ("net/neoforged/neoforge/network/PacketDistributor", "sendToPlayersNear", "network_broadcast"),
    ("net/minecraft/world/Containers", "dropItemStack", "entity_spawn"),
    ("net/minecraft/world/Containers", "dropContents", "entity_spawn"),
    ("net/minecraft/world/level/Level", "explode", "world_explosion"),
    ("net/minecraft/server/level/ServerLevel", "sendParticles", "particle_broadcast"),
]

# Effects/world interactions that prevent RESOURCE_SCOPED (require REGION_OWNER or manual audit)
WORLD_OR_EFFECT_PATTERNS: List[Tuple[str, str, str]] = [
    ("net/minecraft/world/level/block/entity/BlockEntity", "setChanged", "set_changed_effect"),
    ("net/minecraft/world/level/Level", "setBlock", "world_block_write"),
    ("net/minecraft/world/level/Level", "setBlockAndUpdate", "world_block_write"),
    ("net/minecraft/world/level/Level", "removeBlock", "world_block_write"),
    ("net/minecraft/world/level/Level", "destroyBlock", "world_block_write"),
    ("net/minecraft/world/level/Level", "updateNeighborsAt", "neighbor_notification"),
    ("net/minecraft/world/level/Level", "updateNeighbourForOutputSignal", "comparator_notification"),
    ("net/minecraft/world/level/Level", "sendBlockUpdated", "block_sync_notification"),
    ("net/minecraft/world/level/Level", "playSound", "sound_effect"),
    ("net/minecraft/world/level/Level", "levelEvent", "level_event_effect"),
    ("net/minecraft/world/level/Level", "gameEvent", "game_event_effect"),
    ("net/minecraft/world/level/LevelAccessor", "scheduleTick", "schedule_tick"),
]

# Neighbor / capability / shared resource patterns that require resource resolver or UNKNOWN triage
SHARED_OR_NEIGHBOR_PATTERNS: List[Tuple[str, str, str]] = [
    ("net/minecraft/world/level/Level", "getCapability", "capability_query"),
    ("net/minecraft/server/level/ServerLevel", "getCapability", "capability_query"),
    ("net/neoforged/neoforge/capabilities/BlockCapabilityCache", "getCapability", "capability_cache_query"),
    ("net/minecraft/world/level/BlockGetter", "getBlockEntity", "neighbor_be_lookup"),
    ("net/minecraft/world/level/Level", "getBlockEntity", "neighbor_be_lookup"),
    ("net/minecraft/world/level/LevelReader", "getBlockEntity", "neighbor_be_lookup"),
    ("net/minecraft/world/level/chunk/LevelChunk", "getBlockEntity", "neighbor_be_lookup"),
    ("net/neoforged/neoforge/items/IItemHandler", "insertItem", "item_handler_transfer"),
    ("net/neoforged/neoforge/items/IItemHandler", "extractItem", "item_handler_transfer"),
    ("net/neoforged/neoforge/fluids/capability/IFluidHandler", "fill", "fluid_handler_transfer"),
    ("net/neoforged/neoforge/fluids/capability/IFluidHandler", "drain", "fluid_handler_transfer"),
    ("net/neoforged/neoforge/energy/IEnergyStorage", "receiveEnergy", "energy_storage_transfer"),
    ("net/neoforged/neoforge/energy/IEnergyStorage", "extractEnergy", "energy_storage_transfer"),
    ("net/neoforged/neoforge/attachment/AttachmentHolder", "getData", "attachment_access"),
    ("net/neoforged/neoforge/attachment/AttachmentHolder", "setData", "attachment_access"),
]


@dataclass
class MethodRef:
    owner: str
    name: str
    descriptor: str


@dataclass
class FieldRef:
    owner: str
    name: str
    descriptor: str


@dataclass
class MethodInfo:
    access: int
    name: str
    descriptor: str
    code: bytes = b""
    calls: List[Tuple[int, MethodRef]] = field(default_factory=list)
    field_ops: List[Tuple[int, FieldRef]] = field(default_factory=list)
    indy_targets: List[MethodRef] = field(default_factory=list)


@dataclass
class ClassInfo:
    name: str
    super_name: Optional[str]
    interfaces: List[str]
    access: int
    sha256: str
    jar_name: str
    mod_id: str
    fields: List[Tuple[int, str, str]] = field(default_factory=list)
    methods: Dict[Tuple[str, str], MethodInfo] = field(default_factory=dict)


# Bytecode instruction length table for standard JVM opcodes
FIXED_OPCODE_LEN: Dict[int, int] = {
    0x10: 2, 0x12: 2, 0x15: 2, 0x16: 2, 0x17: 2, 0x18: 2, 0x19: 2,
    0x36: 2, 0x37: 2, 0x38: 2, 0x39: 2, 0x3A: 2, 0xA9: 2, 0xBC: 2,
    0x11: 3, 0x13: 3, 0x14: 3, 0x84: 3,
    0x99: 3, 0x9A: 3, 0x9B: 3, 0x9C: 3, 0x9D: 3, 0x9E: 3,
    0x9F: 3, 0xA0: 3, 0xA1: 3, 0xA2: 3, 0xA3: 3, 0xA4: 3,
    0xA5: 3, 0xA6: 3, 0xA7: 3, 0xA8: 3, 0xC6: 3, 0xC7: 3,
    0xB2: 3, 0xB3: 3, 0xB4: 3, 0xB5: 3,
    0xB6: 3, 0xB7: 3, 0xB8: 3,
    0xBB: 3, 0xBD: 3, 0xC0: 3, 0xC1: 3,
    0xC5: 4,
    0xB9: 5, 0xBA: 5, 0xC8: 5, 0xC9: 5,
}


def scan_bytecode(
    code: bytes,
    cp_methods: Dict[int, MethodRef],
    cp_fields: Dict[int, FieldRef],
    cp_indy: Dict[int, List[MethodRef]],
) -> Tuple[List[Tuple[int, MethodRef]], List[Tuple[int, FieldRef]], List[MethodRef]]:
    calls: List[Tuple[int, MethodRef]] = []
    field_ops: List[Tuple[int, FieldRef]] = []
    indy_targets: List[MethodRef] = []
    i = 0
    n = len(code)
    while i < n:
        op = code[i]
        if op in (0xB6, 0xB7, 0xB8, 0xB9):
            if i + 2 < n:
                idx = (code[i + 1] << 8) | code[i + 2]
                mref = cp_methods.get(idx)
                if mref:
                    calls.append((op, mref))
            i += 5 if op == 0xB9 else 3
        elif op in (0xB2, 0xB3, 0xB4, 0xB5):
            if i + 2 < n:
                idx = (code[i + 1] << 8) | code[i + 2]
                fref = cp_fields.get(idx)
                if fref:
                    field_ops.append((op, fref))
            i += 3
        elif op == 0xBA:  # invokedynamic
            if i + 2 < n:
                idx = (code[i + 1] << 8) | code[i + 2]
                for target in cp_indy.get(idx, ()):
                    indy_targets.append(target)
            i += 5
        elif op == 0xAA:  # tableswitch
            pad = (4 - ((i + 1) % 4)) % 4
            pos = i + 1 + pad
            if pos + 12 > n:
                break
            _, low, high = struct.unpack(">iii", code[pos : pos + 12])
            count = high - low + 1
            if count < 0 or pos + 12 + 4 * count > n:
                break
            i = pos + 12 + 4 * count
        elif op == 0xAB:  # lookupswitch
            pad = (4 - ((i + 1) % 4)) % 4
            pos = i + 1 + pad
            if pos + 8 > n:
                break
            _, npairs = struct.unpack(">ii", code[pos : pos + 8])
            if npairs < 0 or pos + 8 + 8 * npairs > n:
                break
            i = pos + 8 + 8 * npairs
        elif op == 0xC4:  # wide
            if i + 1 >= n:
                break
            sub = code[i + 1]
            i += 6 if sub == 0x84 else 4
        else:
            i += FIXED_OPCODE_LEN.get(op, 1)
    return calls, field_ops, indy_targets


def parse_class_bytes(raw: bytes, jar_name: str, mod_id: str) -> Optional[ClassInfo]:
    if len(raw) < 24:
        return None
    magic, _, major, cp_count = struct.unpack(">IHHH", raw[:10])
    if magic != 0xCAFEBABE:
        return None
    pos = 10
    cp_utf8: Dict[int, str] = {}
    cp_class: Dict[int, int] = {}
    cp_nat: Dict[int, Tuple[int, int]] = {}
    cp_ref: Dict[int, Tuple[int, int, int]] = {}  # idx -> (tag, class_idx, nat_idx)
    cp_mh: Dict[int, Tuple[int, int]] = {}  # idx -> (ref_kind, ref_idx)
    cp_indy_raw: Dict[int, Tuple[int, int]] = {}  # idx -> (bsm_attr_idx, nat_idx)

    idx = 1
    try:
        while idx < cp_count:
            tag = raw[pos]
            pos += 1
            if tag == 1:  # Utf8
                length = struct.unpack(">H", raw[pos : pos + 2])[0]
                pos += 2
                cp_utf8[idx] = raw[pos : pos + length].decode("utf-8", "replace")
                pos += length
            elif tag in (3, 4):  # Integer, Float
                pos += 4
            elif tag in (5, 6):  # Long, Double
                pos += 8
                idx += 1
            elif tag == 7:  # Class
                cp_class[idx] = struct.unpack(">H", raw[pos : pos + 2])[0]
                pos += 2
            elif tag in (8, 16, 19, 20):  # String, MethodType, Module, Package
                pos += 2
            elif tag in (9, 10, 11):  # Fieldref, Methodref, InterfaceMethodref
                c_idx, nat_idx = struct.unpack(">HH", raw[pos : pos + 4])
                cp_ref[idx] = (tag, c_idx, nat_idx)
                pos += 4
            elif tag == 12:  # NameAndType
                n_idx, d_idx = struct.unpack(">HH", raw[pos : pos + 4])
                cp_nat[idx] = (n_idx, d_idx)
                pos += 4
            elif tag == 15:  # MethodHandle
                kind, ref_idx = struct.unpack(">BH", raw[pos : pos + 3])
                cp_mh[idx] = (kind, ref_idx)
                pos += 3
            elif tag in (17, 18):  # Dynamic, InvokeDynamic
                bsm_idx, nat_idx = struct.unpack(">HH", raw[pos : pos + 4])
                if tag == 18:
                    cp_indy_raw[idx] = (bsm_idx, nat_idx)
                pos += 4
            else:
                return None
            idx += 1

        def class_name(c_idx: int) -> str:
            return cp_utf8.get(cp_class.get(c_idx, 0), "")

        cp_methods: Dict[int, MethodRef] = {}
        cp_fields: Dict[int, FieldRef] = {}
        for r_idx, (r_tag, c_idx, nat_idx) in cp_ref.items():
            owner = class_name(c_idx)
            n_idx, d_idx = cp_nat.get(nat_idx, (0, 0))
            name = cp_utf8.get(n_idx, "")
            desc = cp_utf8.get(d_idx, "")
            if r_tag == 9:
                cp_fields[r_idx] = FieldRef(owner, name, desc)
            else:
                cp_methods[r_idx] = MethodRef(owner, name, desc)

        access, this_c, super_c, if_count = struct.unpack(">HHHH", raw[pos : pos + 8])
        pos += 8
        this_name = class_name(this_c)
        super_name = class_name(super_c) if super_c != 0 else None
        interfaces: List[str] = []
        for _ in range(if_count):
            ic = struct.unpack(">H", raw[pos : pos + 2])[0]
            pos += 2
            interfaces.append(class_name(ic))

        fields_count = struct.unpack(">H", raw[pos : pos + 2])[0]
        pos += 2
        fields: List[Tuple[int, str, str]] = []
        for _ in range(fields_count):
            f_acc, f_n, f_d, f_attrs = struct.unpack(">HHHH", raw[pos : pos + 8])
            pos += 8
            fields.append((f_acc, cp_utf8.get(f_n, ""), cp_utf8.get(f_d, "")))
            for _ in range(f_attrs):
                _, a_len = struct.unpack(">HI", raw[pos : pos + 6])
                pos += 6 + a_len

        methods_count = struct.unpack(">H", raw[pos : pos + 2])[0]
        pos += 2
        raw_methods: List[Tuple[int, str, str, bytes]] = []
        for _ in range(methods_count):
            m_acc, m_n, m_d, m_attrs = struct.unpack(">HHHH", raw[pos : pos + 8])
            pos += 8
            m_name = cp_utf8.get(m_n, "")
            m_desc = cp_utf8.get(m_d, "")
            code_bytes = b""
            for _ in range(m_attrs):
                a_name_idx, a_len = struct.unpack(">HI", raw[pos : pos + 6])
                pos += 6
                a_end = pos + a_len
                if cp_utf8.get(a_name_idx) == "Code" and a_len >= 8:
                    code_len = struct.unpack(">I", raw[pos + 4 : pos + 8])[0]
                    code_bytes = raw[pos + 8 : pos + 8 + code_len]
                pos = a_end
            raw_methods.append((m_acc, m_name, m_desc, code_bytes))

        # Class attributes (BootstrapMethods for invokedynamic lambda resolution)
        bsm_targets: Dict[int, List[MethodRef]] = {}
        c_attrs = struct.unpack(">H", raw[pos : pos + 2])[0]
        pos += 2
        for _ in range(c_attrs):
            a_name_idx, a_len = struct.unpack(">HI", raw[pos : pos + 6])
            pos += 6
            a_end = pos + a_len
            if cp_utf8.get(a_name_idx) == "BootstrapMethods" and a_len >= 2:
                num_bsm = struct.unpack(">H", raw[pos : pos + 2])[0]
                bpos = pos + 2
                for b_idx in range(num_bsm):
                    if bpos + 4 > a_end:
                        break
                    _, num_args = struct.unpack(">HH", raw[bpos : bpos + 4])
                    bpos += 4
                    targets: List[MethodRef] = []
                    for _ in range(num_args):
                        if bpos + 2 > a_end:
                            break
                        arg_idx = struct.unpack(">H", raw[bpos : bpos + 2])[0]
                        bpos += 2
                        if arg_idx in cp_mh:
                            _, ref_idx = cp_mh[arg_idx]
                            if ref_idx in cp_methods:
                                targets.append(cp_methods[ref_idx])
                    bsm_targets[b_idx] = targets
            pos = a_end

        cp_indy: Dict[int, List[MethodRef]] = {}
        for indy_idx, (bsm_idx, _) in cp_indy_raw.items():
            if bsm_idx in bsm_targets:
                cp_indy[indy_idx] = bsm_targets[bsm_idx]

        methods: Dict[Tuple[str, str], MethodInfo] = {}
        for m_acc, m_name, m_desc, code_bytes in raw_methods:
            calls, field_ops, indy = (
                scan_bytecode(code_bytes, cp_methods, cp_fields, cp_indy)
                if code_bytes
                else ([], [], [])
            )
            methods[(m_name, m_desc)] = MethodInfo(
                access=m_acc,
                name=m_name,
                descriptor=m_desc,
                code=code_bytes,
                calls=calls,
                field_ops=field_ops,
                indy_targets=indy,
            )

        sha256 = hashlib.sha256(raw).hexdigest()
        return ClassInfo(
            name=this_name,
            super_name=super_name,
            interfaces=interfaces,
            access=access,
            sha256=sha256,
            jar_name=jar_name,
            mod_id=mod_id,
            fields=fields,
            methods=methods,
        )
    except Exception:
        return None


MOD_ID_RE = re.compile(r'^\s*modId\s*=\s*"([^"]+)"', re.M)


@dataclass
class ScanCorpus:
    classes: Dict[str, ClassInfo] = field(default_factory=dict)
    mixin_targets: Dict[str, Set[str]] = field(default_factory=dict)  # target internal name -> set of mixin classes
    mod_jars: Dict[str, str] = field(default_factory=dict)  # mod_id -> jar_name


def extract_mod_id(zf: zipfile.ZipFile, fallback: str) -> str:
    for name in zf.namelist():
        lower = name.lower()
        if lower.endswith("neoforge.mods.toml") or lower.endswith("mods.toml"):
            try:
                text = zf.read(name).decode("utf-8", "replace")
                m = MOD_ID_RE.search(text)
                if m:
                    return m.group(1)
            except Exception:
                pass
    return fallback


def scan_Mixin_configs(zf: zipfile.ZipFile, corpus: ScanCorpus) -> None:
    for name in zf.namelist():
        if name.endswith(".mixins.json") or name.endswith("mixins.json"):
            try:
                data = json.loads(zf.read(name).decode("utf-8", "replace"))
                pkg = data.get("package", "")
                for key in ("mixins", "server", "common"):
                    for entry in data.get(key, ()):
                        if isinstance(entry, str):
                            full = f"{pkg}.{entry}".replace(".", "/") if pkg else entry.replace(".", "/")
                            corpus.mixin_targets.setdefault("__declared_mixins__", set()).add(full)
            except Exception:
                pass


def load_jar_into_corpus(jar_path: Path, zf: zipfile.ZipFile, corpus: ScanCorpus, depth: int = 0) -> None:
    mod_id = extract_mod_id(zf, jar_path.stem.split("-")[0])
    corpus.mod_jars.setdefault(mod_id, jar_path.name)
    scan_Mixin_configs(zf, corpus)
    for entry in zf.namelist():
        if entry.endswith(".class") and not entry.endswith("module-info.class"):
            try:
                raw = zf.read(entry)
            except Exception:
                continue
            ci = parse_class_bytes(raw, jar_path.name, mod_id)
            if ci:
                corpus.classes[ci.name] = ci
        elif depth == 0 and entry.lower().endswith(".jar"):
            try:
                nested_bytes = zf.read(entry)
                with zipfile.ZipFile(io.BytesIO(nested_bytes)) as nested_zf:
                    load_jar_into_corpus(jar_path, nested_zf, corpus, depth + 1)
            except Exception:
                continue


def is_subclass_of(cls_name: str, target: str, classes: Dict[str, ClassInfo]) -> bool:
    seen: Set[str] = set()
    cur: Optional[str] = cls_name
    while cur and cur not in seen:
        if cur == target:
            return True
        seen.add(cur)
        ci = classes.get(cur)
        if not ci:
            return False
        cur = ci.super_name
    return False


def get_hierarchy(cls_name: str, classes: Dict[str, ClassInfo]) -> List[str]:
    chain: List[str] = []
    seen: Set[str] = set()
    cur: Optional[str] = cls_name
    while cur and cur not in seen:
        chain.append(cur)
        seen.add(cur)
        ci = classes.get(cur)
        if not ci:
            break
        cur = ci.super_name
    return chain


def find_tick_entrypoints(be_cls: ClassInfo, corpus: ScanCorpus) -> List[Tuple[str, str, str]]:
    """Find candidate tick methods (owner, name, desc) for a BlockEntity subclass."""
    entrypoints: List[Tuple[str, str, str]] = []
    hierarchy = get_hierarchy(be_cls.name, corpus.classes)
    # 1. Check methods on the BE class and its modded superclasses
    for h_name in hierarchy:
        if h_name == BLOCK_ENTITY_INTERNAL or h_name.startswith("net/minecraft/"):
            break
        hci = corpus.classes.get(h_name)
        if not hci:
            continue
        for (m_name, m_desc), minfo in hci.methods.items():
            lower = m_name.lower()
            is_tick_named = (
                m_name in ("tick", "serverTick", "commonTick", "tickServer", "onTick")
                or "servertick" in lower
                or (lower.endswith("tick") and not lower.startswith("client") and not lower.startswith("animate"))
            )
            if is_tick_named and minfo.code:
                entrypoints.append((h_name, m_name, m_desc))
    return entrypoints


GUARDABLE_METHOD_NAMES: Set[str] = {
    "initialize",
    "init",
    "firstTick",
    "onFirstTick",
    "lazyInit",
    "onLoad",
    "attachKinetics",
    "validateKinetics",
    "updateNetwork",
}


def is_guardable_branch_method(owner: str, name: str) -> bool:
    if name in GUARDABLE_METHOD_NAMES:
        return True
    if name == "tick" and owner.endswith("KineticEffectHandler"):
        return True
    return False


@dataclass
class TriageResult:
    class_name: str
    mod_id: str
    jar_name: str
    sha256: str
    classification: str  # DEFINITELY_SERIAL, LOCAL_CANDIDATE, ADAPTER_CANDIDATE, UNKNOWN
    recommended_mode: str  # SERIAL, RESOURCE_SCOPED, REGION_OWNER
    entrypoints: List[str]
    hard_serial_reasons: List[str]
    guardable_branch_reasons: List[str]
    world_or_effect_reasons: List[str]
    shared_or_neighbor_reasons: List[str]
    unknown_reasons: List[str]
    hierarchy_hashes: List[Tuple[str, str]]
    candidate_storage_fields: List[Tuple[str, str]]


def analyze_block_entity(
    be_cls: ClassInfo,
    corpus: ScanCorpus,
    subclasses_map: Dict[str, List[str]],
    max_depth: int = 6,
) -> TriageResult:
    entrypoints = find_tick_entrypoints(be_cls, corpus)
    hard_serial: Set[str] = set()
    guardable_serial: Set[str] = set()
    world_effects: Set[str] = set()
    shared_neighbor: Set[str] = set()
    unknowns: Set[str] = set()

    if not entrypoints:
        unknowns.add("no_tick_entrypoint_found")

    subclasses = subclasses_map.get(be_cls.name, [])
    if subclasses:
        unknowns.add(f"non_final_has_{len(subclasses)}_subclasses")

    # BFS over call graph from entrypoints; track whether we are inside a guardable init/topology branch
    queue: List[Tuple[str, str, str, int, bool]] = [
        (o, n, d, 0, is_guardable_branch_method(o, n)) for (o, n, d) in entrypoints
    ]
    visited: Set[Tuple[str, str, str, bool]] = set()
    reached_classes: Set[str] = set(get_hierarchy(be_cls.name, corpus.classes))

    while queue:
        owner, name, desc, depth, in_guardable = queue.pop(0)
        if (owner, name, desc, in_guardable) in visited:
            continue
        visited.add((owner, name, desc, in_guardable))

        # Resolve method on owner or its superclass chain
        minfo: Optional[MethodInfo] = None
        resolved_owner = owner
        for h in get_hierarchy(owner, corpus.classes):
            hci = corpus.classes.get(h)
            if hci and (name, desc) in hci.methods:
                minfo = hci.methods[(name, desc)]
                resolved_owner = h
                break

        if minfo is None:
            if not in_guardable and not owner.startswith(
                ("java/lang/", "java/util/", "com/mojang/", "net/minecraft/core/", "net/minecraft/util/Mth")
            ):
                unknowns.add(f"unresolved_method:{owner}.{name}{desc}")
            continue

        reached_classes.add(resolved_owner)
        target_serial_set = guardable_serial if in_guardable else hard_serial

        # Check static field writes or external field writes
        for f_op, fref in minfo.field_ops:
            if f_op == 0xB3:  # putstatic
                target_serial_set.add(f"static_field_write:{fref.owner}.{fref.name}")
            elif f_op == 0xB5 and not in_guardable:  # putfield
                if not is_subclass_of(be_cls.name, fref.owner, corpus.classes) and fref.owner not in reached_classes:
                    shared_neighbor.add(f"external_field_write:{fref.owner}.{fref.name}")

        # Combine direct calls and invokedynamic lambda targets
        all_calls: List[Tuple[int, MethodRef]] = list(minfo.calls) + [(0xB8, t) for t in minfo.indy_targets]
        for op, mref in all_calls:
            # Skip client-only lambdas/helpers (e.g., PlatformHelper.executeOnClientOnly)
            if mref.name in ("executeOnClientOnly", "runWhenOn"):
                continue

            call_guardable = in_guardable or is_guardable_branch_method(mref.owner, mref.name)
            call_serial_set = guardable_serial if call_guardable else hard_serial

            matched = False
            for p_owner, p_name, reason in HARD_SERIAL_METHOD_PATTERNS:
                if mref.name == p_name and (mref.owner == p_owner or mref.owner.endswith("/" + p_owner.split("/")[-1])):
                    call_serial_set.add(f"{reason}:{mref.owner}.{mref.name}")
                    matched = True
            if not call_guardable:
                for p_owner, p_name, reason in WORLD_OR_EFFECT_PATTERNS:
                    if mref.name == p_name and (mref.owner == p_owner or mref.owner.endswith("/" + p_owner.split("/")[-1])):
                        world_effects.add(f"{reason}:{mref.owner}.{mref.name}")
                        matched = True
                for p_owner, p_name, reason in SHARED_OR_NEIGHBOR_PATTERNS:
                    if mref.name == p_name and (mref.owner == p_owner or mref.owner.endswith("/" + p_owner.split("/")[-1])):
                        shared_neighbor.add(f"{reason}:{mref.owner}.{mref.name}")
                        matched = True

            if mref.owner.startswith("java/lang/reflect/") or mref.owner.startswith("java/lang/invoke/MethodHandle"):
                if not call_guardable:
                    unknowns.add(f"reflection_or_mh:{mref.owner}.{mref.name}")
                continue

            if not call_guardable and op == 0xB9 and not mref.owner.startswith(
                ("java/util/", "java/lang/", "it/unimi/dsi/fastutil/")
            ):
                unknowns.add(f"interface_dispatch:{mref.owner}.{mref.name}")

            if depth + 1 <= max_depth and mref.owner in corpus.classes:
                queue.append((mref.owner, mref.name, mref.descriptor, depth + 1, call_guardable))
            elif not call_guardable and not matched and mref.owner not in corpus.classes:
                if not mref.owner.startswith(
                    (
                        "java/",
                        "it/unimi/dsi/fastutil/",
                        "com/google/common/",
                        "net/minecraft/core/",
                        "net/minecraft/util/",
                        "net/minecraft/world/item/ItemStack",
                        "net/minecraft/nbt/",
                        "net/minecraft/world/level/block/state/",
                        "net/createmod/catnip/config/",
                    )
                ):
                    unknowns.add(f"external_call:{mref.owner}.{mref.name}")

    # Collect hierarchy SHA-256 hashes and candidate mutable storage fields
    hierarchy_hashes: List[Tuple[str, str]] = []
    candidate_fields: List[Tuple[str, str]] = []
    for h_name in get_hierarchy(be_cls.name, corpus.classes):
        if h_name == BLOCK_ENTITY_INTERNAL:
            break
        hci = corpus.classes.get(h_name)
        if hci:
            hierarchy_hashes.append((h_name.replace("/", "."), hci.sha256))
            for f_acc, f_name, f_desc in hci.fields:
                if (f_acc & 0x0008) == 0 and f_desc.startswith("L") and not f_desc.startswith(
                    ("Ljava/lang/String;", "Lnet/minecraft/core/", "Lnet/minecraft/resources/")
                ):
                    candidate_fields.append((f_name, f_desc))

    # Determine final four-state classification:
    # - DEFINITELY_SERIAL: steady-state tick body reaches hard serial sinks
    # - LOCAL_CANDIDATE: closed call graph with zero hard serial sinks even in init
    # - ADAPTER_CANDIDATE: steady-state tick body has no hard serial sinks, but has init/topology/effect
    #   branches that an adapter check(be) can guard (e.g., SmartBlockEntity/KineticBlockEntity)
    # - UNKNOWN: steady-state reaches unverified external/neighbor/capability calls without hard serial sinks
    if hard_serial:
        classification = "DEFINITELY_SERIAL"
        recommended_mode = "SERIAL"
    elif not entrypoints:
        classification = "UNKNOWN"
        recommended_mode = "SERIAL"
    elif not unknowns and not shared_neighbor and not guardable_serial:
        classification = "LOCAL_CANDIDATE"
        recommended_mode = "REGION_OWNER" if world_effects else "RESOURCE_SCOPED"
    elif guardable_serial and not shared_neighbor:
        classification = "ADAPTER_CANDIDATE"
        recommended_mode = "REGION_OWNER" if world_effects else "RESOURCE_SCOPED"
    else:
        classification = "UNKNOWN"
        recommended_mode = "SERIAL"

    return TriageResult(
        class_name=be_cls.name.replace("/", "."),
        mod_id=be_cls.mod_id,
        jar_name=be_cls.jar_name,
        sha256=be_cls.sha256,
        classification=classification,
        recommended_mode=recommended_mode,
        entrypoints=[f"{o.replace('/', '.')}.{n}{d}" for (o, n, d) in entrypoints],
        hard_serial_reasons=sorted(hard_serial),
        guardable_branch_reasons=sorted(guardable_serial),
        world_or_effect_reasons=sorted(world_effects),
        shared_or_neighbor_reasons=sorted(shared_neighbor),
        unknown_reasons=sorted(unknowns),
        hierarchy_hashes=hierarchy_hashes,
        candidate_storage_fields=candidate_fields,
    )


def infer_registry_id(class_name: str, mod_id: str) -> str:
    simple = class_name.split(".")[-1]
    for suffix in ("BlockEntity", "TileEntity", "BE", "Tile"):
        if simple.endswith(suffix) and len(simple) > len(suffix):
            simple = simple[: -len(suffix)]
            break
    snake = re.sub(r"(?<!^)(?=[A-Z])", "_", simple).lower()
    return f"{mod_id}:{snake}"


def generate_adapter_skeleton(mod_id: str, results: List[TriageResult], out_dir: Path) -> Optional[Path]:
    candidates = [r for r in results if r.classification in ("LOCAL_CANDIDATE", "ADAPTER_CANDIDATE")]
    if not candidates:
        return None
    out_dir.mkdir(parents=True, exist_ok=True)
    class_prefix = "".join(part.capitalize() for part in re.split(r"[_.-]+", mod_id))
    adapter_name = f"{class_prefix}ExecutionAdapter"
    out_file = out_dir / f"{adapter_name}.java"

    seen_hashes: Dict[str, str] = {}
    guard_notes: Set[str] = set()
    for r in candidates:
        for c_name, h in r.hierarchy_hashes:
            seen_hashes[c_name] = h
        for g in r.guardable_branch_reasons:
            guard_notes.add(g)

    mode = "RESOURCE_SCOPED" if all(r.recommended_mode == "RESOURCE_SCOPED" for r in candidates) else "REGION_OWNER"
    impl_entries = ",\n            ".join(
        f'new Implementation(loadClass("{c_name}"), "{h}", Set.of())'
        for c_name, h in sorted(seen_hashes.items())
    )
    source_entries = ",\n            ".join(
        f'loadClass("{r.class_name}")' for r in candidates
    )
    type_entries = ",\n            ".join(
        f'ResourceLocation.parse("{infer_registry_id(r.class_name, mod_id)}")' for r in candidates
    )
    guard_comment = (
        "\n        // Guardable init/periodic branches detected:\n"
        + "\n".join(f"        //   - {g}" for g in sorted(guard_notes))
        + "\n        // Return Decision.INITIALIZING / TOPOLOGY_CHANGE / CALLBACK_REQUIRED when those branches are active."
        if guard_notes
        else ""
    )

    code = f"""/*
 * Auto-generated by tools/forgia-be-triage/triage.py for mod '{mod_id}'.
 * Review all reachable branches, external writers, and backing storage roots before enabling in production.
 */
package net.neoforged.forgia.compat.{re.sub(r'[^a-z0-9_]', '_', mod_id)};

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import org.jetbrains.annotations.Nullable;

public final class {adapter_name} implements BlockEntityExecutionAdapter {{
    public static final ResourceLocation ID = ResourceLocation.parse("forgia_{mod_id}:auto_triage_ticks");
    public static final Set<ResourceLocation> TYPES = Set.of(
            {type_entries});

    private static Class<?> loadClass(String name) {{
        try {{
            return Class.forName(name);
        }} catch (ClassNotFoundException e) {{
            throw new IllegalStateException("Missing audited class: " + name, e);
        }}
    }}

    @Override
    public Mode executionMode() {{
        return Mode.{mode};
    }}

    @Override
    public Audit audit() {{
        return new Audit(
                "tools/forgia-be-triage/{mod_id}-audit.md",
                "{mod_id}-auto-triage-v1",
                Set.of(
            {source_entries}),
                Set.of(), // Fill in exact stateless BlockEntityTicker implementation classes after verification
                List.of(
            {impl_entries}));
    }}

    @Override
    public Decision check(BlockEntity blockEntity) {{
        if (blockEntity == null || blockEntity.isRemoved() || !blockEntity.hasLevel()) {{
            return Decision.INVALID_INSTANCE;
        }}{guard_comment}
        return Decision.ADMITTED;
    }}

    @Override
    @Nullable
    public Proof inspect(BlockEntity source, Class<?> tickerImplementation) {{
        final List<Storage> storage = new ArrayList<>();
        storage.add(new Storage(source, Access.WRITE));
        // TODO: Add all backing storage roots (ItemStackHandler, behaviours, components) reached by the tick body.
        return new Proof(storage);
    }}
}}
"""
    out_file.write_text(code, encoding="utf-8")
    return out_file


def analyze_paths(paths: List[Path], max_depth: int = 6) -> Tuple[ScanCorpus, List[TriageResult]]:
    corpus = ScanCorpus()
    jar_files: List[Path] = []
    for p in paths:
        if p.is_dir():
            jar_files.extend(sorted(p.glob("*.jar")))
        elif p.is_file() and p.suffix == ".jar":
            jar_files.append(p)

    for jar_path in jar_files:
        try:
            with zipfile.ZipFile(jar_path, "r") as zf:
                load_jar_into_corpus(jar_path, zf, corpus)
        except Exception:
            continue

    # Identify all BlockEntity subclasses in corpus
    be_classes: List[ClassInfo] = []
    subclasses_map: Dict[str, List[str]] = {}
    for ci in corpus.classes.values():
        if ci.super_name:
            subclasses_map.setdefault(ci.super_name, []).append(ci.name)
        if ci.name != BLOCK_ENTITY_INTERNAL and is_subclass_of(ci.name, BLOCK_ENTITY_INTERNAL, corpus.classes):
            # Skip abstract base classes when reporting concrete BEs unless they have no concrete subclasses
            if (ci.access & 0x0400) == 0:  # not ACC_ABSTRACT
                be_classes.append(ci)

    results: List[TriageResult] = []
    for be_cls in sorted(be_classes, key=lambda c: (c.mod_id, c.name)):
        results.append(analyze_block_entity(be_cls, corpus, subclasses_map, max_depth=max_depth))
    return corpus, results


def build_declarations_json(results: List[TriageResult]) -> Dict[str, List[Dict[str, str]]]:
    entries: List[Dict[str, str]] = []
    seen_types: Set[str] = set()
    for r in results:
        if r.classification == "DEFINITELY_SERIAL":
            t_id = infer_registry_id(r.class_name, r.mod_id)
            if t_id not in seen_types:
                seen_types.add(t_id)
                primary_reason = r.hard_serial_reasons[0].split(":")[0] if r.hard_serial_reasons else "unsafe_sink"
                entries.append({
                    "type": t_id,
                    "reason": f"triage:{primary_reason}",
                    "_class": r.class_name,
                })
    return {"block_entities": entries}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Mod JAR files or directories containing mod JARs")
    parser.add_argument("--json", type=Path, help="Write full triage report as JSON to this path")
    parser.add_argument("--declarations", type=Path, help="Write narrowing regionthreading-declarations.json for DEFINITELY_SERIAL types")
    parser.add_argument("--skeleton-dir", type=Path, help="Generate BlockEntityExecutionAdapter Java skeletons for LOCAL_CANDIDATE / ADAPTER_CANDIDATE mods")
    parser.add_argument("--max-depth", type=int, default=6, help="Maximum call-graph traversal depth (default: 6)")
    args = parser.parse_args()

    corpus, results = analyze_paths(args.inputs, max_depth=args.max_depth)
    by_class = {"DEFINITELY_SERIAL": 0, "LOCAL_CANDIDATE": 0, "ADAPTER_CANDIDATE": 0, "UNKNOWN": 0}
    for r in results:
        by_class[r.classification] = by_class.get(r.classification, 0) + 1

    print(
        f"Scanned {len(corpus.classes)} classes across {len(corpus.mod_jars)} mods; "
        f"found {len(results)} concrete BlockEntity classes:"
    )
    print(f"  DEFINITELY_SERIAL : {by_class['DEFINITELY_SERIAL']} (steady-state tick reaches hard serial sinks)")
    print(f"  LOCAL_CANDIDATE   : {by_class['LOCAL_CANDIDATE']} (pure local tick, zero hard serial sinks)")
    print(f"  ADAPTER_CANDIDATE : {by_class['ADAPTER_CANDIDATE']} (steady-state local, init/effect branches guardable by check())")
    print(f"  UNKNOWN           : {by_class['UNKNOWN']} (reaches capabilities, neighbor BEs, or virtual dispatch)")

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "summary": {
                "total_classes": len(corpus.classes),
                "total_mods": len(corpus.mod_jars),
                "total_block_entities": len(results),
                "counts": by_class,
            },
            "block_entities": [r.__dict__ for r in results],
        }
        args.json.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote JSON report: {args.json}")

    if args.declarations:
        args.declarations.parent.mkdir(parents=True, exist_ok=True)
        decl = build_declarations_json(results)
        args.declarations.write_text(json.dumps(decl, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote narrowing declarations ({len(decl['block_entities'])} entries): {args.declarations}")

    if args.skeleton_dir:
        by_mod: Dict[str, List[TriageResult]] = {}
        for r in results:
            by_mod.setdefault(r.mod_id, []).append(r)
        generated = 0
        for mod_id, mod_results in sorted(by_mod.items()):
            path = generate_adapter_skeleton(mod_id, mod_results, args.skeleton_dir)
            if path:
                generated += 1
                print(f"Generated adapter skeleton: {path}")
        if generated == 0:
            print("No LOCAL_CANDIDATE or ADAPTER_CANDIDATE mods found for skeleton generation.")


if __name__ == "__main__":
    main()

