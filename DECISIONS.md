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

D-7: Sample rate 44100 Hz (provisional)
Date: 2026-04-16 | Status: Open (provisional)
Priority: Important
Decision: Provisional 44100 Hz, 16-bit PCM, stereo. To be revisited during Step 3 (AudioEngine).
Rationale: Widely supported on Android hardware; device-native rate may differ and will be queried via `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` during Step 3.
Revisit in: Step 3.

D-8: AudioTrack buffer size = 2× minBufferSize (provisional)
Date: 2026-04-16 | Status: Open (provisional)
Priority: Important
Decision: Provisional buffer size of `AudioTrack.getMinBufferSize() * 2` as a glitch-margin starting point. To be tuned during Step 3 and validated for multi-hour stability during Phase 4.
Rationale: Minimum buffer is an under-run threshold, not a comfortable operating point; 2× is the common safety multiplier for continuous generators. Too large wastes memory and increases parameter-change latency; too small invites under-runs on load spikes.
Revisit in: Step 3 (initial tuning) and Phase 4 (long-session stability).

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
