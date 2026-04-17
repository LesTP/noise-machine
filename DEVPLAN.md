---
module: core-playback
phase: 1
phase_title: Core Playback
step: 5 of 5
mode: Review
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
  - **Gradle daemon idle-hangs the invoking shell.** After `BUILD SUCCESSFUL` on Windows, the foreground `gradlew.bat` process often sits for minutes waiting for daemon idle-shutdown. Trust the Gradle-reported build time, not the shell's wall-clock.
  - **`libandroidx.graphics.path.so` strip warning** is benign (missing NDK `strip` on Windows PATH); ignore it.
- **Use the wrapper, not system Gradle.** System `gradle` is 9.4.0, incompatible with AGP 8.7. Always call `.\gradlew.bat` or `./gradlew`.
  - **`local.properties` is per-machine and gitignored.** If a fresh clone fails to find the SDK, write `sdk.dir=...` there.
  - **Config cache can mask stale state.** If a build behaves weirdly after a big change, try `--no-configuration-cache` once.
  - **`testDebugUnitTest` cold cost.** With config cache warm but no daemon, a clean unit-test run takes ~75 s (daemon startup + Kotlin compile). Subsequent runs reuse the daemon and are seconds.
  - **AudioFormat constant naming.** It's `AudioFormat.ENCODING_PCM_16BIT` (no underscore between `16` and `BIT`); `ENCODING_PCM_FLOAT` *does* have the underscore. Easy to get wrong by analogy.
  - **`val state by flow.collectAsStateWithLifecycle()` needs `import androidx.compose.runtime.getValue`.** Without the explicit `getValue` import, Kotlin can't resolve the `by`-delegate operator on `androidx.compose.runtime.State<T>` and the build fails with `has no method 'getValue(…)'`.
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 1: Core Playback
- **Focus** — All 5 steps complete; awaiting Phase Review
- **Blocked/Broken** — None

## Phase 1: Core Playback

**Goal:** Prove the real-time PCM output path — white noise through AudioTrack with Play/Stop UI — before any DSP complexity.

**Regime:** Build — all outcomes verifiable by tests and objective criteria.

### Steps

1. [x] **Android project scaffold** — Gradle project (Kotlin, Compose, min API 26), AndroidManifest, empty MainActivity with Compose, verify clean build. *(done 2026-04-16; T7 passed)*
2. [x] **NoiseSource** — Allocation-free white-noise sample generator. Unit tests: mean ≈ 0, values in [-1,1], no repeated patterns, correct buffer fill. *(done 2026-04-17; T1, T2, T3 passed)*
3. [x] **AudioEngine** — Owns AudioTrack instance (16-bit PCM, 44100 Hz, stereo), dedicated render thread, start/stop API. NoiseSource wired in. Manual verification: audible white noise on device/emulator. *(done 2026-04-17; T4 passed via FakeSink, T5 passed via 20× rapid toggle. Audible-on-device verification deferred to Phase 1 close-out / Step 5.)*
4. [x] **Compose UI + ViewModel** — Main screen with Play/Stop button. PlaybackViewModel exposes PlaybackState (Idle/Playing). Button triggers AudioEngine start/stop through ViewModel. *(done 2026-04-17; T6 + T6a–h passed; assembleDebug passes.)*
5. [x] **End-to-end wiring and test** — Full path works: tap Play → ViewModel → AudioEngine → NoiseSource → AudioTrack → audible output. Tap Stop → silence. Manual M1–M9 verification passed on Pixel 6 emulator (Android 16.0). *(done 2026-04-17; added LifecycleEventEffect(ON_STOP) to fix M7/M9.)*

### Test Spec

| # | What | How | Expected |
|---|------|-----|----------|
| T1 | NoiseSource statistical properties | Fill 10k-sample buffer, compute mean and variance | mean ∈ [-0.05, 0.05], variance ∈ [0.3, 0.36] (uniform dist) |
| T2 | NoiseSource allocation-free | Fill buffer in loop, check no object creation | No allocations in hot path |
| T3 | NoiseSource value range | Fill buffer, check all samples | All values in [-1.0, 1.0] |
| T4 | AudioEngine start/stop lifecycle | Call start(), verify isPlaying, call stop(), verify !isPlaying | State transitions correct |
| T5 | AudioEngine does not crash on rapid toggle | Start/stop 20 times in quick succession | No exceptions, no ANR |
| T6 | ViewModel state transitions | Construct VM with `FakeController`. Call `onPlayClicked()`, then `onStopClicked()`. Observe `state`. | `Idle → Playing → Idle` |
| T6a | Initial state is `Idle` without touching the controller | Construct VM, read `state.value`, inspect `FakeController` call counts | `state == Idle`; `startCalls == 0`; `stopCalls == 0` |
| T6b | `onPlayClicked()` from `Idle` calls `controller.start()` exactly once | From `Idle`, call `onPlayClicked()` | `startCalls == 1`; `state == Playing` |
| T6c | `onStopClicked()` from `Playing` calls `controller.stop()` exactly once | From `Playing`, call `onStopClicked()` | `stopCalls == 1`; `state == Idle` |
| T6d | `onPlayClicked()` while already `Playing` is a no-op | From `Playing`, call `onPlayClicked()` again | `startCalls` stays at 1; `state == Playing` |
| T6e | `onStopClicked()` while already `Idle` is a no-op | From `Idle`, call `onStopClicked()` | `stopCalls == 0`; `state == Idle` |
| T6f | `onCleared()` stops the controller (lifecycle leak prevention) | From `Playing`, invoke the VM's clear-equivalent | `stopCalls == 1`; `state == Idle` |
| T6g | Rapid VM-layer toggle keeps state and controller in sync (mirror of T5) | `repeat(20) { onPlayClicked(); onStopClicked() }` | `state == Idle`; `startCalls == 20`; `stopCalls == 20` |
| T6h | Controller failure does not desynchronize state | `FakeController.start()` throws once; call `onPlayClicked()` | VM does not crash; `state` returns to `Idle`; failure is observable (mechanism TBD in D-12…D-15) |
| T7 | Build succeeds | `./gradlew assembleDebug` | Exit code 0 |

### Decisions to resolve during this phase
- **D-6:** Min API level — *confirmed API 26, compileSdk/targetSdk 34 in Step 1; see DECISIONS.md.*
- **D-7:** Sample rate — *closed in Step 3 at 44100 Hz; AudioTrackSink hard-codes via constructor default; see DECISIONS.md.*
- **D-8:** AudioTrack buffer size — *closed in Step 3 at `max(minBufferBytes × 2, framesPerWrite × bytesPerFrame)`; render quantum 1024 frames; see DECISIONS.md.*
- **D-9:** Toolchain pins (AGP 8.7.3 / Gradle 8.10.2 / Kotlin 2.0.21 / Compose BOM 2024.10.01) — *closed in Step 1; see DECISIONS.md.*
- **D-12:** State surface — *closed in Step 4 as `StateFlow<PlaybackState>`; collected in Compose via `collectAsStateWithLifecycle()`; see DECISIONS.md.*
- **D-13:** Controller seam — *closed in Step 4 as `interface PlaybackController { isPlaying; start(); stop() }`; `AudioEngine` implements it directly (no adapter); tests use `FakeController`; see DECISIONS.md.*
- **D-14:** Step 4 dependencies — *closed in Step 4: added `androidx.lifecycle:lifecycle-viewmodel-compose` and `androidx.lifecycle:lifecycle-runtime-compose` (both 2.8.7 via existing `lifecycleRuntimeKtx` ref).*
- **D-15:** ViewModel construction — *closed in Step 4: `PlaybackViewModel.Factory` constructs `AudioEngine(sinkFactory = { AudioTrackSink() })`; activity passes the factory to `viewModel(factory = …)`; see DECISIONS.md.*

### Step 4 implementation notes
- **Out of scope for Step 4:** Compose UI rendering tests (Play vs. Stop button visibility per state) require either Robolectric or `androidTest` and are deferred to Step 5's manual end-to-end check, consistent with D-11.
- **State shape:** Phase 1 ships only `sealed interface PlaybackState { Idle; Playing }`. The fuller `Starting(fadeIn) | FadingOut(…) | Stopped` shape from ARCHITECTURE.md is Phase 2/3 territory.
- **No persistence in Step 4** — last-used Color and timer selection live in Phase 3.
