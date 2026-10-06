# AE2 Declogger

A Minecraft mod for Forge and NeoForge that shows what your Applied Energistics 2 crafting CPUs are doing, which jobs are stuck, and why. It points you at the machine that is holding things up and lets you look inside it without opening it.

It is read-only. It never moves, inserts or extracts items.

Built for [Sky Greg](https://www.curseforge.com/minecraft/modpacks/sky-greg) (GregTech CEu Modern + AE2) and works in any pack with AE2, including the All the Mods packs from ATM7 to ATM11.

## Requirements

| Minecraft | Loader | Applied Energistics 2 | Packs |
|---|---|---|---|
| 1.18.2 | Forge 40.1 or newer (built against 40.3.12) | 11.x (built against 11.7.6) | All the Mods 7 |
| 1.19.2 | Forge 43.2 or newer (built against 43.5.2) | 12.x (built against 12.9.12) | All the Mods 8 |
| 1.20.1 | Forge 47.1.3 or newer (built against 47.3.0) | 15.x (built against 15.4.10) | All the Mods 9, Sky Greg |
| 1.21.1 | NeoForge 21.1 or newer (built against 21.1.256) | 19.x (built against 19.2.18) | All the Mods 10 |
| 26.1.2 | NeoForge 26.1.2 or newer (built against 26.1.2.114) | 26.1.x (built against 26.1.13-beta) | All the Mods 11 |

GregTech CEu Modern is optional on 1.20.1 (built against 7.5.3, the version in Sky Greg). Other versions show GT machines like any other block.

Install the jar on both the client and the server. In single player, the client is enough.

## Download and install

1. Get the jar:
   - **From a release:** the [Releases page](https://github.com/syhros/ae2-debugger/releases), file `ae2declogger-<loader>-<minecraft version>-<version>.jar`, for example `ae2declogger-neoforge-1.21.1-1.4.0.jar`.
   - **From a CI build:** open the latest green run under the repository's **Actions** tab, download the `jar-<loader>-<minecraft version>` artifact (a zip), and take the jar out of it.
2. Copy it into the `mods` folder of your instance. For Sky Greg in the CurseForge app: right-click the profile, **Open Folder**, then `mods`.
3. On a server, copy the same jar into the server's `mods` folder too.
4. Start the game. There is nothing to craft: the Declogger lives inside AE2's own screens.

## Using it

| Action | What happens |
|---|---|
| Open a crafting CPU in AE2 (the **Crafting Status** tab of a terminal, or right-click a crafting unit) | Slow and stuck entries get a coloured frame |
| Click an entry in that screen | A popup shows its state, how long it has been like that, why, and the machine. Press **Inspect** to open the monitor on that job and step |
| Click the Declogger icon above an AE2 terminal or crafting status screen | Opens the monitor for that terminal's network |
| Press **K** while looking at any ME block (cable, drive, interface...) | Opens the monitor for that block's network |
| Press **K** anywhere else | Opens the monitor for the last network you opened it for |

Frame colours in the crafting screen: yellow = Slow, red = Stalled or Blocked. Entries that are crafting normally have no frame. Turn the frames and popup off with `craftingOverlay` in the client config.

### The monitor

The header shows the network, how many crafting CPUs are busy and the stored AE power. There are four tabs.

**Jobs.** One row per busy crafting CPU: the item and amount, progress, time running, the usual time for that item (once it has history), how long since anything changed, and a status:

| Status | Colour | Meaning |
|---|---|---|
| In Progress | green | Moving normally |
| Slow | yellow | No change for longer than expected, but not yet stuck |
| Stalled | red | Items went out to a machine and nothing came back for too long |
| Blocked | red | A step cannot start and the reason has lasted at least 5 seconds, or a slow step's machine has a fault that will not clear by itself (no power, switched off, not formed, maintenance, output full) |

Click a job to see its steps below it. Each step shows the same three numbers as AE2's crafting status screen (stored in the CPU / out at machines / still to start), its own status, and the reason it is stuck.

**History.** Finished and cancelled jobs, with how long they took, and every step of each job: each item made on the way (a job for a coil block that first makes plates, rods and wire lists each of them, timed from its first craft to its last, marked **Step** with the job it was for). Jobs are followed on every loaded network, whether or not anyone has the monitor open. History is saved with the world, so it is still there after leaving the world or restarting the server, and it stays with a network as long as one of its ME controllers or crafting CPU blocks is still part of it.

**Stats.** Per item: number of jobs, share that completed, average and best time, time per item, and the time per item when it was made as a step of another job.

**Pins.** Your locator pins (see below).

Buttons along the bottom act on the selected step's machine, or on the job's main machine if no step is selected:

| Button | What it does |
|---|---|
| Go To | Closes the monitor and outlines the machine in red, through walls, with the way to it on the HUD. The outline is not saved; it goes away 30 seconds after you reach the machine |
| View Machine | Opens the read-only machine viewer |
| Pin | Marks the machine in red and keeps it in the Pins tab (saved) |

Other controls: **!** shows only slow, stalled and blocked jobs. **♪** turns stall alerts on or off. The search box filters by item name (Ctrl+F jumps to it). Keys **1** to **4** switch tabs. Right-clicking a job or step opens its machine straight away.

History and Stats have **Export CSV**, which writes current jobs and history to `<minecraft folder>/ae2declogger/exports/declogger-<date>-<time>.csv`. A chat message links to the file.

### Why a job is stuck

| Reason | What it means | Usual fix |
|---|---|---|
| Waiting for ingredients | An earlier step is still making the inputs | Nothing; this is normal |
| Missing ingredient | An input is neither in the CPU nor being made. Tools count when the recipe accepts them, used or not (a GregTech file with any durability) | Something consumed it; cancel and restart the job |
| No pattern provider has this pattern | The pattern was removed or its provider is offline | Re-insert the pattern, check channels |
| Pattern providers busy | The provider still holds items it could not hand to the machine | The machine's input is full or wrong side |
| Pattern provider offline | The provider with the pattern has no channel or power | Check channels and power at the provider |
| Machine busy with the previous batch | Another output of the same pattern is still at the machine | Nothing; this is normal while a machine works |
| Pattern provider locked | Lock crafting mode is waiting for redstone or for a result | Check the provider's lock setting |
| Blocking mode: machine still holds the last inputs | Blocking mode is on and the machine has not used its previous inputs | Look at the machine: power, recipe, output space |
| Machine refuses the inputs | Provider is free but every push fails | No inventory on the facing side, or the inventory is full |
| Machine has no power | GregTech machine with an empty energy buffer | Fix power, cables, voltage |
| Machine waiting | GregTech machine reports "Recipe Waiting" (the GT reason is shown) | Depends on the GT reason |
| Machine found no recipe | GregTech machine is idle and its recipe check failed (the GT reason is shown) | Wrong circuit, missing fluid, wrong tier |
| Machine switched off | GregTech machine was disabled (soft mallet) | Turn it back on |
| Multiblock not formed | GregTech structure is broken | Fix the structure |
| Multiblock needs maintenance | GregTech maintenance problems | Fix with tools or a maintenance hatch |
| Machine output full | All output slots are full | Empty the output, or route it back to the network |
| Machine input full | Every input slot is full | Same as above for inputs |
| Result not coming back to the network | Items went out but the result never returned | The machine's output is not connected back to AE2 |
| Crafting CPU offline | The CPU has no power or channel | Check power and channels |
| ME network out of power | Not enough AE stored to push patterns | More energy cells or power input |

### Machine viewer

Shows a machine's slots and tanks, recipe progress, energy and any error messages. GregTech machines and multiblocks are grouped into inputs and outputs; for a multiblock part (input bus, ME pattern buffer) it shows the controller. Other blocks show their item and fluid storage.

- Red outline: the slot is full.
- Yellow outline: the slot holds an item that a pattern on this network sends to the machine.

It refreshes once a second. There is no container behind it, so you cannot take anything out.

### Locator pins

A pin draws a red box and a label on the machine. Both are visible through walls, up to 256 blocks away. The pin you are navigating to also shows a line at the top of the screen with its distance, a direction arrow and how far up or down it is. Pins are saved per world or server in `<minecraft folder>/ae2declogger/pins/`.

In the Pins tab, click a pin to select it and click it again (or press Navigate) to navigate to it.

### Alerts

Press **♪** in the monitor to turn alerts on for that network (one network per player, remembered across restarts). The server then checks it every 2 seconds while it is loaded, whether or not any screen is open. When a job turns Stalled or Blocked you get a chat message with the item, the reason and clickable coordinates, a bell sound and a pop-up. Each breakdown alerts once; it alerts again only after the job has recovered and got stuck again.

## Hotkeys

All can be changed under Options → Controls → Key Binds → AE2 Declogger.

| Key | Action | Default |
|---|---|---|
| Open crafting monitor | Opens the monitor for the ME block you look at, or the last network you opened | **K** |
| Stop navigating | Stops navigating and clears the Go To outline | not bound |
| Show/hide locator pins | Hides or shows all pins in the world | not bound |

Inside the monitor: **1**-**4** switch tabs, **Ctrl+F** focuses the search box, **Esc** closes.

## Configuration

### `config/ae2declogger-common.toml`

Used by the server (in single player, by the built-in server).

| Section | Key | Default | Range | What it does |
|---|---|---|---|---|
| updates | `updateIntervalTicks` | 10 | 5-200 | Ticks between monitor updates (20 = 1 second) |
| updates | `alertIntervalTicks` | 40 | 20-1200 | Ticks between background alert checks |
| updates | `maxJobs` | 32 | 1-256 | Most CPUs listed per network; stuck ones first |
| updates | `maxStepsPerJob` | 24 | 1-200 | Most steps sent per job; stuck ones first |
| updates | `maxMachinesPerStep` | 4 | 1-16 | Most machines inspected per step |
| detection | `slowAfterSeconds` | 30 | 1-86400 | No progress for this long: Slow |
| detection | `stallAfterSeconds` | 120 | 1-86400 | No progress for this long: Stalled |
| detection | `minStallSeconds` | 15 | 1-86400 | Lower limit for the stall time of machines with a known recipe time |
| detection | `recipeTimeMultiplier` | 6.0 | 1-1000 | With a known recipe time (GregTech), stall after recipe time × this, between `minStallSeconds` and `stallAfterSeconds` |
| detection | `blockedAfterSeconds` | 5 | 0-3600 | How long a blocker must last before the step shows Blocked |
| history | `historyEntries` | 1000 | 1-2000 | Finished jobs and job steps kept per network (saved with the world) |
| machineViewer | `maxSlots` | 108 | 9-512 | Most slots and tanks shown for one machine |
| link | `allowCrossDimension` | true | | Let **K** open the last network from another dimension |
| link | `maxLinkDistance` | 0 | 0-1000000 | Most blocks from the last network for **K**; 0 = no limit |

A GregTech machine that reports it is working never shows as Stalled, only as Slow once it runs past recipe time × `recipeTimeMultiplier`. Long GT recipes do not raise false alarms.

### `config/ae2declogger-client.toml`

| Section | Key | Default | What it does |
|---|---|---|---|
| alerts | `chat` | true | Chat message on a stall |
| alerts | `sound` | true | Bell sound on a stall |
| alerts | `toast` | true | Pop-up on a stall |
| ui | `terminalButton` | true | Declogger button on AE2 terminals and crafting status screens |
| ui | `craftingOverlay` | true | Frames on stuck entries and the Inspect popup in AE2's crafting status screen |
| ui | `showOnlyProblems` | false | Start the monitor with the problems-only filter on |
| pins | `renderInWorld` | true | Draw pins in the world |
| pins | `hud` | true | Show the navigation line |
| pins | `renderDistance` | 256 | Do not draw pins further away than this (16-4096) |
| pins | `hudY` | 4 | Pixels from the top of the screen for the navigation line |

## Multiplayer and permissions

- You can watch a network if you can open one of its terminals or crafting CPUs, or if you are close enough to look at one of its blocks. The last network is remembered per player.
- The machine viewer opens only for machines listed in your open monitor, or for operators. Refresh requests are rate-limited.
- The server does no work for networks nobody is watching.

## Compatibility

- **Offline CPUs.** A crafting CPU that loses power or its channel mid-job stays in the list as "Crafting CPU offline" instead of disappearing.
- **AE2 add-ons.** Providers that use AE2's pattern provider logic (ExtendedAE's extended providers, for example) are fully supported. Add-on providers with their own logic class, like Advanced AE's advanced pattern providers, are read by name: their machines, blocking mode and lock reasons show up. GregTech's ME Pattern Buffer is supported; its multiblock controller is shown as the machine. Crafting CPUs from add-ons that keep AE2's crafting logic (MEGA Cells) show full detail.
- **Advanced AE.** Quantum Computer CPUs show full detail too: steps, machines and block reasons. They are read by name (CI checks Advanced AE 1.1 to 1.3 on 1.20.1 and the newest releases for 1.21.1 and 26.1.2), and a job on one counts as done or cancelled from its crafting link, not from its CPU disappearing. A quantum computer keeps one CPU number for all its jobs. Other add-on CPUs show the job, progress and time only.
- **GregTech CEu Modern** (1.20.1). Status, recipe progress, waiting and failure reasons, power, maintenance and formed state come from GT directly. If a different GT version has a different API, the GT part switches itself off with one log line and machines show generic info.
- **Other tech mods.** The monitor says why a machine from these mods is not working:

  | Mod | What it reports |
  |---|---|
  | Mekanism | Its own recipe warnings (not enough energy, no input, output full, no recipe) and redstone off |
  | Ender IO | Its machine states (no power, no capacitor, output full, redstone off) with Ender IO's own text |
  | Immersive Engineering | The whole multiblock from its IO block: redstone off, no energy, process queue full |
  | Modern Industrialization | Structure incomplete (resolved from the input hatch), redstone off, no EU, progress |
  | Industrial Foregoing | Redstone off, no energy, cannot process |
  | Create | Not rotating, overstressed, too slow; a basin with no mixer or press above it |
  | Powah | Energizing Orb with no charged rods aimed at it, or no recipe |
  | Oritech | Not assembled (resolved from a machine core), redstone off, output full, no energy |
  | Iron Furnaces | No fuel, no energy (factory), progress |

  These mods are read by name, not compiled against. If a mod update renames what the Declogger reads, that mod's integration switches itself off with one log line and its machines fall back to the generic view. CI checks every name against the current release of each mod on each Minecraft version.
- **Any other machine.** Machines show their block name, inventory, tanks and FE energy. A machine whose FE buffer is empty is reported as having no power, and a machine that exposes its inventory only on some sides is still read.
- **Other mods.** CI starts a real server on every version with AE2 and its popular add-ons together (Advanced AE, Extended AE, MEGA Cells, AE2 Wireless Terminals, Applied Flux, where they exist for that version) to catch conflicts.
- The mod only reads AE2 state through AE2's public classes. It adds no mixins and changes nothing in AE2, Advanced AE or GregTech. It even avoids AE2's own provider lookup, because walking it would shift which provider AE2 picks next.

## Known limits

- AE2 does not record which pattern provider took which push. When several providers hold the same pattern, all of their machines are listed and the one with a problem is shown first.
- Jobs are looked at once a second when nobody has the monitor open. A step whose items are all made and back between two looks is still recorded (from the previous look), but one that never had items out at a machine during any look is not.
- The mod does not load chunks. Opening the monitor with **K** and background alerts only work while that network is loaded (you are nearby, or it is chunk-loaded).

## Building from source

Each Minecraft version is its own Gradle project under `versions/`, named `<loader>-<minecraft version>`:

```
./gradlew build                                   # every version: compile, release jars
./gradlew :neoforge-1.21.1:build                  # one version
./gradlew :neoforge-1.21.1:runGameTestServer      # in-game tests on a headless server with AE2
./gradlew :neoforge-1.21.1:runSelfTestServer      # runs a crafting job through a network on a dedicated server
./gradlew :neoforge-1.21.1:runClient              # dev client with AE2
./gradlew :neoforge-1.21.1:runSelfTestClient      # scripted client run that saves screenshots (needs a display)
```

The release jar is `versions/<loader>-<minecraft version>/build/libs/ae2declogger-<loader>-<minecraft version>-<version>.jar`. Jars ending in `-dev` or `-gametest` only work in this workspace or in CI.

The code is shared. `src/main/java` holds it, `loaders/<forge|neoforge>` replaces whole files for one loader, and `versions/<name>/src/main/java` replaces whole files for one version. Lines that differ between versions sit in `//#if MC >= 1.20.5` / `//#else` / `//#endif` blocks; the build keeps the branch that fits each version (see `buildSrc/src/main/java/PreprocessTask.java`). Java needs 17, 21 and 25 installed (Gradle can download them).

The detection logic has its own Minecraft-free unit tests: `./gradlew :logic-tests:test`.

To publish a release, set `mod_version` in `gradle.properties`, then either push a tag `v<version>` or open **Actions → Build and test → Run workflow**, pick the branch and tick **release**. The jar is attached to the GitHub release only after every test job passes.

## Tests

- **Unit tests** for the stall/block rules, change timers, alert de-duplication, step timing, history stats, CSV escaping and formatting.
Every test below runs on every Minecraft version unless it says otherwise.

- **Game tests** that build real AE2 networks on a headless server:
  - a job whose results never come back is reported Stalled with the chest as its machine, and lands in History as complete once the results are inserted;
  - blocking mode with leftover inputs is reported Blocked;
  - a provider with nothing to push into is reported, then reports "No pattern provider" once removed;
  - a CPU shaped like Advanced AE's quantum computer threads is read like AE2's own, and its job counts as done or cancelled correctly when the CPU disappears;
  - a two-step job (gravel to cobblestone to stone) followed only by the background pass lands in History as the job plus its cobblestone step;
  - history survives saving and loading with the world;
  - an ingredient the CPU holds with other item data (a used tool) is not reported missing when the pattern accepts it;
  - an add-on pattern provider with its own logic class is read by name;
  - network lookup errors, the remembered network and alert data, and every packet's encoding.
- **Server self-test**: on a real dedicated server, builds a network next to spawn and runs a two-step job through it; the job must stall at the chest, show in the machine viewer, then finish and land in History from the background pass.
- **Client self-test**: creates a world, builds a network with one stalled and one blocked job, drives the real screens over real packets (including AE2's crafting status screen and its Inspect popup), checks what they show and takes screenshots.
- **Advanced AE check**: downloads Advanced AE 1.1, 1.2 and 1.3 for 1.20.1 and the newest releases for 1.21.1 and 26.1.2, and checks every class, field and method name the Declogger reads.
- **Server smoke test**: boots a real dedicated server with the release jar, with AE2 alone, with AE2 and its popular add-ons, and on 1.20.1 with AE2 + GregTech CEu Modern 7.5.3.
- **Machine integrations on a real server**: with the tech mods above installed (each where it exists for that version), every class and member name each integration reads must exist. One unpowered machine per mod is then placed and must be read by that mod's integration. Where the answer is certain, the reason must be right: a Create machine with no rotation, a basin with nothing above it, an iron furnace with ore and no fuel, an unpowered Mekanism machine.
- **GregTech check on a real server** (1.20.1): with AE2 and GregTech CEu Modern 7.5.3 installed, a pattern provider feeds an unpowered LV Electric Furnace; the job must show Blocked with "Machine has no power", and the viewer data must include the LV tier, EU storage and the furnace's input/output slots.

All of these run in GitHub Actions on every pull request.

## License

MIT
