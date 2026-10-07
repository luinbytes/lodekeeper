from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / "overrides" / "shared"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def port_legacy_block_optional_meta(source: Path) -> str:
    text = source.read_text(encoding="utf-8")
    text = text.replace("import io.netty.util.concurrent.ThreadPerTaskExecutor;\n", "")
    executor = "new ThreadPerTaskExecutor(Thread::new)"
    if text.count(executor) != 2:
        raise SystemExit(f"Expected two legacy resource reload executors in {source}")
    text = text.replace(executor, "Runnable::run")
    if "ThreadPerTaskExecutor" in text:
        raise SystemExit(f"Unbounded resource reload executor remains in {source}")
    return text


def port_legacy_network_mixin(source: Path) -> str:
    text = source.read_text(encoding="utf-8")
    header = text[:text.index("package ")]
    return header + """package dev.lodekeeper.navigation.kernel.launch.mixins;

import dev.lodekeeper.navigation.kernel.api.IBaritone;
import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
import dev.lodekeeper.navigation.kernel.api.event.events.PacketEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.type.EventState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class MixinNetworkManager {
    @Shadow @Final private PacketFlow receiving;

    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void lodekeeper$movementPacket(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (receiving != PacketFlow.CLIENTBOUND || !(packet instanceof ServerboundMovePlayerPacket)) return;
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.player == null) return;
        IBaritone kernel = OwnedKernelAPI.getProvider().getBaritoneForConnection(client.player.connection);
        if (kernel != null && client.player.connection.getConnection() == (Connection) (Object) this) {
            kernel.getGameEventHandler().onSendPacket(
                    new PacketEvent((Connection) (Object) this, EventState.PRE, packet));
        }
    }
}
"""


def merge_file(target: Path, base: Path, owned: Path, rel: str, profile: str) -> str:
    if rel.endswith("/api/utils/BlockOptionalMeta.java") and profile != "mc1.20.5":
        return port_legacy_block_optional_meta(target)
    if rel.endswith("/launch/mixins/MixinNetworkManager.java") and profile == "mc1.20":
        return port_legacy_network_mixin(target)

    result = subprocess.run(
        [
            "git", "merge-file", "-p",
            "-L", f"{profile}-pinned-source",
            "-L", "1.11.3-normalized-source",
            "-L", "owned-lifecycle",
            str(target), str(base), str(owned),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    merged = result.stdout
    if "<<<<<<< " not in merged:
        if result.returncode not in (0, 1):
            raise SystemExit(f"git merge-file failed for {profile}/{rel}: {result.stderr}")
        return merged

    if rel.endswith("/pathing/movement/CalculationContext.java"):
        conflict = re.compile(
            r"<<<<<<< [^\n]+\n"
            r"(?P<legacy>[^\n]*this\.frostWalker = EnchantmentHelper\.getEnchantmentLevel\([^\n]+\);[^\n]*)\n"
            r"=======\n"
            r"(?P<owned>(?:[^\n]*\n)*?[^\n]*this\.frostWalker = 0;[^\n]*)\n"
            r">>>>>>> [^\n]+",
            re.MULTILINE,
        )
        merged, count = conflict.subn(
            lambda match: next(
                line for line in match.group("owned").splitlines()
                if "this.safeAutomation =" in line
            ) + "\n" + match.group("legacy"),
            merged,
        )
        if count == 1 and "<<<<<<< " not in merged:
            return merged

    raise SystemExit(f"Unresolved source overlap in {profile}/{rel}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", required=True)
    parser.add_argument("--base-source", required=True, type=Path)
    parser.add_argument("--target-source", required=True, type=Path)
    args = parser.parse_args()

    profile = args.profile
    base_root = args.base_source.resolve()
    target_root = args.target_source.resolve()
    base_manifest_path = base_root.parent / "source-manifest.json"
    target_manifest_path = target_root.parent / "source-manifest.json"
    base_manifest = json.loads(base_manifest_path.read_text(encoding="utf-8"))
    target_manifest = json.loads(target_manifest_path.read_text(encoding="utf-8"))
    base_lock = json.loads((ROOT / "locks" / "base1211.json").read_text(encoding="utf-8"))
    target_lock_path = ROOT / "locks" / f"{profile}.json"
    target_lock = json.loads(target_lock_path.read_text(encoding="utf-8"))
    if (
        base_manifest.get("generation_mode") != "base_only"
        or base_manifest["upstream"]["commit"] != base_lock["source_commit"]
        or base_manifest["upstream"]["archive_sha256"] != base_lock["archive_sha256"]
    ):
        raise SystemExit("The base source tree does not match the locked 1.21.1 ancestor")
    if (
        target_manifest.get("generation_mode") != "base_only"
        or target_manifest["profile"] != profile
        or target_manifest["upstream"]["commit"] != target_lock["source_commit"]
        or target_manifest["upstream"]["archive_sha256"] != target_lock["archive_sha256"]
    ):
        raise SystemExit(f"The {profile} source tree does not match its pinned source lock")

    overrides = ROOT / "overrides" / profile
    if overrides.exists():
        for path in sorted(overrides.rglob("*.java")):
            path.unlink()
    overrides.mkdir(parents=True, exist_ok=True)

    inventory = {}
    changed = []
    for owned in sorted(SHARED.rglob("*.java")):
        rel = owned.relative_to(SHARED).as_posix()
        base = base_root / rel
        target = target_root / rel
        base_hash = sha256(base.read_bytes()) if base.is_file() else None
        target_hash = sha256(target.read_bytes()) if target.is_file() else None
        inventory[rel] = target_hash
        if target_hash == base_hash:
            continue
        changed.append(rel)
        if not base.is_file() or not target.is_file():
            raise SystemExit(f"Pinned source presence changed for {profile}/{rel}")
        output = merge_file(target, base, owned, rel, profile)
        destination = overrides / rel
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(output, encoding="utf-8")

    input_inventory = {
        "schema_version": 1,
        "source_profile": profile,
        "source_commit": target_lock["source_commit"],
        "source_archive_sha256": target_lock["archive_sha256"],
        "base_source_commit": base_lock["source_commit"],
        "generated_sources": inventory,
    }
    input_path = ROOT / "locks" / f"{profile}.source-inputs.json"
    input_path.parent.mkdir(parents=True, exist_ok=True)
    input_path.write_text(json.dumps(input_inventory, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    target_lock["source_inputs_sha256"] = sha256(input_path.read_bytes())
    target_lock_path.write_text(json.dumps(target_lock, indent=2) + "\n", encoding="utf-8")

    manifest = "".join(
        f"{sha256(path.read_bytes())}  {path.relative_to(overrides).as_posix()}\n"
        for path in sorted(overrides.rglob("*.java"))
    )
    (ROOT / "overrides" / f"{profile}.sha256").write_text(manifest, encoding="utf-8")
    print(json.dumps({
        "profile": profile,
        "source_inventory_sha256": target_lock["source_inputs_sha256"],
        "adapted_override_files": changed,
        "adapted_override_count": len(changed),
    }, indent=2))


if __name__ == "__main__":
    main()
