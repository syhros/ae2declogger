Download **`ae2declogger-forge-@MC_VERSION@-@VERSION@.jar`** below and put it in your `mods` folder. On a server, put the same jar in the server's `mods` folder as well.

## Requirements

- Minecraft 1.20.1 with Forge 47.1.3 or newer
- Applied Energistics 2 15.x
- GregTech CEu Modern is optional. With it installed, the monitor shows GT machine status, recipe progress, power and error messages.

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

Hotkeys can be changed under Options → Controls → Key Binds → AE2 Declogger. Settings are in `config/ae2declogger-common.toml` (detection times, update rate, teleport permissions) and `config/ae2declogger-client.toml` (alerts, pins, terminal button, crafting screen overlay).

The full guide, with every config option and every block reason, is in the [README](https://github.com/syhros/ae2-debugger#readme).

## Tested with

Forge 47.3.0, AE2 15.4.10 and GregTech CEu Modern 7.5.3. Every release passes the unit tests, the in-game tests on an AE2 server, a client test that drives the real screens, and a real Forge server started with and without GregTech.
