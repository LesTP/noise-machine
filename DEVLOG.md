# Noise Machine — Development Log

<!-- Chronological record of what happened during development.
     Each step gets a structured entry. This is the audit trail.

     Archival rule: When this file exceeds ~500 lines, move completed
     module entries to DEVLOG_archive.md during phase completion cleanup.
     Add a boundary marker: <!-- Entries above archived from Module N, YYYY-MM-DD --> -->

## Module 1: core-playback

### Phase 1: Core Playback

### Phase Plan: Core Playback
- **Mode:** Discuss
- **Outcome:** complete
- **Contract changes:** none

Broke Phase 1 into 5 steps covering the Build regime:
1. Android project scaffold (Gradle, Compose, min API 26)
2. NoiseSource (allocation-free white-noise generator + unit tests)
3. AudioEngine (AudioTrack 16-bit PCM 44100Hz stereo, render thread, start/stop)
4. Compose UI + ViewModel (Play/Stop button, PlaybackState)
5. End-to-end wiring and integration tests

Test spec defined: 7 tests covering NoiseSource statistics, allocation-freedom, value range, AudioEngine lifecycle, rapid toggle safety, ViewModel state transitions, and build verification.

Provisional decisions queued: min API 26 (D-6), sample rate 44100 Hz (D-7), buffer size 2× minimum (D-8) — to be confirmed during execution.

### Step 1: Android project scaffold
- **Mode:** Code
- **Outcome:** complete — T7 passed (`assembleDebug` BUILD SUCCESSFUL in 3m 58s)
- **Contract changes:** none

Wrote the Gradle scaffold (settings, root + app build scripts, `gradle.properties`, `gradle/libs.versions.toml` version catalog), generated the Gradle wrapper pinned to 8.10.2, and authored a minimal Compose-based `MainActivity` that renders "Noise Machine" centered on a Material 3 `Scaffold`. `AndroidManifest.xml`, `strings.xml`, `themes.xml`, and empty `backup_rules.xml` / `data_extraction_rules.xml` round out the app module. `local.properties` (gitignored) points at the local Android SDK; `.gitignore` expanded to cover `.gradle/`, `build/`, `.idea/`, `*.iml`, `local.properties`, and related Android/Gradle ephemera.

Two decisions closed during this step — see DECISIONS.md:
- **D-6** — min API 26; compileSdk + targetSdk 34; Java 17.
- **D-9** — toolchain pins: AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21, Compose BOM 2024.10.01, AndroidX Core-KTX 1.13.1, Activity-Compose 1.9.3, Lifecycle-Runtime-KTX 2.8.7, Material 3 (from BOM), JUnit 4.13.2.

Minor notes / gotchas:
- First `assembleDebug` took 3m 58s (cold wrapper bootstrap + dependency download + first compilation); subsequent configuration-cache-enabled builds should be much faster.
- Benign warning during packaging: `Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so` — expected on Windows without NDK `strip` on PATH; does not affect the debug APK.
- `org.gradle.daemon` idle shutdown on Windows can appear to "hang" the foreground shell after BUILD SUCCESSFUL; actual build time is the number reported by Gradle, not the wall-clock of the invoking shell.

### Step 2: NoiseSource
- **Mode:** Code
- **Outcome:** complete — T1, T2, T3 passed (`testDebugUnitTest` BUILD SUCCESSFUL in 1m 17s; 3 tests, 0 failures, 0 errors)
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/NoiseSource.kt` as an allocation-free uniform-`[-1.0f, 1.0f)` white-noise generator backed by `java.util.SplittableRandom`. The hot path is a single `while (i < length)` loop that writes `(rng.nextDouble() * 2.0 - 1.0).toFloat()` into the caller-supplied `FloatArray` — no per-sample object creation, no boxing, no synchronized access (SplittableRandom is single-threaded by design, which matches the per-render-thread ownership model called out in CLAUDE.md).

Unit tests in `app/src/test/java/com/noisemachine/app/audio/NoiseSourceTest.kt` cover the three Phase-1 NoiseSource criteria from the DEVPLAN test spec:
- **T1** — 10k-sample buffer with seed `0xC0FFEE`: empirical `mean ∈ [-0.05, 0.05]`, `variance ∈ [0.30, 0.36]` (theoretical variance for uniform `[-1, 1]` is `1/3 ≈ 0.3333`).
- **T2** — allocation-free hot loop: warm up 2,000 fills, GC, snapshot heap, run 10,000 × 1,024-sample fills (~10M samples), GC, assert heap delta `< 256 KB`. A per-sample allocation would balloon the delta into the MB range.
- **T3** — every sample of a 50,000-sample buffer is in `[-1.0, 1.0]` (asserted via raw indexed loop to avoid iterator boxing in the assertion).

One decision closed during this step:
- **D-10** — Noise RNG: use `java.util.SplittableRandom` (allocation-free `nextDouble()`, no synchronized seed update, available since API 24 — well below our minSdk 26). Rejected `java.util.Random` (synchronized CAS on every call) and a hand-rolled `XorShift64` (not needed once SplittableRandom met the allocation-freedom bar). See DECISIONS.md.

Operational note added to DEVPLAN Cold Start gotchas: cold `:app:testDebugUnitTest` is ~75 s (daemon spin-up + Kotlin compile); warm runs are seconds.

### Step 3: AudioEngine
- **Mode:** Code
- **Outcome:** complete — T4 (lifecycle) and T5 (rapid toggle) passed. `:app:testDebugUnitTest` BUILD SUCCESSFUL in 13 s (warm daemon); 6 tests across 2 classes, 0 failures, 0 errors.
- **Contract changes:** none

Implemented three new files in `app/src/main/java/com/noisemachine/app/audio/`:

- **`AudioSink`** — narrow interface (`open(sampleRateHz, channels) → framesPerWrite`, `write(buffer, frames)`, `close()`). Single-use lifecycle; one instance per playback session. Defined explicitly so `AudioEngine` doesn't depend on Android framework types and can be tested entirely on the JVM.
- **`AudioTrackSink`** — production impl wrapping `android.media.AudioTrack` in `MODE_STREAM`. Closes D-7 (44100 Hz fixed at the `AudioEngine` constructor default) and D-8 (`bufferSizeInBytes = max(minBufferBytes × 2, framesPerWrite × bytesPerFrame)`, render quantum 1024 frames). Uses `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` audio attributes; `WRITE_BLOCKING` writes are looped to handle short returns; `close()` is `IllegalStateException`-tolerant so it's safe to call from any lifecycle path.
- **`AudioEngine`** — owns the dedicated render thread (`Thread.MAX_PRIORITY`, name `"noise-render"`). `start()` and `stop()` are guarded by a single `ReentrantLock`, making them idempotent and safe to interleave from any thread (this is what makes T5 a one-liner: `repeat(20) { engine.start(); engine.stop() }`). `isPlaying` is a `@Volatile Boolean` getter. The render loop pre-allocates `monoBuf: FloatArray(framesPerWrite)` and `stereoBuf: ShortArray(framesPerWrite × 2)` once per session and runs `noise.fill → floatMonoToInt16Stereo → sink.write` with no per-iteration allocations. Mono → stereo conversion is restrained-stereo (D-5): both channels carry the identical sample, clip-safe at ±1.0.

Tests in `app/src/test/java/com/noisemachine/app/audio/AudioEngineTest.kt` use a private `FakeSink` that counts `open/close` calls and frames written. T4 verifies the full lifecycle including that the render loop produces at least one buffer of output before `stop()`. T5 uses a fresh sink per `start()` (via the factory) so we can assert `opens == closes == 20` after the rapid-toggle storm — strongest end-state invariant for "no leaks". An additional T4b verifies idempotency of `start()` and `stop()`.

One bug en route — first compile failed with `Unresolved reference 'ENCODING_PCM_16_BIT'`. The constant is `AudioFormat.ENCODING_PCM_16BIT` (no underscore between `16` and `BIT`); `ENCODING_PCM_FLOAT` does have the underscore, which is the source of the confusion. Fixed and added a gotcha in DEVPLAN Cold Start Summary.

Manual on-device "audible white noise" verification deferred to Phase 1 wrap-up (Step 5, end-to-end wiring) when the Compose UI can drive the engine — no point launching the app twice.

Two decisions closed during this step:
- **D-7** — Sample rate: 44100 Hz, fixed at the engine constructor default. Hardware-native rate query deferred until/unless device testing reveals problems.
- **D-8** — AudioTrack buffer sizing: `max(minBufferBytes × 2, framesPerWrite × bytesPerFrame)`; render quantum is 1024 frames per write call. The render quantum and the AudioTrack buffer are intentionally independent — the buffer is a glitch-margin reservoir, the quantum is the engine's per-iteration work unit.

One new decision recorded:
- **D-11** — Test seam: introduce `AudioSink` interface so the engine can be unit-tested on the JVM without Robolectric or instrumented tests; production `AudioTrackSink` is the only Android-framework code in the audio package.

### Phase Plan: Step 4 test spec
- **Mode:** Discuss
- **Outcome:** complete
- **Contract changes:** none

Expanded the Phase-1 test grid: replaced the single `T6` row with `T6 + T6a..T6h` (9 sub-tests covering initial state, start/stop wiring, idempotency, lifecycle cleanup, rapid-toggle parity with T5, and controller-failure resilience). Queued four new decisions (`D-12..D-15`) covering state surface (StateFlow vs. Compose MutableState), controller seam, Step-4 dependencies, and ViewModel construction. Added a `Step 4 implementation notes` subsection documenting what's deliberately out of scope: Compose UI rendering tests deferred to Step 5's manual end-to-end check, the fuller `Starting | FadingOut | Stopped` PlaybackState shape kept for Phase 2/3, and persistence of last-used values left for Phase 3.

### Step 4: Compose UI + ViewModel
- **Mode:** Code
- **Outcome:** complete — T6 + T6a..T6h passed (9 PlaybackViewModel tests). Total Phase-1 test count: 15 (3 NoiseSource + 3 AudioEngine + 9 PlaybackViewModel), 0 failures, 0 errors. `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL in 29 s (warm).
- **Contract changes:** none

Closed all four queued decisions (D-12..D-15) as recommended in the phase plan:
- **D-12** — State surface: `StateFlow<PlaybackState>` exposed by the VM; Compose collects via `collectAsStateWithLifecycle()`. `MutableStateFlow` updates are synchronous on whatever thread fires the event handler — no coroutine machinery needed at this stage.
- **D-13** — Controller seam: introduced `interface PlaybackController { val isPlaying; fun start(); fun stop() }` next to `AudioSink` in the `audio` package. `AudioEngine` implements it directly — its existing signatures already matched, so no adapter was needed; tests inject a `FakeController`.
- **D-14** — Dependencies: added `androidx.lifecycle:lifecycle-viewmodel-compose` and `androidx.lifecycle:lifecycle-runtime-compose`, both pinned to 2.8.7 via the existing `lifecycleRuntimeKtx` version ref. No new test deps; coroutines come in transitively through Compose.
- **D-15** — VM construction: `PlaybackViewModel.Factory` builds a real `AudioEngine(sinkFactory = { AudioTrackSink() })` per VM instance. The activity calls `viewModel(factory = PlaybackViewModel.Factory())` from the composable, keeping the wiring inline (no DI framework yet).

Files added in `app/src/main/java/com/noisemachine/app/`:
- `audio/PlaybackController.kt` — narrow lifecycle interface (3 members).
- `playback/PlaybackState.kt` — sealed interface with `Idle` and `Playing` data objects only (Phase 1 scope).
- `playback/PlaybackViewModel.kt` — `MutableStateFlow<PlaybackState>` state holder; `onPlayClicked()` / `onStopClicked()` are idempotent and swallow controller failures (state reverts to `Idle` so the UI stays usable). `onCleared()` widened to `public override` and ensures the controller is stopped when the VM is destroyed mid-Playing.
- `MainActivity.kt` — replaced placeholder `Text("Noise Machine")` with a `PlaybackControls` composable that switches between `Play` and `Stop` buttons based on collected state. Two `@Preview`s cover both states.

Files modified:
- `app/src/main/java/com/noisemachine/app/audio/AudioEngine.kt` — declared `: PlaybackController`, added `override` modifiers on `isPlaying`, `start()`, `stop()`. No behavior change.
- `gradle/libs.versions.toml` — two new library entries.
- `app/build.gradle.kts` — wired the two new `implementation(...)` deps.

Tests in `app/src/test/java/com/noisemachine/app/playback/PlaybackViewModelTest.kt` use a single `FakeController` that records start/stop call counts and supports a one-shot `failNextStart` flag (T6h). Nine tests in total — T6/T6a..T6h plus a complementary "onCleared while Idle is a no-op" assertion under T6f.

One bug en route — the first build failed with:
```
Type 'androidx.compose.runtime.State<…>' has no method 'getValue(Nothing?, KProperty0<*>)', so it cannot serve as a delegate.
```
on the `val state by viewModel.state.collectAsStateWithLifecycle()` line. Root cause: `androidx.compose.runtime.getValue` is an extension operator, not part of the `State<T>` API surface, and is *not* re-exported by `collectAsStateWithLifecycle`. Adding `import androidx.compose.runtime.getValue` resolved it. Promoted to a DEVPLAN gotcha.

Manual on-device verification of audible noise still belongs to Step 5 (end-to-end wiring). The Compose UI compiles, `assembleDebug` produces a working APK, and the ViewModel's lifecycle is fully covered by JVM tests — but actually hearing the white noise on hardware is the real Phase-1 milestone and the right place for that is Step 5's smoke pass.

### Step 5: End-to-end wiring and on-device verification
- **Mode:** Code
- **Outcome:** complete — M1–M9 manual verification passed on Pixel 6 emulator (Android 16.0 x86_64). All 15 unit tests still pass. `assembleDebug` BUILD SUCCESSFUL.
- **Contract changes:** none

Ran the full manual acceptance suite (M1–M9) on the emulator:
- **M1** — App installs, launches, header and Play button visible. Pass.
- **M2** — Initial state silent. Pass.
- **M3** — Play emits audible broadband white noise; button toggles to Stop. Pass.
- **M4** — Hardware volume controls affect output on the media stream. Pass.
- **M5** — Stop returns to silence; button toggles to Play. Pass.
- **M6** — Rapid toggle ~20× — no crash, no ANR, state consistent at rest. Pass.
- **M7** — Backgrounding (Home press) stops audio. Pass (after fix).
- **M8** — Configuration change (rotation) does not crash. Pass.
- **M9** — Back press stops audio. Pass (after fix).

**Bug found and fixed:** M7 and M9 initially failed — audio continued playing after Home or Back press. Root cause: the ViewModel's `onCleared()` only fires when the Activity is destroyed (which can be delayed), and `onStop()` had no hook to stop playback. Fix: added `LifecycleEventEffect(Lifecycle.Event.ON_STOP)` in the `NoiseMachineApp` composable to call `viewModel.onStopClicked()` when the Activity stops. This is correct Phase 1 behavior — no foreground service yet; Phase 4 will replace this with foreground-service lifecycle that keeps playback alive in the background.

One benign system warning observed: `AppOps: attributionTag not declared in manifest of com.noisemachine.app` — this is an Android system-level message unrelated to our app, safe to ignore.

### Phase Review
- **Mode:** Review
- **Outcome:** complete
- **Contract changes:** none

Reviewed all 14 Phase 1 files (8 source, 3 test, 3 build/config) against ARCHITECTURE.md. No must-fix issues found. Three should-fix items applied:
1. `AudioSink.kt` — corrected `close()` KDoc to reflect actual render-thread calling convention (was incorrectly documented as lifecycle-control thread).
2. `MainActivity.kt` — removed redundant `Surface` wrapper around `Scaffold` (M3 Scaffold already provides `containerColor`).
3. `MainActivity.kt` — sorted imports into proper package grouping.

### Phase 1 Complete
- **Mode:** Review
- **Outcome:** complete
- **Contract changes:** none

Phase 1 delivered the core playback path: NoiseSource → AudioEngine → AudioTrack with Play/Stop Compose UI. 15 unit tests (T1–T7, T6a–h) pass. Manual acceptance M1–M9 verified on Pixel 6 emulator (Android 16.0). 10 decisions closed (D-6–D-15). No architecture drift. No contract changes requiring propagation.

### Phase 2: Color Engine

### Phase Plan: Color Engine
- **Mode:** Discuss
- **Outcome:** complete
- **Contract changes:** none

Broke Phase 2 into 7 steps: 6 Build (ParameterSmoother, Biquad, SpectralShaper, GainSafety, AudioEngine integration, Color slider UI) + 1 Refine (perceptual tuning). Test spec defined: T8–T20. Five decisions queued: D-16 (IIR topology), D-17 (low-end containment), D-18 (smoother algorithm), D-19 (gain compensation), D-20 (Color→coefficient mapping).

### Step 1: ParameterSmoother
- **Mode:** Code
- **Outcome:** complete — T8, T8b, T8c, T9, T10, T10b passed (6 tests). Total test count: 21 (15 Phase 1 + 6 ParameterSmoother), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/ParameterSmoother.kt` — a lock-free, allocation-free exponential parameter ramp for the audio render thread. UI thread sets target via `setTarget(value)` (`@Volatile` write); audio thread calls `next()` once per sample to get the smoothed value. One-pole exponential smoother: `current += (target - current) * alpha` where `alpha = 1 - exp(-1 / (sampleRate * timeSeconds))`. Supports instant mode (`timeSeconds = 0`) for initial value setup.

Tests in `app/src/test/java/com/noisemachine/app/audio/ParameterSmootherTest.kt` cover convergence (T8: reaches target within 1% after 5 time constants), monotonicity (T8b/c: no overshoot or undershoot on step up/down), allocation freedom (T9: 1M calls with heap-delta < 256 KB), initial value (T10: first `next()` returns constructor value), and instant mode (T10b: `timeSeconds=0` snaps immediately).

One decision closed:
- **D-18** — ParameterSmoother algorithm: exponential ramp with `@Volatile` target, default 50 ms time constant at 44100 Hz. No locks needed — single volatile read per `next()` call.

### Step 2: Biquad
- **Mode:** Code
- **Outcome:** complete — T11, T12, T12b, T13, T13b passed (5 tests). Total test count: 26 (15 Phase 1 + 6 ParameterSmoother + 5 Biquad), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/Biquad.kt` — a generic second-order IIR filter using Direct Form II Transposed topology. Coefficients stored normalized (a0 = 1). Companion factory methods for `lowShelf`, `highShelf`, and `passthrough`. `setCoefficients()` allows in-place coefficient updates without allocation (needed by SpectralShaper for smooth Color transitions). `reset()` clears filter state for new playback sessions.

Coefficient formulas follow the Audio EQ Cookbook (Robert Bristow-Johnson). Parameters: `sampleRate`, `freqHz` (shelf transition), `gainDb` (boost/cut), `q` (slope quality, default 0.707 Butterworth).

Tests in `app/src/test/java/com/noisemachine/app/audio/BiquadTest.kt` use a band-energy comparison approach — feeding seeded white noise through the filter and comparing low-band vs high-band RMS energy via moving-average decomposition (avoids FFT dependency). T11 verifies low-shelf boost increases the low/high energy ratio by ≥50%. T12 verifies high-shelf cut has the same effect. T12b confirms passthrough is bit-exact identity. T13 is the standard heap-delta allocation test. T13b runs 5 seconds of filtered noise and asserts all samples are finite and bounded within ±10.0.

No decisions closed in this step; D-16 (IIR topology) remains open until SpectralShaper chooses how many biquads to cascade and how to map Color to their parameters.

### Step 3: SpectralShaper
- **Mode:** Code
- **Outcome:** complete — T14, T15, T15b, T16, T16b passed (5 tests). Total test count: 31 (15 Phase 1 + 6 ParameterSmoother + 5 Biquad + 5 SpectralShaper), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/SpectralShaper.kt` — the core DSP stage that maps Color [0.0, 1.0] to spectral tilt using two cascaded biquad shelving filters. Topology: low-shelf at 250 Hz (0 → +10 dB) + high-shelf at 2500 Hz (0 → -14 dB), both scaling linearly with Color. At Color ≈ 0 the shaper is passthrough (flat/white); at Color = 1.0 the coordinated shelf gains produce a brown-like tilt. Coefficients are recalculated only when Color changes (once per buffer at most), keeping the per-sample inner loop allocation-free.

Modified `Biquad.kt` — added `configureLowShelf()` and `configureHighShelf()` instance methods that recalculate coefficients in-place without allocating a new Biquad. Refactored companion factories (`lowShelf`, `highShelf`) to delegate to these methods, eliminating code duplication. This was necessary so SpectralShaper can update filter parameters during playback without allocation.

Tests use the same band-energy comparison approach as BiquadTest. T14 verifies Color=0 preserves the flat spectrum (within 20% of unfiltered ratio). T15 verifies Color=1 tilts the low/high ratio by at least 2×. T15b confirms Color=0.5 produces an intermediate tilt between the two extremes — verifying monotonicity of the mapping.

Two decisions closed:
- **D-16** — IIR cascade topology: 2 biquad sections (low-shelf + high-shelf) with Color-driven gains. Simpler than a multi-stage cascade and sufficient for a convincing white-to-brown continuum. More sections can be added if perceptual tuning (Step 7) reveals gaps.
- **D-20** — Color → coefficient mapping: linear-in-dB mapping. Low shelf: `gainDb = color * 10`. High shelf: `gainDb = color * -14`. Asymmetric (more high-cut than low-boost) to prevent boominess at the dark end. Initial curve subject to perceptual refinement in Step 7.

### Step 4: GainSafety
- **Mode:** Code
- **Outcome:** complete — T17, T17b, T18, T18b, T18c, T18d passed (6 tests). Total test count: 37 (15 Phase 1 + 6 ParameterSmoother + 5 Biquad + 5 SpectralShaper + 6 GainSafety), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/GainSafety.kt` — output safety stage that sits after SpectralShaper in the render pipeline. Three stages applied in order:

1. **DC blocker** — first-order high-pass at ~20 Hz via leaky integrator subtraction (`y[n] = x[n] - dc_avg[n]`). Removes any DC offset introduced by the shelving filters at the dark end of the Color range. Uses a bilinear-approximated alpha coefficient.
2. **Gain compensation** — piecewise-linear 3-point curve indexed by Color: 0.85 at Color=0 (white noise sounds louder due to full HF energy), 0.95 at Color=0.5 (pink-ish, near unity), 0.60 at Color=1.0 (compensates for low-shelf energy boost). Keeps perceived loudness approximately constant across the Color range.
3. **Hard clip** — clamps every sample to [-1.0, 1.0] as a final safety net before Int16 conversion.

Tests verify clamping of extreme inputs (T17), bounded output through the full SpectralShaper→GainSafety pipeline across all Color values (T17b), DC offset removal on a constant signal (T18), gain reduction at Color=0 (T18b), gain curve continuity at the segment boundary (T18c), and allocation freedom (T18d).

Two decisions closed:
- **D-17** — Low-end containment: DC blocker (first-order HPF ~20 Hz) in GainSafety via leaky integrator subtraction. Chosen over explicit DC-blocker biquad for simplicity — only one state variable needed, and the cutoff is low enough that the approximation is adequate.
- **D-19** — Gain compensation: static piecewise-linear curve indexed by Color, 3 anchor points. Chosen over dynamic RMS measurement for predictability and zero latency. Values are initial estimates; perceptual tuning in Step 7 may adjust the anchors.

### Step 5: AudioEngine integration
- **Mode:** Code
- **Outcome:** complete — T19 passed (1 new test; runs 3 sub-cases at Color 0.0/0.5/1.0). Total test count: 38, 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** `PlaybackController` interface — added `setColor(color: Float)`. Affects: `AudioEngine` (implements), `PlaybackViewModel` (calls), `PlaybackViewModelTest.FakeController` (implements).

Wired the full DSP pipeline into `AudioEngine.renderLoop()`. The per-buffer flow is now: `NoiseSource.fill(buf) → SpectralShaper.process(buf, color) → GainSafety.process(buf, color) → floatMonoToInt16Stereo → AudioSink.write`. A `ParameterSmoother` owned by the engine provides lock-free Color smoothing — the UI thread writes a target via `setColor()` (`@Volatile` write), and the render thread reads the smoothed value once per buffer via `smoother.next()`.

`PlaybackController` interface gained `setColor(Float)` so the ViewModel can forward slider values to the engine without knowing the concrete type. `PlaybackViewModel` exposes `onColorChanged(Float)` and a `color: StateFlow<Float>` for the UI to collect.

SpectralShaper and GainSafety are created fresh per session (inside `start()`) alongside the sink and noise source, matching the existing per-session lifecycle. The smoother is created once at engine construction and persists across sessions, so the Color target survives stop/start cycles.

No new decisions closed. Two compilation errors hit during development: an invalid hex literal (`0xC0L0RL`) and a `@Volatile` annotation on a local variable — both fixed before final test run.

### Step 6: Color slider UI
- **Mode:** Code
- **Outcome:** complete — T20 passed (`assembleDebug` BUILD SUCCESSFUL). All 38 unit tests pass, 0 failures.
- **Contract changes:** none

Added a Compose `Slider` to `MainActivity.kt`, wired through `PlaybackViewModel.onColorChanged()` to `PlaybackController.setColor()`. The slider sits below the Play/Stop button in a full-width column with 32 dp horizontal padding. A "Color" label sits above the slider; "Bright" and "Deep" hints appear at the extremes (Color < 0.2 and > 0.8 respectively). The slider is interactive regardless of playback state — the user can set Color before pressing Play and the engine will use it when playback starts.

`NoiseMachineApp` composable now collects `viewModel.color` via `collectAsStateWithLifecycle()` and passes it to `PlaybackControls`. Both `@Preview` composables updated with the new `color` and `onColorChanged` parameters.

No decisions closed in this step.
