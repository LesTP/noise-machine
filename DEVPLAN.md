---
module: core-playback
phase: 2
phase_title: Color Engine
step: 2 of 7
mode: Code
blocked: null
regime: Build
review_done: false
---

# Noise Machine — Development Plan

<!-- This file is the primary state document for autonomous iteration.
     Workers read it on every cold start to determine what to do next.
     Keep it concise — the DEVPLAN should get SHORTER as work progresses. -->

## Cold Start Summary

<!-- Stable section — update on major shifts, not every step. -->

- **What this is** — Minimal Android sleep-noise app with one Color slider backed by a real-time stereo noise engine (white-noise base + continuous spectral shaping + foreground-service playback).
- **Key constraints** —
  - Android (Kotlin, Jetpack Compose, ViewModel, Foreground Service, AudioTrack).
  - Real-time synthesis only — no prerecorded audio assets.
  - Audio render loop must be allocation-free; no GC pressure during playback.
  - Must survive screen-off, backgrounding, and many-hour sessions without artifacts.
  - No in-app volume slider; native media volume only.
  - Parameter changes must go through smoothing — no zipper noise, no clicks/pops.
- **Gotchas** —
  - **First build is slow.** Cold `.\gradlew.bat assembleDebug` takes ~4 min (wrapper bootstrap + dep download). Warm incremental builds should be well under 30 s with configuration cache.
  - **Gradle daemon idle-hangs the invoking shell.** After `BUILD SUCCESSFUL` on Windows, the foreground `gradlew.bat` process often sits for minutes waiting for daemon idle-shutdown. Trust the Gradle-reported build time, not the shell's wall-clock. Use `--no-daemon` for scripted builds or `.\gradlew.bat --stop` to kill daemons manually.
  - **`libandroidx.graphics.path.so` strip warning** is benign (missing NDK `strip` on Windows PATH); ignore it.
  - **Use the wrapper, not system Gradle.** System `gradle` is 9.4.0, incompatible with AGP 8.7. Always call `.\gradlew.bat` or `./gradlew`.
  - **`local.properties` is per-machine and gitignored.** If a fresh clone fails to find the SDK, write `sdk.dir=...` there.
  - **Config cache can mask stale state.** If a build behaves weirdly after a big change, try `--no-configuration-cache` once.
  - **`testDebugUnitTest` cold cost.** With config cache warm but no daemon, a clean unit-test run takes ~75 s (daemon startup + Kotlin compile). Subsequent runs reuse the daemon and are seconds.
  - **AudioFormat constant naming.** It's `AudioFormat.ENCODING_PCM_16BIT` (no underscore between `16` and `BIT`); `ENCODING_PCM_FLOAT` *does* have the underscore. Easy to get wrong by analogy.
  - **`val state by flow.collectAsStateWithLifecycle()` needs `import androidx.compose.runtime.getValue`.** Without the explicit `getValue` import, Kotlin can't resolve the `by`-delegate operator on `androidx.compose.runtime.State<T>` and the build fails with `has no method 'getValue(…)'`.
  - **`ViewModel.onCleared()` is not enough to stop audio without a foreground service.** The Activity can linger after `onStop()` without triggering `onCleared()`. Use `LifecycleEventEffect(ON_STOP)` to stop playback explicitly. Phase 4's foreground service replaces this.
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 2: Color Engine
- **Focus** — Step 2: Biquad (generic second-order IIR filter primitive)
- **Blocked/Broken** — None

## Phase 1: Core Playback — Complete

5 steps, 15 unit tests (T1–T7, T6a–h), M1–M9 manual verification passed. 10 decisions closed (D-6–D-15). See DEVLOG.md §Phase 1.

## Phase 2: Color Engine

**Goal:** Add continuous spectral shaping driven by a Color slider [0.0 bright/airy → 1.0 deep/soft], with smooth parameter transitions and output safety.

**Regime:** Build (Steps 1–6) → Refine (Step 7: perceptual tuning).

### Steps

1. [x] **ParameterSmoother** — Lock-free exponential ramp for real-time parameter smoothing. *(done 2026-04-17; T8/T8b/T8c/T9/T10/T10b passed; D-18 closed)*
2. [ ] **Biquad** — Generic second-order IIR filter (Direct Form II Transposed). Coefficient calculation for low-shelf and high-shelf. Allocation-free `process(buffer, length)`.
3. [ ] **SpectralShaper** — Cascaded biquad chain driven by Color [0,1]. Maps Color to coordinated filter parameters (spectral tilt, HF attenuation, LF shaping). Processes mono buffer in-place. Initial coefficient curve; final tuning in Step 7.
4. [ ] **GainSafety** — DC blocker (1st-order HPF ~20 Hz) + output normalization across Color range + hard-clip at ±1.0.
5. [ ] **AudioEngine integration** — Wire ParameterSmoother + SpectralShaper + GainSafety into render loop. Add `setColor(Float)` to PlaybackController. Extend engine to accept color changes during playback.
6. [ ] **Color slider UI** — Compose Slider on main screen (center zone). Wire Slider → ViewModel → PlaybackController.setColor(). Interactive in both Idle and Playing states.
7. [ ] **Perceptual tuning** — *(Refine)* Tune coefficient curves, loudness compensation, low-end containment, ramp times on-device.

### Test Spec

| # | What | How | Expected |
|---|------|-----|----------|
| T8 | ParameterSmoother reaches target | Set target, call `next()` in loop | Value converges within tolerance after N samples |
| T9 | ParameterSmoother is allocation-free | Heap-delta test (same pattern as T2) | No allocations in hot path |
| T10 | ParameterSmoother instant-set for initial value | Construct with initial value, read immediately | Returns initial value without ramping |
| T11 | Biquad low-shelf boosts low frequencies | Process white noise, compare low-band vs high-band energy | Low-band energy > high-band energy |
| T12 | Biquad high-shelf cuts high frequencies | Process white noise, compare bands | High-band energy reduced relative to flat |
| T13 | Biquad is allocation-free | Heap-delta test | No allocations in hot path |
| T14 | SpectralShaper Color=0.0 ≈ flat (white) | Process buffer, compare spectral balance | Approximately equal energy in low and high bands |
| T15 | SpectralShaper Color=1.0 = tilted (brown-ish) | Process buffer, compare spectral balance | Low-band energy significantly > high-band energy |
| T16 | SpectralShaper is allocation-free | Heap-delta test | No allocations in hot path |
| T17 | GainSafety output always in [-1, 1] | Feed extreme inputs (DC, large amplitude) | All output samples in [-1.0, 1.0] |
| T18 | GainSafety removes DC offset | Feed constant-offset signal | Output mean ≈ 0.0 |
| T19 | Full pipeline bounded for all Color values | Run engine with Color at 0.0, 0.5, 1.0 via FakeSink | All samples in [-1, 1], no NaN/Inf |
| T20 | Build succeeds with Color slider | `./gradlew assembleDebug` | Exit code 0 |

### Decisions to resolve during this phase

- **D-16:** IIR cascade topology — *open; default: 2 biquads (low-shelf + high-shelf), Color-driven gains. Resolve in Step 2–3.*
- **D-17:** Low-end containment strategy — *open; default: DC blocker (1st-order HPF ~20 Hz) in GainSafety. Resolve in Step 4.*
- **D-18:** ParameterSmoother algorithm — *closed in Step 1: exponential ramp, `@Volatile` target, 50 ms default time constant; see DECISIONS.md.*
- **D-19:** Gain compensation approach — *open; default: static Color-indexed compensation curve (lookup + lerp). Resolve in Step 4.*
- **D-20:** Color → coefficient mapping — *open; default: coordinated shelf gains + cutoff shift; initial curve refined in Step 7. Resolve in Step 3 + 7.*

### Refine step (Step 7) structure

**Goals:**
- Color slider produces a natural-sounding continuum from bright/airy to deep/soft
- No sudden jumps, clicks, or artifacts when sliding during playback
- Perceived loudness stays approximately consistent across the full range
- Dark extreme (Color=1.0) is soft and deep, not muddy, boomy, or drifting

**Constraints:**
- All DSP must remain allocation-free
- Must be verified on real device audio (emulator may not be representative)
- Ramp times must feel smooth but responsive (not sluggish)

**First item to show:** Play with Color at 0.0, 0.5, and 1.0 — do they sound distinct and pleasant?

**Time budget:** 2–3 sessions
