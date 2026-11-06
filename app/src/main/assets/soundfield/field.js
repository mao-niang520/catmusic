/*
 * CatMusic sound field — an original WebGL stage for the full-screen lyric page.
 *
 * Two effects share one canvas and one palette:
 *   orbit — roughly ten thousand cover-coloured points in even latitude rings.
 *   tide  — a perspective lattice of light ribbons that ripples with the low end.
 *
 * The renderer has no third-party scene, shader or geometry code. It receives
 * only local audio bands, an accent colour and a tiny cover preview through the
 * page bridge. Rendering stops while the page is hidden.
 */
(() => {
  "use strict";

  const canvas = document.getElementById("field");
  if (!canvas) return;

  const EFFECT_ORBIT = "orbit";
  const EFFECT_TIDE = "tide";
  const STORAGE_EFFECT = "catmusic.soundfield.effect.v2";
  const STORAGE_LEGACY_EFFECT = "catmusic.soundfield.effect.v1";
  const STORAGE_VIEW = "catmusic.soundfield.view.v2.";
  const TAU = Math.PI * 2;

  const params = new URLSearchParams(window.location.search);
  const qualityKey = params.get("quality") || "immersive";
  const QUALITY = {
    light: {
      orbRings: 48,
      orbMeridians: 176,
      tideRows: 54,
      tideCols: 128,
      tideColumnStep: 12,
      pointStepRow: 3,
      pointStepCol: 4,
      dpr: 1.6
    },
    balanced: {
      orbRings: 56,
      orbMeridians: 190,
      tideRows: 64,
      tideCols: 144,
      tideColumnStep: 10,
      pointStepRow: 2,
      pointStepCol: 3,
      dpr: 1.8
    },
    immersive: {
      orbRings: 64,
      orbMeridians: 200,
      tideRows: 78,
      tideCols: 160,
      tideColumnStep: 8,
      pointStepRow: 2,
      pointStepCol: 2,
      dpr: 2.25
    },
    ultra: {
      orbRings: 72,
      orbMeridians: 200,
      tideRows: 92,
      tideCols: 184,
      tideColumnStep: 7,
      pointStepRow: 2,
      pointStepCol: 2,
      dpr: 2.5
    }
  };
  const config = QUALITY[qualityKey] || QUALITY.immersive;

  let fpsMode = (params.get("fps") || "adaptive").trim().toLowerCase();
  let adaptiveFps = fpsMode === "adaptive";
  let targetFps = adaptiveFps
    ? 0
    : (fpsMode === "unlimited" || fpsMode === "0"
      ? 0
      : Math.min(240, Math.max(24, parseInt(fpsMode, 10) || 60)));
  let frameInterval = targetFps > 0 ? 1000 / targetFps : 0;
  let lastBoostAt = 0;

  /**
   * Adaptive mode runs at 24 fps while paused, 30 while playing, and briefly
   * unlocks 60 fps for gestures, track changes and lyric line changes.
   */
  function boostFrameRate(durationMs = 1100) {
    const now = performance.now();
    if (now - lastBoostAt < 48) return;
    lastBoostAt = now;
    const until = now + Math.max(180, Number(durationMs) || 1100);
    if (until > state.frameBoostUntil) state.frameBoostUntil = until;
  }

  function setFrameRate(value) {
    const next = String(value == null ? "adaptive" : value).trim().toLowerCase();
    fpsMode = next;
    adaptiveFps = next === "adaptive";
    const parsed = parseInt(next, 10);
    targetFps = (!adaptiveFps && next !== "unlimited" && next !== "0" &&
      Number.isFinite(parsed))
      ? Math.min(240, Math.max(24, parsed))
      : 0;
    frameInterval = targetFps > 0 ? 1000 / targetFps : 0;
    state.frameAccumulator = adaptiveFps ? 0 : frameInterval;
    state.lastFrameAt = 0;
    boostFrameRate(1200);
    if (state.visible) start();
  }

  function frameIntervalFor(now) {
    if (!adaptiveFps) return frameInterval;
    const fps = now < state.frameBoostUntil
      ? 60
      : (state.audioTarget.playing ? 30 : 24);
    return 1000 / fps;
  }

  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const mix = (a, b, t) => a + (b - a) * t;
  const fract = value => value - Math.floor(value);

  function hash01(value) {
    return fract(Math.sin(value * 127.1 + 311.7) * 43758.5453123);
  }

  function readEffect() {
    try {
      const saved = window.localStorage.getItem(STORAGE_EFFECT);
      if (saved === EFFECT_ORBIT || saved === EFFECT_TIDE) return saved;
      const legacy = window.localStorage.getItem(STORAGE_LEGACY_EFFECT);
      if (legacy === "terrain") return EFFECT_TIDE;
      if (legacy === "particles") return EFFECT_ORBIT;
    } catch (error) {
      /* localStorage can be unavailable in a private WebView. */
    }
    return EFFECT_ORBIT;
  }

  function saveEffect(effect) {
    try {
      window.localStorage.setItem(STORAGE_EFFECT, effect);
    } catch (error) {
      /* The current session still works without persistence. */
    }
  }

  function readView(effect) {
    try {
      const raw = window.localStorage.getItem(STORAGE_VIEW + effect);
      const parsed = raw ? JSON.parse(raw) : null;
      if (parsed && typeof parsed === "object") {
        return {
          yaw: clamp(Number(parsed.yaw) || 0, -TAU * 2, TAU * 2),
          tilt: clamp(Number(parsed.tilt) || 0, -0.72, 0.72),
          zoom: clamp(Number(parsed.zoom) || 1, 0.62, 1.95)
        };
      }
    } catch (error) {
      /* Ignore a malformed saved view. */
    }
    return { yaw: 0, tilt: 0, zoom: 1 };
  }

  function saveView(effect) {
    try {
      const view = state.views[effect];
      window.localStorage.setItem(STORAGE_VIEW + effect, JSON.stringify({
        yaw: Math.round(view.yaw * 1000) / 1000,
        tilt: Math.round(view.tilt * 1000) / 1000,
        zoom: Math.round(view.zoom * 1000) / 1000
      }));
    } catch (error) {
      /* noop */
    }
  }

  const state = {
    effect: readEffect(),
    ready: false,
    gl: null,
    glPrograms: null,
    glBuffers: null,
    glLost: false,
    glUnavailable: false,
    orbData: null,
    orbCount: 0,
    tideLines: null,
    tideLineCount: 0,
    tidePoints: null,
    tidePointCount: 0,
    pixels: null,
    pixelSize: 64,
    artwork: null,
    accent: [255, 170, 109],
    palette: {
      top: [52, 31, 26],
      mid: [25, 16, 18],
      bottom: [7, 6, 9]
    },
    audioTarget: {
      bands: new Float32Array(8),
      beat: 0,
      level: 0,
      playing: false
    },
    bands: new Float32Array(8),
    level: 0,
    beatEnergy: 0,
    burst: 0,
    beatIntensity: 0.65,
    time: 0,
    lastTime: 0,
    lastFrameAt: 0,
    frameAccumulator: 0,
    frameBoostUntil: 0,
    lastBeatAt: -10,
    drawnFrames: 0,
    width: 0,
    height: 0,
    dpr: 1,
    landscape: false,
    views: {
      orbit: readView(EFFECT_ORBIT),
      tide: readView(EFFECT_TIDE)
    },
    orbitSpin: 0,
    orbitYawVelocity: 0,
    lastOrbitDragAt: -10,
    orbitAutoAt: 0,
    gyroYaw: 0,
    gyroPitch: 0,
    gyroYawTarget: 0,
    gyroPitchTarget: 0,
    pointers: new Map(),
    drag: null,
    pinchDistance: 0,
    pinchZoom: 1,
    raf: 0,
    visible: !document.hidden,
    manualHidden: false,
    dirtyOrb: true,
    dirtyTide: true
  };

  /* ------------------------------------------------------------------ */
  /* Palette helpers                                                     */
  /* ------------------------------------------------------------------ */

  const ORB_VERTEX = `
    precision highp float;
    attribute vec3 aPosition;
    attribute vec3 aColor;
    attribute vec3 aSeed;
    attribute float aSize;
    attribute float aKind;

    uniform vec2 uResolution;
    uniform vec2 uCenter;
    uniform float uRadius;
    uniform float uDpr;
    uniform float uYaw;
    uniform float uTilt;
    uniform float uTime;
    uniform float uLevel;
    uniform float uBurst;
    uniform float uPlaying;
    uniform float uB0;
    uniform float uB1;
    uniform float uB2;
    uniform float uB3;
    uniform float uB4;
    uniform float uB5;
    uniform float uB6;
    uniform float uB7;

    varying mediump vec3 vColor;
    varying mediump float vAlpha;
    varying mediump float vKind;

    float bandValue(float y) {
      float index = clamp((y * 0.5 + 0.5) * 7.999, 0.0, 7.999);
      if (index < 1.0) return uB0;
      if (index < 2.0) return uB1;
      if (index < 3.0) return uB2;
      if (index < 4.0) return uB3;
      if (index < 5.0) return uB4;
      if (index < 6.0) return uB5;
      if (index < 7.0) return uB6;
      return uB7;
    }

    void main() {
      float band = bandValue(aPosition.y);
      float energy = clamp(uLevel * 1.16 + uB0 * 0.38 + uB2 * 0.16, 0.0, 1.0);
      float breathe = energy * 0.09 + band * 0.11;
      float wave = sin(aPosition.y * 7.6 + uTime * (0.9 + energy * 1.5)
                       + aSeed.x * 2.1);
      float ripple = wave * (0.004 + uBurst * 0.070 + energy * 0.035);
      float impulse = uBurst * (0.40 + 0.60 * smoothstep(-0.88, 0.90, aSeed.z));
      float flare = smoothstep(0.20, 1.0, aSeed.x) * energy * 0.14;
      float radius = 1.0 + breathe + ripple +
                     impulse * (0.36 + aSeed.x * 0.28 + 0.16 * (aPosition.y * 0.5 + 0.5)) +
                     flare;

      vec3 point = aPosition * radius;
      vec3 outward = normalize(aPosition + aSeed * 0.30);
      point += outward * uBurst * (0.045 + 0.25 * abs(aSeed.z));

      vec3 tangent = normalize(cross(aPosition, vec3(0.0, 1.0, 0.0)) +
                               vec3(0.0, 0.0, 0.0001));
      point += tangent * sin(aSeed.z * 6.2831 + uTime * 0.44 +
                              aPosition.y * 3.2) *
               (uBurst * 0.052 + energy * 0.015);

      float cy = cos(uYaw);
      float sy = sin(uYaw);
      point = vec3(cy * point.x + sy * point.z, point.y,
                   -sy * point.x + cy * point.z);
      float ct = cos(uTilt);
      float st = sin(uTilt);
      point = vec3(point.x, ct * point.y - st * point.z,
                   st * point.y + ct * point.z);

      float cameraZ = 3.35;
      float viewZ = max(cameraZ - point.z, 0.72);
      float perspective = cameraZ / viewZ;
      vec2 screen = uCenter + vec2(point.x * uRadius * perspective,
                                   -point.y * uRadius * perspective);
      gl_Position = vec4(screen.x / uResolution.x * 2.0 - 1.0,
                         1.0 - screen.y / uResolution.y * 2.0, 0.0, 1.0);

      float depth = smoothstep(-0.76, 0.82, point.z);
      float size = abs(aSize) * uDpr * perspective *
        (0.92 + band * 0.26 + uLevel * 0.32 + uBurst * 0.20);
      gl_PointSize = clamp(size, 1.0, 44.0 * uDpr);
      vColor = aColor * (0.68 + depth * 0.30 + uBurst * 0.05);
      vAlpha = (0.14 + depth * 0.86) *
               (0.86 + band * 0.12) *
               (0.64 + 0.36 * uPlaying);
      vKind = aKind;
    }
  `;

  const ORB_FRAGMENT = `
    precision mediump float;
    varying mediump vec3 vColor;
    varying mediump float vAlpha;
    varying mediump float vKind;
    void main() {
      vec2 q = gl_PointCoord * 2.0 - 1.0;
      float d = dot(q, q);
      if (d > 1.0) discard;
      float core = pow(1.0 - d, 1.55);
      float halo = exp(-d * 3.5);
      vec3 color = vColor * (0.82 + core * 0.45);
      // Keep the highlight inside the cover's own hue instead of washing every
      // particle toward the same warm white.
      color += vColor * core * core * 0.12;
      float alpha = vAlpha * (0.14 + core * 0.86);
      if (vKind > 0.5) {
        alpha = vAlpha * halo;
      }
      gl_FragColor = vec4(color, alpha);
    }
  `;

  const TIDE_VERTEX = `
    precision highp float;
    attribute vec2 aPlane;
    attribute vec3 aColor;
    attribute float aPhase;

    uniform vec2 uResolution;
    uniform vec2 uScreenCenter;
    uniform vec3 uCamPos;
    uniform vec3 uCamForward;
    uniform vec3 uCamRight;
    uniform vec3 uCamUp;
    uniform vec4 uBounds;
    uniform float uFocal;
    uniform float uDpr;
    uniform float uTime;
    uniform float uLevel;
    uniform float uBurst;
    uniform float uPlaying;
    uniform float uPointMode;
    uniform float uB0;
    uniform float uB1;
    uniform float uB2;
    uniform float uB3;
    uniform float uB4;
    uniform float uB5;
    uniform float uB6;
    uniform float uB7;

    varying mediump vec3 vColor;
    varying mediump float vAlpha;
    varying mediump float vPointMode;

    float bandValue(float x) {
      float index = clamp(x * 7.999, 0.0, 7.999);
      if (index < 1.0) return uB0;
      if (index < 2.0) return uB1;
      if (index < 3.0) return uB2;
      if (index < 4.0) return uB3;
      if (index < 5.0) return uB4;
      if (index < 6.0) return uB5;
      if (index < 7.0) return uB6;
      return uB7;
    }

    void main() {
      float x = aPlane.x;
      float z = aPlane.y;
      float normX = (x - uBounds.x) / (uBounds.y - uBounds.x);
      float normZ = (z - uBounds.z) / (uBounds.w - uBounds.z);
      float band = bandValue(clamp(normX * 0.72 + normZ * 0.28, 0.0, 1.0));
      float radial = length(vec2(x * 0.52, z + 0.82));
      float ripple = sin(radial * 5.7 - uTime * 1.18 + aPhase * 0.9) *
                     (0.110 + uB0 * 0.185 + uLevel * 0.120);
      float crossWave = sin(x * 1.86 + z * 3.72 + uTime * 0.63 +
                            aPhase * 2.0) *
                        (0.058 + uB1 * 0.085);
      float ring = sin(radial * 9.2 - uBurst * 3.4 + aPhase * 1.3) *
                   uBurst * 0.255 * exp(-radial * 0.22);
      float bandLift = sin(x * 2.3 - z * 3.0 + uTime * 0.70 +
                           aPhase * 3.1) * band * 0.100;
      float y = ripple + crossWave + ring + bandLift;

      vec3 rel = vec3(x, y, z) - uCamPos;
      float depth = dot(rel, uCamForward);
      if (depth <= 0.12) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vColor = vec3(0.0);
        vAlpha = 0.0;
        vPointMode = uPointMode;
        return;
      }
      float sx = dot(rel, uCamRight);
      float sy = dot(rel, uCamUp);
      vec2 screen = uScreenCenter + vec2(sx, -sy) / depth * uFocal;
      gl_Position = vec4(screen.x / uResolution.x * 2.0 - 1.0,
                         1.0 - screen.y / uResolution.y * 2.0, 0.0, 1.0);

      float edge = smoothstep(0.0, 0.13, normX) *
                   smoothstep(0.0, 0.13, 1.0 - normX) *
                   smoothstep(0.0, 0.15, normZ) *
                   smoothstep(0.0, 0.10, 1.0 - normZ);
      float depthFade = exp(-max(depth - 2.3, 0.0) * 0.20);
      float glow = 0.48 + uLevel * 0.26 + uBurst * 0.24 + band * 0.08;
      vColor = aColor * (0.70 + band * 0.20 + uBurst * 0.14);
      vAlpha = edge * depthFade * glow * (uPointMode > 0.5 ? 0.88 : 0.41);
      gl_PointSize = clamp((1.35 + aPhase * 2.9) * uDpr * (3.1 / depth) *
                           (1.0 + uLevel * 0.65 + uBurst * 0.75),
                           1.0, 28.0 * uDpr);
      vPointMode = uPointMode;
    }
  `;

  const TIDE_FRAGMENT = `
    precision mediump float;
    varying mediump vec3 vColor;
    varying mediump float vAlpha;
    varying mediump float vPointMode;
    void main() {
      if (vPointMode > 0.5) {
        vec2 q = gl_PointCoord * 2.0 - 1.0;
        float d = dot(q, q);
        if (d > 1.0) discard;
        float core = pow(1.0 - d, 1.45);
        gl_FragColor = vec4(vColor * (0.82 + core * 0.72),
                            vAlpha * (0.10 + core * 0.90));
      } else {
        gl_FragColor = vec4(vColor, vAlpha);
      }
    }
  `;

  const BG_VERTEX = `
    attribute vec2 aQuad;
    varying vec2 vUv;
    void main() {
      vUv = aQuad * 0.5 + 0.5;
      gl_Position = vec4(aQuad, 0.0, 1.0);
    }
  `;

  const BG_FRAGMENT = `
    precision mediump float;
    varying vec2 vUv;
    uniform vec3 uTop;
    uniform vec3 uMid;
    uniform vec3 uBottom;
    uniform vec3 uAccent;
    uniform vec2 uCenter;
    uniform float uAspect;
    uniform float uLevel;
    uniform float uBurst;
    uniform float uTime;

    float hash21(vec2 p) {
      p = fract(p * vec2(123.34, 456.21));
      p += dot(p, p + 45.32);
      return fract(p.x * p.y);
    }

    void main() {
      vec2 q = (vUv - vec2(0.5)) * vec2(uAspect, 1.0);
      float r = length(q);
      float vertical = smoothstep(0.0, 1.0, vUv.y);
      vec3 color = uBottom * 0.26;
      color += uMid * (0.10 + vertical * 0.06);
      color += uTop * (0.045 + smoothstep(0.35, 1.0, vUv.y) * 0.05);

      vec2 c = (vUv - uCenter) * vec2(uAspect, 1.0);
      float core = exp(-dot(c, c) * 2.55);
      color += uAccent * core * (0.085 + uLevel * 0.075 + uBurst * 0.060);

      float veil = sin((vUv.x * 1.6 + vUv.y * 0.7) * 6.2831 + uTime * 0.055);
      color += uTop * smoothstep(0.45, 1.0, veil) * 0.026;
      vec2 offset = q - vec2(0.94, 0.44);
      color += uMid * exp(-dot(offset, offset) * 2.2) * 0.075;
      offset = q - vec2(-0.88, -0.34);
      color += uBottom * exp(-dot(offset, offset) * 3.0) * 0.11;

      float vignette = 1.0 - smoothstep(0.34, 1.52, r);
      color *= mix(0.27, 1.0, vignette);
      color += (hash21(gl_FragCoord.xy + floor(uTime * 30.0)) - 0.5) * 0.007;
      gl_FragColor = vec4(max(color, vec3(0.0)), 1.0);
    }
  `;

  function setFallbackBackground() {
    const accent = state.accent;
    const mid = state.palette.mid;
    canvas.style.background =
      "radial-gradient(ellipse at 52% 43%, rgba(" +
      (accent[0] | 0) + "," + (accent[1] | 0) + "," + (accent[2] | 0) +
      ",.22), transparent 44%), radial-gradient(ellipse at 50% 68%, rgba(" +
      (mid[0] | 0) + "," + (mid[1] | 0) + "," + (mid[2] | 0) +
      ",.55), transparent 72%), linear-gradient(160deg, #0b0910, #050507 68%, #09070a)";
  }

  function markWebglUnavailable() {
    state.glUnavailable = true;
    document.body.classList.add("no-webgl");
    setFallbackBackground();
  }

  function colorAverage(pixels, size, y0, height) {
    let r = 0;
    let g = 0;
    let b = 0;
    let count = 0;
    const yEnd = Math.min(size, y0 + height);
    for (let y = y0; y < yEnd; y++) {
      for (let x = 0; x < size; x++) {
        const offset = (y * size + x) * 4;
        if (pixels[offset + 3] < 36) continue;
        r += pixels[offset];
        g += pixels[offset + 1];
        b += pixels[offset + 2];
        count++;
      }
    }
    return count ? [r / count, g / count, b / count] : [26, 17, 20];
  }

  function vibrantAverage(pixels) {
    let r = 0;
    let g = 0;
    let b = 0;
    let weight = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      if (pixels[i + 3] < 72) continue;
      const red = pixels[i];
      const green = pixels[i + 1];
      const blue = pixels[i + 2];
      const max = Math.max(red, green, blue);
      const min = Math.min(red, green, blue);
      const saturation = max > 0 ? (max - min) / max : 0;
      const luma = (red * 0.299 + green * 0.587 + blue * 0.114) / 255;
      const w = 0.22 + saturation * 1.55 + luma * 0.26;
      r += red * w;
      g += green * w;
      b += blue * w;
      weight += w;
    }
    return weight ? [r / weight, g / weight, b / weight] : [255, 170, 109];
  }

  function mixColor(a, b, t) {
    return [
      a[0] + (b[0] - a[0]) * t,
      a[1] + (b[1] - a[1]) * t,
      a[2] + (b[2] - a[2]) * t
    ];
  }

  function setPaletteFromPixels(pixels) {
    const top = colorAverage(pixels, state.pixelSize, 0, 19);
    const mid = colorAverage(pixels, state.pixelSize, 20, 19);
    const bottom = colorAverage(pixels, state.pixelSize, 39, 25);
    state.palette.top = mixColor(top, [19, 13, 18], 0.58);
    state.palette.mid = mixColor(mid, [12, 9, 14], 0.66);
    state.palette.bottom = mixColor(bottom, [5, 5, 8], 0.84);
    const vivid = vibrantAverage(pixels);
    state.accent = mixColor(vivid, state.accent, 0.14);
    document.documentElement.style.setProperty(
      "--cover-accent",
      `rgb(${state.accent.map(v => Math.round(clamp(v, 0, 255))).join(",")})`
    );
    setFallbackBackground();
    buildOrb();
    buildTide();
  }

  function rebuildPaletteFromAccent() {
    state.palette.top = mixColor(
      mixColor(state.accent, [255, 183, 117], 0.36),
      [16, 11, 16],
      0.62
    );
    state.palette.mid = mixColor(
      mixColor(state.accent, [43, 23, 33], 0.34),
      [10, 8, 12],
      0.68
    );
    state.palette.bottom = [7, 6, 9];
    setFallbackBackground();
  }

  function setAccent(hex) {
    const raw = String(hex || "").replace("#", "");
    if (!/^[0-9a-f]{6}$/i.test(raw)) return;
    state.accent = [
      parseInt(raw.slice(0, 2), 16),
      parseInt(raw.slice(2, 4), 16),
      parseInt(raw.slice(4, 6), 16)
    ];
    if (!state.pixels) {
      rebuildPaletteFromAccent();
      buildOrb();
      buildTide();
    }
  }

  function sampleCover(u, v) {
    if (!state.pixels) return null;
    const size = state.pixelSize;
    const x = clamp(Math.floor(u * size), 0, size - 1);
    const y = clamp(Math.floor(v * size), 0, size - 1);
    const offset = (y * size + x) * 4;
    if (state.pixels[offset + 3] < 36) return null;
    return [state.pixels[offset], state.pixels[offset + 1], state.pixels[offset + 2]];
  }

  function coverInk(u, v, boost) {
    let color = sampleCover(u, v);
    if (!color) {
      const fallback = mixColor(state.accent, [255, 233, 208], boost);
      color = mixColor(fallback, [30, 16, 22], v * 0.36);
    }
    const average = (color[0] + color[1] + color[2]) / 3;
    const gain = average < 48 ? 1.62 : average < 92 ? 1.30 : 1.06;
    const saturation = 1.34;
    let r = average + (color[0] - average) * saturation;
    let g = average + (color[1] - average) * saturation;
    let b = average + (color[2] - average) * saturation;
    const warm = 0.02 + (1 - v) * 0.025;
    r = clamp(r * gain + 12 + 32 * warm, 0, 255);
    g = clamp(g * gain + 10 + 14 * warm, 0, 255);
    b = clamp(b * gain + 10, 0, 255);
    return [r, g, b];
  }

  let artworkSerial = 0;
  // Keep in-flight decodes rooted. A local Image with no other reference can
  // be collected before onload, which would silently leave the globe on its
  // accent fallback instead of the album's own colours.
  const pendingArtworkImages = new Set();

  function setArtworkData(data) {
    // A decoded cover arrives asynchronously. Ignore an older image if the
    // track changed while it was decoding, otherwise a fast skip can leave
    // the new globe painted with the previous song's colours.
    const request = ++artworkSerial;
    if (!data) {
      state.artwork = null;
      state.pixels = null;
      rebuildPaletteFromAccent();
      buildOrb();
      buildTide();
      return;
    }
    const image = new Image();
    pendingArtworkImages.add(image);
    image.onload = () => {
      pendingArtworkImages.delete(image);
      if (request !== artworkSerial) return;
      state.artwork = image;
      const size = 64;
      const sample = document.createElement("canvas");
      sample.width = size;
      sample.height = size;
      const context = sample.getContext("2d", { willReadFrequently: true });
      if (!context) return;
      const side = Math.min(image.width, image.height);
      const sx = (image.width - side) * 0.5;
      const sy = (image.height - side) * 0.5;
      context.drawImage(image, sx, sy, side, side, 0, 0, size, size);
      let pixels;
      try {
        pixels = context.getImageData(0, 0, size, size).data;
      } catch (error) {
        if (request !== artworkSerial) return;
        state.pixels = null;
        rebuildPaletteFromAccent();
        buildOrb();
        buildTide();
        return;
      }
      if (request !== artworkSerial) return;
      state.pixels = pixels;
      state.pixelSize = size;
      setPaletteFromPixels(pixels);
    };
    image.onerror = () => {
      pendingArtworkImages.delete(image);
      if (request !== artworkSerial) return;
      state.artwork = null;
      state.pixels = null;
      rebuildPaletteFromAccent();
      buildOrb();
      buildTide();
    };
    image.src = data;
  }

  /* ------------------------------------------------------------------ */
  /* Geometry                                                            */
  /* ------------------------------------------------------------------ */

  function buildOrb() {
    const rings = config.orbRings;
    const rowCounts = new Int16Array(rings);
    let count = 0;
    for (let ring = 0; ring < rings; ring++) {
      // Rows are evenly spaced in screen height: y is uniform, so the horizon
      // and the poles get the same vertical gap. Each row then gets only as
      // many points as its circumference can hold. The result is a calm,
      // uniform latitude lattice instead of the old pole-heavy stripes.
      const y = 1 - 2 * ((ring + 0.5) / rings);
      const ringRadius = Math.sqrt(Math.max(0, 1 - y * y));
      const rowCount = Math.max(
        4,
        Math.round(config.orbMeridians * ringRadius / 4) * 4
      );
      rowCounts[ring] = rowCount;
      count += rowCount;
    }

    const data = new Float32Array(count * 11);
    let offset = 0;
    let pointIndex = 0;
    for (let ring = 0; ring < rings; ring++) {
      const y = 1 - 2 * ((ring + 0.5) / rings);
      const ringRadius = Math.sqrt(Math.max(0, 1 - y * y));
      const meridians = rowCounts[ring];
      const v = Math.acos(clamp(y, -1, 1)) / Math.PI;
      for (let meridian = 0; meridian < meridians; meridian++) {
        const index = pointIndex++;
        const theta = (meridian / meridians) * TAU;
        const x = Math.cos(theta) * ringRadius;
        const z = Math.sin(theta) * ringRadius;

        // The cover wraps around the sphere without a mirror: screen-left is
        // cover-left at the front, so turning the globe follows the finger.
        let u = 0.75 - theta / TAU;
        u -= Math.floor(u);
        const color = coverInk(u, v, 0.30);

        // Only the base particles remain: one size, one shade, no random
        // sparkle. The audio envelope still moves and colours the lattice.
        data[offset++] = x;
        data[offset++] = y;
        data[offset++] = z;
        data[offset++] = clamp(color[0] / 255, 0, 1);
        data[offset++] = clamp(color[1] / 255, 0, 1);
        data[offset++] = clamp(color[2] / 255, 0, 1);
        data[offset++] = hash01(index * 5.73 + 0.4) * 2 - 1;
        data[offset++] = hash01(index * 6.19 + 2.2) * 2 - 1;
        data[offset++] = hash01(index * 7.31 + 4.8) * 2 - 1;
        data[offset++] = 1.68;
        data[offset++] = 0;
      }
    }
    state.orbData = data;
    state.orbCount = count;
    state.dirtyOrb = true;
  }

  const TIDE_MIN_X = -3.35;
  const TIDE_MAX_X = 3.35;
  const TIDE_MIN_Z = -4.15;
  const TIDE_MAX_Z = 1.65;

  function tideInk(u, v) {
    let color = sampleCover(u, v);
    if (!color) {
      color = mixColor(
        mixColor(state.accent, [255, 238, 222], 0.34),
        [23, 16, 27],
        v * 0.46
      );
    }
    const average = (color[0] + color[1] + color[2]) / 3;
    const gain = average < 50 ? 1.9 : average < 100 ? 1.45 : 1.12;
    let r = average + (color[0] - average) * 1.20;
    let g = average + (color[1] - average) * 1.20;
    let b = average + (color[2] - average) * 1.20;
    r = clamp(r * gain + 26, 0, 255);
    g = clamp(g * gain + 22, 0, 255);
    b = clamp(b * gain + 24, 0, 255);
    return [r, g, b];
  }

  function phaseAt(x, z) {
    return (Math.sin(x * 1.37 + z * 2.11 + 0.8) * 0.5 + 0.5);
  }

  function pushTideVertex(target, x, z) {
    const u = clamp((x - TIDE_MIN_X) / (TIDE_MAX_X - TIDE_MIN_X), 0, 1);
    const v = clamp((z - TIDE_MIN_Z) / (TIDE_MAX_Z - TIDE_MIN_Z), 0, 1);
    const color = tideInk(u, v);
    target.push(x, z, color[0] / 255, color[1] / 255, color[2] / 255, phaseAt(x, z));
  }

  function buildTide() {
    const rows = config.tideRows;
    const cols = config.tideCols;
    const lineVertices = [];
    const pointVertices = [];

    for (let row = 0; row < rows; row++) {
      const z = mix(TIDE_MIN_Z, TIDE_MAX_Z, row / (rows - 1));
      for (let col = 0; col < cols - 1; col++) {
        const x0 = mix(TIDE_MIN_X, TIDE_MAX_X, col / (cols - 1));
        const x1 = mix(TIDE_MIN_X, TIDE_MAX_X, (col + 1) / (cols - 1));
        pushTideVertex(lineVertices, x0, z);
        pushTideVertex(lineVertices, x1, z);
      }
    }

    for (let col = 0; col < cols; col += config.tideColumnStep) {
      const x = mix(TIDE_MIN_X, TIDE_MAX_X, col / (cols - 1));
      for (let row = 0; row < rows - 1; row++) {
        const z0 = mix(TIDE_MIN_Z, TIDE_MAX_Z, row / (rows - 1));
        const z1 = mix(TIDE_MIN_Z, TIDE_MAX_Z, (row + 1) / (rows - 1));
        pushTideVertex(lineVertices, x, z0);
        pushTideVertex(lineVertices, x, z1);
      }
    }

    for (let row = 0; row < rows; row += config.pointStepRow) {
      const z = mix(TIDE_MIN_Z, TIDE_MAX_Z, row / (rows - 1));
      for (let col = 0; col < cols; col += config.pointStepCol) {
        const x = mix(TIDE_MIN_X, TIDE_MAX_X, col / (cols - 1));
        pushTideVertex(pointVertices, x, z);
      }
    }

    state.tideLines = new Float32Array(lineVertices);
    state.tideLineCount = lineVertices.length / 6;
    state.tidePoints = new Float32Array(pointVertices);
    state.tidePointCount = pointVertices.length / 6;
    state.dirtyTide = true;
  }

  /* ------------------------------------------------------------------ */
  /* WebGL                                                               */
  /* ------------------------------------------------------------------ */

  function compileShader(context, type, source) {
    const shader = context.createShader(type);
    context.shaderSource(shader, source);
    context.compileShader(shader);
    if (!context.getShaderParameter(shader, context.COMPILE_STATUS)) {
      const message = context.getShaderInfoLog(shader);
      context.deleteShader(shader);
      throw new Error(message || "shader compilation failed");
    }
    return shader;
  }

  function linkProgram(context, vertexSource, fragmentSource, bindings) {
    const vertex = compileShader(context, context.VERTEX_SHADER, vertexSource);
    const fragment = compileShader(context, context.FRAGMENT_SHADER, fragmentSource);
    const program = context.createProgram();
    context.attachShader(program, vertex);
    context.attachShader(program, fragment);
    if (bindings) {
      Object.keys(bindings).forEach(name => {
        context.bindAttribLocation(program, bindings[name], name);
      });
    }
    context.linkProgram(program);
    context.deleteShader(vertex);
    context.deleteShader(fragment);
    if (!context.getProgramParameter(program, context.LINK_STATUS)) {
      const message = context.getProgramInfoLog(program);
      context.deleteProgram(program);
      throw new Error(message || "program link failed");
    }
    return program;
  }

  function uniformMap(context, program, names) {
    const result = {};
    names.forEach(name => {
      result[name] = context.getUniformLocation(program, name);
    });
    return result;
  }

  function initWebgl() {
    if (state.glLost || state.glUnavailable) return false;
    if (state.glPrograms) return true;
    let context = null;
    try {
      context = canvas.getContext("webgl", {
        alpha: false,
        antialias: false,
        depth: false,
        stencil: false,
        premultipliedAlpha: false,
        preserveDrawingBuffer: false,
        powerPreference: "high-performance"
      }) || canvas.getContext("experimental-webgl");
    } catch (error) {
      context = null;
    }
    if (!context) {
      markWebglUnavailable();
      return false;
    }
    try {
      const bg = linkProgram(context, BG_VERTEX, BG_FRAGMENT, { aQuad: 0 });
      const orb = linkProgram(context, ORB_VERTEX, ORB_FRAGMENT, {
        aPosition: 0,
        aColor: 1,
        aSeed: 2,
        aSize: 3,
        aKind: 4
      });
      const tide = linkProgram(context, TIDE_VERTEX, TIDE_FRAGMENT, {
        aPlane: 0,
        aColor: 1,
        aPhase: 2
      });
      const quadBuffer = context.createBuffer();
      context.bindBuffer(context.ARRAY_BUFFER, quadBuffer);
      context.bufferData(
        context.ARRAY_BUFFER,
        new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]),
        context.STATIC_DRAW
      );
      const orbBuffer = context.createBuffer();
      const tideLineBuffer = context.createBuffer();
      const tidePointBuffer = context.createBuffer();
      const orbUniforms = uniformMap(context, orb, [
        "uResolution", "uCenter", "uRadius", "uDpr", "uYaw", "uTilt",
        "uTime", "uLevel", "uBurst", "uPlaying"
      ]);
      orbUniforms.bands = [];
      for (let i = 0; i < 8; i++) {
        orbUniforms.bands.push(context.getUniformLocation(orb, "uB" + i));
      }
      const tideUniforms = uniformMap(context, tide, [
        "uResolution", "uScreenCenter", "uCamPos", "uCamForward",
        "uCamRight", "uCamUp", "uBounds", "uFocal", "uDpr", "uTime",
        "uLevel", "uBurst", "uPlaying", "uPointMode"
      ]);
      tideUniforms.bands = [];
      for (let i = 0; i < 8; i++) {
        tideUniforms.bands.push(context.getUniformLocation(tide, "uB" + i));
      }
      state.gl = context;
      state.glPrograms = {
        bg,
        orb,
        tide,
        bgUniforms: uniformMap(context, bg, [
          "uTop", "uMid", "uBottom", "uAccent", "uCenter", "uAspect",
          "uLevel", "uBurst", "uTime"
        ]),
        orbUniforms,
        tideUniforms
      };
      state.glBuffers = {
        quad: quadBuffer,
        orb: orbBuffer,
        tideLines: tideLineBuffer,
        tidePoints: tidePointBuffer,
        orbUploaded: false,
        tideUploaded: false
      };
      context.disable(context.DEPTH_TEST);
      context.disable(context.CULL_FACE);
      context.disable(context.BLEND);
      context.clearColor(0, 0, 0, 1);
      state.ready = true;
      uploadDirty();
      return true;
    } catch (error) {
      console.error("[CatField] WebGL initialisation failed:", error);
      state.glPrograms = null;
      state.gl = null;
      markWebglUnavailable();
      return false;
    }
  }

  function uploadDirty() {
    if (!state.gl || !state.glBuffers) return;
    const context = state.gl;
    if (state.dirtyOrb && state.orbData) {
      context.bindBuffer(context.ARRAY_BUFFER, state.glBuffers.orb);
      context.bufferData(context.ARRAY_BUFFER, state.orbData, context.STATIC_DRAW);
      state.glBuffers.orbUploaded = true;
      state.dirtyOrb = false;
    }
    if (state.dirtyTide && state.tideLines && state.tidePoints) {
      context.bindBuffer(context.ARRAY_BUFFER, state.glBuffers.tideLines);
      context.bufferData(context.ARRAY_BUFFER, state.tideLines, context.STATIC_DRAW);
      context.bindBuffer(context.ARRAY_BUFFER, state.glBuffers.tidePoints);
      context.bufferData(context.ARRAY_BUFFER, state.tidePoints, context.STATIC_DRAW);
      state.glBuffers.tideUploaded = true;
      state.dirtyTide = false;
    }
  }

  /* ------------------------------------------------------------------ */
  /* Layout / camera                                                     */
  /* ------------------------------------------------------------------ */

  function layout() {
    const width = state.width;
    const height = state.height;
    const landscape = state.landscape;
    return {
      landscape,
      orbCenterX: width * (landscape ? 0.695 : 0.5),
      orbCenterY: height * (landscape ? 0.485 : 0.445),
      orbRadius: Math.min(width, height) * (landscape ? 0.455 : 0.355),
      tideCenterX: width * (landscape ? 0.70 : 0.5),
      tideCenterY: height * (landscape ? 0.60 : 0.64),
      focal: landscape
        ? Math.min(width * 0.70, height * 1.20)
        : Math.min(width * 1.35, height * 1.02)
    };
  }

  function resize() {
    const width = Math.max(1, window.innerWidth);
    const height = Math.max(1, window.innerHeight);
    const dpr = Math.min(window.devicePixelRatio || 1, config.dpr);
    state.landscape = width > height * 1.02;
    if (state.width === width && state.height === height && state.dpr === dpr) {
      return;
    }
    state.width = width;
    state.height = height;
    state.dpr = dpr;
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    canvas.style.width = width + "px";
    canvas.style.height = height + "px";
    if (state.gl) state.gl.viewport(0, 0, canvas.width, canvas.height);
    if (!state.glPrograms) {
      setFallbackBackground();
    }
  }

  function unitColor(color, scale) {
    return [
      clamp(color[0] / 255 * scale, 0, 1),
      clamp(color[1] / 255 * scale, 0, 1),
      clamp(color[2] / 255 * scale, 0, 1)
    ];
  }

  function tideCamera() {
    const view = state.views.tide;
    const data = layout();
    const yaw = clamp(view.yaw, -1.02, 1.02);
    const elevation = clamp(0.38 + view.tilt, 0.20, 0.65);
    const distance = 4.0 / clamp(view.zoom, 0.62, 1.95);
    const target = [0, -0.03, -0.55];
    const camPos = [
      target[0] + Math.sin(yaw) * Math.cos(elevation) * distance,
      target[1] + Math.sin(elevation) * distance,
      target[2] + Math.cos(yaw) * Math.cos(elevation) * distance
    ];
    const fx = target[0] - camPos[0];
    const fy = target[1] - camPos[1];
    const fz = target[2] - camPos[2];
    const forwardLength = Math.hypot(fx, fy, fz) || 1;
    const forward = [fx / forwardLength, fy / forwardLength, fz / forwardLength];
    const rx = -forward[2];
    const rz = forward[0];
    const rightLength = Math.hypot(rx, rz) || 1;
    const right = [rx / rightLength, 0, rz / rightLength];
    const up = [
      right[1] * forward[2] - right[2] * forward[1],
      right[2] * forward[0] - right[0] * forward[2],
      right[0] * forward[1] - right[1] * forward[0]
    ];
    return {
      camPos,
      forward,
      right,
      up,
      focal: data.focal * view.zoom,
      screenCenter: [data.tideCenterX, data.tideCenterY]
    };
  }

  /* ------------------------------------------------------------------ */
  /* Interaction                                                         */
  /* ------------------------------------------------------------------ */

  function blockedTarget(target) {
    return target && target.closest &&
      target.closest("button, input, .menu, .menu-scrim, .menu-layer, .topbar, " +
        ".dock, .calibration, .lyrics-state, .plain, .lyric-line, .toast, .empty-button");
  }

  function activeView() {
    return state.views[state.effect];
  }

  function startDrag(event) {
    state.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (state.pointers.size === 1) {
      state.orbitYawVelocity = 0;
      state.drag = {
        id: event.pointerId,
        x: event.clientX,
        y: event.clientY,
        lastX: event.clientX,
        lastAt: performance.now(),
        startYaw: activeView().yaw,
        startTilt: activeView().tilt,
        velocity: 0,
        moved: false
      };
      if (state.effect === EFFECT_ORBIT) state.lastOrbitDragAt = state.time;
    } else if (state.pointers.size === 2) {
      const points = Array.from(state.pointers.values());
      state.drag = null;
      state.orbitYawVelocity = 0;
      state.pinchDistance = Math.hypot(
        points[0].x - points[1].x,
        points[0].y - points[1].y
      );
      state.pinchZoom = activeView().zoom;
    }
  }

  function moveDrag(event) {
    const point = state.pointers.get(event.pointerId);
    if (!point) return;
    point.x = event.clientX;
    point.y = event.clientY;
    const view = activeView();
    if (state.pointers.size >= 2) {
      const points = Array.from(state.pointers.values());
      const distance = Math.hypot(
        points[0].x - points[1].x,
        points[0].y - points[1].y
      );
      if (state.pinchDistance > 4) {
        view.zoom = clamp(
          state.pinchZoom * (distance / state.pinchDistance),
          0.62,
          1.95
        );
      }
      return;
    }
    if (!state.drag || state.drag.id !== event.pointerId) return;
    const dx = event.clientX - state.drag.x;
    const dy = event.clientY - state.drag.y;
    if (!state.drag.moved && Math.abs(dx) + Math.abs(dy) > 5) {
      state.drag.moved = true;
    }
    if (!state.drag.moved) return;
    if (state.effect === EFFECT_ORBIT) {
      // Horizontal distance drives an unbounded yaw, so the globe can be
      // dragged through a full 360° and beyond. A short flick keeps rotating
      // briefly after release instead of stopping dead under the finger.
      const now = performance.now();
      const stepX = event.clientX - state.drag.lastX;
      const stepSeconds = Math.max(0.012, Math.min(0.12, (now - state.drag.lastAt) / 1000));
      const yawStep = stepX * 0.0082;
      view.yaw = state.drag.startYaw + dx * 0.0082;
      view.tilt = clamp(state.drag.startTilt + dy * 0.0030, -0.72, 0.72);
      state.drag.lastX = event.clientX;
      state.drag.lastAt = now;
      state.drag.velocity = state.drag.velocity * 0.55 +
        clamp(yawStep / stepSeconds, -5.5, 5.5) * 0.45;
      state.lastOrbitDragAt = state.time;
    } else {
      view.yaw = clamp(state.drag.startYaw + dx * 0.0046, -1.02, 1.02);
      view.tilt = clamp(state.drag.startTilt + dy * 0.0026, -0.15, 0.28);
    }
  }

  function endDrag(event) {
    if (!state.pointers.has(event.pointerId)) return;
    state.pointers.delete(event.pointerId);
    if (state.pointers.size < 2) {
      state.pinchDistance = 0;
    }
    if (state.pointers.size === 1) {
      const point = Array.from(state.pointers.values())[0];
      state.drag = {
        id: Array.from(state.pointers.keys())[0],
        x: point.x,
        y: point.y,
        lastX: point.x,
        lastAt: performance.now(),
        startYaw: activeView().yaw,
        startTilt: activeView().tilt,
        velocity: 0,
        moved: true
      };
      state.orbitYawVelocity = 0;
    } else if (state.pointers.size === 0) {
      if (state.effect === EFFECT_ORBIT && state.drag) {
        state.orbitYawVelocity = clamp(state.drag.velocity, -5.5, 5.5);
        state.lastOrbitDragAt = state.time;
      } else {
        state.orbitYawVelocity = 0;
      }
      state.drag = null;
      saveView(state.effect);
    }
  }

  window.addEventListener("pointerdown", event => {
    if (blockedTarget(event.target)) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    boostFrameRate();
    startDrag(event);
  }, { capture: true, passive: true });

  window.addEventListener("pointermove", event => {
    if (!state.pointers.has(event.pointerId)) return;
    boostFrameRate();
    if (event.cancelable) event.preventDefault();
    moveDrag(event);
  }, { capture: true, passive: false });

  window.addEventListener("pointerup", endDrag, { capture: true, passive: true });
  window.addEventListener("pointercancel", endDrag, { capture: true, passive: true });
  window.addEventListener("wheel", event => {
    if (blockedTarget(event.target)) return;
    boostFrameRate();
    event.preventDefault();
    const view = activeView();
    view.zoom = clamp(view.zoom * (event.deltaY > 0 ? 0.94 : 1.06), 0.62, 1.95);
    saveView(state.effect);
  }, { passive: false });

  window.addEventListener("dblclick", event => {
    if (blockedTarget(event.target)) return;
    resetView(false);
  });

  /* ------------------------------------------------------------------ */
  /* Audio                                                               */
  /* ------------------------------------------------------------------ */

  function ingestAudio(payload) {
    if (!payload || typeof payload !== "object") return;
    const bands = payload.bands;
    if (Array.isArray(bands) || ArrayBuffer.isView(bands)) {
      for (let i = 0; i < 8; i++) {
        const value = Number(bands[i]);
        state.audioTarget.bands[i] = Number.isFinite(value)
          ? clamp(value, 0, 1)
          : 0;
      }
    }
    const beat = Number(payload.beat);
    const level = Number(payload.level);
    state.audioTarget.beat = Number.isFinite(beat) ? clamp(beat, 0, 1) : 0;
    state.audioTarget.level = Number.isFinite(level) ? clamp(level, 0, 1) : 0;
    if (typeof payload.playing === "boolean") {
      state.audioTarget.playing = payload.playing;
    }
    tickIfNeeded();
  }

  function updateAudio(dt) {
    const target = state.audioTarget;
    const response = 1 - Math.pow(0.001, dt);
    state.level += (target.level - state.level) * response * 0.72;
    let sum = 0;
    for (let i = 0; i < 8; i++) {
      state.bands[i] += (target.bands[i] - state.bands[i]) * response * 0.84;
      sum += state.bands[i];
    }
    if (!target.playing) {
      state.level += (0 - state.level) * response * 0.52;
      for (let i = 0; i < 8; i++) state.bands[i] *= 1 - response * 0.36;
      sum = 0;
    }
    const low = (state.bands[0] + state.bands[1] + state.bands[2]) / 3;
    const beat = target.playing
      ? Math.max(target.beat, low * 0.90, sum / 8 * 0.62)
      : 0;
    state.beatEnergy = Math.max(state.beatEnergy * Math.pow(0.025, dt), beat);
    if (target.playing && (target.beat > 0.30 || low > 0.40) &&
      state.time - state.lastBeatAt > 0.14) {
      state.burst = Math.max(
        state.burst,
        (0.55 + beat * 0.58) * (0.34 + state.beatIntensity * 0.66)
      );
      state.lastBeatAt = state.time;
    }
    state.burst = Math.max(
      state.burst * Math.pow(0.014, dt),
      state.beatEnergy * 0.23 * state.beatIntensity
    );
  }

  /* ------------------------------------------------------------------ */
  /* Rendering                                                           */
  /* ------------------------------------------------------------------ */

  function updateMotion(dt) {
    const gyroResponse = 1 - Math.pow(0.004, dt);
    state.gyroYaw += (state.gyroYawTarget - state.gyroYaw) * gyroResponse;
    state.gyroPitch += (state.gyroPitchTarget - state.gyroPitch) * gyroResponse;
    if (state.effect === EFFECT_ORBIT) {
      if (!state.drag && state.orbitYawVelocity !== 0) {
        state.orbitSpin += state.orbitYawVelocity * dt;
        state.orbitYawVelocity *= Math.pow(0.06, dt);
        if (Math.abs(state.orbitYawVelocity) < 0.012) state.orbitYawVelocity = 0;
      }
      if (!state.drag && state.time - state.lastOrbitDragAt > 2.2 &&
        state.time > state.orbitAutoAt) {
        state.orbitSpin += dt * (0.042 + state.level * 0.10);
      }
    }
  }

  function drawBackground() {
    const context = state.gl;
    const programs = state.glPrograms;
    const buffers = state.glBuffers;
    if (!context || !programs) return;
    const data = layout();
    context.disable(context.BLEND);
    context.useProgram(programs.bg);
    context.bindBuffer(context.ARRAY_BUFFER, buffers.quad);
    context.enableVertexAttribArray(0);
    context.vertexAttribPointer(0, 2, context.FLOAT, false, 0, 0);
    for (let i = 1; i < 5; i++) context.disableVertexAttribArray(i);
    const uniforms = programs.bgUniforms;
    const top = unitColor(state.palette.top, 1.0);
    const mid = unitColor(state.palette.mid, 1.0);
    const bottom = unitColor(state.palette.bottom, 1.0);
    const accent = unitColor(state.accent, 1.0);
    context.uniform3f(uniforms.uTop, top[0], top[1], top[2]);
    context.uniform3f(uniforms.uMid, mid[0], mid[1], mid[2]);
    context.uniform3f(uniforms.uBottom, bottom[0], bottom[1], bottom[2]);
    context.uniform3f(uniforms.uAccent, accent[0], accent[1], accent[2]);
    const center = state.effect === EFFECT_ORBIT
      ? [data.orbCenterX / state.width, 1 - data.orbCenterY / state.height]
      : [data.tideCenterX / state.width, 1 - data.tideCenterY / state.height];
    context.uniform2f(uniforms.uCenter, center[0], center[1]);
    context.uniform1f(uniforms.uAspect, state.width / state.height);
    context.uniform1f(uniforms.uLevel, state.level);
    context.uniform1f(uniforms.uBurst, state.burst);
    context.uniform1f(uniforms.uTime, state.time);
    context.drawArrays(context.TRIANGLE_STRIP, 0, 4);
  }

  function drawOrb() {
    const context = state.gl;
    const programs = state.glPrograms;
    const buffers = state.glBuffers;
    if (!context || !programs || !state.orbCount) return;
    if (!buffers.orbUploaded) uploadDirty();
    const data = layout();
    const view = state.views.orbit;
    const baseTilt = -0.15;
    const tilt = clamp(baseTilt + view.tilt + state.gyroPitch * 0.45, -0.66, 0.58);
    const yaw = view.yaw + state.orbitSpin + state.gyroYaw * 0.32;
    context.enable(context.BLEND);
    // Normal alpha over the sphere keeps overlapping points from summing into
    // a white core; the cover's own hues stay readable through the cloud.
    context.blendFunc(context.SRC_ALPHA, context.ONE_MINUS_SRC_ALPHA);
    context.useProgram(programs.orb);
    context.bindBuffer(context.ARRAY_BUFFER, buffers.orb);
    const stride = 11 * 4;
    context.enableVertexAttribArray(0);
    context.enableVertexAttribArray(1);
    context.enableVertexAttribArray(2);
    context.enableVertexAttribArray(3);
    context.enableVertexAttribArray(4);
    context.vertexAttribPointer(0, 3, context.FLOAT, false, stride, 0);
    context.vertexAttribPointer(1, 3, context.FLOAT, false, stride, 3 * 4);
    context.vertexAttribPointer(2, 3, context.FLOAT, false, stride, 6 * 4);
    context.vertexAttribPointer(3, 1, context.FLOAT, false, stride, 9 * 4);
    context.vertexAttribPointer(4, 1, context.FLOAT, false, stride, 10 * 4);
    const uniforms = programs.orbUniforms;
    context.uniform2f(uniforms.uResolution, canvas.width, canvas.height);
    context.uniform2f(uniforms.uCenter, data.orbCenterX * state.dpr, data.orbCenterY * state.dpr);
    context.uniform1f(uniforms.uRadius, data.orbRadius * view.zoom * state.dpr);
    context.uniform1f(uniforms.uDpr, state.dpr);
    context.uniform1f(uniforms.uYaw, yaw);
    context.uniform1f(uniforms.uTilt, tilt);
    context.uniform1f(uniforms.uTime, state.time);
    context.uniform1f(uniforms.uLevel, state.level);
    context.uniform1f(uniforms.uBurst, state.burst);
    context.uniform1f(uniforms.uPlaying, state.audioTarget.playing ? 1 : 0);
    for (let i = 0; i < 8; i++) {
      context.uniform1f(uniforms.bands[i], state.bands[i]);
    }
    context.drawArrays(context.POINTS, 0, state.orbCount);
    context.disable(context.BLEND);
  }

  function drawTide() {
    const context = state.gl;
    const programs = state.glPrograms;
    const buffers = state.glBuffers;
    if (!context || !programs || !state.tideLineCount) return;
    if (!buffers.tideUploaded) uploadDirty();
    const camera = tideCamera();
    const gyroYaw = clamp(state.gyroYaw * 0.26, -0.22, 0.22);
    if (gyroYaw !== 0) {
      const cy = Math.cos(gyroYaw);
      const sy = Math.sin(gyroYaw);
      camera.camPos = [
        cy * camera.camPos[0] + sy * camera.camPos[2],
        camera.camPos[1],
        -sy * camera.camPos[0] + cy * camera.camPos[2]
      ];
      camera.forward = [
        cy * camera.forward[0] + sy * camera.forward[2],
        camera.forward[1],
        -sy * camera.forward[0] + cy * camera.forward[2]
      ];
      camera.right = [
        cy * camera.right[0] + sy * camera.right[2],
        camera.right[1],
        -sy * camera.right[0] + cy * camera.right[2]
      ];
    }
    const uniforms = programs.tideUniforms;
    context.enable(context.BLEND);
    context.blendFunc(context.SRC_ALPHA, context.ONE);
    context.useProgram(programs.tide);
    context.uniform2f(uniforms.uResolution, canvas.width, canvas.height);
    context.uniform2f(uniforms.uScreenCenter,
      camera.screenCenter[0] * state.dpr,
      camera.screenCenter[1] * state.dpr);
    context.uniform3f(uniforms.uCamPos,
      camera.camPos[0], camera.camPos[1], camera.camPos[2]);
    context.uniform3f(uniforms.uCamForward,
      camera.forward[0], camera.forward[1], camera.forward[2]);
    context.uniform3f(uniforms.uCamRight,
      camera.right[0], camera.right[1], camera.right[2]);
    context.uniform3f(uniforms.uCamUp,
      camera.up[0], camera.up[1], camera.up[2]);
    context.uniform4f(uniforms.uBounds, TIDE_MIN_X, TIDE_MAX_X, TIDE_MIN_Z, TIDE_MAX_Z);
    context.uniform1f(uniforms.uFocal, camera.focal * state.dpr);
    context.uniform1f(uniforms.uDpr, state.dpr);
    context.uniform1f(uniforms.uTime, state.time);
    context.uniform1f(uniforms.uLevel, state.level);
    context.uniform1f(uniforms.uBurst, state.burst);
    context.uniform1f(uniforms.uPlaying, state.audioTarget.playing ? 1 : 0);
    for (let i = 0; i < 8; i++) {
      context.uniform1f(uniforms.bands[i], state.bands[i]);
    }
    const stride = 6 * 4;
    context.bindBuffer(context.ARRAY_BUFFER, buffers.tideLines);
    context.enableVertexAttribArray(0);
    context.enableVertexAttribArray(1);
    context.enableVertexAttribArray(2);
    context.vertexAttribPointer(0, 2, context.FLOAT, false, stride, 0);
    context.vertexAttribPointer(1, 3, context.FLOAT, false, stride, 2 * 4);
    context.vertexAttribPointer(2, 1, context.FLOAT, false, stride, 5 * 4);
    context.uniform1f(uniforms.uPointMode, 0);
    context.drawArrays(context.LINES, 0, state.tideLineCount);
    context.bindBuffer(context.ARRAY_BUFFER, buffers.tidePoints);
    context.vertexAttribPointer(0, 2, context.FLOAT, false, stride, 0);
    context.vertexAttribPointer(1, 3, context.FLOAT, false, stride, 2 * 4);
    context.vertexAttribPointer(2, 1, context.FLOAT, false, stride, 5 * 4);
    context.uniform1f(uniforms.uPointMode, 1);
    context.drawArrays(context.POINTS, 0, state.tidePointCount);
    context.disable(context.BLEND);
  }

  function render() {
    if (!state.glPrograms) return;
    const context = state.gl;
    if (!context || state.glLost) return;
    uploadDirty();
    context.viewport(0, 0, canvas.width, canvas.height);
    context.clearColor(0, 0, 0, 1);
    context.clear(context.COLOR_BUFFER_BIT);
    drawBackground();
    if (state.effect === EFFECT_ORBIT) drawOrb();
    else drawTide();
  }

  function frame(now) {
    state.raf = 0;
    if (!state.visible) return;
    if (!state.glPrograms && !initWebgl()) return;
    const elapsed = state.lastFrameAt > 0 ? Math.min(now - state.lastFrameAt, 100) : 0;
    state.lastFrameAt = now;
    const interval = frameIntervalFor(now);
    if (interval > 0) {
      state.frameAccumulator += elapsed;
      if (state.frameAccumulator < interval * 0.92) {
        state.raf = window.requestAnimationFrame(frame);
        return;
      }
      state.frameAccumulator = Math.max(0, state.frameAccumulator - interval);
    } else {
      state.frameAccumulator = 0;
    }
    const dt = state.lastTime
      ? clamp((now - state.lastTime) / 1000, 0.008, 0.05)
      : 0.016;
    state.lastTime = now;
    state.time += dt;
    state.drawnFrames += 1;
    updateAudio(dt);
    updateMotion(dt);
    render();
    state.raf = window.requestAnimationFrame(frame);
  }

  function start() {
    if (state.raf || !state.visible) return;
    resize();
    if (!state.glPrograms && !initWebgl()) return;
    state.lastTime = 0;
    state.lastFrameAt = 0;
    state.frameAccumulator = adaptiveFps ? 0 : frameInterval;
    boostFrameRate(1000);
    state.raf = window.requestAnimationFrame(frame);
  }

  function stop() {
    if (state.raf) {
      window.cancelAnimationFrame(state.raf);
      state.raf = 0;
    }
  }

  function tickIfNeeded() {
    if (state.visible) start();
  }

  /* ------------------------------------------------------------------ */
  /* Public surface                                                      */
  /* ------------------------------------------------------------------ */

  function applyEffect(effect, persist) {
    const next = effect === EFFECT_TIDE ? EFFECT_TIDE : EFFECT_ORBIT;
    state.effect = next;
    state.pointers.clear();
    state.drag = null;
    state.orbitYawVelocity = 0;
    state.pinchDistance = 0;
    if (next === EFFECT_ORBIT) state.orbitAutoAt = state.time + 2.4;
    document.body.classList.toggle("mode-tide", next === EFFECT_TIDE);
    if (persist) saveEffect(next);
    resize();
    start();
  }

  function resetView(notify) {
    const view = activeView();
    view.yaw = 0;
    view.tilt = 0;
    view.zoom = 1;
    state.orbitSpin = 0;
    state.orbitYawVelocity = 0;
    state.lastOrbitDragAt = state.time;
    state.orbitAutoAt = state.time + 4.0;
    saveView(state.effect);
    if (notify && typeof window.catmusic === "object") {
      // The UI layer owns the toast; this callback lets it announce the reset.
      document.dispatchEvent(new CustomEvent("catfield:reset"));
    }
  }

  function setGyro(yawDegrees, pitchDegrees) {
    const yaw = clamp(Number(yawDegrees) || 0, -22, 22) * Math.PI / 180;
    const pitch = clamp(Number(pitchDegrees) || 0, -15, 15) * Math.PI / 180;
    state.gyroYawTarget = yaw;
    state.gyroPitchTarget = pitch;
  }

  function clearGyro() {
    state.gyroYawTarget = 0;
    state.gyroPitchTarget = 0;
  }

  function setBeatIntensity(value) {
    state.beatIntensity = clamp(Number(value) || 0, 0, 1);
  }

  function init() {
    rebuildPaletteFromAccent();
    buildOrb();
    buildTide();
    document.body.classList.toggle("mode-tide", state.effect === EFFECT_TIDE);
    resize();
    state.orbitAutoAt = state.time + 2.4;
    if (!initWebgl()) {
      // Keep the stage alive; app.js still calls ready() so the lyric page can
      // show the UI over the CSS fallback.
      return;
    }
    start();
  }

  function setVisible(visible) {
    state.manualHidden = !visible;
    state.visible = visible && !document.hidden;
    if (state.visible) {
      state.lastTime = 0;
      state.lastFrameAt = 0;
      start();
    } else {
      stop();
    }
  }

  document.addEventListener("visibilitychange", () => {
    state.visible = !state.manualHidden && !document.hidden;
    if (state.visible) {
      state.lastTime = 0;
      state.lastFrameAt = 0;
      start();
    } else {
      stop();
    }
  });

  window.addEventListener("resize", () => {
    resize();
    if (state.visible) start();
  });

  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", () => {
      resize();
      if (state.visible) start();
    });
  }

  canvas.addEventListener("webglcontextlost", event => {
    event.preventDefault();
    state.glLost = true;
    state.glPrograms = null;
    state.gl = null;
    state.glBuffers = null;
    state.dirtyOrb = true;
    state.dirtyTide = true;
    stop();
  });

  canvas.addEventListener("webglcontextrestored", () => {
    state.glLost = false;
    state.glUnavailable = false;
    state.dirtyOrb = true;
    state.dirtyTide = true;
    document.body.classList.remove("no-webgl");
    start();
  });

  window.CatField = {
    getEffect: () => state.effect,
    setEffect: effect => applyEffect(effect, true),
    setAccent,
    setArtwork: setArtworkData,
    setAudio: ingestAudio,
    setBeatIntensity,
    setGyro,
    clearGyro,
    setVisible,
    setFrameRate,
    boostFrameRate,
    resetView: () => resetView(true),
    getDebug: () => ({
      effect: state.effect,
      quality: qualityKey,
      renderer: state.glPrograms ? "webgl" : "fallback",
      orbParticles: state.orbCount,
      tideLineVertices: state.tideLineCount,
      tidePoints: state.tidePointCount,
      targetFps: adaptiveFps ? "adaptive" : (targetFps || "unlimited"),
      drawnFrames: state.drawnFrames,
      level: state.level,
      burst: state.burst,
      accent: state.accent,
      artwork: !!state.artwork,
      yaw: state.views[state.effect].yaw,
      tilt: state.views[state.effect].tilt,
      zoom: state.views[state.effect].zoom,
      gyro: [state.gyroYaw, state.gyroPitch],
      landscape: state.landscape,
      viewport: [state.width, state.height, state.dpr]
    })
  };

  init();
})();
