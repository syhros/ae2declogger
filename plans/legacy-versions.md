# Plan: AE2 Declogger for 1.16.5, 1.12.2 and GTNH 1.7.10

Written 2026-10-18. It replaces the standalone approach in `plans/gtnh-1.7.10-port.md`. That file is still the reference for the GTNH and GT5u API facts.

## Why these three get their own tree

- **ModDevGradle can't build them.** It refuses anything before 1.17 ("NFRT currently does not support MCP versions that did not make use of official Mojang mappings (pre 1.17)"). We tested it with a throwaway project on 2026-10-18.
- **Their Minecraft APIs differ from 1.17+.** Even 1.16.5's official mappings use other packages (no `net.minecraft.world.level`, no `net.minecraft.network.chat`).
- **They target Java 8,** and our shared code uses records and pattern matching.
- **Their AE2 API is a different family from AE2 10+.** All three use AE2's older API: AE2 8 on 1.16.5, AE2 Extended Life (AE2UEL) on 1.12.2, and AE2 Unofficial rv3 on GTNH. That family has `IAEItemStack`, `IItemList`, `ICraftingGrid.getCpus()`, `CraftingCPUCluster` with private `tasks`/`waitingFor`, `getListOfItem(CraftingItemList)`, `CraftingGridCache.getMediums(pattern)`, and `DualityInterface`/`IInterfaceHost.getTargets()` pushing patterns from ME Interfaces (there are no pattern providers). It has no `AEKey`.

So the three share one **legacy tree** with its own `//#if MC` preprocessing, and share only the Minecraft-free `logic/` package with the modern mod.

## Layout

```
legacy/
  src/                    shared legacy sources (//#if MC >= 1.12 / MC >= 1.16 ...)
  forge-1.16.5/           ForgeGradle 6 build, official 1.16.5 mappings, AE2 8.4.7 (fg.deobf from modmaven)
  forge-1.12.2/           RetroFuturaGradle build, AE2UEL 0.56.7 (curse.maven:ae2-extended-life-570458:6302098), Jabel
  gtnh-1.7.10/            GTNH convention build (gtnhsettingsconvention 2.0.33), AE2U rv3-beta-695-GTNH + GT5u 5.09.51.482
```

Each one is its own Gradle build, because ForgeGradle and RetroFuturaGradle don't mix with ModDevGradle. `legacy/gradle/shared.gradle` feeds the preprocessor `logic/` (synced from the root tree), `legacy/src` and the build's own `src` (whole-file overrides); each build's `buildSrc` compiles the root `PreprocessTask.java`.

All legacy code is plain Java 8. (Decided in Phase 1, 2026-10-18: instead of a bytecode downgrader, `logic/` itself was made Java 8 compatible. Its five records became final classes with the same accessors, and one switch expression became a switch statement. That keeps dev runs on each version's own Java 8 and avoids a downgrader on three different build plugins.)

## What each version reads (from the 2026-10-18 research)

| | 1.16.5 / AE2 8.4.7 | 1.12.2 / AE2UEL 0.56.7 | 1.7.10 / AE2U rv3 GTNH |
|---|---|---|---|
| Grid from a tile | `IGridHost.getGridNode(AEPartLocation.INTERNAL)`; cable parts via `IPartHost.getPart` | same | `IGridHost.getGridNode(ForgeDirection)` |
| All CPUs, including inactive | `grid.getMachines(CraftingStorageTileEntity.class)` → `getCluster()` | `TileCraftingTile.getCluster()` | `CraftingGridCache.craftingCPUClusters` (reflection) |
| Job contents | `getListOfItem(ACTIVE/PENDING/STORAGE)`; tasks via reflection or the public `writeToNBT` | `getListOfItem`; `tasks`/`TaskProgress.value` via reflection | `getModernListOfItem`; `tasks` via reflection |
| Why a pattern isn't pushed | rebuilt by us: medium `isBusy`, blocking setting, target inventory | rebuilt by us, plus `getCraftingLockedReason()` | **AE2's own `getScheduledReason(stack)`**, plus our stall timing |
| Pattern holders | `getMediums(details)` → `DualityInterface` → host `IInterfaceHost.getTargets()` | same | same, plus the GT5u ME Crafting Input Buffer |
| Done vs cancelled | keep our own link reference (`cancel()` nulls `myLastLink`) | `ICraftingLink.isDone/isCanceled` | same |
| Time | `getElapsedTime()` is nanoseconds | same | same, only advancing on returns; use `lastTime` |
| Fluids | no fluid crafting | AE2FC fluid drops (optional) | native generic stacks |
| Crafting screen | `CraftingCPUScreen`, cells 67×22, 3×5, `craftingStatusBtn` in `MEMonitorableScreen` | `GuiCraftingCPU` 238×184, 3×6, `craftingStatusBtn` in `GuiMEMonitorable` | `GuiCraftingCPU`, 3×6, private `visualState` |
| Machine status mod | (later: Mekanism 10.0, IE, Create 0.3) | (later: GTCEu 1.12 / GT5u-style) | GT5-Unofficial (in the GTNH plan) |

## Phases

1. **Build skeletons.** All three builds compile `logic/` plus an `@Mod` entry point that logs "ready". CI gets a `legacy` job matrix that builds the three jars and runs the logic tests.
2. **Legacy key model.** A small `LegacyKey` wrapper around `IAEStack` (identity, display name, NBT and packet serialization), plus legacy `JobView`/`StepView`/`MachineRef`/`HistoryEntry` data written against it. These are copied from the modern records.
3. **Server analysis.** CPU listing, job and step views, stall classification (shared `logic/`), pattern-holder index, push-target machines and reasons. GTNH maps AE2's `ScheduledReason`; 1.12.2 and 1.16.5 rebuild the reason as listed above. Includes the Queued rule from 1.5.1.
4. **Networking and history.** SimpleChannel / SimpleNetworkWrapper messages and WorldSavedData history.
5. **Client.** The monitor, machine viewer, terminal button above Crafting Status, K keybind, alerts, then pins and the crafting-screen overlay.
6. **Tests.** A server self-test per version: a real network, a job that stalls, then completes, then is recorded. GTNH also gets Horizon-QA game tests. Client self-tests where the version's dev run allows them.
7. **Machine status.** GT5u on GTNH (Phase 3 of the GTNH plan), then the legacy versions of the to-implement integrations.
8. **Release.** Add the three jars to the release table and README.

## Status (2026-10-18, end of day)

All eight phases are implemented on branch `legacy-versions` (draft PR syhros/ae2declogger#8):

- Phases 1-5: the three builds, the shared data (`LKey`, `LText`, `LPos`, views on `net/Buf`), the server analysis (`GridAnalyzer`, `CpuAccess`, `Ae` for the version differences, `GridTracker`, `HistoryData`), one channel message per direction (`net/Messages`), and the client (monitor and machine viewer written once against `client/ui/Canvas`, pins, HUD line, alerts, terminal button, K hotkey).
- Phase 6: `selftest/ServerSelfTest` (dev server, `-PselfTest`) passes locally on 1.12.2 and 1.16.5; `selftest/ClientSelfTest` (dev client, `-PclientSelfTest`) draws every screen and saves screenshots. Both run in CI for all three (GTNH only in CI).
- Phase 7: GT5u machine status on GTNH (`compat/Machines`, reflection only), checked against GT5u 5.09.51.482 in a second CI run.
- Phase 8: release table, release notes, README; `mod_version` 1.6.0.

Not done, for later:
- The crafting status screen overlay on GTNH (done on 1.16.5 and 1.12.2: shared `client/CraftingOverlay`, per-version `client/OverlayCanvas` reading AE2's table layout by reflection).
- The "Stop navigating" and "Show/hide pins" hotkeys, alert toasts.
- Machine integrations on 1.16.5 and 1.12.2 (Mekanism 10, IE, Create 0.3; GTCEu 1.12) and a GT machine placed by a test on GTNH.
- Testing against AE2 rv6 (non-Extended-Life 1.12.2 packs) and GTNH 2.7 / 2.9.

## Verification per phase
- Every build compiles. CI is green for the build, the logic tests and that phase's self-test.
- No AE2 10+ or 1.17+ types appear in `legacy/src`:
  ```
  grep -rnE "AEKey|GenericStack|PatternProviderLogic|net\.minecraft\.world\.level|network\.chat" legacy/src
  ```
  must return nothing.
- Never call `pushPattern`, `cancel`, `provideCrafting` or `checkRecipe` from inspection code. Note that AE2UEL's and AE2 8's `isBusy()` prune tasks as a side effect. Read `getListOfItem` instead of calling `isBusy` where possible.
