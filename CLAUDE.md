# Claude Worker Adapter — Noise Machine

> **Contract:** Follow `WORKER_SPEC.md` for iteration lifecycle, allowed actions,
> one-action rule, escalation conditions, and output contract. This file covers
> Claude-specific mechanics only.

## Framework
e2e autonomous development loop (see `ref/AUTONOMOUS_SOFTWARE_DEVELOPMENT.md` in the e2e templates repo for the methodology overview).

## Always Loaded
- @PROJECT.md — scope, constraints, success criteria
- @ARCHITECTURE.md — component map, layer contracts, implementation sequence
- @GOVERNANCE.md — development process reference
- @DEVPLAN.md — current status, cold start summary, gotchas
- @WORKER_SPEC.md — backend-agnostic worker contract

## Load for Current Module
Determine the active track and module from DEVPLAN's Current Status section.
For layer contracts and module dependencies, see ARCHITECTURE.md.

<!-- Per-module ARCH files are not created yet. Add a lookup table here once Phase 2
     module boundaries are formalized, e.g.:
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

## Claude-Specific Tool Rules
- **Edit tool requires fresh reads:** Before editing any file (especially DEVPLAN.md), read it immediately before the edit — not at the start of the iteration.
- **No subagent spawning for simple tasks:** Do NOT spawn Agent(Explore) subagents for simple file discovery — use `bash find` or `bash ls` instead.
- **Windows host + Git Bash:** the loop runs on Windows under Git Bash / MSYS. Prefer forward-slash paths for bash-invoked commands; reserve backslash paths for PowerShell-only usage.

## Claude-Specific Runner Info
**Runner:** `run-iteration.sh` — runs `claude -p` per iteration, logs to `logs/loop/`.

**Slash commands:** Project commands in `.claude/commands/` — these are NOT
Skill-tool skills. To use them, read the `.md` file and follow its instructions.
Do NOT call them via the Skill tool.

| Action (from WORKER_SPEC) | Claude command file |
|---------------------------|---------------------|
| Phase Plan | `.claude/commands/phase-plan.md` |
| Step Execution | `.claude/commands/step-done.md` |
| Phase Review | `.claude/commands/phase-review.md` |
| Phase Complete | `.claude/commands/phase-complete.md` |

## Autonomy
This project supports autonomous execution. When invoked with
`autonomous: true` in the prompt, commands auto-proceed and the agent follows
`WORKER_SPEC.md`. Otherwise, commands pause for human approval.

See WORKER_SPEC.md §8 for mode definitions (autonomous vs. supervised).
