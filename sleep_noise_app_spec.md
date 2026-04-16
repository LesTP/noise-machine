# Noise Machine Android App — Product and Technical Specification

## 1. Purpose

This app is a minimal Android sleep aid and ambient noise blocker. Its purpose is to generate a continuously tunable masking-noise signal that helps users:

- sleep more comfortably
- reduce awareness of environmental noise
- mask intermittent disturbances such as speech, traffic, neighbors, HVAC, or other ambient sounds

The intended feel is **appliance-like rather than toy-like**. The app should be calm, simple, and reliable for long unattended playback.

This is **not** primarily a "noise color reference" app, a synthesizer, or a broad ambient-sound playground. It is a **sleep-oriented masking noise generator** with a minimal interface and a technically sophisticated underlying engine.

---

## 2. Product Positioning

### Core product concept

A **minimal sleep-noise app with one main Color control**, backed by a real-time generated and continuously shaped stereo noise engine.

### What the app is optimizing for

- low-fatigue sound
- smooth transitions while adjusting controls
- long-session stability
- simple bedside usability
- sufficient masking power without excessive harshness

### What the app is not optimizing for

- educational display of exact white/pink/brown definitions
- a large number of visible controls
- mathematically perfect textbook implementations at the expense of usability
- elaborate visuals or exploration-heavy UX

---

## 3. UX Principles

### Primary UX principles

1. **Minimal surface UI**
   - Users should be able to open the app, press play, adjust one main parameter, optionally set a timer, and stop thinking about it.

2. **Sophisticated internals, simple exterior**
   - The audio engine may be complex internally, but the visible interface should remain sparse.

3. **No abrupt behavior**
   - No clicks, pops, zipper noise, or sudden timbral jumps.

4. **Good default sound matters more than many options**
   - Since most controls are hidden, the default tuning must already be pleasant and useful.

5. **Sleep-first interaction model**
   - Controls should be understandable and usable when tired, in the dark, or half-asleep.

---

## 4. Main User Interface

### Main screen

The main screen should be extremely simple.

#### Visible controls

- **Play / Stop** button
- **Color** slider (main sound-quality / sound-character control)
- **Timer** control (compact)
- **Settings** icon or entry point

#### Explicitly omitted from the main screen

- volume slider
- multiple noise-color presets
- stereo controls
- micro-variation controls
- advanced DSP parameters
- complicated visualizations

### Volume behavior

The app should use the phone's **native media volume controls**.

Reasons:

- less UI clutter
- users already understand hardware volume buttons
- avoids redundant gain controls
- keeps the app aligned with its minimal philosophy

The app may still internally apply a safe output ceiling and gain compensation, but this should not be a primary visible control.

---

## 5. Main Audible Control: Color Slider

### Important clarification

The main slider is **not just a simple high-frequency filter**.

A naive implementation as only a low-pass filter would likely:

- remove brightness
- make the sound progressively muffled
- fail to create a convincing continuum from white-like to pink-like to brown-like noise

### Correct conceptual model

The main control should instead represent **spectral tilt / Color / noise color balance**.

That means the slider continuously adjusts the balance between high and low frequency energy rather than merely cutting treble.

### Practical interpretation of the slider

The slider should move across a continuum:

- **Deep / Soft** on the right
- **Bright / Airy** on the left

### Internal meaning

The slider should drive a coordinated set of internal DSP parameters such as:

- spectral tilt amount
- high-frequency attenuation / retention
- low-frequency containment / sanity protection
- output normalization compensation
- perhaps smoothing behavior or other hidden tuning values

In other words, **one visible slider may control several internal parameters**.

### Why this is preferable

This preserves the minimal interface while allowing a much better-sounding engine than a simple filter sweep.

---

## 6. Noise Model

### Preferred model

The app should use:

- **one dynamically generated base noise source**
- **continuous spectral shaping**
- **smooth interpolation of control changes**

This is preferred over:

- three separate generators for white, pink, and brown noise
- hard mode switching between named noise colors
- simple crossfades between unrelated noise presets

### Reasoning

A continuous spectral-shaping model is a better fit because it:

- feels like one coherent sound family
- supports smooth transitions
- is more elegant technically
- better matches the one-slider UX
- avoids the fake feeling of crossfading between distinct sound personalities

### Named points

Named points such as white, pink, and brown may exist only as:

- optional internal reference targets
- optional subtle labels or tick marks
- optional settings-level educational metadata

They are **not required** as visible main UI modes.

---

## 7. Stereo Support

### Decision

The app should support **stereo output**.

### V1 stereo recommendation

For the initial version, stereo should be present but understated.

Recommended default behavior:

- stereo PCM output
- channels either identical or only very slightly decorrelated

### Why restrained stereo is preferable

Overly aggressive stereo width or motion can feel:

- distracting
- artificial
- headphone-specific
- attention-grabbing rather than calming

Since this is a sleep app, stereo should enhance spaciousness subtly rather than create active movement.

### Future extension

Advanced stereo width or decorrelation settings can be exposed later in Settings, not on the main screen.

---

## 8. Micro-Variation

### Decision

Micro-variation is considered promising, but should be **deferred** until the base engine is already solid.

### Definition

Micro-variation means extremely subtle, extremely slow changes that make the generated noise feel slightly less sterile over long listening sessions.

Possible future forms:

- tiny long-timescale drift in spectral tilt
- very mild decorrelation changes between channels
- very subtle texture drift

### Constraints

Micro-variation must never become:

- pulsing
- obvious modulation
- a moving effect
- an attention-grabbing feature

If implemented, it should be:

- shallow
- slow
- almost below conscious notice

### Product treatment

If added, it should live under Settings or advanced options, not on the main screen.

---

## 9. Texture as a Secondary Parameter

### Decision

A second user-facing parameter is acceptable, but it should be hidden in **Settings**, not shown prominently in the main interface.

### Best second parameter

The preferred second parameter is **Texture**.

### Why Texture is the right second dimension

Two noises can have the same overall Color or brightness while still feeling different:

- smooth / velvety / blended
- grainy / hissy / static-like

That distinction is meaningful for sleep use, but not important enough to deserve main-screen prominence.

### Texture control placement

Texture should be exposed in Settings as a secondary control.

Possible presentation:

- slider
- a small stepped control such as Smooth / Balanced / Grainy

### DSP interpretation of Texture

Texture may eventually affect aspects like:

- short-timescale roughness or smoothness
- amount of very high-frequency microstructure
- subtle filtering character
- fine perceived grain of the noise field

This can remain implementation-defined as long as the user-facing result is perceptually meaningful.

---

## 10. Timer

### Decision

A timer is important enough to likely appear on the main screen, but it should remain visually compact.

### Possible UI forms

- small Timer button opening a bottom sheet
- chip control such as Off / 30m / 1h / 2h
- compact single-row control

### Behavior

When the timer expires, the app should:

- begin a gradual fade-out
- stop playback cleanly after fade-out

### Why timer matters

For a sleep app, timer support is not just convenience; it is close to a core use-case feature.

---

## 11. Audio Engine Design

### Core stack

Recommended Android stack:

- **Jetpack Compose** for UI
- **ViewModel** for state management
- **Foreground Service** for long-running playback
- **AudioTrack** for PCM streaming output

### Why AudioTrack

This app needs real-time synthesis rather than file playback.

AudioTrack is appropriate because it allows:

- continuous PCM generation
- low-level output control
- real-time parameter changes while playing
- reliable long playback without depending on prerecorded assets

### Conceptual engine layers

1. **NoiseSource**
   - generates white noise samples

2. **SpectralShaper**
   - continuously shapes the spectrum according to Color

3. **TextureShaper** (future or optional)
   - adjusts perceived grain/smoothness

4. **ParameterSmoother**
   - ramps values gradually to avoid artifacts

5. **Gain / safety stage**
   - output normalization, clipping avoidance, fade handling

6. **Stereo stage**
   - initial stereo routing and later optional decorrelation

7. **PlaybackService**
   - handles playback lifecycle, notification, and background persistence

---

## 12. DSP Strategy

### Preferred DSP direction

Use:

- a **white-noise base generator**
- a **continuous spectral shaping stage**
- **smooth coefficient or parameter interpolation**

### Not preferred as the main approach

- separate white, pink, brown generators crossfaded together
- hard preset switching
- overcomplicated FFT-domain spectral shaping in the first version

### Why continuous shaping is preferred

It best matches the product's core requirement:

- one audible family of sound
- smoothly traversed
- without mode boundaries

### Likely practical implementation direction

A likely good implementation would use:

- white noise generation
- cascaded IIR-based shaping stages or equivalent low-cost real-time filtering
- coefficient interpolation or parameter-driven filter behavior

This is favored because it is:

- efficient
- suitable for real-time mobile playback
- flexible enough for a continuous slider
- easier to tune perceptually than a more academic or heavyweight solution

### The important target

The target is not strict mathematical perfection of white/pink/brown definitions.

The target is a sound that is:

- calm
- useful
- smooth across the full slider range
- pleasant over long listening periods

Perceptual quality matters more than textbook purity.

---

## 13. Color Slider Mapping: Internal vs Visible

### Visible behavior

The user sees one control: **Color**.

### Internal behavior

That one Color value may drive multiple hidden parameters simultaneously.

Examples:

- increase spectral downward tilt as Color moves darker
- reduce harsh high-frequency content
- constrain excessive low-frequency build-up
- compensate output level to reduce perceived loudness drift
- alter subtle shaping behavior near extremes

### Why this mapping is important

This allows a very simple UI while avoiding the common trap of having each point on the slider sound like a crude filter sweep.

The audible result should feel like one coherent family of masking-noise textures, not a brittle technical demo.

---

## 14. Long-Session Sleep Requirements

Because the app is meant for sleep, it must support unattended long playback.

### This implies

- playback continues with screen off
- playback survives app backgrounding
- no audible discontinuities during long operation
- no unnecessary memory churn or repeated allocation in the audio loop
- reliable stop/fade behavior when timer expires

### Product distinction

This is one of the main differences between a prototype and a real sleep aid.

---

## 15. Background Playback and Service Model

### Decision

The app should use a **foreground service** for audio playback.

### Why

Users will often:

- lock the phone
- switch apps
- leave playback running for a long time

A foreground service makes the app behave like a legitimate long-running audio app rather than a temporary in-activity demo.

### Service responsibilities

- own playback lifecycle
- keep engine alive during background use
- expose notification controls
- respond to play/stop and timer state
- handle cleanup cleanly

---

## 16. Artifact Prevention and Sound Quality Requirements

### Critical quality requirements

The app must avoid:

- clicks
- pops
- zipper noise
- sudden timbral changes
- unstable low-frequency drift
- clipping or harsh loudness jumps

### Therefore the implementation must include

- parameter smoothing
- output safety / normalization
- low-end containment / DC protection
- fade-in and fade-out handling

### Parameter smoothing

This is mandatory, especially for:

- Color slider changes
- future Texture changes
- timer fade-out
- any future stereo or variation parameters

Without smoothing, the app will feel brittle and low quality.

---

## 17. Low-End Control / Brown-Like Extremes

### Important note

Very dark or brown-like noise behavior can become problematic if implemented naively.

Possible problems:

- drift
- boomy low end
- muddiness
- speaker-unfriendly behavior
- unstable accumulation behavior

### Requirement

Even at the dark end of the Color range, the engine should apply sensible low-end control.

That may include:

- leaky rather than uncontrolled accumulation behavior
- DC blocking or equivalent protection
- bounded coefficient ranges
- careful tuning of the darkest region

The dark end should feel soft and deep, not broken or swampy.

---

## 18. Loudness / Level Consistency

### Problem

As spectral balance changes, perceived loudness often changes too.

Without compensation:

- darker sounds may feel heavier or louder in one way
- brighter sounds may feel sharper or louder in another
- the Color slider may end up acting like a hidden volume control

### Requirement

The engine should include some degree of output normalization or compensation so that the Color slider primarily changes **character**, not gross loudness.

Perfect loudness neutrality is not required, but wild level drift should be avoided.

---

## 19. Settings

### Candidate settings for early versions

- Texture
- fade-in duration
- fade-out duration
- default timer
- remember last setting (likely on by default)
- optional future stereo width / decorrelation
- optional future micro-variation toggle or amount
- optional future subtle reference labels for white/pink/brown

### Candidate settings to avoid exposing early

- too many technical DSP values
- exact filter terminology
- multiple educational noise-type toggles
- synthesizer-like expert panels

Settings should remain secondary and relatively sparse.

---

## 20. Default Behavior Recommendations

Recommended default runtime behavior:

- app restores the last used Color value
- app starts with a very short fade-in when playback begins
- timer expiration triggers a graceful fade-out
- playback continues with screen off
- media volume buttons control loudness natively
- the default Color position is slightly toward a pink-ish / soft balanced region rather than an extreme

---

## 21. Recommended Development Phases

### Phase 1: Core playback

Build:

- real-time white-noise generation
- AudioTrack output
- Play / Stop
- native volume behavior through system media volume

### Phase 2: Color engine

Add:

- continuous spectral shaping
- smoothed parameter control
- basic output normalization / safety
- tuning of full Color range

### Phase 3: Productization

Add:

- timer
- fade-in / fade-out
- settings screen
- persistence of last used state

### Phase 4: Background robustness

Add:

- foreground service
- notification controls
- reliable screen-off / background playback behavior

### Phase 5: Secondary polish

Add later as appropriate:

- Texture setting
- restrained stereo decorrelation options
- optional micro-variation
- anchor labels or presets only if truly useful

---

## 22. Decisions Made So Far

### Confirmed decisions

- The app is for **sleep aid / ambient noise blocking**.
- The audio should be **dynamically generated**, not based on mixing a few static preset sources.
- **Spectral shaping** is preferred over a three-source mix model.
- **Smooth transitions** are strongly preferred.
- **Named anchor points are optional**, not central.
- **Stereo is desired**, but can start restrained.
- **Micro-variation is acceptable in principle**, but should be deferred.
- The interface should be **minimal**.
- Main UI should include **Play/Stop**, one main **Color** control, and likely **Timer**.
- **Volume should use native phone volume controls**.
- A **Texture** parameter is acceptable, but should live in **Settings**, not on the main screen.
- A 2D pad or square-with-dot interface is **not preferred** as the main control model.

---

## 23. Final Product Summary

This app should be built as a **minimal Android sleep-noise application** whose visible experience is intentionally simple:

- play
- stop
- adjust one main Color slider
- optionally set a timer

Under that minimal surface, the app should use a more sophisticated real-time engine:

- dynamically generated white-noise base
- continuously variable spectral shaping
- smooth interpolation
- stereo output
- protection against artifacts and unstable low-end behavior
- room for a future Texture control and subtle micro-variation

The overall philosophy is:

**simple interface, calm product feel, strong defaults, and technically careful audio behavior optimized for sleep rather than novelty.**
