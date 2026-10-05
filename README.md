# AE2 Declogger

A Forge 1.20.1 mod that shows what your Applied Energistics 2 crafting CPUs are doing, which jobs are stuck, and why. It points you at the machine that is holding things up and lets you look inside it without opening it.

It is read-only. It never moves, inserts or extracts items.

Built for [Sky Greg](https://www.curseforge.com/minecraft/modpacks/sky-greg) (GregTech CEu Modern + AE2) and works in any pack with AE2 15.x.

## Requirements

| | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.1.3 or newer (built against 47.3.0) |
| Applied Energistics 2 | 15.x (built against 15.4.10) |
| GregTech CEu Modern | optional. Built against 7.5.3, the version in Sky Greg |

Install the jar on both the client and the server. In single player, the client is enough.

## Download and install

1. Get the jar:
   - **From a release:** the [Releases page](https://github.com/syhros/ae2-debugger/releases), file `ae2declogger-forge-1.20.1-<version>.jar`.
   - **From a CI build:** open the latest green run under the repository's **Actions** tab, download the `ae2declogger-jar` artifact (a zip), and take the jar out of it.
2. Copy it into the `mods` folder of your instance. For Sky Greg in the CurseForge app: right-click the profile, **Open Folder**, then `mods`.
3. On a server, copy the same jar into the server's `mods` folder too.
4. Start the game. The item is under Tools & Utilities, or craft it (recipe below).

## Getting the item

**AE2 Declogger**, crafted from:

```
 . S .
 L N L
 . C .
```

- S = Spyglass
- L = Logic Processor
- N = Network Tool
- C = Calculation Processor

It is also in the creative **Tools & Utilities** tab.

## Using it

| Action | What happens |
|---|---|
| Right-click any ME network block (cable, drive, interface, terminal...) | Links the Declogger to that network and opens the monitor |
| Right-click in the air | Opens the monitor for the linked network |
| Sneak + right-click any block | Opens the read-only machine viewer for that block (within 8 blocks) |
| Sneak + right-click in the air | Turns stall alerts on or off for this Declogger |
| Press **K** | Opens the monitor for the first linked Declogger in your inventory |
| Click the Declogger icon above an AE2 terminal or crafting status screen | Opens the monitor for that terminal's network |

A linked Declogger glows and shows the linked position in its tooltip.

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

**History.** Finished and cancelled jobs, with how long they took. Jobs only show up here if they finish while someone has the monitor open or carries a linked Declogger with alerts on, because that is when the server is watching.

**Stats.** Per item: number of jobs, share that completed, average and best time, time per item.

**Pins.** Your locator pins (see below).

Buttons along the bottom act on the selected step's machine, or on the job's main machine if no step is selected:

| Button | What it does |
|---|---|
| Go To | Pins the machine, starts navigating to it and closes the monitor |
| View Machine | Opens the read-only machine viewer |
| Pin | Pins the machine without navigating |
| Teleport | Teleports you next to the machine. Operators can teleport to any machine or pin; creative players without op only to machines the monitor lists (see config) |

Other controls: **!** shows only slow, stalled and blocked jobs. **♪** turns stall alerts on or off. The search box filters by item name (Ctrl+F jumps to it). Keys **1** to **4** switch tabs. Right-clicking a job or step opens its machine straight away.

History and Stats have **Export CSV**, which writes current jobs and history to `<minecraft folder>/ae2declogger/exports/declogger-<date>-<time>.csv`. A chat message links to the file.

### Why a job is stuck

| Reason | What it means | Usual fix |
|---|---|---|
| Waiting for ingredients | An earlier step is still making the inputs | Nothing; this is normal |
| Missing ingredient | An input is neither in the CPU nor being made | Something consumed it; cancel and restart the job |
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

A pin draws a coloured box and a label on the machine. Both are visible through walls, up to 256 blocks away. The pin you are navigating to also shows a line at the top of the screen with its distance, a direction arrow and how far up or down it is. Pins are saved per world or server in `<minecraft folder>/ae2declogger/pins/`.

In the Pins tab, click a pin to select it and click it again (or press Navigate) to navigate to it.

### Alerts

With alerts on (the default), carrying a linked Declogger anywhere in your inventory makes the server check that network every 2 seconds. When a job turns Stalled or Blocked you get a chat message with the item, the reason and clickable coordinates, a bell sound and a pop-up. Each breakdown alerts once; it alerts again only after the job has recovered and got stuck again.

## Hotkeys

All can be changed under Options → Controls → Key Binds → AE2 Declogger.

| Key | Action | Default |
|---|---|---|
| Open crafting monitor | Opens the monitor for the first linked Declogger in your inventory | **K** |
| Stop navigating to pin | Clears the active pin | not bound |
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
| history | `historySize` | 50 | 1-1000 | Finished jobs kept per network |
| machineViewer | `maxSlots` | 108 | 9-512 | Most slots and tanks shown for one machine |
| machineViewer | `directViewRange` | 8 | 1-64 | Range for sneak + right-click viewing |
| link | `allowCrossDimension` | true | | Allow opening the monitor from another dimension |
| link | `maxLinkDistance` | 0 | 0-1000000 | Most blocks from the linked network; 0 = no limit |
| teleport | `enabled` | true | | Show and allow the Teleport buttons |
| teleport | `permissionLevel` | 2 | 0-4 | Permission level needed to teleport |
| teleport | `allowInCreative` | true | | Creative-mode players may always teleport |

A GregTech machine that reports it is working never shows as Stalled, only as Slow once it runs past recipe time × `recipeTimeMultiplier`. Long GT recipes do not raise false alarms.

### `config/ae2declogger-client.toml`

| Section | Key | Default | What it does |
|---|---|---|---|
| alerts | `chat` | true | Chat message on a stall |
| alerts | `sound` | true | Bell sound on a stall |
| alerts | `toast` | true | Pop-up on a stall |
| ui | `terminalButton` | true | Declogger button on AE2 terminals and crafting status screens |
| ui | `showOnlyProblems` | false | Start the monitor with the problems-only filter on |
| pins | `renderInWorld` | true | Draw pins in the world |
| pins | `hud` | true | Show the navigation line |
| pins | `renderDistance` | 256 | Do not draw pins further away than this (16-4096) |
| pins | `hudY` | 4 | Pixels from the top of the screen for the navigation line |

## Multiplayer and permissions

- Anyone holding a linked Declogger can watch that network. Linking needs a right-click on one of its blocks.
- The machine viewer opens only for machines listed in your open monitor, for blocks within `directViewRange` that you may interact with while carrying a Declogger, or for operators. Refresh requests are rate-limited.
- Teleport needs `permissionLevel` (operators by default) to go anywhere. With `allowInCreative`, creative players without that level can only teleport to machines their open monitor lists.
- The server does no work for networks nobody is watching.

## Compatibility

- **Offline CPUs.** A crafting CPU that loses power or its channel mid-job stays in the list as "Crafting CPU offline" instead of disappearing.
- **AE2 add-ons.** Providers that use AE2's pattern provider logic (ExtendedAE's extended providers, for example) are fully supported. GregTech's ME Pattern Buffer is supported; its multiblock controller is shown as the machine. Crafting CPUs from add-ons that keep AE2's crafting logic (MEGA Cells) show full detail. Others show the job, progress and time only.
- **GregTech CEu Modern.** Status, recipe progress, waiting and failure reasons, power, maintenance and formed state come from GT directly. If a different GT version has a different API, the GT part switches itself off with one log line and machines show generic info.
- **Without GregTech.** Everything works; machines show their block name, inventory, tanks and FE energy.
- The mod only reads AE2 state through AE2's public classes. It adds no mixins and changes nothing in AE2 or GregTech. It even avoids AE2's own provider lookup, because walking it would shift which provider AE2 picks next.

## Known limits

- AE2 does not record which pattern provider took which push. When several providers hold the same pattern, all of their machines are listed and the one with a problem is shown first.
- Items with NBT variants (damaged tools) that a pattern accepts by fuzzy match may show as "waiting for ingredients" a little longer than they should.
- History is kept in memory and resets when the server restarts.
- The mod does not load chunks. Opening the monitor with **K** and background alerts only work while the linked network is loaded (you are nearby, or it is chunk-loaded).

## Building from source

```
./gradlew build                 # compile, unit tests, release jar in build/libs/
./gradlew runGameTestServer     # in-game tests on a headless server with AE2
./gradlew runClient             # dev client with AE2
./gradlew runSelfTestClient     # scripted client run that saves screenshots (needs a display)
```

The release jar is `build/libs/ae2declogger-forge-1.20.1-<version>.jar`. The `-dev` jar only works in this workspace.

The detection logic has its own Minecraft-free test build: `cd logic-tests && gradle test`.

To publish a release, set `mod_version` in `gradle.properties`, then either push a tag `v<version>` or open **Actions → Build and test → Run workflow**, pick the branch and tick **release**. The jar is attached to the GitHub release only after every test job passes.

## Tests

- **Unit tests** for the stall/block rules, change timers, alert de-duplication, history stats, CSV escaping and formatting.
- **Game tests** that build real AE2 networks on a headless server:
  - a job whose results never come back is reported Stalled with the chest as its machine, and lands in History as complete once the results are inserted;
  - blocking mode with leftover inputs is reported Blocked;
  - a provider with nothing to push into is reported, then reports "No pattern provider" once removed;
  - network lookup errors, item link data, and every packet's encoding.
- **Client self-test**: creates a world, builds a network with one stalled and one blocked job, drives the real screens over real packets, checks what they show and takes screenshots.
- **Server smoke test**: boots a real Forge server with the release jar, with AE2 alone and with AE2 + GregTech CEu Modern 7.5.3.
- **GregTech check on a real server**: with AE2 and GregTech CEu Modern 7.5.3 installed, a pattern provider feeds an unpowered LV Electric Furnace; the job must show Blocked with "Machine has no power", and the viewer data must include the LV tier, EU storage and the furnace's input/output slots.

All of these run in GitHub Actions on every pull request.

## License

MIT
