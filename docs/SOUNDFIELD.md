# CatMusic sound field

The current (1.34.5) sound field is CatMusic's own full-screen lyric stage. It
replaces the earlier experimental scene with an implementation that is entirely
contained in this repository.

## Files

```text
app/src/main/assets/soundfield/
├── index.html   page structure, top bar, lyrics stage, transport, menu
├── style.css    dark glass UI, responsive lyric typography, portrait/landscape
├── field.js     original WebGL renderer, cover sampling, gestures, audio envelope
└── app.js       lyrics, playback, calibration, menu, gyroscope, native bridge
```

`SoundFieldScreen.kt` creates the offline WebView, loads
`file:///android_asset/soundfield/index.html?quality=…&fps=…`, and bridges the
page to Media3 and the local repository.

## Effects

### Star Tide (星潮)

- Roughly 10,000 base point sprites arranged in evenly spaced latitude rows.
  The immersive tier uses 64 rows and a circumference-proportional point count;
  all base points share the same size and shade. There are no enlarged glints
  or floating highlight dots.
- Each point takes its colour from a 64×64 centre-cropped preview of the
  current album artwork. The UV mapping is non-mirrored: at the front view the
  cover's left side appears on the left of the screen.
- Static interleaved buffer: position, cover colour, per-point seed, size and
  a reserved kind slot. Every particle currently uses the base kind; per-frame
  work is limited to uniforms.
- Eight decoded bands and a smoothed level breathe the shell. A spring-like
  beat envelope pushes points outward into a hollow cloud and pulls them back.
- The camera is a simple WebGL point-sprite projection; horizontal dragging
  rotates through a full turn, vertical dragging is clamped above the lower
  half of the globe, and pinching scales from 0.62× to 1.95×.
- A separate full-screen fragment shader paints the cover-tinted atmosphere,
  accent bloom, vignette and fine grain.

### Echo (回声)

- A perspective lattice of light ribbons, built from continuous base
  coordinates; row and column segments share a coherent phase so the surface
  never tears.
- Sine ripples, a cross wave and band-driven lift create the moving surface.
  A beat envelope adds an outward-travelling ring.
- Additive lines and larger glow beads are drawn from the same shader with a
  `uPointMode` uniform.
- The camera orbit is deliberately bounded to keep the finite lattice looking
  like a continuous horizon. Pinch zoom uses the same 0.62×–1.95× range.

## Audio and art

Kotlin sends the following to `window.catmusic`:

| Function | Payload |
|---|---|
| `lyrics` | `{hasTiming, lines:[{t,text,tr?}], loading?, missing?}` |
| `track` | `{title, artist}` |
| `palette` | `{accent:"#RRGGBB"}` |
| `artwork` | `{data:"data:image/jpeg;base64,..."}` from a local 96×96 preview |
| `playback` | `{position, duration, playing, offset}` |
| `audio` | `{bands:[8], beat, level, playing}` at about 20 Hz |
| `safeArea` | `{top,bottom,left,right}` in CSS pixels |
| `gyroState` | `{enabled, available, reset}` |
| `orientation` | `{p, r}` pitch/roll in radians |

The page calls `CatMusicBridge.ready / close / toggle / seek / setOffset /
importLyrics / toggleAudioMode / setGyroEnabled`. All calls are local; the
WebView has network loads blocked.

The artwork preview is generated on an IO dispatcher in Kotlin and never
exposes arbitrary app files. If a track has no artwork, the page derives a
palette from the deterministic accent supplied by the app instead.

## Lyrics and gestures

- The active line is found by binary search over `position - offset`. Positive
  offsets delay the lyrics; tapping a line seeks to `line.t + offset`.
- Vertical dragging on a timed lyric enters a seek preview: up selects a later
  line, down an earlier one. A small HUD shows the target `m:ss`; releasing
  keeps the preview and a tap commits. Three seconds without a tap returns to
  the live line. Holding a finger down pauses the countdown.
- The HUD lives outside the masked lyrics stage so the top/bottom fade does not
  clip it. The native Compose lyric page mirrors the same drag/tap contract.
- The visible row count is adjustable from 3 to 10 and the font size is
  recalculated from the actual lyric-stage height.
- The active line and its translation ride a short PCM beat envelope; the
  whole lyric plane receives a very small gyro-driven tilt so it stays
  readable while the field moves.
- Dragging the field starts outside interactive UI (menu, transport, buttons,
  lyric lines). The renderer keeps one pointer table and supports two-finger
  scaling. The page stops its animation loop when hidden.
- Android back first closes the in-page menu or calibration panel. Only a
  second back leaves the sound field.

## Quality and persistence

| Setting | Values |
|---|---|
| Quality | `light`, `balanced`, `immersive` (default), `ultra` |
| Internal DPR cap | 1.6×, 1.8×, 2.25×, 2.5× |
| Star Tide rows × base points | 48×176, 56×190, 64×200, 72×200 |
| Frame rate | 30 / 45 / 60 / 90 / 120 / unlimited |
| Lyric rows | 3–10 |
| Beat strength | 0–100% |
| Gyro sensitivity | 0–200% |

Saved locally in the WebView:

- `catmusic.soundfield.effect.v2` — `orbit` or `tide`
- `catmusic.soundfield.view.v2.<effect>` — yaw, tilt, zoom
- `catmusic.soundfield.beat.v2`
- `catmusic.soundfield.lyricLines.v2`
- `catmusic.soundfield.gyro.v2`
- `catmusic.soundfield.gyroSensitivity.v2`

The page also reads the old `catmusic.lyric.*` and
`catmusic.soundfield.effect.v1` keys when the new ones are absent, so upgrades
from the previous WebView implementation keep the user's tuning where possible.

## Performance

- Geometry buffers are built on load or track change and cached; every frame
  only updates uniforms and draws.
- Device pixel ratio is capped by the quality tier.
- The target frame rate is parsed from the URL and enforced by the animation
  loop. The page pauses rendering when `document.hidden` is true.
- If WebGL cannot initialize or the page does not signal `ready` within 12
  seconds, the app falls back to the native lyric screen rather than showing a
  black stage.

## Licensing

This implementation is original CatMusic code under the repository's PolyForm
Noncommercial 1.0.0 license. It contains no third-party scene code. The
screenshots in this repository use synthetic artwork and demo lyrics.
