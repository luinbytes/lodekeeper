#!/usr/bin/env python3
"""Inspect pinned mining fields without Java, Minecraft, or a dependency download."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import tarfile
import zipfile


class UnsupportedMapping(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise UnsupportedMapping(message)


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, length):
        end = self.offset + length
        require(0 <= length and end <= len(self.data), "Truncated classfile")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def uint(self, length):
        return int.from_bytes(self.take(length), "big")


class ClassFile:
    def __init__(self, data):
        reader = Reader(data)
        require(reader.uint(4) == 0xCAFEBABE, "Invalid classfile magic")
        self.minor, self.major = reader.uint(2), reader.uint(2)
        self.pool = [None] * reader.uint(2)
        index = 1
        while index < len(self.pool):
            tag = reader.uint(1)
            if tag == 1:
                entry = reader.take(reader.uint(2)).decode("utf-8", errors="replace")
            elif tag in (3, 4):
                entry = (tag, reader.uint(4))
            elif tag in (5, 6):
                entry = (tag, reader.uint(8))
            elif tag in (7, 8, 16, 19, 20):
                entry = (tag, reader.uint(2))
            elif tag in (9, 10, 11, 12, 17, 18):
                entry = (tag, reader.uint(2), reader.uint(2))
            elif tag == 15:
                entry = (tag, reader.uint(1), reader.uint(2))
            else:
                raise UnsupportedMapping(f"Unsupported constant-pool tag {tag}")
            self.pool[index] = entry
            index += 2 if tag in (5, 6) else 1
        self.flags = reader.uint(2)
        self.name = self.class_name(reader.uint(2))
        self.super_name = self.class_name(reader.uint(2))
        self.interfaces = [self.class_name(reader.uint(2)) for _ in range(reader.uint(2))]
        self.fields = self.members(reader)
        self.methods = self.members(reader)
        self.attributes = self.attributes_from(reader)
        require(reader.offset == len(data), "Unexpected trailing classfile data")
        self.bootstrap_methods = []
        if "BootstrapMethods" in self.attributes:
            bootstrap = Reader(self.attributes["BootstrapMethods"])
            for _ in range(bootstrap.uint(2)):
                method = bootstrap.uint(2)
                args = [bootstrap.uint(2) for _ in range(bootstrap.uint(2))]
                self.bootstrap_methods.append((method, args))
            require(bootstrap.offset == len(bootstrap.data), "Malformed bootstrap methods")

    def class_name(self, index):
        if index == 0:
            return None
        tag, name = self.pool[index]
        require(tag == 7, "Expected class constant")
        return self.pool[name]

    def member_reference(self, index):
        tag, owner, name_and_type = self.pool[index]
        require(tag in (9, 10, 11), "Expected member reference")
        nt_tag, name, descriptor = self.pool[name_and_type]
        require(nt_tag == 12, "Expected name-and-type constant")
        return self.class_name(owner), self.pool[name], self.pool[descriptor]

    def attributes_from(self, reader):
        attributes = {}
        for _ in range(reader.uint(2)):
            name = self.pool[reader.uint(2)]
            require(name not in attributes, f"Duplicate attribute {name}")
            attributes[name] = reader.take(reader.uint(4))
        return attributes

    def members(self, reader):
        members = []
        for _ in range(reader.uint(2)):
            flags, name, descriptor = reader.uint(2), reader.uint(2), reader.uint(2)
            attributes = self.attributes_from(reader)
            member = {"flags": flags, "name": self.pool[name], "descriptor": self.pool[descriptor]}
            if "Code" in attributes:
                code = Reader(attributes["Code"])
                code.uint(2)
                code.uint(2)
                member["code"] = code.take(code.uint(4))
            if "Signature" in attributes:
                member["signature"] = self.pool[int.from_bytes(attributes["Signature"], "big")]
            members.append(member)
        return members


OPERAND_LENGTHS = {
    0x10: 1, 0x11: 2, 0x12: 1, 0x13: 2, 0x14: 2,
    **{opcode: 1 for opcode in range(0x15, 0x1A)},
    **{opcode: 1 for opcode in range(0x36, 0x3B)},
    0x84: 2,
    **{opcode: 2 for opcode in range(0x99, 0xA9)},
    0xA9: 1,
    **{opcode: 2 for opcode in range(0xB2, 0xB9)},
    0xB9: 4, 0xBA: 4, 0xBB: 2, 0xBC: 1, 0xBD: 2,
    0xC0: 2, 0xC1: 2, 0xC5: 3, 0xC6: 2, 0xC7: 2, 0xC8: 4, 0xC9: 4,
}


def instructions(code):
    reader = Reader(code)
    decoded = []
    while reader.offset < len(code):
        offset, opcode = reader.offset, reader.uint(1)
        require(opcode <= 0xC9, f"Unsupported bytecode opcode {opcode:#x}")
        if opcode in (0xAA, 0xAB):
            reader.take((-reader.offset) % 4)
            reader.take(4)
            if opcode == 0xAA:
                low, high = struct.unpack(">ii", reader.take(8))
                require(high >= low, "Invalid tableswitch range")
                reader.take((high - low + 1) * 4)
            else:
                pairs = reader.uint(4)
                reader.take(pairs * 8)
        elif opcode == 0xC4:
            modified = reader.uint(1)
            require(modified in (*range(0x15, 0x1A), *range(0x36, 0x3B), 0x84, 0xA9), "Invalid wide opcode")
            reader.take(4 if modified == 0x84 else 2)
        else:
            reader.take(OPERAND_LENGTHS.get(opcode, 0))
        decoded.append((offset, opcode, code[offset + 1:reader.offset]))
    return decoded


def referenced_member(classfile, instruction):
    return classfile.member_reference(int.from_bytes(instruction[2][:2], "big"))


def method(classfile, name, descriptor):
    matches = [entry for entry in classfile.methods if entry["name"] == name and entry["descriptor"] == descriptor]
    require(len(matches) == 1 and "code" in matches[0], f"Missing or ambiguous method {name}{descriptor}")
    return matches[0]


def inspect_source(path, release):
    commit = release["source_commit"]
    with tarfile.open(path) as archive:
        matches = [entry for entry in archive.getmembers()
                   if entry.name == f"baritone-{commit}/src/main/java/baritone/process/MineProcess.java"]
        require(len(matches) == 1, "Source archive does not contain its pinned MineProcess commit")
        source_bytes = archive.extractfile(matches[0]).read()
    source = source_bytes.decode("utf-8")
    required = (
        "private List<BlockPos> knownOreLocations;",
        "private List<BlockPos> blacklist;",
        "this.knownOreLocations = new ArrayList<>();",
        "this.blacklist = new ArrayList<>();",
        "this.branchPoint = null;",
        "this.branchPointRunaway = null;",
        "knownOreLocations.removeIf(blacklist::contains);",
        "List<BlockPos> curr = new ArrayList<>(knownOreLocations);",
        "PathingCommand command = updateGoal();",
        "List<BlockPos> locs = knownOreLocations;",
        "knownOreLocations = locs2;",
        "new GoalComposite(locs2.stream()",
        "private static List<BlockPos> prune(",
        ".filter(pos -> !blacklist.contains(pos))",
        "PathingCommandType.REVALIDATE_GOAL_AND_PATH",
        "if (mineGoalUpdateInterval != 0",
        "mine(0, (BlockOptionalMetaLookup) null);",
    )
    require(all(fragment in source for fragment in required), "Pinned source mining structure changed")
    return {
        "commit": commit,
        "archive_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "file": matches[0].name,
        "file_sha256": hashlib.sha256(source_bytes).hexdigest(),
        "structure_line_numbers": {
            "known_list_declaration": source[:source.index("private List<BlockPos> knownOreLocations;")].count("\n") + 1,
            "blacklist_declaration": source[:source.index("private List<BlockPos> blacklist;")].count("\n") + 1,
            "native_goal_reads_known_list": source[:source.index("List<BlockPos> locs = knownOreLocations;")].count("\n") + 1,
            "native_goal_assigns_pruned_list": source[:source.index("knownOreLocations = locs2;")].count("\n") + 1,
            "reset_known_list": source[:source.index("this.knownOreLocations = new ArrayList<>();")].count("\n") + 1,
            "reset_blacklist": source[:source.index("this.blacklist = new ArrayList<>();")].count("\n") + 1,
        },
    }


def inspect_jar(path, release, source_proof):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    require(digest == release["sha256"], f"Artifact SHA-256 mismatch for {release['version']}")
    with zipfile.ZipFile(path) as archive:
        candidates = []
        for name in archive.namelist():
            if name.startswith("baritone/") and name.endswith(".class"):
                parsed = ClassFile(archive.read(name))
                if "baritone/api/process/IMineProcess" in parsed.interfaces:
                    candidates.append(parsed)
    require(len(candidates) == 1, "Missing or ambiguous IMineProcess implementation")
    implementation = candidates[0]
    require(implementation.major == release["class_major"], "Unexpected implementation class major")
    mine_descriptor = "(ILbaritone/api/utils/BlockOptionalMetaLookup;)V"
    tick_descriptor = "(ZZ)Lbaritone/api/process/PathingCommand;"
    mine = method(implementation, "mine", mine_descriptor)
    tick = method(implementation, "onTick", tick_descriptor)
    require(mine["flags"] & 0x1 and tick["flags"] & 0x1, "Mining API methods are not public")
    mine_code, tick_code = instructions(mine["code"]), instructions(tick["code"])
    list_fields = {field["name"]: field for field in implementation.fields if field["descriptor"] == "Ljava/util/List;"}
    require(len(list_fields) == 2, "Expected exactly two mining List fields")
    for field in list_fields.values():
        require(field["flags"] & 0x2 and not field["flags"] & (0x8 | 0x10), "Mining List must be private, mutable, and instance-owned")
    signatures = {field.get("signature") for field in list_fields.values()}
    require(len(signatures) == 1 and next(iter(signatures)) in {
        "Ljava/util/List<Lnet/minecraft/class_2338;>;",
        "Ljava/util/List<Lnet/minecraft/core/BlockPos;>;"}, "Mining Lists do not share a supported BlockPos generic signature")
    reset_offsets = {}
    for index, instruction in enumerate(mine_code):
        if instruction[1] != 0xB5:
            continue
        owner, name, descriptor = referenced_member(implementation, instruction)
        if owner != implementation.name or descriptor != "Ljava/util/List;":
            continue
        require(name not in reset_offsets and index >= 4, "Ambiguous mining List reset")
        before = mine_code[index - 4:index]
        require([entry[1] for entry in before] == [0x2A, 0xBB, 0x59, 0xB7], "Mining List reset is not this/new/dup/constructor")
        require(implementation.class_name(int.from_bytes(before[1][2], "big")) == "java/util/ArrayList", "Mining List reset allocates a different type")
        require(referenced_member(implementation, before[3]) == ("java/util/ArrayList", "<init>", "()V"), "Unexpected List constructor")
        reset_offsets[name] = instruction[0]
    require(set(reset_offsets) == set(list_fields), "Both mining Lists must have one direct ArrayList reset")

    # ProGuard inlines updateGoal in these releases. Identify each List by its
    # role in known.removeIf(blacklist::contains), not its opaque name or order.
    blacklist_predicates = []
    for index, instruction in enumerate(tick_code):
        if instruction[1] != 0xBA:
            continue
        constant = implementation.pool[int.from_bytes(instruction[2][:2], "big")]
        require(constant[0] == 18, "Expected invokedynamic constant")
        _, bootstrap_index, name_and_type = constant
        _, _, descriptor_index = implementation.pool[name_and_type]
        if implementation.pool[descriptor_index] != "(Ljava/util/List;)Ljava/util/function/Predicate;":
            continue
        bootstrap_handle, arguments = implementation.bootstrap_methods[bootstrap_index]
        handle = implementation.pool[bootstrap_handle]
        require(handle[0] == 15, "Expected bootstrap method handle")
        if implementation.member_reference(handle[2])[0:2] != ("java/lang/invoke/LambdaMetafactory", "metafactory"):
            continue
        contains = [implementation.member_reference(implementation.pool[arg][2]) for arg in arguments
                    if isinstance(implementation.pool[arg], tuple) and implementation.pool[arg][0] == 15]
        if contains != [("java/util/List", "contains", "(Ljava/lang/Object;)Z")]:
            continue
        require(index >= 7 and index + 1 < len(tick_code), "Truncated bound blacklist predicate")
        before = tick_code[index - 7:index]
        require([entry[1] for entry in before] == [0x2A, 0xB4, 0x2A, 0xB4, 0x59, 0xB8, 0x57],
                "Changed bound blacklist predicate receiver structure")
        require(referenced_member(implementation, before[5]) ==
                ("java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                "Changed blacklist receiver null check")
        require(tick_code[index + 1][1] == 0xB9 and referenced_member(implementation, tick_code[index + 1]) ==
                ("java/util/List", "removeIf", "(Ljava/util/function/Predicate;)Z"),
                "Blacklist predicate is not consumed by List.removeIf")
        blacklist_predicates.append((instruction[0], referenced_member(implementation, before[1]),
                                     referenced_member(implementation, before[3])))
    require(len(blacklist_predicates) == 1, "Missing or ambiguous blacklist removal predicate")
    predicate_offset, known_reference, blacklist_reference = blacklist_predicates[0]
    owner, known, descriptor = known_reference
    require(owner == implementation.name and descriptor == "Ljava/util/List;" and known in list_fields,
            "Blacklist removal receiver is not a mining List")
    owner, blacklist, descriptor = blacklist_reference
    require(owner == implementation.name and descriptor == "Ljava/util/List;" and blacklist in list_fields
            and blacklist != known, "Blacklist predicate is not bound to the other mining List")

    emptiness_checks = []
    list_writes = []
    for index, instruction in enumerate(tick_code):
        if instruction[1] == 0xB9 and referenced_member(implementation, instruction) == ("java/util/List", "isEmpty", "()Z"):
            previous = tick_code[index - 1]
            if previous[1] == 0xB4:
                emptiness_checks.append(referenced_member(implementation, previous))
        if instruction[1] == 0xB5:
            reference = referenced_member(implementation, instruction)
            if reference[0] == implementation.name and reference[2] == "Ljava/util/List;":
                list_writes.append((index, instruction[0], reference))
    require(emptiness_checks and all(reference == known_reference for reference in emptiness_checks),
            "onTick direct List emptiness checks disagree with known locations")
    require(list_writes and all(reference == known_reference for _, _, reference in list_writes),
            "onTick writes a List other than the identified known locations")

    # The native GoalComposite construction followed by the known List write and
    # a REVALIDATE PathingCommand proves adoption is in onTick, without mine().
    goal_writes = []
    for index, offset, reference in list_writes:
        before = tick_code[max(0, index - 4):index]
        after = tick_code[index + 1:index + 16]
        if not before or before[0][1] != 0xB7 or referenced_member(implementation, before[0]) != (
                "baritone/api/pathing/goals/GoalComposite", "<init>", "([Lbaritone/api/pathing/goals/Goal;)V"):
            continue
        calls = [referenced_member(implementation, entry) for entry in after if entry[1] == 0xB7]
        revalidation = [referenced_member(implementation, entry) for entry in after if entry[1] == 0xB2]
        require(calls == [("baritone/api/process/PathingCommand", "<init>",
                           "(Lbaritone/api/pathing/goals/Goal;Lbaritone/api/process/PathingCommandType;)V")],
                "Changed native goal command construction")
        require(set(revalidation) == {
            ("baritone/api/process/PathingCommandType", "FORCE_REVALIDATE_GOAL_AND_PATH", "Lbaritone/api/process/PathingCommandType;"),
            ("baritone/api/process/PathingCommandType", "REVALIDATE_GOAL_AND_PATH", "Lbaritone/api/process/PathingCommandType;")},
                "Changed native goal revalidation types")
        goal_writes.append(offset)
    require(len(goal_writes) == 1, "Missing or ambiguous native goal List adoption")

    lost = method(implementation, "onLostControl", "()V")
    lost_code = instructions(lost["code"])
    require(any(entry[1] in (0xB6, 0xB7) and referenced_member(implementation, entry) == (implementation.name, "mine", mine_descriptor)
                for entry in lost_code), "onLostControl no longer calls the mining reset API")
    return {
        "version": release["version"], "jarSha256": digest,
        "class_major": implementation.major, "targetClass": implementation.name.replace("/", "."),
        "knownField": known, "blacklistField": blacklist,
        "field_descriptor": "Ljava/util/List;", "field_generic_signature": next(iter(signatures)),
        "proof": {
            "mine_descriptor": mine_descriptor, "mine_code_sha256": hashlib.sha256(mine["code"]).hexdigest(),
            "list_reset_putfield_offsets": reset_offsets, "on_tick_descriptor": tick_descriptor,
            "on_tick_code_sha256": hashlib.sha256(tick["code"]).hexdigest(),
            "goal_updater": "inlined in onTick",
            "blacklist_contains_predicate_offset": predicate_offset,
            "known_list_putfield_offsets": [offset for _, offset, _ in list_writes],
            "known_list_goal_putfield_offset": goal_writes[0],
            "source": source_proof,
        },
    }


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=root / "third-party/baritone/dependencies.json")
    parser.add_argument("--jar-dir", type=Path, default=root / ".cache/baritone")
    parser.add_argument("--source-dir", type=Path, default=root / ".cache/baritone/sources")
    parser.add_argument("--version", action="append", help="Inspect an explicit release; missing artifacts fail")
    parser.add_argument("--require-all", action="store_true", help="Fail unless every manifest release is present")
    parser.add_argument("--output", type=Path, help="Write the verified JSON; otherwise print it")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    require(manifest["schema_version"] == 1, "Unsupported dependency manifest schema")
    releases = {entry["version"]: entry for entry in manifest["releases"]}
    selected = args.version or list(releases)
    require(all(version in releases for version in selected), "Requested release is not pinned")
    result = {"schema_version": 1, "supported": [], "unavailable_releases": []}
    for version in selected:
        release = releases[version]
        jar = args.jar_dir / f"baritone-api-fabric-{version}.jar"
        if not jar.exists():
            require(not args.require_all and not args.version, f"Missing pinned artifact {jar}")
            result["unavailable_releases"].append(version)
            continue
        source = args.source_dir / f"baritone-{version}-{release['source_commit']}.tar.gz"
        require(source.exists(), f"Missing pinned source {source}")
        result["supported"].append(inspect_jar(jar, release, inspect_source(source, release)))
    require(result["supported"], "No local artifacts were inspected")
    result["bridge_policy"] = {
        "target_remap": False, "field_descriptor": "Ljava/util/List;", "maximum_merged_targets": 64,
        "requirements": [
            "Select a mapping by the complete pinned artifact SHA-256, not a version string alone.",
            "Generate exact-target getter/setter accessors for known locations and a blacklist getter.",
            "Merge a new mutable List on the Minecraft main thread only while the owned MineProcess is active.",
            "Retain current known targets in order; append fresh discoveries only when room remains under the cap.",
            "Exclude blacklisted, duplicate, unloaded, changed, protected, and out-of-request-bound discoveries.",
            "Keep mineGoalUpdateInterval zero so an asynchronous rescan cannot overwrite the merge.",
            "Do not call mine or cancel to merge; the next native onTick prunes and revalidates the goal.",
            "Unknown artifact or changed structure requires accessor regeneration and rebuild before use.",
        ],
        "limitations": [
            "Native onTick may prune a discovery or blacklist the closest target after a calculation failure.",
            "A merge preserves process fields but native goal revalidation can change the current path.",
            "An inactive MineProcess requires a separately justified start; a List setter cannot activate it.",
            "onLostControl calls mine(0,null), so switching away to descent still resets native mining state.",
            "Setting the interval to zero does not stop an already queued asynchronous rescan; establish the lease before mining starts.",
            "The metadata is bytecode/source evidence, not a Mixin compilation or native-runtime validation.",
        ],
    }
    serialized = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        require(not args.output.exists(), "Refusing to overwrite existing bridge evidence")
        args.output.write_text(serialized)
    else:
        print(serialized, end="")


if __name__ == "__main__":
    try:
        main()
    except (UnsupportedMapping, KeyError, IndexError, OSError, tarfile.TarError, zipfile.BadZipFile) as failure:
        print(f"Unsupported mining bridge: {failure}. Regenerate and rebuild the accessor after reviewing the pinned artifact.", file=sys.stderr)
        sys.exit(1)
