# Plan: AE2 Declogger for GT: New Horizons (Minecraft 1.7.10)

Made with `make-plan` on 2026-10-06. Each phase is meant to run in a fresh session: read this file, the phase's references, then do only that phase. Upstream source links are pinned to tags so line numbers stay valid.

## Scope and key decision

GTNH's AE2 fork (Applied Energistics 2 Unofficial, "AE2U") **already shows a reason per pattern** in its crafting CPU screen: `appeng.util.ScheduledReason` (SOMETHING_STUCK, BLOCKING_MODE, LOCK_MODE, NO_TARGET, NOT_ENOUGH_INGREDIENTS, SAME_NETWORK, UNSUPPORTED_STACK). The port reads those reasons instead of recomputing them, and adds what AE2U does not have:

- time-based stall detection (nothing returned for N seconds), using AE2U's `lastTime` and our `logic/StallClassifier`;
- pushes skipped for lack of AE power (AE2U skips them silently);
- the machine behind the job, with GT5-Unofficial status: no power, output full, maintenance, structure incomplete, disabled, progress;
- the monitor, history and stats, alerts, machine viewer, pins and Go To.

Not in scope for the first release: GT++/TecTech multiblocks that do not extend `MTEMultiBlockBase`, and a crafting-screen overlay if reflection into `GuiCraftingCPU` turns out too fragile (Phase 6 decides).

## Target versions

| GTNH pack | GT5-Unofficial | AE2U | Role |
|---|---|---|---|
| 2.8.4 (current stable) | `5.09.51.482` | `rv3-beta-695-GTNH` | **compile against** |
| 2.7.4 | `5.09.50.119` | `rv3-beta-485-GTNH` | supported by reflection fallbacks |
| 2.9.0-RC / daily | `5.09.54.205`+ | `rv3-beta-1080`+ | supported; uses newer back-references when present |

Maven: `https://nexus.gtnewhorizons.com/repository/public/`, coordinates `com.github.GTNewHorizons:GT5-Unofficial:<v>:dev` and `com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:<v>:dev`. GT5u pulls AE2U in transitively.

---

## Phase 0: Documentation discovery (done 2026-10-06)

### Allowed APIs

**AE2U** (source: github.com/GTNewHorizons/Applied-Energistics-2-Unofficial at tag `rv3-beta-1082-GTNH`, paths under `src/main/java/appeng/`; check each against `rv3-beta-695` before use):

- CPUs:
  - `api/networking/crafting/ICraftingGrid.getCpus()` lists active CPUs only (`me/cache/CraftingGridCache.java:738`, filter :884-915).
  - All CPUs live in `CraftingGridCache.craftingCPUClusters` (protected, :120). This needs reflection.
- `me/cluster/implementations/CraftingCPUCluster`, public methods:
  - `isBusy()` (:1357)
  - `getName()`, `isActive()`, `isDestroyed()`, `getFinalMultiOutput()`
  - `getElapsedTime()`, in **nanoseconds**; it only advances when an output returns (:1809)
  - `getRemainingItemCount()`, `getStartItemCount()`
  - `isSuspended()`
  - `getInventory()` (:302)
  - `getModernListOfItem(IItemList, CraftingItemList.ACTIVE|PENDING|STORAGE|ALL)` (:1471), `getStackAmount(..)` (:1531)
  - `getProviders(IAEStack)` returns a `List<NamedDimensionalCoord>` of interface locations (:1972)
  - `getScheduledReason(IAEStack)` (:2050)
  - `cancel()` (:711)
- `CraftingCPUCluster`, protected fields (reflection):
  - `tasks` is a `Map<ICraftingPatternDetails, TaskProgress>` (:147). `TaskProgress.value` is protected (:2153).
  - `waitingFor` (:166)
  - `reasonProvider` (:231)
  - `lastTime`, the nanoTime of the last returned output (:178)
  - `myLastLink`
- Pattern holders: `CraftingGridCache.getMediums(ICraftingPatternDetails)` (:774) returns the `ICraftingMedium`s.
- `ICraftingMedium` (`api/networking/crafting/ICraftingMedium.java:24-65`): `isBusy()`, `getBlockingMode()`, `getScheduledReason()`.
- Interfaces:
  - `helpers/IInterfaceHost`: `getInterfaceDuality()`, `getTargets()` (an `EnumSet<ForgeDirection>`), `getTileEntity()`.
  - `helpers/DualityInterface`: `getBlockingMode()` (:1672), `getCraftingLockedReason()` (:1818), `getWaitingToSend()` (:642), public `somethingStuck`, `getLocation()`.
  - Push/target logic to mirror (read only): `DualityInterface.java:1217-1366`. For each target side it tries `ICraftingMachine`, skips an interface on the same network, then uses `InventoryAdaptor`.
- Grid:
  - `api/networking/IGridHost.getGridNode(ForgeDirection)`
  - `IGridNode.getGrid()`
  - `IGrid.getCache(Class)`
  - `energy/IEnergyGrid.isNetworkPowered()`
  - `storage/IStorageGrid.getItemInventory()`
- Stacks (`api/storage/data/`):
  - `IAEStack`: `equals` (type + NBT, not size), `isItem()`/`isFluid()`, `getDisplayName()`, `getStackSize()`
  - Serialization: `toNBTGeneric()` / `fromNBTGeneric()`, `writeToPacketGeneric(ByteBuf)` / `fromPacketGeneric(ByteBuf)`
- Client:
  - `client/gui/implementations/GuiCraftingCPU`: GUI 238×184, 3 columns × 6 rows, cell origin `guiLeft + 9 + col*68`, `guiTop + 18 + row*23`, first visible index `scrollbar.getCurrentScroll()*3` (:57-90, :322-397).
  - Entries are in the private `visualState.filteredEntries` (:663). `container/implementations/CraftingCpuEntry` has the getters.
- GTNH extras:
  - AE2FC Dual Interface: `com.glodblock.github...TileFluidInterface` / `PartFluidInterface`. Both extend the AE2U interface classes, so `IInterfaceHost` covers them.

**GT5-Unofficial** (source: github.com/GTNewHorizons/GT5-Unofficial at tag `5.09.51.482`; master differences noted):

- From a position: `world.getTileEntity(x,y,z)` instanceof `gregtech.api.interfaces.tileentity.IGregTechTileEntity`, then `getMetaTileEntity()`.
- On `IGregTechTileEntity`:
  - `getProgress()`, `getMaxProgress()`, `isActive()`, `isAllowedToWork()`
  - `wasShutdown()`, `getLastShutDownReason()`
  - `getStoredEU()`, `getEUCapacity()`, `getInputVoltage()`
- Single blocks (`MTEBasicMachine`):
  - Fields: `mProgresstime`, `mMaxProgresstime`, `mOutputBlocked` (an int; non-zero means the output is blocked), `mStuttering`, `mTier`.
  - **Never call `checkRecipe()`**: it consumes inputs.
- Multiblocks (`MTEMultiBlockBase`):
  - Formed: `mMachine`.
  - Maintenance: `mWrench`, `mScrewdriver`, `mSoftMallet` (`mSoftHammer` on 2.7.4), `mHardHammer`, `mSolderingTool`, `mCrowbar`, plus `getRepairStatus()` / `getIdealStatus()`.
  - Progress: `mProgresstime`, `mMaxProgresstime`.
  - Last recipe check: `getCheckRecipeResult()` on 2.8+. On 2.7.4 read the field `checkRecipeResult` by reflection.
  - Hatch lists: `mInputBusses`, `mInputHatches`, `mDualInputHatches`, `mEnergyHatches`.
- Message registries (compare by `getID()`):
  - `CheckRecipeResultRegistry`: NO_RECIPE, ITEM_OUTPUT_FULL, FLUID_OUTPUT_FULL, insufficientPower and others.
  - `ShutDownReasonRegistry`: POWER_LOSS, STRUCTURE_INCOMPLETE, NO_REPAIR, NO_MACHINE_PART and others.
- From a hatch to its controller:
  - 2.7/2.8: scan loaded `MTEMultiBlockBase` controllers for identity in their hatch lists.
  - 2.9+: `MTEHatch.watchers` (protected) or `ae2CraftingIconOwner` (private) give a direct reference.
- ME hatches:
  - `gregtech.common.tileentities.machines.MTEHatchCraftingInputME` (the "CRIB") is itself an AE2 `ICraftingProvider`. `pushPattern` fails when it is inactive or not allowed to work; `isBusy()` is always false. It sits in the controller's `mDualInputHatches`.
  - `MTEHatchCraftingInputSlave.getMaster()` returns its CRIB.

**Build** (source: `F:\HustleGPT\gtcalc-exporter`, which matches GTNH's ExampleMod1.7.10 template):

- Settings plugin: `settings.gradle.kts` L1-21 uses `com.gtnewhorizons.gtnhsettingsconvention` 2.0.33 and the GTNH maven.
- `build.gradle.kts` uses `com.gtnewhorizons.gtnhconvention`.
- Gradle 9.7.1, with the daemon JVM on 25.
- `gradle.properties` keys: `modId`, `modGroup`, `minecraftVersion=1.7.10`, `forgeVersion=10.13.4.1614`, `enableModernJavaSyntax`, `generateGradleTokenClass`, `usesMixins=false`.
- Entry point to copy: `src/main/java/com/syhros/packextract/PackExtract.java` L15-39 (`@Mod`, `@SidedProxy`, FML pre-init and init handlers).
- Dependency lines to copy:
  - GT5u's own `dependencies.gradle` L46 (AE2U `api(...)`)
  - ae2stuff `dependencies.gradle` L5 and L14 (`api` AE2U, `compileOnly` GT5u)
- Tests: **Horizon-QA** (github.com/GTNewHorizons/Horizon-QA, tag 0.15.0) reimplements the GameTest API for 1.7.10:
  - Dependency: `devOnlyNonPublishable('com.github.GTNewHorizons:Horizon-QA:0.15.0:dev')`.
  - Tests are `@GameTestHolder` classes with `@GameTest` methods that take a `GameTestHelper`.
  - CI run: `./gradlew runServer --mcJvmArgs=-Dhorizonqa.mode=ci --mcJvmArgs=-Dhorizonqa.reportDir=...`, which produces a JUnit XML report.
- Dedicated server in CI (GTNH-Actions-Workflows `build-and-test.yml` ~L194-237):
  - Write `eula=true` into `run/` and `run/server/`.
  - Run `./gradlew runServer < run/stop.txt`.
- Game JVM properties must go through `--mcJvmArgs=-D...`; a plain `-D` only reaches the Gradle daemon.

**Our code** (`F:\HustleGPT\ae2-debugger`):
- Shared as-is: `src/main/java/com/syhros/ae2declogger/logic/` (11 classes; java.* only) and its JUnit 5 tests under `logic-tests/`.
- Everything else needs rewriting. Its behaviour is the reference: `server/GridAnalyzer`, `server/ProviderIndex`, `server/MachineInspector`, `compat/gtceu/GtMachineReader`, `network/DeclogNetwork` (11 messages), `client/screen/DecloggerScreen` (~1,100 lines) and `MachineViewerScreen` (~380 lines).

### Anti-patterns (things that do not exist on 1.7.10 / AE2U)

- **No AE2 objects from 1.20.** None of these exist in AE2U:
  - `AEKey`, `KeyCounter`, `GenericStack`
  - `PatternProviderLogic`, `ICraftingService`
  - `IGrid.getService(...)` (use `getCache(...)`)
- **No modern Minecraft types.** These don't exist on 1.7.10: `BlockPos`, `Component`, `ResourceKey<Level>`, `FriendlyByteBuf`, `GuiGraphics`, `SavedData`, `BlockEntity`, capabilities. Use instead:
  - x/y/z ints and the dimension id
  - plain strings with `StatCollector.translateToLocal`
  - `ByteBuf` with `ByteBufUtils`
  - `GuiScreen` with `Tessellator`
  - `WorldSavedData`
  - `TileEntity`, `IInventory` / `ISidedInventory`, `IFluidHandler`
- **Not GT's newer classes.** No GTCEu. No `GT_MetaTileEntity_*` either: both 2.7.4 and 2.8.4 already use the `MTE*` names.
- **Don't call side-effecting methods** while inspecting: `checkRecipe()`, `pushPattern()`, `provideCrafting()`.
- **Unit mistakes:**
  - `getElapsedTime()` is not wall time, and it is in nanoseconds.
  - `ICraftingGrid.getCpus()` omits inactive CPUs.
- **The same Gradle build can't host both.** The root build uses ModDevGradle on Gradle 8.14, while GTNH needs its settings plugin on Gradle 9.7. Use a separate build (Phase 1).
- **Records:** jabel does not support Java records, and `logic/Thresholds` (and any other record in `logic/`) is one. Use `enableModernJavaSyntax = jvmDowngrader`, as GT5u does, or confirm in Phase 1 that jabel compiles them.

---

## Phase 1: Build skeleton

**What to implement**
1. Create a standalone build folder `gtnh-1.7.10/` in this repo. It must not be part of the root `settings.gradle`. Copy these from `F:\HustleGPT\gtcalc-exporter`:
   - `settings.gradle.kts`
   - `build.gradle.kts`
   - `gradle.properties`
   - `gradle/` (wrapper and daemon JVM)
   - `gradlew` and `gradlew.bat`
   - `repositories.gradle`
2. Change the mod identity in `gradle.properties`:
   - `modName = AE2 Declogger`
   - `modId = ae2declogger`
   - `modGroup = com.syhros.ae2declogger`
   - `generateGradleTokenClass = com.syhros.ae2declogger.Tags`
   - `enableModernJavaSyntax = jvmDowngrader` (see the records anti-pattern)
3. In `build.gradle.kts`, add the shared logic as a source directory: `../src/main/java`, with an include filter for `com/syhros/ae2declogger/logic/**` only. `logic-tests/build.gradle` does the same; copy its pattern. Also add the logic tests from `../src/test/java` filtered to `logic/**`, plus `useJUnitPlatform()`.
4. In `dependencies.gradle`:
   - `compileOnly("com.github.GTNewHorizons:GT5-Unofficial:5.09.51.482:dev")`
   - `api`/`compileOnly` AE2U `rv3-beta-695-GTNH:dev`
   - `runtimeOnlyNonPublishable` the same versions, for dev runs
   - JUnit 5 lines copied from gtcalc-exporter `dependencies.gradle` L16-17
5. Write the entry point by copying `PackExtract.java` L15-39: an `@Mod(modid="ae2declogger", dependencies="required-after:appliedenergistics2;after:gregtech")` class with a `@SidedProxy` for CommonProxy and ClientProxy, which only logs "ready".
6. CI: add a `gtnh` job to `.github/workflows/build.yml`. Copy the `build-1710` job (gtcalc-exporter `build.yml` L39-91): JDKs 8, 17, 21 and 25, then `./gradlew setupCIWorkspace`, then `./gradlew build`, with `working-directory: gtnh-1.7.10`.

**Verification**
- Locally: `cd gtnh-1.7.10 && ./gradlew compileJava` succeeds. Full `build` and `test` are CI-only, because Windows Defender blocks GTNHExtLib on the test classpath.
- In CI, the `gtnh` job builds the jar and runs the shared logic unit tests. The test count must equal the `logic-tests` count.
- `unzip -l` on the jar shows `com/syhros/ae2declogger/logic/` and `mcmod.info`.

**Anti-pattern guards**
- Don't add `gtnh-1.7.10` to the root `settings.gradle`.
- Don't copy non-logic shared sources.
- Don't hand-edit the GTNH buildscript plugin versions; keep the template's.

## Phase 2: Server core, reading CPUs and jobs

**What to implement** (package `com.syhros.ae2declogger.gtnh.server`)
1. `CpuReader`:
   - List all CPUs on a grid. Read `CraftingGridCache.craftingCPUClusters` by reflection, falling back to `getCpus()`.
   - For each busy CPU, read:
     - the final output (`getFinalMultiOutput`)
     - the start and remaining counts
     - the tasks, via `tasks` and `TaskProgress.value`, by reflection
     - the active, pending and stored lists via `getModernListOfItem`
     - `getScheduledReason(stack)` for each task output
     - `lastTime`
   - Copy the field and method names from Phase 0. Use the `Refl`-style cached lookups from `src/main/java/com/syhros/ae2declogger/compat/Refl.java`, copied into the gtnh tree (it is java.* only).
2. `ProviderIndex`:
   - For each pattern, list its holders with `CraftingGridCache.getMediums(details)`.
   - For an `IInterfaceHost`, find the push targets: for each side in `getTargets()`, take the tile at `tile + side.offset`.
   - Mirror the skip rules in `DualityInterface.java:1217-1366`.
   - For `MTEHatchCraftingInputME`, the "machine" is the controller (Phase 3).
3. `JobAnalyzer`: build job and step views (a gtnh-local data class for now) and classify them with the shared `logic/StallClassifier` and `ChangeTimer`.
   - Map AE2U reasons to our `BlockReason` (names checked in `logic/BlockReason.java`):
     - `BLOCKING_MODE` → `PROVIDER_BLOCKING`
     - `LOCK_MODE` → `PROVIDER_LOCKED`
     - `NO_TARGET` → `NO_PROVIDER`
     - `NOT_ENOUGH_INGREDIENTS` → `MISSING_INGREDIENT`
     - `SAME_NETWORK` → `NO_PROVIDER`, with detail text
     - `UNSUPPORTED_STACK` → `PUSH_REJECTED`
     - `SOMETHING_STUCK` → `PUSH_REJECTED`, refined by Phase 3 into a `MACHINE_*` reason when the machine is readable
   - Add a time-based stall from `lastTime` (`OUTPUT_NOT_RETURNING` via `StallClassifier`), and `NETWORK_POWER` from `IEnergyGrid.isNetworkPowered()`.
4. A debug command `/declogger dump`, server-side, that prints the analysis for the network of the block you are looking at. It is temporary until the GUI exists.

**References**
- `CraftingCPUCluster.java:813-1066`: how reasons are assigned. Read it before mapping.
- `:1471-1556`: lists by category.
- `:1972-2081`: provider resolution.
- Our behaviour to match: `src/main/java/com/syhros/ae2declogger/server/GridAnalyzer.java` and `ProviderIndex.java`.

**Verification**
- In CI, `compileJava` passes against 2.8.4 jars.
- Horizon-QA test (wired in Phase 7, written here): build a network with a CPU, an interface with a processing pattern and a chest; start a job. The job must be listed, point at the chest, and turn stalled once nothing comes back.
- `grep -r "AEKey\|BlockPos\|Component" gtnh-1.7.10/src` finds nothing.

**Anti-pattern guards**
- Never call `cancel()`, `pushPattern()` or `provideCrafting()` from the reader.
- Don't treat `getElapsedTime()` as wall time.
- Don't use `getCpus()` alone, because it hides inactive CPUs.

## Phase 3: GT5-Unofficial machine status

**What to implement** (`com.syhros.ae2declogger.gtnh.compat.Gt5Reader`; reflection only, no compile dependency needed beyond `compileOnly`)
1. Resolve: tile → `IGregTechTileEntity` → `getMetaTileEntity()`.
2. Single blocks (`MTEBasicMachine`):
   - Disabled when `!isAllowedToWork()`.
   - No power when `mStuttering` is set or `mProgresstime < 0`.
   - Output full when `mOutputBlocked > 0`.
   - Working when `isActive()`; progress is `mProgresstime / mMaxProgresstime`.
   - Tier comes from `mTier`. Stored EU and capacity come from the IGTE.
3. Multiblocks (`MTEMultiBlockBase`):
   - Not formed when `!mMachine`.
   - Maintenance when `getRepairStatus() < getIdealStatus()`. Read the tool flags with an `mSoftMallet` → `mSoftHammer` fallback.
   - The last check result comes from `getCheckRecipeResult()`, or the field on 2.7.4. Map by `getID()`: `no_recipe`, `item_output_full`, `fluid_output_full`, and the insufficient-power family.
   - The shutdown reason comes from `getLastShutDownReason()`.
   - Energy is summed over `mEnergyHatches`: each `getBaseMetaTileEntity().getStoredEU()/getEUCapacity()`.
4. Hatch → controller:
   - On 2.9+, read `MTEHatch.watchers` or `ae2CraftingIconOwner` by reflection when present.
   - Otherwise scan loaded tile entities within 16 blocks for an `MTEMultiBlockBase` with this hatch (or its CRIB slave or master) in `mInputBusses`, `mInputHatches` or `mDualInputHatches`. Do the scan through chunk tile-entity maps, the same idea as `compat/Refl.nearby`.
   - CRIB slave → `getMaster()`.
5. Map everything to `BlockReason` and `MachineStatus` (both shared, java-only).

**References**
- `MTEBasicMachine.java:596-720` and `:1096-1155`
- `MTEMultiBlockBase.java:628-643`, `:1432-1468` and `:2366-2384`
- `MTEHatch.java:43-90`
- `MTEHatchCraftingInputME.java:1221-1272`
- `MTEHatchCraftingInputSlave.java:143-150`
- Our GTCEu reader is the behaviour reference: `src/main/java/com/syhros/ae2declogger/compat/gtceu/GtMachineReader.java`.
- The `names()` self-check pattern: `src/main/java/com/syhros/ae2declogger/compat/ModReader.java`.

**Verification**
- A `names()` self-check lists every reflected name. A Horizon-QA test asserts that none are missing on 2.8.4.
- A Horizon-QA test with an unpowered LV machine (a single block, e.g. an electric furnace) holding an input reports no power.
- A test with an incomplete multiblock (just a controller) reports not formed.
- Compare against `gametest/GtServerSelfTest.java`.

**Anti-pattern guards**
- Never call `checkRecipe()`.
- Don't assume `getCheckRecipeResult()` exists (2.7.4 lacks it).
- Don't assume `watchers` exists (2.9+ only).

## Phase 4: Networking and data

**What to implement**
1. Copy the gtnh data classes from the modern records, with type swaps:
   - `JobView`, `StepView`, `MachineRef`, `MachineSnapshot`, `GridSummary`, `HistoryEntry`
   - Positions become dim + x/y/z.
   - Text becomes translation keys plus args.
   - Stacks become `IAEStack` via `writeToPacketGeneric` / `fromPacketGeneric`.
2. Messages: copy the 11 messages from `network/DeclogNetwork.java` (6 to the server, 5 to the client) as `SimpleNetworkWrapper` `IMessage`/`IMessageHandler` pairs. Server handlers must do their work on the main thread.
3. History: `WorldSavedData` stored in `world.mapStorage`. Serialize with `toNBTGeneric` / `fromNBTGeneric`. Behaviour reference: `server/HistorySavedData.java`.

**Verification**
- A Horizon-QA test round-trips every message through a `ByteBuf`. Copy the idea of `packetsRoundTrip` in `gametest/DecloggerGameTests.java`.
- A test saves and loads history.

**Anti-pattern guards**
- No `FriendlyByteBuf`. No `SavedData`. Don't send text that was already translated server-side; send keys.

## Phase 5: Client, monitor and machine viewer

**What to implement**
1. `GuiDeclogger` (`GuiScreen`) with the jobs list, steps, and History, Stats and Pins tabs. Copy the layout and behaviour from `client/screen/DecloggerScreen.java`; drawing uses `Gui.drawRect`, `fontRendererObj`, `RenderItem` and `Tessellator`.
2. `GuiMachineViewer`, from `client/screen/MachineViewerScreen.java`.
3. A K keybind (`ClientRegistry.registerKeyBinding`) and an FML client tick handler.
4. A terminal button: `GuiScreenEvent.InitGuiEvent.Post`, if Forge 10.13.4 has it (verify; otherwise hook `GuiOpenEvent`).

**Verification**
- A client self-test, copying the idea of `gametest/ClientSelfTest.java`. In CI it runs `runClient` under xvfb, as gtcalc-exporter does (`build.yml` L107-153), with env-var driven automation like `PACKEXTRACT_AUTO`. It opens the monitor on a seeded job and saves screenshots.
- Review the screenshots by eye.

**Anti-pattern guards**
- No `GuiGraphics` or `PoseStack`.
- Check every Forge event name against Forge 10.13.4 sources before use.

## Phase 6: Overlay, pins and alerts

**What to implement**
1. A crafting-screen overlay on AE2U's `GuiCraftingCPU`:
   - Frame stalled entries, using the cell geometry from `GuiCraftingCPU.java:322-397`. Entries come from the private `visualState.filteredEntries`, by reflection.
   - Add an Inspect click.
   - If reflection is unreliable across 2.7, 2.8 and 2.9, skip the overlay and note it. AE2U already colours entries by reason.
2. Pins and Go To: world rendering in `RenderWorldLastEvent` with `Tessellator` lines and boxes, plus a HUD line in `RenderGameOverlayEvent.Text`. Behaviour reference: `client/pins/`.
3. Alerts: a chat line and a sound when a job stalls. Copy the behaviour of `client/AlertHandler.java` and `logic/AlertTracker` (shared).

**Verification**
- Client self-test screenshots of the overlay frame, a pin box and the HUD line.

**Anti-pattern guards**
- The overlay must not change AE2U's GUI state; drawing only.
- Use reflection lookups with a log-once fallback, the same pattern as `compat/Machines.disable`.

## Phase 7: Tests and CI

**What to implement**
1. Add Horizon-QA with `devOnlyNonPublishable('com.github.GTNewHorizons:Horizon-QA:0.15.0:dev')`. Move the Phase 2-4 tests into `@GameTestHolder("ae2declogger")` classes.
2. Port the tests from `gametest/DecloggerGameTests.java`. Tests that need a CPU outside AE2U (the add-on CPU ones) are dropped.
   - stalled job reported, then complete
   - blocking mode reported as blocked (from `BLOCKING_MODE`)
   - no target, then no provider
   - offline CPU stays listed
   - cancelled job recorded as cancelled
   - steps of a job recorded
   - history saved with the world
   - packets round trip
3. CI jobs:
   - `gtnh` build: Phase 1.
   - `gtnh-gametest`: `./gradlew runServer --mcJvmArgs=-Dhorizonqa.mode=ci --mcJvmArgs=-Dhorizonqa.reportDir=$PWD/build/horizonqa`. It fails on a non-zero exit and uploads `TEST-horizonqa.xml`.
   - `gtnh-client`: the client self-test under xvfb.
   - A pack matrix that runs `gtnh-gametest` against 2.7.4, 2.8.4 and 2.9 dev jars, overriding the versions with `-P` properties in `dependencies.gradle`.
4. Release: add the gtnh jar to `release_notes.py`'s table and the release job's assets.

**Verification**
- CI is green on all three pack versions.
- The release dry run lists six jars.

## Phase 8: Final verification

1. Grep the gtnh tree for the anti-patterns:
   ```
   grep -rnE "AEKey|BlockPos|Component|FriendlyByteBuf|GuiGraphics|SavedData|checkRecipe\(|pushPattern\(|provideCrafting\(" gtnh-1.7.10/src
   ```
   This must return nothing, except comments explaining why not.
2. Every reflected name is in a `names()` list, and the Horizon-QA names test passes on 2.7.4, 2.8.4 and 2.9.
3. Shared `logic/` is unchanged by the port. `git diff main -- src/main/java/com/syhros/ae2declogger/logic` shows no gtnh-motivated edits, or each one has a passing `logic-tests` run.
4. Manual check in a real GTNH 2.8.4 instance:
   - a stuck CRIB job shows its multiblock controller;
   - a soft-malleted machine shows "disabled";
   - a maintenance problem shows.
5. README:
   - add GTNH to the version table;
   - add a "GTNH" section saying what is read from AE2U versus added;
   - add the build instructions for `gtnh-1.7.10/`.

## Open questions to settle in Phase 1-2

- Does `ScheduledReason` behave the same on `rv3-beta-695` (2.8.4) as on 1082? The enum exists at 695; the assignment loop is unchecked.
- jabel or jvmDowngrader for records in `logic/`: confirm by compiling.
- Is UniMixins in GTNH? If so, mixin accessors could replace reflection for AE2U internals. The default stays reflection.
- Exact pack AE2FC version (`1.5.111-gtnh` in GT5u's pins): only needed if the Dual Interface needs special handling, which it should not, since it extends the AE2U interface classes.
