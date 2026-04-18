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

### Step 7: Perceptual tuning
- **Mode:** Code + Refine
- **Outcome:** complete — Bug fix (smoother rate mismatch) + on-device verification. M10–M20 manual tests passed on Pixel 6 emulator (Android 16.0). All 38 unit tests pass, 0 failures.
- **Contract changes:** none

On-device testing revealed the Color slider had no audible effect. Root cause: `ParameterSmoother.next()` advances by 1 sample, but AudioEngine called it once per buffer (1024 frames). The effective time constant was ~1000× slower than intended (~51 seconds instead of 50 ms), so the smoothed Color value barely moved from its initial 0.0.

Fix: added `nextBlock(samples: Int)` to ParameterSmoother — advances the ramp by N samples in O(1) using the closed-form `retain = exp(n * ln(1 - alpha))`, then `current += (target - current) * (1 - retain)`. AudioEngine now calls `colorSmoother.nextBlock(framesPerWrite)` instead of `next()`.

After the fix, on-device testing confirmed: Color=0 produces bright white noise, Color=1 produces deep brown-ish noise, and the slider transitions smoothly between them. The initial coefficient curves (low-shelf +10 dB at 250 Hz, high-shelf -14 dB at 2500 Hz) and gain compensation (0.85/0.95/0.60 at Color 0/0.5/1.0) produce an acceptable Color continuum without further tuning. M10–M20 all pass.

No decisions closed in this step. The initial curves from D-16/D-20 proved adequate; further refinement can happen in a future iteration if needed.

---

## Phase Review — core-playback.2 (Color Engine)

### Review Findings
- **Must fix (1):** `PlaybackViewModel.onCleared()` missing `super.onCleared()` — accidentally removed in Step 5. Restored.
- **Should fix (2):** Unused import `kotlin.math.exp` in GainSafety — removed. ARCHITECTURE.md provisional contracts listed two resolved items as open (coefficient schedule D-16, low-end containment D-17) — updated.
- **Optional (3):** PlaybackController KDoc lacks setColor() docs, ARCHITECTURE.md flow diagram uses old API names, SpectralShaperTest duplicates BiquadTest helpers — deferred.

All fixes applied; 38 tests pass, 0 failures.

---

## Phase 2 Complete — core-playback.2 (Color Engine)

Phase 2 delivered the full Color engine: continuous spectral shaping from white (Color=0) to brown-like (Color=1) via a two-biquad shelving cascade, with allocation-free parameter smoothing, DC blocking, gain compensation, and a Compose slider UI.

**7 steps completed:**
1. ParameterSmoother — lock-free exponential ramp (T8/T9/T10)
2. Biquad — DFII-T second-order IIR filter (T11/T12/T13)
3. SpectralShaper — Color-driven cascaded biquads (T14/T15/T16)
4. GainSafety — DC blocker + gain compensation + hard clip (T17/T18)
5. AudioEngine integration — DSP pipeline wired + setColor API (T19)
6. Color slider UI — Compose Slider wired through ViewModel (T20)
7. Perceptual tuning — smoother rate fix + on-device verification (M10–M20)

**5 decisions closed:** D-16 (IIR topology), D-17 (low-end containment), D-18 (smoother design), D-19 (gain compensation), D-20 (Color→coefficient mapping).

**Test count:** 38 unit tests (15 Phase 1 + 23 Phase 2), 0 failures. Manual tests M10–M20 passed on Pixel 6 emulator.

**Known issue:** One-time audio glitch/stop observed after ~10 min on emulator; not reproducible on second 15+ min run. Logged for hardware-device testing in Phase 4 (background robustness).

**Key artifacts:**
- `ParameterSmoother.kt` + `ParameterSmootherTest.kt`
- `Biquad.kt` + `BiquadTest.kt`
- `SpectralShaper.kt` + `SpectralShaperTest.kt`
- `GainSafety.kt` + `GainSafetyTest.kt`
- Modified: `AudioEngine.kt`, `PlaybackController.kt`, `PlaybackViewModel.kt`, `MainActivity.kt`
- Modified tests: `AudioEngineTest.kt`, `PlaybackViewModelTest.kt`

### Phase 3: Productization

### Phase Plan: Productization
- **Mode:** Discuss
- **Outcome:** complete
- **Contract changes:** none

Broke Phase 3 into 6 steps: master gain smoother, fade-in/fade-out orchestration, timer + countdown, persistence, Settings screen + timer chip UI, end-to-end verification. Test spec: T21–T31, M21–M30. Five decisions queued (D-21–D-25).

### Step 1: Master gain smoother in AudioEngine
- **Mode:** Code
- **Outcome:** complete — T21, T22 passed. Total test count: 40 (38 Phase 1+2 + 2 new), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL in 22s.
- **Contract changes:** `PlaybackController` interface — added `setGain(gain: Float)`. Affects: `AudioEngine` (implements), `PlaybackViewModelTest.FakeController` (implements).

Added master gain support to the audio engine for fade-in/fade-out (D-21):

- `PlaybackController.kt` — added `fun setGain(gain: Float)` to interface.
- `AudioEngine.kt` — added `fadeTimeSeconds` constructor parameter (default 2.0f), `gainSmoother` ParameterSmoother (initialValue=1.0, reuses proven smoother class), `setGain()` override, and gain multiply in render loop after `GainSafety.process()` and before `floatMonoToInt16Stereo()`. Gain applied post-clip so the GainSafety hard-clip guarantee is preserved (gain ≤ 1.0 only reduces amplitude).
- `AudioEngineTest.kt` — T21 verifies gain=0.5 produces ~half RMS amplitude vs gain=1.0 (same seed, instant smoother). T22 verifies gain ramp convergence: after setting gain 1→0 with 50ms time constant, tail samples are near-silent (RMS < 100 in Int16 range).
- `PlaybackViewModelTest.kt` — FakeController updated with `setGain()` recording `lastGain`.

One decision closed:
- **D-21** — Fade mechanism: second ParameterSmoother for master gain in AudioEngine, applied post-GainSafety. Reuses proven smoother; gain ≤ 1.0 preserves clip guarantee.

### Step 2: PlaybackState expansion + fade orchestration
- **Mode:** Code
- **Outcome:** complete — T23, T24, T25 passed. Total test count: 43 (40 Phase 1–3.1 + 3 new), 0 failures.
- **Contract changes:** `PlaybackController` — added `snapGain(gain: Float)`. `ParameterSmoother` — added `snapTo(value: Float)`. `PlaybackState` — added `FadingIn`, `FadingOut` variants.

Added fade-in/fade-out orchestration to the ViewModel:

- `ParameterSmoother.kt` — added `snapTo(value)` to instantly set both current and target, bypassing the ramp. Used for fade-in initialization.
- `PlaybackController.kt` — added `fun snapGain(gain: Float)` to interface. `AudioEngine` implements via `gainSmoother.snapTo()`.
- `PlaybackState.kt` — expanded sealed interface: `Idle | FadingIn | Playing | FadingOut`.
- `PlaybackViewModel.kt` — added `fadeInMs`/`fadeOutMs` constructor params (default 0 for backward compat). Fade-in: snapGain(0) → start → setGain(1) → delay → Playing. Fade-out: setGain(0) → delay → stop → Idle. Uses `viewModelScope.launch` with cancellable jobs. Production Factory uses 2s/5s defaults (D-25).
- `MainActivity.kt` — updated `when` expression for exhaustive match on new states. FadingIn shows Stop button, FadingOut shows disabled "Stopping…" button.
- `PlaybackViewModelTest.kt` — T23 verifies fade-in state sequence (FadingIn → Playing after delay). T24 verifies fade-out state sequence (FadingOut → Idle after delay, stop called only then). T25 verifies stop is not called prematurely at halfway point. All use `StandardTestDispatcher` + `runCurrent()`.
- `libs.versions.toml` + `build.gradle.kts` — added `kotlinx-coroutines-test:1.7.3` as testImplementation.

Gotcha: `StandardTestDispatcher` requires `runCurrent()` after `advanceTimeBy()` to execute coroutine continuations that resume at the advanced time. Without it, the continuation is queued but not dispatched.

### Step 3: TimerState + countdown coroutine + timer→fade-out integration
- **Mode:** Code
- **Outcome:** complete — T26, T27, T28 passed. Total test count: 46 (43 Phase 1–3.2 + 3 new), 0 failures.
- **Contract changes:** none (timer is orthogonal to PlaybackController; no interface changes)

Added auto-stop countdown timer as a separate `StateFlow<TimerState>` parallel to `PlaybackState` (D-22):

- `TimerState.kt` — new sealed interface: `Off | Armed(remainingMs: Long)`. Kept separate from `PlaybackState` to avoid state explosion per D-22.
- `PlaybackViewModel.kt` — added `_timerState`/`timerState` StateFlow (initial: `Off`), `timerJob: Job?`, and `onTimerSelected(durationMs: Long)`. The countdown coroutine loops `delay(1000)`, decrementing remaining by 1000 each tick. On expiry, sets timer to `Off` then calls `onStopClicked()` (which triggers fade-out if configured). `onStopClicked()` updated to cancel `timerJob` and reset timer to `Off` before the idle guard — ensures manual stop always clears the timer. `onCleared()` updated to cancel `timerJob`.
- `PlaybackViewModelTest.kt` — T26 verifies countdown ticks (Armed(60000) → Armed(59000) → Armed(58000) after 1s + 1s). T27 verifies timer expiry triggers fade-out (3s timer → FadingOut → Idle after fade completes). T28 verifies manual stop cancels timer (Armed resets to Off, no re-arm after advancing past original duration).

Edge case handling: timer expiry calls `onStopClicked()` which cancels `timerJob` — safe because the coroutine is past its last suspension point. Timer armed while not playing still counts down; if it expires and playback is idle, `onStopClicked()` is a no-op (existing idle guard). Timer cleanup in `onStopClicked()` is placed before the idle guard so it always runs.

### Step 4: Persistence via SharedPreferences
- **Mode:** Code
- **Outcome:** complete — T29, T30 passed. Total test count: 48 (46 Phase 1–3.3 + 2 new), 0 failures.
- **Contract changes:** `PlaybackViewModel` constructor — added optional `prefs: PrefsStore?` parameter (default `null`, backward-compatible with existing tests).

Added persistence of Color and timer duration across app restarts (D-23):

- `PrefsStore.kt` — new interface with `var color: Float` and `var timerDurationMs: Long`. Abstracts storage so the VM stays pure-JVM testable.
- `SharedPrefsStore.kt` — production implementation backed by `SharedPreferences`. Synchronous reads (negligible for 2 scalars), `apply()` for async writes.
- `PlaybackViewModel.kt` — added `prefs: PrefsStore?` constructor param. Init block restores saved color (sets `_color` + `controller.setColor()`) and timer duration (`lastTimerDurationMs` for UI pre-selection, timer not auto-armed). `onColorChanged()` persists color; `onTimerSelected()` persists duration. Factory updated to obtain `Application` from `CreationExtras.APPLICATION_KEY` and pass `SharedPrefsStore` to VM.
- `PlaybackViewModelTest.kt` — added `FakePrefsStore` (in-memory backing). T29 verifies saved color restored on construction (both StateFlow and controller). T30 verifies saved timer duration restored without auto-arming.

One decision closed:
- **D-23** — SharedPreferences over DataStore. Only 2 scalar values, read once at startup, no reactive/Flow requirement. Zero new dependencies.

### Step 5: Settings screen + timer chip UI + dark theme
- **Mode:** Code
- **Outcome:** complete — T31 passed (`assembleDebug` BUILD SUCCESSFUL). Total test count: 48 (unchanged), 0 failures.
- **Contract changes:** none

Full UI redesign to match visual spec — dark navy background, muted blue icon-only interface:

- `MainActivity.kt` — rewrote from scratch. Dark color scheme (`DarkNavy` #0F1A2E, `MutedBlue` #5A7BAF). Layout: timer clock icon (upper left), settings gear (upper right), large play triangle / stop square (center), color slider (1/3 up from bottom, no labels). Timer uses tap-to-cycle interaction: `∞ → 15m → 30m → 1h → 2h → ∞`, selected value appears as large centered text that fades out after 2 seconds. Settings screen with back arrow, shows fade-in/fade-out durations. Navigation via `var showSettings` state toggle (D-24).
- `res/drawable/ic_timer.xml` — new vector drawable: clock face with hour and minute hands (white fill, tinted at runtime).
- Fixed deprecation: `Icons.Filled.ArrowBack` → `Icons.AutoMirrored.Filled.ArrowBack`.

Two decisions closed:
- **D-24** — Settings navigation: simple `var showSettings` state toggle. Two screens, no deep linking needed, avoids NavHost dependency.
- **D-25** — Fade defaults: 2s fade-in, 5s fade-out. Displayed in Settings screen (read-only for now).

### Step 6: End-to-end wiring + manual on-device verification
- **Mode:** Verify
- **Outcome:** complete — M21–M30 manual verification passed on Pixel 6 emulator. All 17 ViewModel tests pass, 0 failures.
- **Contract changes:** none

Manual verification results:
- **M21** — Audible fade-in: gradual volume increase from silence. **Pass.**
- **M22** — Audible fade-out: gradual decrease, then silence. **Pass.**
- **M23** — Timer clock icon visible on main screen (upper left). **Pass.**
- **M24** — Timer selection (15m/30m/1h/2h) arms countdown on play. **Pass.**
- **M25** — Timer countdown visible below play button (updating text). **Pass.**
- **M26** — Timer expiry produces smooth fade-out then silence. **Pass.**
- **M27** — Color value persists across app kill + relaunch. **Pass.**
- **M28** — Timer selection persists across app kill + relaunch. **Pass** (fixed: timerIndex now restores from persisted lastTimerDurationMs).
- **M29** — Settings screen accessible from gear icon. **Pass.**
- **M30** — Settings read-only for now (fade durations displayed). **Pass** (deferred to polish phase).

Follow-up fixes during verification:
- Timer starts on play, not on selection (user feedback). ViewModel refactored: `onTimerSelected()` stores duration, `onPlayClicked()` starts countdown.
- Selecting ∞ properly cancels active timer.
- Play button moved 1/3 from top, timer label shown above play button (not overlapping).
- Play/stop icon uses `AnimatedContent` crossfade matching audio fade durations (2s in, 5s out).
- Play triangle drawn with Canvas + `CornerPathEffect(16dp)` for rounded vertices matching stop square's rounded aesthetic.
- Timer cycling index restored from prefs on relaunch.

### Phase Review
- **Mode:** Review
- **Outcome:** complete
- **Contract changes:** none

Review findings:
- **Must fix (1):** Play during fade-out with `fadeInMs=0` didn't restore gain to 1.0 — added `controller.snapGain(1f)` in the no-fade path + T25b test.
- **Should fix (3):** Fully qualified `Column` in SettingsScreen (missing import) — fixed. Duplicate "T25" in test docstring — fixed. D-22 missing from DECISIONS.md — added.
- **Optional (1):** Redundant `public` on `onCleared()` — kept, required for test access (`protected` in base class).

All fixes applied; 18 tests pass, 0 failures.

### Phase 3 Complete

Phase 3 delivered productization features: smooth fade-in/fade-out via gain smoother, auto-stop countdown timer, SharedPreferences persistence, dark-themed icon-only UI, and a Settings skeleton.

**6 steps completed:**
1. Master gain smoother in AudioEngine (T21/T22)
2. PlaybackState expansion + fade orchestration (T23/T24/T25/T25b)
3. TimerState + countdown coroutine + timer→fade-out (T26/T27/T28)
4. Persistence via SharedPreferences (T29/T30)
5. Settings screen + timer cycling UI + dark theme (T31)
6. End-to-end wiring + manual on-device verification (M21–M30)

**6 decisions closed:** D-21 (fade mechanism), D-22 (timer architecture), D-23 (persistence), D-24 (settings navigation), D-25 (fade defaults).

**Test count:** 18 ViewModel tests (T6/T6a–h, T23–T30, T25b, T31), 6 AudioEngine tests (T4/T5/T21/T22), 6 ParameterSmoother tests, 5 Biquad tests, 5 SpectralShaper tests, 6 GainSafety tests, 1 integration test = 47+ total. Manual tests M21–M30 passed on Pixel 6 emulator.

**Key artifacts:**
- New: `TimerState.kt`, `PrefsStore.kt`, `SharedPrefsStore.kt`, `ic_timer.xml`
- Modified: `PlaybackState.kt`, `PlaybackViewModel.kt`, `PlaybackViewModelTest.kt`, `AudioEngine.kt`, `PlaybackController.kt`, `ParameterSmoother.kt`, `MainActivity.kt`

### Phase 4: Background Robustness

### Phase Plan: Background Robustness
- **Mode:** Discuss
- **Outcome:** complete
- **Contract changes:** none

Broke Phase 4 into 5 steps: PlaybackService skeleton, service↔ViewModel binding, timer migration to service scope, notification stop action + audio focus + onTaskRemoved, end-to-end verification. Test spec: T32–T37, M31–M40. Five decisions closed (D-26–D-30): Binder binding, plain notification, timer in service, stop on task-removed, audio focus gain.

### Step 1: PlaybackService skeleton
- **Mode:** Build
- **Outcome:** complete
- **Contract changes:** AndroidManifest.xml (permissions + service declaration), build.gradle.kts (Robolectric dep)

Created `PlaybackService` as a foreground service owning the `AudioEngine` lifecycle. Added `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `POST_NOTIFICATIONS` permissions, plus `foregroundServiceType="mediaPlayback"` in the manifest. Service dispatches `ACTION_START`/`ACTION_STOP` intents to create/destroy the engine and manage `startForeground()`/`stopForeground()`. Notification channel (`IMPORTANCE_LOW`) created in `onCreate()`. Engine injection via companion `controllerFactory` for tests. Added Robolectric 4.11.1 + AndroidX Test Core 1.5.0 for service lifecycle testing. T32 (start + notification) and T33 (stop + onDestroy) pass, plus idempotency test.

### Step 2: Service ↔ ViewModel binding
- **Mode:** Build
- **Outcome:** complete
- **Contract changes:** PlaybackController ownership moved from ViewModel to PlaybackService; PlaybackViewModel.Factory signature changed to `(controller, appContext)`; LifecycleEventEffect(ON_STOP) removed from MainActivity

Service now implements `PlaybackController` directly. Added `LocalBinder` inner class; `onBind()` returns binder. `start()` creates engine + `startForeground()` synchronously through the binder, preserving the VM's sequential `snapGain→start→setGain` fade-in pattern. `stop()` tears down engine + `stopForeground()` + `stopSelf()`. Removed `ACTION_START` intent path (binder replaces it); kept `ACTION_STOP` for notification button (Step 4.4).

Activity calls `startService()` in `onCreate()` to put the service in "started" state (so `startForeground()` is legal later), then `bindService()`. Bound service stored in `mutableStateOf` for Compose reactivity — composable renders only after binding completes (~instant same-process).

`PlaybackViewModel.Factory` now accepts `(controller: PlaybackController, appContext: Context)` instead of creating its own `AudioEngine`. `onCleared()` cancels fade/timer jobs but does NOT call `controller.stop()` — the service owns engine lifecycle. This is critical: the engine must survive Activity/VM destruction for background playback. T6f updated to expect 0 stop calls on `onCleared()`.

T34 verifies VM delegates play/stop to the controller. All 25 tests pass (6 service + 19 VM).

### Step 3: Timer migration to service scope
- **Mode:** Build
- **Outcome:** complete — T35 passed + timer tick/expiry/cancel/fallback tests. All 31 tests pass (13 service + 18 VM).
- **Contract changes:** New `TimerController` interface; `PlaybackViewModel` constructor and Factory gain `timerController` param; `PlaybackService` implements `TimerController`; `NoiseMachineApp` composable gains `timerController` param.

Moved the timer countdown coroutine from `viewModelScope` to the service's own `CoroutineScope` (D-28). Created `TimerController` interface (`timerState: StateFlow<TimerState>`, `onTimerExpired` callback, `startTimer/cancelTimer`) to keep the timer contract separate from `PlaybackController`. `PlaybackService` implements it with a `serviceScope` backed by `SupervisorJob()` and an injectable `timerDispatcher` for test control.

The ViewModel no longer owns `_timerState`, `timerJob`, or `startTimerCountdown()` — it delegates to `timerController.startTimer/cancelTimer` and observes `timerController.timerState` as a pass-through. The `onTimerExpired` callback pattern lets the service's timer expiry trigger the VM's `onStopClicked()` (for proper fade-out). When the VM is destroyed, `onCleared()` nulls the callback; the service falls back to calling `stop()` directly (acceptable since no UI is watching).

Review fix: clamped remaining with `maxOf(0, remaining)` to prevent negative `Armed` values for non-1000-multiple durations. Fixed misleading `assertSame` → `assertNotNull` in VM test.

### Step 4: Notification stop action + audio focus + onTaskRemoved
- **Mode:** Build
- **Outcome:** complete — T36 passed + onTaskRemoved + audio focus tests. All 34 tests pass (16 service + 18 VM).
- **Contract changes:** none (all changes internal to PlaybackService)

Three additions to `PlaybackService`:

1. **Notification stop action** — `buildNotification()` now includes a `PendingIntent` → `ACTION_STOP` wired as a `Notification.Action` labeled "Stop". The existing `onStartCommand(ACTION_STOP)` handler routes it to `stop()`.

2. **Audio focus** — `start()` requests `AUDIOFOCUS_GAIN` via `AudioFocusRequest.Builder` with `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`. Focus denied = early return (no playback). `stop()` and `onDestroy()` abandon focus. `OnAudioFocusChangeListener` handles `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`, and `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` by calling `stop()` — for a sleep noise app, any interruption should stop rather than duck/resume.

3. **onTaskRemoved** — overrides `onTaskRemoved()` → `stop()` per D-29 (swipe from recents = explicit dismissal).

T36 verifies the notification has a "Stop" action. Audio focus tests use an `internal audioFocusListener` accessor to simulate focus loss/transient loss. `onTaskRemoved` test verifies engine stops. `AudioFocusRequest`'s `focusGain` and `onAudioFocusChangeListener` are not public API, so tests verify behavior through the internal listener and observable engine state rather than shadow inspection.

### Step 5: End-to-end manual verification + review fixes
- **Mode:** Build
- **Outcome:** complete — M31–M39 verified on emulator (API 36) + physical device. 78 tests pass (26 service + 21 VM + 31 audio). M40 (multi-hour) deferred to ongoing use.
- **Contract changes:** `NoiseMachineApp` composable signature changed from `(PlaybackController, TimerController)` to `(PlaybackService)`; `PlaybackService` gained `onStopRequested` callback and `dispatchStop()` helper.

Manual verification on emulator and physical device uncovered 5 bugs, all fixed:

1. **VM state sync on Activity recreation** — `PlaybackViewModel._state` always initialized as `Idle`. After screen-off/on, the stop button showed as play triangle even while audio was playing. Fixed by checking `controller.isPlaying` at init.

2. **Fade-in regression** — VM calls `snapGain(0f)` before `start()`, but the service creates the engine inside `start()`, so `snapGain` went to a null engine. Audio started at full volume abruptly. Fixed with `pendingSnapGain` buffering in `PlaybackService`: if `snapGain()` is called before the engine exists, the value is stored and applied when `start()` creates the engine.

3. **No notification visible** — `POST_NOTIFICATIONS` permission was `granted=false` on API 36. Granted via `adb shell pm grant`. Runtime permission request dialog deferred (out of Phase 4 scope).

4. **Notification stop: no fade-out, stale button** — `ACTION_STOP` from the notification called `service.stop()` directly, bypassing the VM's fade-out orchestration and leaving `_state` at `Playing`. Fixed with `onStopRequested` callback pattern (analogous to `onTimerExpired`): the composable wires `service.onStopRequested = { viewModel.onStopClicked() }` via `DisposableEffect`, so external stop triggers (notification, audio focus loss) route through the VM for proper fade-out + state update. Falls back to direct `stop()` when VM is destroyed.

5. **`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` stopping playback** — A notification chime triggered a 5-second fade-out, which is wrong for a sleep noise app. Fixed: duck events are now ignored (playback continues through brief audio interruptions). Only `AUDIOFOCUS_LOSS` and `AUDIOFOCUS_LOSS_TRANSIENT` stop playback.

Additional review fixes:
- `onTaskRemoved` uses direct `stop()` (not `dispatchStop`) — user explicitly dismissed the app, no fade needed. Avoids zombie service from cancelled fadeJob.
- `pendingSnapGain` cleared in `stop()` — no stale gain across sessions.
- `dispatchStop()` helper captures `onStopRequested` in a local var before invoking — prevents non-atomic read-invoke on the callback.

+11 new tests: `onStopRequested` callback routing (3 tests), `pendingSnapGain` buffering (4 tests), duck-ignored, task-removed-always-direct, callback-null-fallback, VM `isPlaying` init sync.

### Phase Completion: Background Robustness
- **Mode:** Review
- **Outcome:** complete — Phase 4 done. 78 tests (26 service, 21 VM, 31 audio), 0 failures. M31–M39 verified on emulator (API 36) + physical device. M40 (multi-hour) deferred to ongoing use.
- **Contract changes:** none (review-only fixes: KDoc updates, import cleanup)

Phase 4 delivered a foreground service (`PlaybackService`) that owns `AudioEngine` lifecycle, persistent notification with stop action, timer countdown in service scope, audio focus management, and task-removed cleanup. Five bugs found and fixed during manual verification (VM state sync, fade-in regression, notification permission, notification stop bypass, duck focus handling). Review fixes: stale KDoc on focus behavior, inline `DisposableEffect` import.

**Test count at phase end:** 78 total (26 PlaybackServiceTest, 21 PlaybackViewModelTest, 6 AudioEngineTest, 6 ParameterSmootherTest, 5 BiquadTest, 5 SpectralShaperTest, 6 GainSafetyTest, 3 NoiseSourceTest).

**Key artifacts (Phase 4):**
- New: `PlaybackService.kt`, `TimerController.kt`, `PlaybackServiceTest.kt`
- Modified: `PlaybackViewModel.kt`, `PlaybackViewModelTest.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `build.gradle.kts`

### Phase 5: Secondary Polish

### Phase Plan: Secondary Polish
- **Mode:** Discuss
- **Outcome:** complete
- **Contract changes:** none

Broke Phase 5 into 6 steps: 5 Build + 1 Refine. Scope: Settings screen expansion with functional DSP controls for all deferred parameters plus POST_NOTIFICATIONS fix.

Features planned:
1. TextureShaper — zero-order hold (sample decimation) for smooth↔grainy control
2. StereoStage — first-order all-pass on R channel for subtle stereo decorrelation
3. MicroDrift — slow LFO modulating Color offset for subtle tonal wandering
4. Fade durations — configurable fade-in/fade-out via Settings (currently read-only)
5. About section — app name, version, description
6. POST_NOTIFICATIONS — runtime permission dialog on API 33+

Test spec: T38–T48, M41–M55. Five decisions queued (D-31–D-35).

DSP pipeline change: SpectralShaper now receives `color + microDriftOffset`; TextureShaper inserts after SpectralShaper; StereoStage replaces inline `floatMonoToInt16Stereo`. Contract changes expected: `PlaybackController` gains `setTexture`, `setStereoWidth`, `setMicroDriftDepth`; `PrefsStore` gains 5 new fields.

### Step 1: TextureShaper DSP
- **Mode:** Code
- **Outcome:** complete — T38, T38b, T39, T39b, T39c, T40, reset test passed (7 tests). Total test count: 85 (78 Phase 1–4 + 7 TextureShaper), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/TextureShaper.kt` — variable-rate zero-order hold (sample decimation) for smooth ↔ grainy texture control. At texture=0, passthrough (fast path returns immediately). At texture=1, each sample is held for MAX_HOLD=6 frames (~7350 Hz effective resolution at 44100 Hz). The hold length is `1 + floor(texture * (MAX_HOLD - 1))`, giving a linear mapping from the slider to graininess.

Implementation is allocation-free: two state variables (`heldSample: Float`, `holdCounter: Int`) track the current held value and remaining repeats. The `process()` inner loop is branchless except for the hold-counter check. `reset()` clears state for new playback sessions.

Tests in `TextureShaperTest.kt`: T38 (passthrough at texture=0 — exact match), T38b (passthrough with large noise buffer), T39 (texture=1 repeat rate >50%), T39b (texture=0.5 intermediate stepping), T39c (all output within [-1, 1]), T40 (heap delta < 256 KB after 10M samples), plus a reset edge-case test.

One decision closed: D-31 (Texture DSP approach: zero-order hold, MAX_HOLD=6).

### Step 2: StereoStage DSP
- **Mode:** Code
- **Outcome:** complete — T41, T41b, T42, T42b, T42c, T43, T43b, reset test passed (8 tests). Total test count: 93 (85 Phase 1–5.1 + 8 StereoStage), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/StereoStage.kt` — first-order all-pass decorrelation for stereo width control. Takes a mono `FloatArray` and produces an interleaved stereo `ShortArray` (Int16 PCM), replacing the inline `floatMonoToInt16Stereo` function in AudioEngine.

At width=0, fast path produces identical L/R channels (D-5 behavior). At width>0, L = mono (direct), R = mono × (1−width) + allpass(mono) × width. The all-pass filter uses coefficient 0.6, placing the 90° phase-shift crossover around 1–2 kHz for broad decorrelation across the audible range. Two state variables (`apX1`, `apY1`) maintain continuity across buffer boundaries.

The `toInt16` companion function replicates the clip-safe conversion from the old `floatMonoToInt16Stereo` — `v >= 1.0f → MAX_VALUE`, `v <= -1.0f → MIN_VALUE`, else `(v * 32767).toShort()`.

Tests: T41 (identical L/R at width=0), T41b (specific conversion values match legacy), T42 (>80% L≠R at width=0.3), T42b (>90% L≠R at width=1.0), T42c (monotonicity: width=1.0 diff > width=0.3 diff), T43 (all output bounded Int16), T43b (heap delta < 256 KB), plus reset edge-case test.

One decision closed: D-32 (Stereo decorrelation: first-order all-pass, coeff=0.6).

### Step 3: MicroDrift DSP
- **Mode:** Code
- **Outcome:** complete — T47, T47b, T48, T48b, T48c, T48d, reset test passed (7 tests). Total test count: 100 (93 Phase 1–5.2 + 7 MicroDrift), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/MicroDrift.kt` — slow triangle-wave LFO at 0.05 Hz (~20 s period) producing a small Color offset for subtle tonal wandering. At depth=0, offset is always 0. At depth=1, offset swings ±0.05 (MAX_OFFSET). Intermediate depths scale linearly.

The triangle wave maps phase [0,1) → [-1,+1] via a symmetric piecewise-linear function. Phase advances by `BASE_FREQ_HZ / sampleRate` per sample, accumulated per-block in `nextBlock(frames)`. Depth changes are smoothed via a one-pole filter using closed-form per-block stepping (`1 - (1-alpha)^frames`, matching ParameterSmoother.nextBlock), with a 200 ms time constant to avoid abrupt jumps.

Key design: `@Volatile` depth target for cross-thread safety; `nextBlock` called once per render buffer; phase always advances (even at depth=0) to avoid jump when depth is re-enabled. Allocation-free — no object creation in the hot path.

Tests: T47 (depth=0 → zero offset over 60 s), T47b (depth returns to zero after being nonzero), T48 (depth=1 → multiple distinct offset values over 30 s), T48b (offset bounded by ±MAX_OFFSET over 60 s), T48c (offset changes sign over a full period), T48d (depth=0.5 peak ≈ half of depth=1.0 peak), plus reset edge-case test.

One decision closed: D-33 (Micro-drift mechanism: slow triangle LFO at 0.05 Hz, ±0.05 max offset, depth-scaled).

### Step 4: Engine + controller integration
- **Mode:** Code
- **Outcome:** complete — T44 passed (full pipeline bounded at 3 Color/Texture/Width/Drift combos). Total test count: 101 (100 Phase 1–5.3 + 1 T44), 0 failures. `testDebugUnitTest` BUILD SUCCESSFUL.
- **Contract changes:** `PlaybackController` interface — added `setTexture(Float)`, `setStereoWidth(Float)`, `setMicroDriftDepth(Float)`. Propagated to `PlaybackService` (delegate methods) and both test fakes (`PlaybackServiceTest.FakeController`, `PlaybackViewModelTest.FakeController`).

Wired TextureShaper, StereoStage, and MicroDrift into AudioEngine's render loop. The new pipeline order:

```
NoiseSource.fill(buf)
  → SpectralShaper.process(buf, effectiveColor)     // effectiveColor = color + driftOffset
  → TextureShaper.process(buf, texture)              // NEW
  → GainSafety.process(buf, effectiveColor)
  → masterGain multiply
  → StereoStage.processToStereo(mono, stereo, width) // REPLACES floatMonoToInt16Stereo
  → AudioSink.write
```

AudioEngine changes: added `textureSmoother` and `stereoWidthSmoother` (ParameterSmoother instances, class-level with @Volatile targets). MicroDrift instance created per-session in `start()` with class-level reference for `setMicroDriftDepth` to reach its @Volatile target. TextureShaper, StereoStage created per-session (stateful, not thread-safe). Removed the now-redundant private `floatMonoToInt16Stereo` method.

PlaybackService: three new delegate methods forwarding to `engine?.setTexture/setStereoWidth/setMicroDriftDepth`.

No decisions closed in this step (D-34 fade duration configurability is a Step 5 Settings UI concern).

### Step 5: Settings UI + persistence + About + permission
- **Mode:** Code
- **Outcome:** complete — T45 passed (assembleDebug compiles), 101 unit tests pass. POST_NOTIFICATIONS wired (T46 = manual verification on API 33+ device).
- **Contract changes:** `PrefsStore` interface — added `texture: Float`, `stereoEnabled: Boolean`, `microDriftDepth: Float`, `fadeInMs: Long`, `fadeOutMs: Long`. Propagated to `SharedPrefsStore` (production) and `FakePrefsStore` (test). `PlaybackViewModel` — added public methods `onTextureChanged`, `onStereoToggled`, `onMicroDriftDepthChanged`, `onFadeInChanged`, `onFadeOutChanged` and StateFlows `texture`, `stereoEnabled`, `microDriftDepth`, `fadeInMsFlow`, `fadeOutMsFlow`.

Expanded Settings screen with three sections:

**Sound controls:** Texture slider (smooth ↔ grainy, full-width), Micro drift slider (none ↔ max, full-width), Stereo toggle (Switch). Each change immediately forwards to the controller and persists via PrefsStore.

**Timing:** Fade-in and fade-out duration pickers (OutlinedButton + DropdownMenu) with options 0s/1s/2s/5s/10s per D-34. ViewModel `fadeInMs`/`fadeOutMs` are now mutable — read from prefs at init and updated live. Factory reads fade values from prefs instead of using hardcoded defaults.

**About:** Description ("Generates ambient noise to mask distractions and help you sleep. The Color slider shapes the tone from bright (white) through balanced (pink) to deep (brown)."), "Made by The Moving Finger Studios", "In Memoriam Sergei Skarupo, 1973–2021", version string from PackageInfo.

**POST_NOTIFICATIONS (D-35):** `rememberLauncherForActivityResult(RequestPermission)` in NoiseMachineApp. On API 33+, first Play tap checks `checkSelfPermission` and launches the permission dialog if not granted. Denied = playback works, notification invisible.

Settings screen is scrollable (verticalScroll) with dividers between sections. Stereo uses a fixed width of 0.3 when enabled (restrained per D-5).

Two decisions closed: D-34 (fade picker), D-35 (POST_NOTIFICATIONS flow).
