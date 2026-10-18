<!-- dgc-policy-v11 -->
# Dual-Graph Context Policy

This project uses a local dual-graph MCP server for efficient context retrieval.

## MANDATORY: Adaptive graph_continue rule

**Call ``graph_continue`` ONLY when you do NOT already know the relevant files.**

### Call ``graph_continue`` when:
- This is the first message of a new task / conversation
- The task shifts to a completely different area of the codebase
- You need files you haven't read yet in this session

### SKIP ``graph_continue`` when:
- You already identified the relevant files earlier in this conversation
- You are doing follow-up work on files already read (verify, refactor, test, docs, cleanup, commit)
- The task is pure text (writing a commit message, summarising, explaining)

**If skipping, go directly to ``graph_read`` on the already-known ``file::symbol``.**

## When you DO call graph_continue

1. **If ``graph_continue`` returns ``needs_project=true``**: call ``graph_scan`` with ``pwd``. Do NOT ask the user.

2. **If ``graph_continue`` returns ``skip=true``**: fewer than 5 files  -  read only specifically named files.

3. **Read ``recommended_files``** using ``graph_read``.
   - Always use ``file::symbol`` notation (e.g. ``src/auth.ts::handleLogin``)  -  never read whole files.
   - ``recommended_files`` entries that already contain ``::`` must be passed verbatim.

4. **Obey confidence caps:**
   - ``confidence=high`` -> Stop. Do NOT grep or explore further.
   - ``confidence=medium`` -> ``fallback_rg`` at most ``max_supplementary_greps`` times, then ``graph_read`` at most ``max_supplementary_files`` more symbols. Stop.
   - ``confidence=low`` -> same as medium. Stop.

## Session State (compact, update after every turn)

Maintain a short JSON block in your working memory. Update it after each turn:

``````json
{
  "files_identified": ["path/to/file.py"],
  "symbols_changed": ["module::function"],
  "fix_applied": true,
  "features_added": ["description"],
  "open_issues": ["one-line note"]
}
``````

Use this state  -  not prose summaries  -  to remember what's been done across turns.

## Token Usage

A ``token-counter`` MCP is available for tracking live token usage.

- Before reading a large file: ``count_tokens({text: "<content>"})`` to check cost first.
- To show running session cost: ``get_session_stats()``
- To log completed task: ``log_usage({input_tokens: N, output_tokens: N, description: "task"})``

## Rules

- Do NOT use ``rg``, ``grep``, or bash file exploration before calling ``graph_continue`` (when required).
- Do NOT do broad/recursive exploration at any confidence level.
- ``max_supplementary_greps`` and ``max_supplementary_files`` are hard caps  -  never exceed them.
- Do NOT call ``graph_continue`` more than once per turn.
- Always use ``file::symbol`` notation with ``graph_read``  -  never bare filenames.
- After edits, call ``graph_register_edit`` with changed files using ``file::symbol`` notation.

## Context Store

Whenever you make a decision, identify a task, note a next step, fact, or blocker during a conversation, append it to ``.dual-graph/context-store.json``.

**Entry format:**
``````json
{"type": "decision|task|next|fact|blocker", "content": "one sentence max 15 words", "tags": ["topic"], "files": ["relevant/file.ts"], "date": "YYYY-MM-DD"}
``````

**To append:** Read the file -> add the new entry to the array -> Write it back -> call ``graph_register_edit`` on ``.dual-graph/context-store.json``.

**Rules:**
- Only log things worth remembering across sessions (not every minor detail)
- ``content`` must be under 15 words
- ``files`` lists the files this decision/task relates to (can be empty)
- Log immediately when the item arises  -  not at session end

## Session End

When the user signals they are done (e.g. "bye", "done", "wrap up", "end session"), proactively update ``CONTEXT.md`` in the project root with:
- **Current Task**: one sentence on what was being worked on
- **Key Decisions**: bullet list, max 3 items
- **Next Steps**: bullet list, max 3 items

Keep ``CONTEXT.md`` under 20 lines total. Do NOT summarize the full conversation  -  only what's needed to resume next session.

## Project notes: AE2 Declogger

- One codebase, five builds: Forge 1.18.2 (AE2 11), Forge 1.19.2 (AE2 12), Forge 1.20.1 (AE2 15, optional GTCEu 7.5.3), NeoForge 1.21.1 (AE2 19), NeoForge 26.1.2 (AE2 26.1 beta, Java 25). Mod id `ae2declogger`, package `com.syhros.ae2declogger`.
- Layout: `src/main/java` is shared and written so its active branch is Forge 1.20.1; `loaders/<forge|neoforge>/{java,resources}` and `versions/<loader>-<mc>/src/main/{java,resources}` replace whole files. Each `versions/<name>` has `build.gradle` + `version.properties`; shared build logic is `gradle/mod/{common,forge,neoforge}.gradle`. List new versions in `settings.gradle` (CI reads `versions/` via `.github/scripts/versions_matrix.py`).
- Preprocessor (`buildSrc/.../PreprocessTask.java`, .java/.json/.toml): `//#if MC >= 1.20.5 && NEOFORGE`, `//#elseif`, `//#else`, `//#endif`; active lines written `//$$ ` get uncommented. Prefer the version helpers over new `//#if` blocks: `Mc`, `Text`, `Nbt`, `network/Bufs`, `server/Caps`, `client/screen/Gfx`, `client/screen/Widgets`.
- Build one version: `./gradlew :neoforge-1.21.1:build`, `:runGameTestServer`, `:runSelfTestServer`, `:runSelfTestClient`. Set JAVA_HOME to a JDK 17; Gradle finds 21 and 25 as toolchains.
- 26.1 specifics: game tests are registered from `versions/neoforge-26.1.2/.../gametest/GameTests261` (reflection over `DecloggerGameTests`); pins are drawn as vanilla gizmos from the client tick (26.1 `PinRenderer` override); no spawn chunks, so `ServerSelfTest` force-loads its chunks; event subscriber classes must not build item stacks in static fields.
- GregTech compat is 1.20.1 only (other versions get a stub `GtMachineReader`).
- Other tech mods: `compat/Machines` (facade over GT and the readers), `compat/mods/*Reader` (one per mod, reflection by name through `compat/Refl`, never compiled against the mod), `compat/MachineInfo` (result). A reader throwing `ReflectiveOperationException`/`LinkageError` switches itself off. Each reader lists the names it uses in `names()`; `gametest/ModMachineSelfTest` checks them plus one placed machine per mod on the CI "tech" server (`fetch_mods.py` pack `tech`). Plan and to-implement list: `docs/machine-integrations.md`.
- Read-only by design: never extract/insert, never iterate `CraftingService.getProviders()` (it moves AE2's round-robin cursor; use `ProviderIndex.providersFor`).
- `logic/` is Minecraft-free and unit tested: `./gradlew :logic-tests:test`.
- Full checks run in GitHub Actions (`.github/workflows/build.yml`) per version: build, `runGameTestServer`, `runSelfTestServer`, `runSelfTestClient` under xvfb (screenshots pushed to the `ci-screenshots` branch under `<version>/`), and real dedicated servers with the release jar (AE2 only, AE2 + add-ons, AE2 + GTCEu on 1.20.1).
- No items (removed in 1.1). Entry points: `client/CraftingScreenOverlay` (frames + Inspect popup on AE2's `CraftingCPUScreen`, layout read by reflection, falls back to `getStackUnderMouse`), the terminal icon button, and the K hotkey (looked-at ME block, else `server/NetworkMemory` last network). Alerts are per player per network via `NetworkMemory.alerts` in player persistent data.
- Add-on pattern providers that are not AE2's `PatternProviderLogic` (Advanced AE) are read by reflection in `server/ProviderIndex`. Advanced AE (quantum computer) CPUs are read by reflection in `server/CpuAccess` (`craftingLogic` field, AE2 method names, `job.tasks[].value`); `gametest/FakeAddonCpu` mimics them and the `advanced-ae` CI job javap-checks real AAE release jars. Job outcome comes from the crafting link (`GridTracker.completed`), not CPU disappearance.
- No Teleport (removed in 1.2). Go To = unsaved red outline (`PinManager.setTarget`); Pin = saved red pin.
- `gametest/` and the test structures are excluded from the release jar (`devOnly` in common.gradle).
- Legacy versions (1.16.5 FG6, 1.12.2 RFG, GTNH 1.7.10 GTNH convention) are separate Gradle builds in `legacy/<name>`, sharing `legacy/src` (own `//#if MC` blocks, Java 8, AE2's old CraftingCPUCluster/interface design) plus root `logic/` (kept Java 8 compatible: no records). Per-version files: `Plat`, `Channel`, `Boot`, `ClientPlat`, `HostScreen`, `ClientBoot`, `selftest/TestWorld`; GTNH also `compat/Machines` (GT5u by reflection). Screens are written once against `client/ui/Canvas`. Checks: `./gradlew runServer -PselfTest` and `runClient -PclientSelfTest` in each folder (GTNH runs only in CI: Windows Defender blocks GTNHExtLib). Plan and status: `plans/legacy-versions.md`.
- Release: push a `v*` tag, or run the "Build and test" workflow manually (Actions → Run workflow) with "release" ticked; the release job creates tag `v<mod_version>` and attaches every version's jar (notes from `.github/RELEASE_NOTES.md`, `@JARS@` becomes the jar table). Bump `mod_version` in gradle.properties first. Cloud sessions cannot push tags (403), so they use the manual run.

