# Noise Machine — Decision Log

<!-- Record non-trivial design and implementation decisions here.
     Use the full template for genuine design forks with trade-offs.
     For reactive decisions during Refine work, a one-line note in
     the DEVLOG is sufficient — don't over-use this file.

     Once Closed, don't reopen unless new evidence appears. -->

D-1: Continuous spectral shaping over multi-source mixing
Date: 2026-04-16 | Status: Closed
Priority: Critical
Decision: Use one white-noise base generator with continuous spectral shaping rather than crossfading between separate white/pink/brown generators.
Rationale: Produces one coherent noise family; supports smooth slider transitions; matches the one-slider UX; more efficient; avoids the "fake crossfade between distinct personalities" feel. Alternative considered: three-source mix with crossfade — rejected because it makes Color feel discontinuous and is harder to tune perceptually.
Revisit if: perceptual tuning proves unachievable with real-time IIR cascades and FFT-domain shaping becomes necessary.

D-2: Color slider drives multiple internal parameters
Date: 2026-04-16 | Status: Closed
Priority: Critical
Decision: The single visible Color slider maps to a coordinated vector of internal parameters (spectral tilt amount, HF attenuation, LF containment, output-level compensation, smoothing behavior).
Rationale: Keeps the UI minimal while avoiding the trap of a simple filter sweep, which would feel muffled at one end and sterile at the other. Alternative considered: expose tilt, HF, LF as separate sliders — rejected because it violates the minimal sleep-first UX.
Revisit if: users report the slider feels ambiguous, non-monotonic, or unintuitive.

D-3: Native media volume only — no in-app volume slider
Date: 2026-04-16 | Status: Closed
Priority: Important
Decision: Volume is controlled exclusively via the phone's hardware / system media volume controls. The app may apply an internal safety ceiling but exposes no visible gain control.
Rationale: Less UI clutter; users already understand hardware buttons; avoids redundant controls; aligns with the minimal sleep-first philosophy. Alternative considered: dedicated in-app volume slider — rejected as visual noise on a bedside-use main screen.
Revisit if: usability feedback indicates users cannot reach desired listening levels via system volume alone.

D-4: Foreground service for playback
Date: 2026-04-16 | Status: Closed
Priority: Critical
Decision: Playback runs in an Android Foreground Service with a persistent notification.
Rationale: Required for reliable long-session playback with screen off and during backgrounding; makes the app behave like a legitimate long-running audio app rather than an in-activity demo. Alternative considered: bound-service or activity-scoped playback — rejected because it cannot reliably survive backgrounding.
Revisit if: Android lifecycle APIs change in a way that offers a more appropriate primitive.

D-5: Restrained stereo in V1
Date: 2026-04-16 | Status: Closed
Priority: Nice-to-have
Decision: V1 stereo output is either identical or only very slightly decorrelated between channels. Width / motion / decorrelation controls are deferred to a later phase.
Rationale: Aggressive stereo width or motion is distracting and attention-grabbing — the opposite of what a sleep app should feel like. Alternative considered: prominent stereo width slider — rejected as misaligned with the calm/appliance product feel.
Revisit if: user feedback consistently asks for a subtle-width option that can be exposed without compromising the calm default.

D-6: Min API 26, compileSdk / targetSdk 34, Java 17
Date: 2026-04-16 | Status: Closed
Priority: Important
Decision: `minSdk = 26` (Android 8.0 Oreo), `compileSdk = 34`, `targetSdk = 34`, `sourceCompatibility = targetCompatibility = JavaVersion.VERSION_17`, `kotlinOptions.jvmTarget = "17"`.
Rationale: API 26 covers ≈97% of active Android devices in 2026 and is the widely-used floor for modern Jetpack / Compose apps; it gives access to `AudioAttributes.USAGE_MEDIA`, MediaSession APIs, notification channels, and `AudioTrack.Builder` without legacy-path branching. compileSdk/targetSdk 34 (Android 14) is the highest stable API level with full AGP 8.7.x support and matches what is locally installed. Java 17 is the installed JDK and the current long-term recommendation for AGP 8.x. Alternatives considered: minSdk 24 (marginal ≈1–2% coverage gain, forces extra AudioTrack compat branches — rejected); compileSdk 35/36 (installed but paired with newer AGP requirements that destabilize Gradle 8.10 — rejected for V1).
Revisit if: a concrete Android-14+ API materially improves audio stability or battery behavior and justifies raising targetSdk, or if a required dependency drops API 26 support.

D-7: Sample rate 44100 Hz
Date: 2026-04-16 | Status: Closed (resolved 2026-04-17 in Step 3)
Priority: Important
Decision: Engine default sample rate is **44100 Hz**, 16-bit PCM, stereo. Hard-coded as the `AudioEngine` constructor default; `AudioTrackSink` opens its `AudioTrack` at whatever rate the engine passes in. No device-native rate query in V1.
Rationale: 44100 Hz is universally supported on Android `AudioTrack` and is the historical PCM standard; for broadband white noise the audible difference between 44100 and a device-native 48000 Hz is negligible. Querying `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` and matching it would avoid one resampling stage in the audio HAL but adds branching, an Android-context dependency at sink construction time, and a new failure mode (hardware reports a rate AudioTrack then refuses). Not worth the complexity for sleep-noise output.
Revisit if: device profiling shows persistent CPU or power overhead from the HAL’s rate conversion, or if a device family rejects 44100 Hz outright.

D-8: AudioTrack buffer size = max(minBufferBytes × 2, framesPerWrite × bytesPerFrame); render quantum = 1024 frames
Date: 2026-04-16 | Status: Closed (resolved 2026-04-17 in Step 3)
Priority: Important
Decision: `AudioTrackSink` sizes the `AudioTrack` internal buffer to `max(AudioTrack.getMinBufferSize(...) × 2, framesPerWrite × bytesPerFrame)`. The engine's per-write **render quantum is fixed at 1024 stereo frames** (~23 ms at 44100 Hz), independent of the AudioTrack buffer.
Rationale: Two separate concerns: (1) the AudioTrack buffer is the glitch-margin reservoir that protects against scheduler hiccups — keep it at 2× the platform-reported under-run threshold per the original Step-3 plan; (2) the render quantum is the unit of work the engine does per loop iteration. A larger quantum would mean the loop checks the `running` flag less often (slower stop response, larger pre-allocated buffers); a much smaller quantum (e.g. 128 frames) would burn more CPU on per-call overhead. 1024 frames is a balance that keeps stop() responsive (worst-case ~23 ms) while writing in chunks AudioTrack can comfortably accept. The `max(…)` floor guards against pathological devices where minBuffer is smaller than one render quantum.
Alternatives considered: dynamic quantum (e.g. half the AudioTrack buffer) — rejected because it makes the engine’s allocation budget a runtime moving target; very small quantum (128–256 frames) for low-latency parameter response — unnecessary, sleep-noise has no perceptual latency requirement.
Revisit if: long-session testing in Phase 4 reveals under-runs (raise the AudioTrack multiplier) or power profiling shows the loop wakes up too often (raise the render quantum).

D-9: Toolchain pins — AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21, Compose BOM 2024.10.01
Date: 2026-04-16 | Status: Closed
Priority: Important
Decision:
- Android Gradle Plugin **8.7.3**
- Gradle wrapper **8.10.2** (distribution-type `bin`)
- Kotlin **2.0.21** (with the `org.jetbrains.kotlin.plugin.compose` plugin; no `kotlinCompilerExtensionVersion` block needed)
- AndroidX Compose BOM **2024.10.01**; transitive versions (Material 3, UI, etc.) come from the BOM
- AndroidX Core-KTX **1.13.1**, Activity-Compose **1.9.3**, Lifecycle-Runtime-KTX **2.8.7**
- JUnit **4.13.2** for JVM unit tests
- Version catalog lives in `gradle/libs.versions.toml`

Rationale: AGP 8.7.x is the newest stable 8.x line and is the last one that cleanly pairs with Gradle 8.x (AGP 8.8+ and Gradle 9.x have known incompatibilities on some tasks). Gradle 8.10.2 is a well-tested point release. Kotlin 2.0.21 + the Compose Compiler Gradle Plugin is the current Kotlin 2.x–native approach (replaces the old `kotlinCompilerExtensionVersion` manual pinning). Compose BOM 2024.10.01 tracks Kotlin 2.0.x correctly. The installed system Gradle (9.4.0) is intentionally NOT used for the project build; the wrapper pins the version so that every contributor and CI runs the same Gradle regardless of system install. Alternatives considered: AGP 8.8+ with Gradle 9.x (rejected — more moving parts while we want a stable scaffold); bumping compileSdk to 35 or 36 (rejected — see D-6).

Revisit if: Kotlin or AGP release a security fix or a materially better Compose-Compiler pairing; or if the local `PROJECT.md`/ARCHITECTURE.md requires an API only available above API 26 / targetSdk 34.

D-10: Noise RNG — `java.util.SplittableRandom`
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: `NoiseSource` uses `java.util.SplittableRandom` as its uniform source. One instance per audio thread, never shared. Sample mapping: `(rng.nextDouble() * 2.0 - 1.0).toFloat()` → uniform `[-1.0f, 1.0f)`.
Rationale: SplittableRandom's `nextDouble()` is allocation-free (returns a primitive `double`), uses no internal synchronization (its state is a plain `long` advanced by an arithmetic step — no `AtomicLong` CAS loop), and is available since API 24, comfortably below our minSdk 26. It is the cheapest way to satisfy DEVPLAN test T2 (allocation-free) and the CLAUDE.md non-negotiable "audio render loop must be allocation-free" without writing a custom PRNG. Alternatives considered: `java.util.Random` — its `next(int)` performs a `compareAndSet` loop on an `AtomicLong` seed, which is correct but unnecessarily contentious and harder to prove allocation-free across JVM versions; rejected. Hand-rolled `XorShift64` — would also work and is fully allocation-free by construction, but adds custom DSP-adjacent code we'd need to maintain and statistically validate; rejected as not warranted once SplittableRandom met the bar.
Revisit if: a future profiling pass reveals SplittableRandom allocations on Android's ART runtime (currently no evidence), or if a higher-quality (e.g., longer-period) PRNG becomes necessary for stereo decorrelation work in Phase 5.

D-11: AudioSink test seam — narrow interface in front of AudioTrack
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: `AudioEngine` depends on a tiny `AudioSink` interface (`open / write / close`); production code uses `AudioTrackSink`, tests inject a `FakeSink`. JVM unit tests for the engine's lifecycle, threading, and no-leak guarantees run with no Android framework dependency and no `androidTest` step.
Rationale: Phase 1 test spec T4 (start/stop lifecycle) and T5 (rapid toggle, no crash/ANR) are about the engine's own state machine and thread management, not about Android's audio HAL. Routing them through Robolectric or `androidTest` would add seconds to minutes per run and gate iteration on emulator availability — disproportionate for what we're actually testing. The seam also matches the layered render pipeline already documented in ARCHITECTURE.md (NoiseSource → … → sink) and aligns with the CLAUDE.md test strategy note ("specific framework chosen during Phase 1"). Alternatives considered: Robolectric — partial AudioTrack shadow, brittle, large dep; rejected. Pure `androidTest` — most realistic but requires emulator/device for every iteration; rejected. AudioTrack-direct in `AudioEngine` with no seam — simpler code, but un-unit-testable; rejected.
Revisit if: a future feature requires us to assert behavior tightly bound to AudioTrack semantics (e.g. precise underrun handling), at which point a small targeted instrumented test under `app/src/androidTest/` is the right addition — the seam doesn't prevent it.

D-12: PlaybackViewModel state surface — `StateFlow<PlaybackState>`
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: `PlaybackViewModel` exposes its UI state as `StateFlow<PlaybackState>` backed by a private `MutableStateFlow`. Compose collects via `androidx.lifecycle.compose.collectAsStateWithLifecycle()`, which respects the activity lifecycle (no collection while stopped) and re-collects on configuration changes.
Rationale: `StateFlow` is the idiomatic VM-to-UI surface for Kotlin/Compose, plays well with `viewModelScope` if we later need async state, and \u2014 critically for our one-test-runtime constraint \u2014 is testable in plain JVM JUnit by reading `state.value` synchronously. Updates from `onPlayClicked()` / `onStopClicked()` happen on whatever thread fires the event handler; `MutableStateFlow.value` is thread-safe so no further synchronization is required at this stage. Alternative considered: Compose `mutableStateOf<PlaybackState>` \u2014 simpler, but couples the VM to the Compose runtime and reads less idiomatically from non-Compose tests; rejected.
Revisit if: we ever need a one-shot event channel (errors, navigation) that doesn't fit a `StateFlow` \u2014 then we add a separate `SharedFlow` next to the state flow.

D-13: PlaybackController seam \u2014 narrow interface in front of AudioEngine
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: `PlaybackViewModel` depends on a tiny `PlaybackController` interface (`val isPlaying: Boolean; fun start(); fun stop()`). `AudioEngine` implements it directly \u2014 its existing signatures already match \u2014 so no adapter class is needed. Tests inject a `FakeController` that records call counts and supports a one-shot `failNextStart` flag for T6h.
Rationale: Same separation-of-concerns rationale as D-11 (AudioSink), one layer up. Lets the VM's state-machine and idempotency contracts (T6, T6a..T6h) be validated entirely on the JVM with no audio framework anywhere on the test classpath. Implementing the interface on `AudioEngine` directly (vs. wrapping it in a `PlaybackController` adapter) avoids dead code: `AudioEngine.start/stop/isPlaying` already had the right signatures, the only change was adding `: PlaybackController` and `override` modifiers.
Revisit if: a second production controller appears (e.g. a Foreground-Service-mediated controller in Phase 4) and the interface needs to grow \u2014 expected but not yet pressing.

D-14: Step 4 dependencies \u2014 lifecycle-viewmodel-compose + lifecycle-runtime-compose
Date: 2026-04-17 | Status: Closed
Priority: Nice-to-have
Decision: Add `androidx.lifecycle:lifecycle-viewmodel-compose` and `androidx.lifecycle:lifecycle-runtime-compose`, both pinned to 2.8.7 via the existing `lifecycleRuntimeKtx` version ref in `gradle/libs.versions.toml`. No new test dependencies; `kotlinx-coroutines-core` (needed for `MutableStateFlow`) comes in transitively through Compose runtime.
Rationale: `lifecycle-viewmodel-compose` provides the `viewModel(factory = \u2026)` Composable function used by `MainActivity.NoiseMachineApp`; `lifecycle-runtime-compose` provides `collectAsStateWithLifecycle()`, the lifecycle-aware variant of `collectAsState()` and the recommended default since 2.7.x. Both are small, both share the version we already pin, and adding them now avoids wedging in an extra version family later. Alternative considered: pull only `lifecycle-viewmodel-compose` and use `collectAsState()` instead \u2014 functional but produces background collection while the activity is stopped, defeating part of the lifecycle story; rejected.
Revisit if: a future Lifecycle/Compose release breaks one of these artifacts and we need to split versions.

D-15: PlaybackViewModel construction \u2014 inline Factory wires the production AudioEngine
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: `PlaybackViewModel.Factory` (a nested `ViewModelProvider.Factory`) constructs `AudioEngine(sinkFactory = { AudioTrackSink() })` and passes it to a fresh `PlaybackViewModel`. The activity calls `viewModel(factory = PlaybackViewModel.Factory())` from the top-level Composable. No DI framework for now.
Rationale: Phase 1 has exactly one ViewModel and one production controller \u2014 a hand-rolled factory is the lowest-friction wiring that still keeps the VM constructor pure (no Android-context dependency, fully unit-testable). Pulling in Hilt or Koin for one VM would be premature. Alternative considered: instantiate the engine inside the activity and pass it through a `LocalCompositionProvider` \u2014 flexible but a layer of indirection without a current consumer; rejected.
Revisit if: a second production-graph dependency appears (e.g. a `PlaybackService` shared between activity and notification controls in Phase 4) \u2014 at that point Hilt is the standard answer.

D-21: Fade mechanism \u2014 second ParameterSmoother for master gain in AudioEngine
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: Fade-in/fade-out is implemented as a second `ParameterSmoother` instance (`gainSmoother`) in `AudioEngine`, with `setGain(Float)` on `PlaybackController`. The gain multiply is applied post-`GainSafety` in the render loop, preserving the hard-clip guarantee (gain in [0, 1] only reduces amplitude). `initialValue = 1.0f` so existing behavior (no fade) is unchanged without explicit `setGain()` calls. `fadeTimeSeconds` is a constructor parameter (default 2.0f) for testability.
Rationale: Reuses the proven `ParameterSmoother` class (lock-free, allocation-free, exponential ramp) and follows the exact same pattern as `colorSmoother`. Post-GainSafety placement means no additional clipping stage is needed. Alternatives considered: separate gain stage class \u2014 rejected as unnecessary indirection; inline gain in GainSafety \u2014 rejected because GainSafety is Color-indexed and master gain is orthogonal.
Revisit if: asymmetric fade-in/fade-out time constants require a smoother with runtime-configurable alpha (Step 3.2 may address this).

D-22: TimerState as separate sealed interface
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: Timer state is modeled as a separate `TimerState` sealed interface (`Off` / `Armed(remainingMs)`) exposed as its own `StateFlow`, orthogonal to `PlaybackState`.
Rationale: Merging timer state into `PlaybackState` would create a combinatorial explosion (`Playing+Armed`, `FadingOut+Armed`, etc.) with no benefit. The timer and playback lifecycles are independent — the timer can be armed while idle (waiting for play), and playback can be active without a timer. Separate flows let the UI observe each independently.
Revisit if: a future feature requires atomic transitions across both states simultaneously.

D-23: Persistence — SharedPreferences over DataStore
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: Persist Color (Float) and timer duration (Long) via `SharedPreferences`, abstracted behind a `PrefsStore` interface for testability. The VM reads once on construction; writes use `apply()` (async fire-and-forget). No new dependencies — SharedPreferences is in the Android SDK. The `Factory` obtains `Application` from `CreationExtras.APPLICATION_KEY` and passes the `PrefsStore` into the VM constructor.
Rationale: Only 2 scalar values are persisted, read once at startup with no reactive/Flow requirement. DataStore (`androidx.datastore:datastore-preferences`) adds a dependency and coroutine/Flow machinery that is unnecessary for this scale. SharedPreferences `getFloat()`/`getLong()` for 2 keys has negligible main-thread cost (no ANR risk). `apply()` is already async for writes. The `PrefsStore` interface keeps the VM constructor pure and JVM-testable (tests inject a `FakePrefsStore`). Alternatives considered: DataStore — modern and coroutine-native but overkill for 2 scalars with no reactive reads; rejected. Plain constructor defaults (defer persistence) — doesn't satisfy T29/T30 spec; rejected.
Revisit if: persistence grows beyond a handful of scalars (e.g. preset lists, history) and reactive reads become useful — DataStore or Room become worth the dependency.

D-24: Settings navigation — simple state toggle over NavHost
Date: 2026-04-17 | Status: Closed
Priority: Nice-to-have
Decision: Settings screen is toggled via a simple `var showSettings` boolean state in the top-level Composable, with an `if/else` branch between the main screen and settings screen. No `NavHost` or navigation-compose dependency.
Rationale: The app has exactly two screens with no deep linking, no back-stack requirements beyond a single pop, and no arguments to pass. A `NavHost` would add a dependency and boilerplate for zero functional gain. A boolean toggle is trivial, readable, and testable.
Revisit if: a third screen appears or deep-link support is needed — at that point pull in navigation-compose.

D-25: Fade defaults — 2s fade-in, 5s fade-out
Date: 2026-04-17 | Status: Closed
Priority: Nice-to-have
Decision: Default fade-in duration is 2000ms, fade-out duration is 5000ms. Constants live in `PlaybackViewModel.Companion` and are passed via the production Factory. Test VMs use 0ms for deterministic tests.
Rationale: 2s fade-in is long enough to avoid an abrupt start but short enough to feel responsive. 5s fade-out gives a gentle wind-down suitable for sleep context. These are the values used throughout Phase 3 development. Configurable via Settings screen (read-only display for now).
Revisit if: user feedback suggests different defaults, or if per-user configurability is added in a future phase.

D-26: Service binding — Binder over intent-only
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: ViewModel communicates with PlaybackService via a bound service with `Binder`, not intent-only messaging.
Rationale: VM needs synchronous access to `isPlaying`, `setColor()`, `setGain()`. Intent-based communication is too asynchronous for continuous slider updates (~16ms touch events). Binder gives direct method calls on the service object.
Revisit if: multi-process architecture is needed (Binder is in-process only).

D-27: Notification style — plain notification over MediaStyle
Date: 2026-04-17 | Status: Closed
Priority: Nice-to-have
Decision: Use a plain notification with a single Stop action button. No `MediaStyle`, no `MediaSession`.
Rationale: The app needs only a stop button on the notification. MediaStyle adds lock-screen controls, Bluetooth metadata, and media routing — none of which add value for a white noise sleep app. Plain notification is simpler and sufficient.
Revisit if: users request lock-screen playback controls or Bluetooth display metadata.

D-28: Timer ownership — service-scoped over ViewModel-scoped
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: The countdown timer coroutine runs in the service's `CoroutineScope`, not `viewModelScope`. The ViewModel observes timer state from the service via binding.
Rationale: The timer must survive Activity destruction (screen-off, Home press). `viewModelScope` is cancelled when the Activity is destroyed; the service persists.
Revisit if: timer needs to survive service restarts (would require persistence + reschedule).

D-29: onTaskRemoved — stop audio on swipe-dismiss
Date: 2026-04-17 | Status: Closed
Priority: Nice-to-have
Decision: Override `onTaskRemoved()` to call `stopSelf()`, stopping audio when the user swipes the app from recents.
Rationale: Swiping from recents is an explicit dismissal gesture. Continuing audio after dismissal would be surprising and annoying.
Revisit if: users report wanting audio to survive task removal (unlikely for a sleep app).

D-30: Audio focus — request AUDIOFOCUS_GAIN
Date: 2026-04-17 | Status: Closed
Priority: Important
Decision: Request `AUDIOFOCUS_GAIN` on playback start, release on stop. Standard Android audio focus protocol.
Rationale: Without audio focus, other apps' audio can overlap with noise playback. Requesting focus is standard behavior for audio apps and ensures clean coexistence with calls, alarms, and other media.
Revisit if: users want noise to mix with other audio (would use `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` instead).

D-31: Texture DSP approach — zero-order hold (sample decimation)
Date: 2026-04-18 | Status: Closed
Priority: Important
Decision: TextureShaper uses variable-rate zero-order hold. At texture=0, every sample passes through (smooth). At texture=1, each sample is held/repeated for MAX_HOLD (6) frames before advancing (grainy). Inserted between SpectralShaper and GainSafety.
Rationale: Orthogonal to Color (changes temporal microstructure, not spectral tilt). Very cheap — one counter and one held-sample value, no allocations. Produces a clear, distinctive audible effect that users can perceive on a slider. At 44100 Hz with MAX_HOLD=6, the effective resolution drops to ~7350 Hz — distinctly grainy without being harsh. Alternatives considered: amplitude modulation with slow noise envelope — more organic but overlaps with micro-variation; variable-cutoff LPF — overlaps with Color control; both rejected.
Revisit if: perceptual tuning reveals the zero-order hold sounds too "digital" at moderate settings, in which case a first-order interpolated hold or noise-modulated smoothing could soften the effect.

D-32: Stereo decorrelation method — first-order all-pass on R channel
Date: 2026-04-18 | Status: Closed
Priority: Important
Decision: StereoStage applies a first-order all-pass filter to the mono signal to produce the R channel. L = mono, R = mono × (1 − width) + allpass(mono) × width. At width=0, identical channels (D-5 behavior). Width range 0.0–0.3 (restrained).
Rationale: All-pass preserves frequency content (spectral tilt from Color stays consistent between ears), produces natural-sounding width with minimal CPU cost (one state variable). Alternatives considered: second independent NoiseSource — doubles noise generation cost and creates a "two different sounds" feel; delayed copy — comb-filtering at short delays; both rejected.
Revisit if: headphone testing reveals the all-pass width is too subtle, in which case a short FIR decorrelation filter could add more spatial depth.

D-33: Micro-drift mechanism — slow LFO modulating Color offset
Date: 2026-04-18 | Status: Open
Priority: Nice-to-have
Decision: MicroDrift generates a slow LFO (0.02–0.1 Hz) that produces a small Color offset added to the user's Color value before SpectralShaper. Depth parameter [0, 1] scales the maximum offset (e.g., ±0.05 at depth=1.0). The effective Color is clamped to [0, 1].
Rationale: Creates a very slow, subtle tonal wandering that makes the noise feel more organic and alive without being attention-grabbing. Modulating Color reuses the existing spectral shaping pipeline — no new DSP topology needed. The drift rate is well below conscious perception (~15–60 second cycle).
Revisit if: the drift is perceptible as a rhythmic pattern (would need to switch from periodic LFO to random walk).

D-34: Fade duration configurability — picker with fixed options
Date: 2026-04-18 | Status: Open
Priority: Nice-to-have
Decision: Settings screen offers fade-in and fade-out duration pickers with options: 0s / 1s / 2s / 5s / 10s. Values persisted via PrefsStore. ViewModel reads at init and uses for fade orchestration. Current defaults (2s in, 5s out from D-25) remain the initial selection.
Rationale: Fixed options are simpler than a slider and cover the useful range. 0s allows instant start/stop for users who prefer it. 10s is the upper bound — longer fades are unusual for sleep noise.
Revisit if: users want finer control (switch to a slider) or custom values beyond 10s.

D-35: POST_NOTIFICATIONS permission flow — request on first play
Date: 2026-04-18 | Status: Open
Priority: Important
Decision: On API 33+, request `POST_NOTIFICATIONS` via `ActivityResultLauncher<String>` (rememberLauncherForActivityResult) when the user first taps Play. If denied, playback proceeds normally — the foreground service notification is invisible but playback still works.
Rationale: Requesting on first play is contextual — the user understands why the app needs notifications at that moment. Not blocking playback on denial keeps the app functional. The notification is helpful (shows Stop button) but not essential.
Revisit if: Android changes foreground service requirements to mandate visible notifications (would need to block playback on denial).
