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

- Forge 1.20.1 mod (ModDevGradle legacyforge 2.0.86, Forge 47.3.0, Java 17). Mod id `ae2declogger`, package `com.syhros.ae2declogger`.
- Depends on AE2 15.x (`appeng:appliedenergistics2-forge` from modmaven.dev); GregTech CEu Modern 7.5.3 is compile-only and optional (`compat/gtceu`, only `GtMachineReader` touches GT classes).
- Read-only by design: never extract/insert, never iterate `CraftingService.getProviders()` (it moves AE2's round-robin cursor; use `ProviderIndex.providersFor`).
- `logic/` is Minecraft-free and unit tested: `cd logic-tests && gradle test` works without Minecraft downloads.
- Full checks run in GitHub Actions (`.github/workflows/build.yml`): build + unit tests, `runGameTestServer` (AE2 game tests in `gametest/`), `runSelfTestClient` under xvfb (screenshots pushed to the `ci-screenshots` branch), and a real Forge server smoke test with the release jar (AE2 only, and AE2 + GTCEu).
- No items (removed in 1.1). Entry points: `client/CraftingScreenOverlay` (frames + Inspect popup on AE2's `CraftingCPUScreen`, layout read by reflection, falls back to `getStackUnderMouse`), the terminal icon button, and the K hotkey (looked-at ME block, else `server/NetworkMemory` last network). Alerts are per player per network via `NetworkMemory.alerts` in player persistent data.
- Advanced AE (quantum computer) CPUs are read by reflection in `server/CpuAccess` (`craftingLogic` field, AE2 method names, `job.tasks[].value`); `gametest/FakeAddonCpu` mimics them and the `advanced-ae` CI job javap-checks real AAE release jars. Job outcome comes from the crafting link (`GridTracker.completed`), not CPU disappearance.
- No Teleport (removed in 1.2). Go To = unsaved red outline (`PinManager.setTarget`); Pin = saved red pin.
- `gametest/` and `data/ae2declogger/structures/` are excluded from the release jar.
- Release: push a `v*` tag, or run the "Build and test" workflow manually (Actions → Run workflow) with "release" ticked; the release job creates tag `v<mod_version>` and attaches the jar. Bump `mod_version` in gradle.properties first. Cloud sessions cannot push tags (403), so they use the manual run.

