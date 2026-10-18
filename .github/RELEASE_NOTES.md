Pick the jar for your Minecraft version below and put it in your `mods` folder. On a server, put the same jar in the server's `mods` folder as well.

@JARS@

## Requirements

| Minecraft | Loader | Applied Energistics 2 | Packs |
|---|---|---|---|
| 1.18.2 | Forge 40.1 or newer | 11.x | All the Mods 7 |
| 1.19.2 | Forge 43.2 or newer | 12.x | All the Mods 8 |
| 1.20.1 | Forge 47.1.3 or newer | 15.x | All the Mods 9, Sky Greg |
| 1.21.1 | NeoForge 21.1 or newer | 19.x | All the Mods 10 |
| 26.1.2 | NeoForge 26.1.2 or newer | 26.1.x | All the Mods 11 |
| 1.16.5 | Forge 36.2 or newer | 8.x | All the Mods 6 |
| 1.12.2 | Forge 14.23.5 | AE2 Extended Life | GregTech CEu packs, Enigmatica 2 |
| 1.7.10 | Forge 10.13.4 | GTNH's AE2 Unofficial | GT: New Horizons 2.8 |

GregTech CEu Modern is optional on 1.20.1. With it installed, the monitor shows GT machine status, recipe progress, power and error messages.

## What's new in 1.6

- **Older versions:** Forge 1.16.5 (AE2 8, All the Mods 6), Forge 1.12.2 (AE2 Extended Life) and GT: New Horizons 1.7.10. They have the monitor with Jobs, History, Stats and Pins, the machine viewer, the terminal button, the K hotkey, alerts, Go To and pins. On 1.16.5 and 1.12.2, stuck entries in AE2's crafting status screen get frames and the Inspect popup too.
- **GT: New Horizons:** GregTech 5 machines report why they are not working (switched off, not enough energy, output full, multiblock not formed, maintenance, the last recipe check, the shutdown reason), and input buses and ME crafting input buffers point at their multiblock.
- Not on GTNH yet: the frames and Inspect popup in AE2's crafting status screen. The older versions' settings are in `config/ae2declogger.properties`. The README has the details.

## What's new in 1.5.1

- **Queued instead of Blocked.** When two steps of the same job need the same machine (silver and gold dust from one macerator for electrum, for example), the one waiting its turn now shows **Queued** with the step it is waiting behind, instead of Blocked. It no longer makes the whole job look stuck. A machine busy with a different crafting CPU's job still counts as blocked.
- The Declogger button on AE2 terminals now sits directly above AE2's Crafting Status button.
- Messages on empty tabs wrap inside the window instead of running past its edges.

## What's new in 1.5

- **Machine status for the big tech mods.** When a job is stuck at one of their machines, the monitor and the machine viewer now say why:
  - **Mekanism:** not enough energy, waiting for input, output full, no recipe, redstone off.
  - **Ender IO:** no power, no capacitor, output full, redstone off.
  - **Immersive Engineering:** redstone off, no energy, process queue full, read from the multiblock's IO block.
  - **Modern Industrialization:** structure incomplete, redstone off, no EU, read from the input hatch.
  - **Industrial Foregoing:** redstone off, no energy, cannot process.
  - **Create:** not rotating, overstressed, too slow, basin with no mixer or press.
  - **Powah:** Energizing Orb with no charged rods, or no recipe.
  - **Oritech:** not assembled, redstone off, output full, no energy.
  - **Iron Furnaces:** no fuel, no energy.
- **Any machine** with an empty FE buffer is reported as having no power.
- **Sided inventories:** machines that expose their inventory only on some sides now show it in the viewer.
- Each integration switches itself off if a mod update renames what it reads, and CI checks them against the current release of every mod.

## What's new in 1.4

- **More Minecraft versions.** The Declogger now runs on Forge 1.18.2, Forge 1.19.2, Forge 1.20.1, NeoForge 1.21.1 and NeoForge 26.1.2. Every version has the same features: the monitor, the crafting status overlay and Inspect, the machine viewer, pins, alerts, History and Stats.
- **Add-on pattern providers are read correctly.** Advanced AE's advanced pattern providers, and other add-on providers that are not AE2's own class, were missed, so a job waiting on one had no machine to show. They are now read by name, like the quantum computer, so their machines, blocking mode and lock reasons show up. CI checks the names against Advanced AE releases for 1.20.1, 1.21.1 and 26.1.2.

## What's new in 1.3

- **Tools no longer show as a missing ingredient.** A GregTech file, hammer or other tool that has been used has different damage data than the one in the pattern. The Declogger now matches ingredients the way AE2 does when it pushes a pattern, so a used tool counts.
- **Every step of a job goes into History and Stats.** A job that makes 50 intermediate items records each of them: how many, how long from its first craft to its last, and which job it was for. Stats shows the time per item for things made as a step, so you can see roughly how long any item takes, not only the one you ordered.
- **History is saved with the world.** Leaving the world or restarting the server keeps it. It stays with a network as long as one of its ME controllers or crafting CPU blocks is still part of it.
- **Jobs are recorded without the monitor open.** Every loaded network with a busy crafting CPU is followed once a second.
- The history setting is now `historyEntries` (default 1000, up to 2000), because each job now adds an entry per step. The old `historySize` value is no longer used.

## What's new in 1.2

- **Advanced AE Quantum Computer support.** Jobs on a quantum computer now show their steps, the machine and why they are stuck, so Go To, View Machine and Pin work for them. The crafting status popup finds them too (no more "Waiting for the Declogger...").
- **Finished jobs no longer show as Cancelled.** A job's result now comes from its crafting link. Before, a quantum computer job counted as cancelled because its CPU disappears when it finishes. History and Stats are correct again.
- A quantum computer keeps one CPU number instead of a new one per job.
- **Go To** outlines the machine in red with the way to it on the HUD, without adding a pin. **Pin** marks it in red and keeps it.
- **Teleport is removed**, along with its `teleport` config section.
- When the Declogger has no step details for an entry, the popup shows the job's state instead of waiting forever.

## What's new in 1.1

- **No item any more.** The Declogger is built into AE2's own screens; there is nothing to craft or carry.
- **Inspect from the crafting status screen.** Open a crafting CPU (the Crafting Status tab of a terminal, or a crafting unit). Slow and stuck entries get a yellow or red frame. Click an entry to see its state, how long, why and which machine, then press **Inspect** to open the monitor on that job and step.
- **K** opens the monitor for the ME block you look at, or the last network you opened.
- Stall alerts are turned on per network with the **♪** button in the monitor.
- Removed: the Declogger item and recipe, sneak + right-click machine viewing, and the `directViewRange` setting. The machine viewer still opens from the monitor.

## Quick start

1. Open an AE2 terminal and go to its **Crafting Status** tab, or click the Declogger icon above the terminal.
2. Click a framed entry and press **Inspect**.
3. Press **K** to open the monitor again later.

Hotkeys can be changed under Options → Controls → Key Binds → AE2 Declogger. Settings are in `config/ae2declogger-common.toml` (detection times, update rate) and `config/ae2declogger-client.toml` (alerts, pins, terminal button, crafting screen overlay).

The full guide, with every config option and every block reason, is in the [README](https://github.com/syhros/ae2declogger#readme).

## Tested with

Every release is built and tested on each Minecraft version above with the AE2 version listed in the README. Each version passes the unit tests, the in-game tests on an AE2 server, a server test that runs a crafting job through a real network, a client test that drives the real screens, and real dedicated servers started with AE2 alone and with AE2 plus its popular add-ons (Advanced AE, Extended AE, MEGA Cells, AE2 Wireless Terminals, Applied Flux) where they exist for that version. On 1.20.1 a server with GregTech CEu Modern 7.5.3 is tested too.
