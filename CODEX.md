# Codex Worker Adapter — Noise Machine

> **Contract:** Follow `WORKER_SPEC.md` for iteration lifecycle, allowed actions,
> one-action rule, escalation conditions, and output contract. This file covers
> Codex-specific mechanics only.

## Framework
e2e autonomous development loop (see `ref/AUTONOMOUS_SOFTWARE_DEVELOPMENT.md` in the e2e templates repo for the methodology overview).

## Required Reading — Every Iteration

You do not have `@`-reference loading. You must explicitly read these files at
the start of every iteration before taking any action:

1. `WORKER_SPEC.md` — backend-agnostic worker contract (read first)
2. `PROJECT.md` — scope, constraints, success criteria
3. `ARCHITECTURE.md` — component map, layer contracts, implementation sequence
4. `GOVERNANCE.md` — development process reference
5. `DEVPLAN.md` — current status, cold start summary, gotchas

Do not skip any of these reads. Do not assume their contents from previous
iterations. You are stateless.

## Load for Current Module
After reading DEVPLAN, determine the active track and module from its Current
Status section. Then read the relevant section of ARCHITECTURE.md for the
layer contract and module dependencies.

<!-- Per-module ARCH files are not created yet. Add a lookup table here once
     Phase 2 module boundaries are formalized, e.g.:
| Module | ARCH file |
|--------|----------|
| audio-engine | `ARCH_audio_engine.md` |
| playback-service | `ARCH_playback_service.md` |
-->

## Available Modules

**Track A — Single track (phases executed sequentially):**
- Phase 1 — Core playback: NoiseSource + AudioTrack + Play/Stop
- Phase 2 — Color engine: SpectralShaper + ParameterSmoother + GainSafety + tuning
- Phase 3 — Productization: Timer, fade-in/fade-out, Settings skeleton, persistence
- Phase 4 — Background robustness: Foreground Service + notification + screen-off behavior
- Phase 5 — Secondary polish: Texture, restrained stereo decorrelation, optional micro-variation

## Project-Specific Notes
- **Source language:** Kotlin.
- **UI framework:** Jetpack Compose (+ ViewModel for state).
- **Audio output:** Android `AudioTrack` (PCM streaming; no prerecorded assets).
- **Playback lifecycle:** Android Foreground Service (mandatory — see D-4 in DECISIONS.md).
- **Persistence:** DataStore or SharedPreferences for last-used Color and timer selection (TBD during Phase 3).
- **Test strategy:** Unit tests for DSP components (NoiseSource statistics, SpectralShaper frequency response, ParameterSmoother monotonicity, GainSafety bounds). Instrumented tests for PlaybackService lifecycle. Specific framework (JUnit/Robolectric/Espresso) chosen during Phase 1.
- **Non-negotiable constraints:**
  - The audio render loop must be allocation-free.
  - Every exposed parameter change must go through `ParameterSmoother` — no direct assignment into the DSP stages.
  - At the dark (brown-like) extreme of Color, low-end containment must be active (leaky accumulation, DC blocker, or bounded coefficients — choice is provisional).

## Codex-Specific Tool Rules
- **No `@` references.** Read files explicitly using file-read tools or CLI.
  When a file contains `@FILENAME` references, treat them as file paths to read.
- **Command files shared with Claude.** Action procedures live in
  `.claude/commands/*.md`. Read these files and follow their instructions the
  same way Claude does — the content is backend-agnostic.
- **Fresh reads before edits.** Before editing any file (especially DEVPLAN.md),
  read it immediately before the edit — not at the start of the iteration.
- **Shell usage.** Use CLI tools directly for builds, tests, git operations,
  file discovery, and search.
- **Search tool availability.** This loop environment may not have `rg`
  installed. Before using `rg`, check availability with `command -v rg`. If it
  is absent, use portable fallbacks instead: `find` for file discovery,
  `grep -RIn` for text search, and `sed -n` for bounded file reads. Do not
  repeatedly attempt `rg` after it has failed in the same iteration.
- **Windows host + Git Bash:** the loop runs on Windows under Git Bash / MSYS.
  Prefer forward-slash paths for bash-invoked commands; reserve backslash paths
  for PowerShell-only usage.

## Action Instructions

WORKER_SPEC.md defines four allowed actions. Here is how to execute each one
in Codex. Perform **exactly one** per iteration.

### Phase Plan
**When:** No active phase for the current module.
1. Read `.claude/commands/phase-plan.md` and follow its instructions.
2. Commit with message: `phase-plan: <module>.<phase> — <summary>`.
3. Emit exit signal and stop.

### Step Execution
**When:** A phase is in progress with remaining steps.
1. Pick the next step from DEVPLAN. Do all file read/write work.
2. Run builds, tests, and git operations as needed.
3. Read `.claude/commands/step-done.md` and follow its instructions.
4. Emit exit signal and stop. Do **not** start the next step.

### Phase Review
**When:** All steps in the current phase are complete.
1. Read `.claude/commands/phase-review.md` and follow its instructions.
2. Emit exit signal and stop.

### Phase Complete
**When:** Review is done and fixes (if any) are applied.
1. Read `.claude/commands/phase-complete.md` and follow its instructions.
2. Emit exit signal and stop.

## Output Contract

End every iteration with exactly these four lines — no additional text after:

```
LOOP_SIGNAL: CONTINUE | ESCALATE
REASON: <one-line summary>
ACTION_TYPE: PHASE_PLAN | STEP | REVIEW | COMPLETE
ACTION_ID: <module.phase.step>
```

## Autonomy

When invoked in autonomous mode, execute the action and emit the exit signal
without waiting for human input. In supervised mode, surface proposed changes
for approval before committing.

See WORKER_SPEC.md §8 for full mode definitions.
