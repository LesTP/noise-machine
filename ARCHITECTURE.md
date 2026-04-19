# Noise Machine — Architecture

## Component Map
| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| UI (Compose) | Renders main screen (Color slider, Play/Stop, Timer chip, Settings entry) and Settings screen | ViewModel |
| ViewModel | Holds observable UI state, maps user events to engine parameter changes, persists last-used values | PlaybackService |
| PlaybackService (foreground) | Owns playback lifecycle; notification; survives backgrounding and screen-off; routes play/stop/timer | AudioEngine |
| AudioEngine | Orchestrates the render pipeline, owns the `AudioTrack` instance, runs the render loop | NoiseSource, SpectralShaper, TextureShaper, GainSafety, StereoStage, MicroDrift, ParameterSmoother |
| NoiseSource | Produces white-noise samples on demand (per-frame, allocation-free) | none |
| SpectralShaper | Applies continuous Color-driven cascaded IIR shaping to the noise stream | ParameterSmoother |
| TextureShaper | Zero-order hold sample decimation for smooth↔grainy control | none |
| ParameterSmoother | Ramps exposed parameters (Color, Texture, gain, stereo width, fade) to avoid zipper noise and discontinuities | none |
| GainSafety | Output normalization across Color range, DC/low-end protection, clipping avoidance | none |
| StereoStage | First-order all-pass decorrelation for mono→stereo with variable width | none |
| MicroDrift | Slow LFO (0.05 Hz) producing subtle Color offset for tonal wandering | none |
| Persistence | Stores last Color and timer selection (SharedPreferences behind PrefsStore interface) | none |

## Data Flow

### Core Objects
- **ColorValue** — `Float` in [0.0, 1.0]. 0.0 = bright/airy, 1.0 = deep/soft. Drives a vector of internal coefficients via `EngineParams`.
- **TextureValue** — `Float` in [0.0, 1.0]. 0.0 = smooth, 1.0 = grainy (zero-order hold decimation).
- **TimerState** — `sealed`: `Off | Armed(remainingMs)`.
- **EngineParams** — derived from ColorValue via a mapping function: spectral-tilt amount, HF attenuation, LF containment factor, output-level compensation. Not user-visible.
- **AudioFrame** — interleaved stereo `ShortArray` buffer sized to the `AudioTrack` write quantum.
- **PlaybackState** — `sealed`: `Idle | FadingIn | Playing | FadingOut`.

### Flow
```
User event (slider/play/timer)
   → UI (Compose)
   → ViewModel (updates observable state + persists)
   → PlaybackService (start/stop/timer arming)
   → AudioEngine.render loop, each buffer:
       NoiseSource.fill(buf)
       → SpectralShaper.process(buf, color + MicroDrift.nextBlock())
       → TextureShaper.process(buf, texture)
       → GainSafety.process(buf, color)
       → masterGain multiply (ParameterSmoother)
       → StereoStage.processToStereo(mono, stereo, width)
       → AudioSink.write(stereo)
```
The render loop runs on a dedicated high-priority audio thread owned by AudioEngine. Parameter changes never touch audio-thread state directly — they are handed off through ParameterSmoother's lock-free ramp targets.

## Interaction Model

### User Actions
- Press Play / Stop.
- Drag Color slider (continuous).
- Select Timer option (Off / 30 m / 1 h / 2 h or similar).
- Open Settings.
- (In Settings) adjust Texture, fade durations; toggle deferred advanced options if present.

### UI States
- **Idle** — Play button shown; Color slider active but silent; timer chip shows current selection.
- **Playing** — Stop button shown; Color slider continues to adjust live audio; timer shows armed / countdown.
- **FadingOut** — Stop button disabled or shown as fading; timer shows short countdown remaining.
- **Settings** — secondary screen; main screen paused visually but audio state unaffected.

### Layout Zones
- **Top**: app title / minimal status, Settings icon.
- **Center**: large Color slider (primary control, calm visual).
- **Bottom**: Play/Stop button and compact Timer chip.

## Implementation Sequence
| Order | Module | Rationale | Status |
|-------|--------|-----------|--------|
| 1 | Phase 1 — Core playback: NoiseSource + AudioTrack + Play/Stop | Proves the real-time PCM output path before any DSP complexity | Phase 1 complete |
| 2 | Phase 2 — Color engine: SpectralShaper + ParameterSmoother + GainSafety + tuning | Primary product feature; locks in the audible quality bar | Phase 2 complete |
| 3 | Phase 3 — Productization: Timer, fade-in/fade-out, Settings skeleton, persistence | Table stakes for a sleep app | Complete |
| 4 | Phase 4 — Background robustness: Foreground service + notification + screen-off behavior | Required for long unattended sessions | Complete |
| 5 | Phase 5 — Secondary polish: Texture, stereo decorrelation, micro-drift, fade config, About, permission | Deferred enhancements that must not break the calm default feel | In progress |

## Coupling Notes
- **SpectralShaper ↔ ParameterSmoother** — tight. Any change to how Color maps to internal coefficients must go through ParameterSmoother to preserve artifact-free transitions. Watch: changes to coefficient schedule require re-tuning smoother ramp times.
- **GainSafety ↔ SpectralShaper** — tight perceptually. Shifts in shaping character move perceived loudness; GainSafety's compensation curve must be re-validated whenever shaping changes.
- **AudioEngine ↔ PlaybackService** — loose. Service owns lifecycle; engine exposes a narrow start/stop/setParam surface.
- **UI ↔ ViewModel** — loose (standard Compose state observation).
- **StereoStage ↔ AudioEngine** — additive; first-order all-pass decorrelation with ParameterSmoother-controlled width. Width=0 produces identical L/R (mono).
- **TextureShaper ↔ AudioEngine** — additive; sits between SpectralShaper and GainSafety. Texture=0 is passthrough.
- **MicroDrift ↔ AudioEngine** — additive; slow LFO offset added to Color before SpectralShaper. Depth=0 is no drift.

## Key Decisions

**D-1: Continuous spectral shaping over multi-source mixing**
Date: 2026-04-16 | Status: Closed
Decision: Use one white-noise base generator with continuous spectral shaping rather than crossfading between separate white/pink/brown generators.
Rationale: Produces one coherent noise family, supports smooth slider transitions, matches the one-slider UX, is more efficient, and avoids the "fake crossfade between personalities" feel. (Spec §6, §12.)
Revisit if: perceptual tuning proves unachievable with real-time IIR cascades and we need FFT-domain shaping.

**D-2: Color slider drives multiple internal parameters**
Date: 2026-04-16 | Status: Closed
Decision: The single visible Color slider maps to a coordinated vector of internal parameters (spectral tilt, HF attenuation, LF containment, output-level compensation).
Rationale: Keeps the UI minimal while avoiding the trap of a simple filter sweep. (Spec §5, §13.)
Revisit if: users report the slider feels ambiguous or non-monotonic in character.

**D-3: Native media volume only — no in-app volume slider**
Date: 2026-04-16 | Status: Closed
Decision: Volume is controlled exclusively via the phone's hardware / system media volume. Engine may apply an internal safety ceiling but exposes no visible gain control.
Rationale: Less UI clutter; hardware volume already well-understood; avoids redundant controls. (Spec §4.)
Revisit if: users consistently report difficulty achieving desired listening levels.

**D-4: Foreground service for playback**
Date: 2026-04-16 | Status: Closed
Decision: Playback runs in an Android Foreground Service with a persistent notification.
Rationale: Required for reliable long-session playback with screen off and backgrounding; makes the app behave like a legitimate long-running audio app. (Spec §15.)
Revisit if: Android APIs change or a more appropriate lifecycle primitive emerges.

**D-5: Stereo width — user-controlled slider**
Date: 2026-04-16 | Status: Closed (updated 2026-04-19)
Decision: Stereo width is a continuous slider (0.0–1.0) in Settings. Default is 0 (mono). User controls the amount of all-pass decorrelation. Max width (1.0) is intentionally "too much" so users can find their preferred level.
Rationale: Original V1 decision was "restrained, toggle only." On-device testing showed the fixed 0.3 toggle was too subtle. A slider gives full user control while defaulting to calm mono.
Revisit if: users report confusion about what the slider does.

## Provisional Contracts
- **SpectralShaper coefficient schedule** — resolved in D-16: 2 biquad sections (low-shelf 250 Hz + high-shelf 2500 Hz), Color-driven gains (linear-in-dB). Initial curve adequate per on-device testing.
- **Low-end containment** — resolved in D-17: first-order DC blocker (~20 Hz) in GainSafety via leaky integrator subtraction.
- **Texture DSP definition** — resolved in D-31: zero-order hold sample decimation. Orthogonal to Color, allocation-free.
- **AudioTrack buffer configuration** — sample rate (44100 Hz, D-7), buffer size, and write-mode tuning resolved in Phase 1. Multi-hour glitch-free playback to be validated in Phase 4.
