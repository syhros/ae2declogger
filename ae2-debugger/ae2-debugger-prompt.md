# AE2 Declogger - Mod Development Prompt

## Project Overview
Create a Minecraft mod that debugs and diagnoses AE2 autocrafting bottlenecks in real time. Users can identify stuck recipes, blocked machines, and performance issues within their autocrafting system.

**Target**: Sky Greg (and general AE2 compatibility)
**Name options**: AE2 Declogger / AE2 Autocrafting Debugger / AE2 Status Monitor
**Scope**: Diagnostic tool, read-only (no item manipulation)

---

## Phase 1: Research & Architecture

### Use `/cheap-scout` for:
- Existing AE2 mods and how they extend AE2 (Ae2FluidSupplier, AE2 Stuff, Applied Energistics 2 itself)
- AE2 API surface: how to hook into autocrafting status, network info, crafting CPU data
- AE2 event system and change listeners (recipe updates, job status, etc.)
- How WAILA/TOP mods read machine state (for error reporting reference)
- Forge/Fabric event hooks for ticking/network updates
- Current mod version for Sky Greg and compatibility range

### Then determine:
- Which AE2 version(s) to target (Sky Greg's current + newer versions)
- Whether to build on Forge or Fabric (if Sky Greg uses Forge, start there)
- Data sources: pull from AE2 CPU, grid, autocrafting jobs directly
- Network sync strategy: client-side display of server-side autocrafting state

---

## Phase 2: Core Features (MVP)

### 1. Autocrafting Monitor Panel
- **Access**: Integrate into AE2's terminal GUI or create a new "Declogger" item/block
- **Display**: List all active autocrafting jobs
  - Item name + quantity requested
  - Current progress (items crafted so far)
  - Status: "In Progress" / "Blocked" / "Stalled" / "Complete"
  - Elapsed time vs. expected recipe time
  - Color coding: green (on track), yellow (slow), red (stuck/blocked)

### 2. Bottleneck Detection
- Compare elapsed time to recipe duration:
  - If a recipe takes 10s but has been running 2+ minutes → flag as "Stalled"
  - If a recipe is complete but next step hasn't started → "Blocked" (dependency waiting)
- Report blocker reason if detectable:
  - Machine unavailable / occupied
  - Input missing (storage doesn't have next ingredient)
  - Output full (destination storage full)
  - Power starved (if applicable to machine)

### 3. Locator Pins & Machine Navigation
- Click on a stalled/blocked job → place a locator pin on the map
- Click the locator pin → teleport camera/navigate to the offending machine
- Show machine coordinates in log entry

### 4. Machine Status Viewer (Read-Only)
- Click "View Machine" button from a job entry
- Opens an overlay showing the target machine's GUI in read-only mode
  - Display recipe progress, inputs/outputs in slots
  - Show machine status (working, idle, error)
  - Do NOT allow item extraction/manipulation
- Include error messages from the machine (e.g., "Missing GT Energy" like WAILA shows)
- Highlight problematic slots (missing items, full output, etc.)

### 5. Error Reporting
- Hook into machine's error state (GT machines report power issues, IO issues, etc.)
- Display in machine viewer: `[⚠️] Missing GT Energy`
- Also report in the main job list where applicable

---

## Phase 3: Implementation Details

### Architecture
```
1. Server-Side (Mod)
   - Monitor active AE2 autocrafting jobs (tick listener)
   - Calculate elapsed time, expected duration
   - Detect blocked/stalled conditions
   - Query machine state at coordinates (GT machines, other blockers)
   - Broadcast job status to client (network packet)

2. Client-Side (GUI)
   - Render job list with status indicators
   - Handle clicks: "View Machine", "Go To", "Clear"
   - Display read-only machine GUI overlay
   - Manage locator pins on the map

3. Data Structures
   - JobStatus: { itemId, qty, elapsed, expected, status, blockReason, machineCoords }
   - MachineStatus: { blockState, inventory, power, errorMsg }
```

### AE2 Integration
- Hook: `IGridNode.getGrid()` to access the AE2 network
- Hook: Autocrafting CPU job list (iterate active jobs)
- Hook: Machine state at coordinates (if GT or similar, use their APIs)
- Network packet: Push job/machine status updates to client
- GUI: Extend or hook into AE2's terminal/crafting UI, or create a new item/block that opens the debugger

### Machine Status Integration
- **GT Machines**: Query `getMetaTileEntity()` for status, power, progress
- **Other machines**: Use generic inventory/state reading if no specific API
- **Error messages**: Pull from machine's error state or internal data
- **Fall back** to "Machine state unknown" if machine API not accessible

### Read-Only Machine Viewer
- Display machine's inventory slots (inputs, outputs, internal buffers)
- Show recipe progress bar (if available)
- Show machine-specific data (power level, speed, etc.)
- Disable all interaction; info-only mode

---

## Phase 4: Testing & Compatibility

### Testing Checklist
- [ ] Spawn AE2 system with ME Controller, crafting CPU, molecular assemblers
- [ ] Queue autocrafting job, verify job appears in debugger
- [ ] Monitor job status as it progresses (mark as "In Progress")
- [ ] Deliberately block a job (e.g., turn off assembler, remove input)
  - Verify "Blocked" status appears
  - Verify error reason is shown if available
- [ ] Click "Go To" on a blocked job
  - Verify locator pin placed and navigation works
- [ ] Click "View Machine" on a stuck job
  - Verify read-only GUI appears
  - Verify no items can be extracted
- [ ] Test with GT machines specifically
  - Verify power issues, IO errors reported
- [ ] Test in Sky Greg environment
  - Run alongside other mods (GTCE, AE2, etc.)
  - Verify no crashes or conflicts
- [ ] Test in vanilla AE2 pack
  - Verify mod works without Sky Greg dependencies

### Compatibility Notes
- **Do not** modify AE2 code directly; only use public APIs
- **Do not** hard-depend on GT machines; make them optional
- **Version range**: Sky Greg's AE2 version + 1–2 major versions above (if available)
- **Mods to test alongside**: GTCE, Thermal Expansion, Immersive Engineering, Storagedrawers, etc.

---

## Phase 5: Polish & Release

### Features to Add After MVP
- [ ] History log (past autocrafting jobs, completion times)
- [ ] Autocrafting performance stats (avg craft time, success rate)
- [ ] Filter/search jobs by item name
- [ ] Favorite/bookmark frequently-checked machines
- [ ] Optional: Alert sounds/notifications when job stalls
- [ ] Optional: Export job log to file

### Release
- Post to CurseForge as standalone mod (AE2 Declogger / AE2 Status Monitor)
- Mark compatible with Sky Greg, vanilla AE2 packs
- Include crafting recipe for debugger item (or allow creative-mode access)
- Write mod description: "Real-time autocrafting diagnostics for AE2. Spot bottlenecks, view machine status, troubleshoot blockers—without leaving your terminal."

---

## Technical Constraints

### Modding Framework
- **Language**: Java (Minecraft mod standard)
- **Loader**: Forge (if Sky Greg is Forge) or Fabric
- **Minecraft version**: Sky Greg's version (check beforehand)
- **Dependencies**: AE2 (required), optional: GTCE (for better error messages)

### GUI Considerations
- Integrate with AE2's terminal GUI or create new screen
- Use Minecraft's native rendering (no external frameworks)
- Ensure readable on all resolutions; scale gracefully
- Keep performance light (don't poll every tick; batch updates)

### Network Performance
- Send updates every 5–10 ticks (not every tick)
- Only update changed job statuses (delta packets)
- Cap packet size (limit concurrent jobs monitored)

---

## Delegation & Autonomy

**Use `/cheap-scout` to**:
1. Research AE2 mod API and event listeners
2. Find how existing AE2 extension mods hook into the system
3. Locate WAILA/TOP code for machine error reporting patterns
4. Identify Sky Greg's current AE2 version and compatible versions

**Opus should then**:
- Design the architecture based on research
- Implement core features (MVP first)
- Build and test in a Sky Greg environment
- Handle edge cases and mod conflicts autonomously

---

## Success Criteria
1. Debugger correctly identifies stalled/blocked autocrafting jobs
2. Machine status viewer displays read-only GUI without crashes
3. Locator pin navigation works smoothly
4. No mod conflicts in Sky Greg or vanilla AE2 packs
5. Mod is releasable on CurseForge with documentation
