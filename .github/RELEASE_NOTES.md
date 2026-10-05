Download **`ae2declogger-forge-@MC_VERSION@-@VERSION@.jar`** below and put it in your `mods` folder. On a server, put the same jar in the server's `mods` folder as well.

## Requirements

- Minecraft 1.20.1 with Forge 47.1.3 or newer
- Applied Energistics 2 15.x
- GregTech CEu Modern is optional. With it installed, the monitor shows GT machine status, recipe progress, power and error messages.

## Quick start

1. Craft the **AE2 Declogger** (Spyglass, 2 Logic Processors, Network Tool, Calculation Processor) or take it from the Tools & Utilities creative tab.
2. Right-click any ME network block to link it. The monitor opens.
3. Press **K** to open the monitor again from anywhere, as long as the linked Declogger is in your inventory.
4. Sneak + right-click a block to open the read-only machine viewer.

Hotkeys can be changed under Options → Controls → Key Binds → AE2 Declogger. Settings are in `config/ae2declogger-common.toml` (detection times, update rate, teleport permissions) and `config/ae2declogger-client.toml` (alerts, pins, terminal button).

The full guide, with every config option and every block reason, is in the [README](https://github.com/syhros/ae2-debugger#readme).

## Tested with

Forge 47.3.0, AE2 15.4.10 and GregTech CEu Modern 7.5.3. Every release passes the unit tests, the in-game tests on an AE2 server, a client test that drives the real screens, and a real Forge server started with and without GregTech.
