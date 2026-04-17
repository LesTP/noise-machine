---
module: core-playback
phase: 1
phase_title: Core Playback
step: 3 of 5
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
  - **Gradle daemon idle-hangs the invoking shell.** After `BUILD SUCCESSFUL` on Windows, the foreground `gradlew.bat` process often sits for minutes waiting for daemon idle-shutdown. Trust the Gradle-reported build time, not the shell's wall-clock.
  - **`libandroidx.graphics.path.so` strip warning** is benign (missing NDK `strip` on Windows PATH); ignore it.
- **Use the wrapper, not system Gradle.** System `gradle` is 9.4.0, incompatible with AGP 8.7. Always call `.\gradlew.bat` or `./gradlew`.
  - **`local.properties` is per-machine and gitignored.** If a fresh clone fails to find the SDK, write `sdk.dir=...` there.
  - **Config cache can mask stale state.** If a build behaves weirdly after a big change, try `--no-configuration-cache` once.
  - **`testDebugUnitTest` cold cost.** With config cache warm but no daemon, a clean unit-test run takes ~75 s (daemon startup + Kotlin compile). Subsequent runs reuse the daemon and are seconds.
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 1: Core Playback
- **Focus** — Step 3: AudioEngine (AudioTrack 16-bit PCM 44100 Hz stereo, render thread, start/stop)
- **Blocked/Broken** — None

## Phase 1: Core Playback

**Goal:** Prove the real-time PCM output path — white noise through AudioTrack with Play/Stop UI — before any DSP complexity.

**Regime:** Build — all outcomes verifiable by tests and objective criteria.

### Steps

1. [x] **Android project scaffold** — Gradle project (Kotlin, Compose, min API 26), AndroidManifest, empty MainActivity with Compose, verify clean build. *(done 2026-04-16; T7 passed)*
2. [x] **NoiseSource** — Allocation-free white-noise sample generator. Unit tests: mean ≈ 0, values in [-1,1], no repeated patterns, correct buffer fill. *(done 2026-04-17; T1, T2, T3 passed)*
3. [ ] **AudioEngine** — Owns AudioTrack instance (16-bit PCM, 44100 Hz, stereo), dedicated render thread, start/stop API. NoiseSource wired in. Manual verification: audible white noise on device/emulator.
4. [ ] **Compose UI + ViewModel** — Main screen with Play/Stop button. PlaybackViewModel exposes PlaybackState (Idle/Playing). Button triggers AudioEngine start/stop through ViewModel.
5. [ ] **End-to-end wiring and test** — Full path works: tap Play → ViewModel → AudioEngine → NoiseSource → AudioTrack → audible output. Tap Stop → silence. Unit tests for ViewModel state transitions. Verify no crashes on rapid start/stop.

### Test Spec

| # | What | How | Expected |
|---|------|-----|----------|
| T1 | NoiseSource statistical properties | Fill 10k-sample buffer, compute mean and variance | mean ∈ [-0.05, 0.05], variance ∈ [0.3, 0.36] (uniform dist) |
| T2 | NoiseSource allocation-free | Fill buffer in loop, check no object creation | No allocations in hot path |
| T3 | NoiseSource value range | Fill buffer, check all samples | All values in [-1.0, 1.0] |
| T4 | AudioEngine start/stop lifecycle | Call start(), verify isPlaying, call stop(), verify !isPlaying | State transitions correct |
| T5 | AudioEngine does not crash on rapid toggle | Start/stop 20 times in quick succession | No exceptions, no ANR |
| T6 | ViewModel state transitions | Observe state flow through play/stop cycle | Idle → Playing → Idle |
| T7 | Build succeeds | `./gradlew assembleDebug` | Exit code 0 |

### Decisions to resolve during this phase
- **D-6:** Min API level — *confirmed API 26, compileSdk/targetSdk 34 in Step 1; see DECISIONS.md.*
- **D-7:** Sample rate — provisionally 44100 Hz. AudioTrack native rate may differ on some devices; resolve during Step 3.
- **D-8:** AudioTrack buffer size — use `AudioTrack.getMinBufferSize() * 2` as starting point; tune during Step 3.
- **D-9:** Toolchain pins (AGP 8.7.3 / Gradle 8.10.2 / Kotlin 2.0.21 / Compose BOM 2024.10.01) — *closed in Step 1; see DECISIONS.md.*
