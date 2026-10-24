# SimplyFlashback inventory patch

Fixes an inventory-packet compatibility issue in **SimplyFlashback 0.1.1**.
Flashback playback crashed in `ReplayGamePacketHandler.handleContainerSetSlot`
with `ArrayIndexOutOfBoundsException: Index 41 out of bounds for length 9`.

Flashback expects synthetic container-0 updates with hotbar indices **0–8**.
SimplyFlashback records ordinary container slot indices instead. This patch
maps slots by their actual inventory owner/index, preserving item components
and empty-slot updates. It also handles full inventory synchronization and
nested bundles. Chest contents, armor and offhand slots are excluded.

## Files

- `HotbarPackets.java`: inventory-to-hotbar normalization.
- `CaptureFilter.java`: allows full inventory sync through the 26.3 capture filter.
- `PatchJar.java`: applies these hooks to the SHA-256-pinned original 0.1.1 JAR.

No public upstream source repository was available, so the implementation is
provided as source helpers plus an ASM build-time patcher. For an upstream
integration, apply the same normalization before capture encoding and allow
full inventory packets through the outer filter. Movement handling is unchanged.

## Test environment

- Server: **Purpur 26.3 build 2654** (`26.3-2654-HEAD@5cd3596`), Java 25.
- Client: **Minecraft 26.3**, **Flashback 0.43.6**, **Fabric Loader 0.19.5**.
- Original recorder: **SimplyFlashback 0.1.1**; patched test version: **0.1.2-beta.1**.

Local regression checks covered slot 41 mapping to hotbar slot 5, empty slots,
container ownership, full synchronization, nested bundles and a Minecraft packet
codec round trip. A new recording was generated on the server and opened in the
client; complete gameplay acceptance remains pending. A separate observed mob
movement issue is not addressed by this inventory patch.

## Build-time inputs

JDK 25, Minecraft 26.3 classes/libraries and SimplyFlashback 0.1.1 are needed to
compile the two runtime helpers. Compile `PatchJar.java` separately with ASM 9.9
(`asm` and `asm-tree`). Keep compiled runtime helpers separate from build-tool
classes. Run `patch.simplyflashback.build.PatchJar` from this repository root
with four arguments: original JAR, runtime-helper classes directory, output JAR,
and patched version. The patcher checks the original JAR digest and includes
LICENSE/NOTICE. These inputs are not redistributed here.

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
