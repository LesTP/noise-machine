# Noise Machine

## Spark
> A minimal Android sleep-noise app that generates continuously tunable masking noise — appliance-like, not toy-like — for long unattended playback.

## What This Is
An Android application that acts as a sleep aid and ambient noise blocker. It generates a real-time stereo PCM noise signal whose spectral character is continuously shaped by a single visible Color control. The intended feel is calm and simple; the visible surface is deliberately sparse (Play/Stop, Color slider, timer, settings), while the audio engine underneath is technically careful: smoothed parameter changes, output safety, low-end containment, and foreground-service-backed playback for long unattended sessions.

## Audience
Adults who want a reliable, low-fatigue masking-noise generator for sleep — to reduce awareness of environmental sound such as speech, traffic, neighbors, or HVAC. The target user wants something bedside-usable in the dark when tired, not a synthesizer or an educational noise-color reference.

## Scope

### Core
- Real-time white-noise generation streamed through `AudioTrack` (no prerecorded assets).
- Continuous spectral shaping driven by a single Color slider (Deep/Soft ↔ Bright/Airy).
- Play / Stop control and a compact Timer with clean fade-out on expiry.
- Stereo output (restrained; channels identical or only very slightly decorrelated in V1).
- Foreground-service playback that survives screen-off, backgrounding, and long sessions.
- Artifact-free behavior: parameter smoothing, output normalization, DC / low-end protection, fade-in / fade-out.

### Flexible
- [in] Texture parameter (smooth ↔ grainy) exposed in Settings.
- [in] Fade-in and fade-out duration settings.
- [in] Persistence of last-used Color and timer selection.
- [in] Settings screen scaffolding.
- [deferred] Micro-variation (very slow subtle drift in spectral tilt or decorrelation).
- [deferred] Stereo decorrelation width exposed as a setting.
- [deferred] Named anchor labels / tick marks for white / pink / brown reference points.

### Exclusions
- No in-app volume slider — the app uses the phone's native media volume controls exclusively.
- No multi-preset noise-color buttons (white / pink / brown as distinct modes).
- No 2D pad or square-with-dot main control.
- No synthesizer-style expert panels or educational DSP terminology on the main screen.
- No FFT-domain spectral shaping in V1.
- No large crossfade-between-presets model — one coherent noise family, not a mix of unrelated sources.

## Constraints
- **Platform**: Android. Minimum/target API TBD during Phase 1.
- **Language / framework**: Kotlin, Jetpack Compose for UI, ViewModel for state, Foreground Service for playback lifecycle, `AudioTrack` for PCM streaming output.
- **DSP approach**: real-time IIR-style (or equivalent low-cost) cascaded shaping on a white-noise base, driven by smoothed coefficients. Not FFT-based in V1.
- **Performance**: must run continuously on mobile CPUs for many hours without memory churn, allocation in the audio render loop, or thermal issues.
- **Quality**: must be free of clicks, pops, zipper noise, sudden timbral jumps, unstable low-frequency drift, and loudness spikes across the entire Color range.
- **Deployment**: standalone Android app; no backend or network dependencies.

## Prior Art
- Common sleep / noise apps on Android and iOS — [watch] the majority expose multiple color presets and volume sliders; this project deliberately departs from that pattern in favor of one continuous Color control and native media volume. Direct competitive analysis is not yet performed.

## Success Criteria
- A user can open the app, press Play, adjust Color to taste, and leave playback running overnight with the screen off.
- A user can set a timer (e.g., 30 m / 1 h / 2 h), and playback fades out cleanly without clicks or pops at expiry.
- The user's only audible volume control is the phone's hardware / system media volume — in-app there is no visible gain slider.
- Moving the Color slider smoothly changes the spectral **character** of the noise without producing zipper noise, abrupt jumps, or large perceived loudness swings.
- Playback survives backgrounding, screen-off, and long sessions via a foreground service, with a notification users can act on.
- At the dark (brown-like) extreme, the output remains stable — soft and deep, not muddy, boomy, or drifting.

## Risks and Open Questions
- [must-resolve] Exact IIR cascade topology and coefficient schedule that produces a perceptually smooth Color continuum across the full range. Blocks detailed Phase 2 work.
- [must-resolve] Low-end containment strategy (leaky integrator vs. explicit DC blocker vs. bounded coefficients) at the dark extreme. Blocks Phase 2 tuning.
- [implementation] Loudness-compensation curve so Color slider changes character rather than gross loudness.
- [implementation] AudioTrack buffer sizing and render-loop allocation strategy to guarantee zero glitches over multi-hour playback.
- [implementation] Minimum Android API level and AudioTrack configuration (sample rate, channel mask, encoding) — resolvable during Phase 1.
- [watch] Scope creep on micro-variation — keep it deferred and never allow it to become a pulsing or attention-grabbing effect.
- [watch] Texture parameter definition — its DSP meaning is implementation-defined; risk is that it ends up redundant with Color.

## Extension Points
- Texture (second user-facing parameter) — additive; lives in Settings.
- Stereo decorrelation width — additive; hidden until explicitly enabled.
- Micro-variation drift (very slow, very shallow) — additive; Settings toggle.
- Named anchor labels or subtle tick marks on the Color slider — purely cosmetic additive.
- Future presets / session memories — potential additive layer over persisted Color state.

## Size Estimate
**Multi-module.** Meaningful internal boundaries exist between UI (Compose + ViewModel), playback lifecycle (Foreground Service), and the audio engine pipeline (NoiseSource → SpectralShaper → ParameterSmoother → GainSafety → StereoStage → AudioTrack). The audio engine alone has enough internal structure that per-module contracts will be useful.

---

## Change History
| Date | What Changed | Why |
|------|-------------|-----|
| 2026-04-16 | Initial PROJECT.md synthesized from `sleep_noise_app_spec.md` | Project bootstrap |
