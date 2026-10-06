# Machine integrations: plan and to-implement list

The machine viewer already shows the inventory, tanks and FE energy of any block, through the standard item, fluid and energy capabilities. GregTech CEu (1.20.1) also gets status, progress, block reasons and multiblock awareness. This list covers the other tech mods that AE2 pattern providers push into, so a stuck job can say why: no power, turned off, structure not formed, output full, no recipe.

Sources:
- Mod lists from Modpack Index, mirroring the latest CurseForge files: ATM7 339 mods, ATM8 381, ATM9 435, ATM10 495, ATM11 271 (an early port).
- Popularity was also checked against 9 other big packs: Craftoria 1 and 2, FTB StoneBlock 4, Skies 2, Evolution, OceanBlock 2, E9, E9 Expert and E10.
- Class and method names come from each mod's GitHub source (October 2026). Anything marked "unverified" was not confirmed in source.

## How an integration plugs in

GregTech is the template. Each mod's integration needs:

- **New package** `compat/<mod>/` holding three classes:
  - an entry point that is safe without the mod and switches itself off with one log line on a `LinkageError`;
  - a reader that touches the mod's classes;
  - an info record using our own `MachineStatus` and `BlockReason`.
- **`server/MachineInspector`**: call the integration in `describe` (status lines) and `snapshot` (the viewer).
- **`server/ProviderIndex`**: register the mod's AE2-facing block in `canReceive`. If the AE2-facing block is part of a multiblock, add a controller lookup.
- **Build**: compile-only dependency, gated by a `<mod>_version` property in the versions where the mod exists. Where the API moved between versions, use reflection by name instead, as with Advanced AE.
- **Tests**: a server self-test like `GtServerSelfTest` on the versions the mod exists for, plus a name check in CI for every reflected class and method.

Existing block reasons cover most needs:

- `MACHINE_NO_POWER`
- `MACHINE_DISABLED`
- `MACHINE_NOT_FORMED`
- `MACHINE_OUTPUT_FULL`
- `MACHINE_INPUT_FULL`
- `MACHINE_WAITING`
- `MACHINE_NO_RECIPE`
- `MACHINE_MAINTENANCE`

## Generic improvements (all mods, do first)

1. **Side fallback.** When a block exposes no item or fluid handler without a side, try each side. Some mods only expose storage on configured sides.
2. **Generic "on/off" and "no power" heuristics.** Use FE stored at 0 on a block that has FE capacity, and a block that is not loaded. These need no mod knowledge.

## Tier 1: done in 1.5.0

These are in most ATM packs and other big packs, and have readable status. All nine are implemented in `compat/mods/` as reflection readers, with the shared code in `compat/Machines` and `compat/Refl`. CI checks them on a real server per Minecraft version (`gametest/ModMachineSelfTest`, the "tech" server pack). The two "generic improvements" above are done as well.

| Mod | Versions | AE2-facing block | What we can read | Access |
|---|---|---|---|---|
| Mekanism | 1.18.2 to 1.21.1 (26.1 branch) | Machine, factory; multiblock valve or port | Typed recipe errors (no energy, no input, output full, no recipe), redstone mode, active, progress, formed | `CachedRecipe.OperationTracker.RecipeError` is public API; `TileEntityRecipeMachine#getWarningCheck`, `TileEntityMekanism#canFunction/getControlType/getActive` |
| Ender IO | 1.20.1, 1.21.1, 26.1 | Alloy Smelter, SAG Mill, Slicer, Soul Binder, Vat | `getMachineStates()`: NO_POWER, FULL_OUTPUT, EMPTY_INPUT, REDSTONE, NO_CAPACITOR and more, each with its own text; progress | Internal; the package moved between 1.20.1 and 1.21.1, so look up both by name |
| Immersive Engineering | 1.18.2 to 1.21.1 | Multiblock IO dummy blocks | Master state from any part, redstone or computer control, energy, process queue (full = won't accept), progress | 1.20.1+: public `api.multiblocks`; 1.18/1.19: `MultiblockPartBlockEntity` (internal) |
| Modern Industrialization | 1.21.1 (26.1 branch) | Input hatches, single-block machines | Active recipe, progress, EU, redstone, shape valid | Public `api.machine` holders; the hatch-to-controller lookup needs a scan |
| Industrial Foregoing | 1.18.2 to 1.21.1 | Processing machines | Progress, `canIncrease()` (blocked, cause unknown), redstone mode, FE | Internal (Titanium) |
| Create | 1.18.2 to 1.21.1 | Basin, depot, millstone, crushing wheels, mechanical crafter, spout | Speed 0 ("no rotation"), overstressed, speed too low, mixer/press running, basin heat | Internal, but `KineticBlockEntity` has been stable since 0.5.1 |
| Powah | 1.18.2 to 26.1 | Energizing Orb | Current recipe (none = no recipe), orb buffer as progress, rods' FE as "no power" | Internal |
| Oritech | 1.21.1, 26.1 | Controller or machine core | Not assembled, redstone disabled, no recipe, output blocked, no energy, progress | Internal; `isActive` was renamed `isAssembled` on 26.1 |
| Iron Furnaces | 1.18.2 to 26.1.2 | Furnace | Burn time (no fuel), cook progress, redstone setting, FE on factory types | Internal public fields |

Suggested order:

1. Mekanism: the biggest reach and the best error data.
2. Ender IO: its machine states map almost directly onto our reasons.
3. Immersive Engineering.
4. Create.
5. Modern Industrialization.
6. Industrial Foregoing.
7. Iron Furnaces.
8. Powah.
9. Oritech.

## Tier 2: to implement

- **Thermal Expansion** (1.18.2 to 1.20.1): active flag, progress, redstone, FE; the input and output checks are protected. The 1.18.2 package differs from 1.19+. It is in Tier 2 only because it has no 1.21 version; do it first if 1.18 to 1.20 players matter.
- **PneumaticCraft** (1.18.2 to 1.21.1): Pressure Chamber valve, with public `isValidRecipeInChamber` and `isSufficientPressureInChamber`.
- **Mystical Agriculture** (all versions): the Infusion Altar waits for a redstone pulse or a wand click. Show "waiting for activation".
- **Productive Bees** (all versions): centrifuge `recipeProgress` is public.
- **Flux Networks** (1.18.2 to 1.21.1): public `IFluxDevice` API (buffer, transfer change, chunk loaded) to explain where "no power" comes from.
- **Actually Additions** (1.21.1) and **Draconic Evolution** (1.20.1, 1.21.1, uses OP): unverified.
- **Not researched in depth:** Productive Metalworks, Railcraft Reborn, Energized Power, XyCraft Machines, Modular Machinery Reborn, Theurgy, and Ars Nouveau (Imbuement Chamber).
- **Mekanism: More Machine**: probably covered by the Mekanism integration (unverified).

## Not applicable or not worth it

- **Applied Mekanistics:** only enables chemical patterns; it has no machines of its own.
- **Botania:** missing from ATM10/11 and all 9 other packs, and its inputs are usually dropped item entities.
- **Occultism:** rituals need an activation click.
- **Extended Crafting:** in none of the ATM packs.
- **Generators** (Mekanism Generators, reactors, RFTools Power and so on): power sources, not push targets. The FE reading already covers "no power" at the machine.
