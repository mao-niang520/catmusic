/*
 * CatMusic particle sound field.
 *
 * A fully local sound-field renderer that sits beside the original Sonic
 * Topography terrain. The field is a ten-thousand-point dotted globe: every
 * point takes its colour from the same UV on the current album cover, and the
 * decoded bands + beat envelope push the latitude rings outward until the
 * shell bursts open with a short spring.
 *
 * Rendering goes through a small WebGL point-sprite pipeline instead of the
 * Canvas 2D fallback used by earlier versions. The terrain's own WebGL context
 * is hidden and paused while this effect is selected, so the two contexts are
 * never drawing at the same time. The effect choice is persisted locally.
 */
(() => {
  "use strict";

  const STORAGE_EFFECT = "catmusic.soundfield.effect.v1";
  const EFFECT_PARTICLES = "particles";
  const EFFECT_TERRAIN = "terrain";
  const TAU = Math.PI * 2;
  const MERIDIANS = 100;
  const RINGS = 100;
  const PARTICLE_COUNT = MERIDIANS * RINGS;

  // The same frame-rate preference that drives the terrain is passed in the
  // URL. Respect it so 30 fps really saves battery and a 120 Hz panel is not
  // asked to redraw ten thousand dots for no visible gain.
  const fpsParam = new URLSearchParams(window.location.search).get("fps") || "60";
  const targetFps = fpsParam === "unlimited" || fpsParam === "0"
    ? 0
    : Math.min(240, Math.max(24, parseInt(fpsParam, 10) || 60));
  const frameInterval = targetFps > 0 ? 1000 / targetFps : 0;

  const canvas = document.getElementById("particle-scene");
  if (!canvas) return;
  canvas.style.backgroundColor = "#060504";

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function readEffect() {
    try {
      const saved = window.localStorage.getItem(STORAGE_EFFECT);
      return saved === EFFECT_TERRAIN ? EFFECT_TERRAIN : EFFECT_PARTICLES;
    } catch (error) {
      return EFFECT_PARTICLES;
    }
  }

  function saveEffect(effect) {
    try {
      window.localStorage.setItem(STORAGE_EFFECT, effect);
    } catch (error) {
      /* localStorage can be unavailable; the current page still works. */
    }
  }

  const state = {
    effect: readEffect(),
    field: null,
    particleCount: PARTICLE_COUNT,
    accent: [255, 154, 77],
    coverTop: [46, 22, 12],
    coverMid: [23, 12, 8],
    coverBottom: [7, 5, 4],
    pixels: null,
    pixelSize: 64,
    artwork: null,
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
    time: 0,
    lastTime: 0,
    lastFrameAt: 0,
    frameAccumulator: 0,
    drawnFrames: 0,
    lastBeatAt: -10,
    beatIntensity: 0.65,
    yaw: 0,
    tilt: -0.16,
    userYaw: 0,
    userTilt: 0,
    zoom: 1,
    lastDragAt: -10,
    pointers: new Map(),
    pinchDistance: 0,
    width: 0,
    height: 0,
    dpr: 1,
    radius: 1,
    centerX: 0,
    centerY: 0,
    gl: null,
    glState: null,
    glLost: false,
    glUnavailable: false,
    raf: 0,
    visible: !document.hidden
  };

  const ui = {
    options: document.getElementById("effect-options"),
    value: document.getElementById("effect-value"),
    hint: document.getElementById("effect-hint"),
    menuHint: document.querySelector(".menu-hint-top"),
    menu: document.getElementById("menu"),
    menuClose: document.getElementById("menu-close"),
    beatRange: document.getElementById("beat-range"),
    resetTilt: document.querySelector('[data-action="reset-tilt"]')
  };

  const DEFAULT_MENU_HINT =
    "单指拖动可水平 360° 环绕音浪；上下视角停在地面以上，不会进入地下。" +
    "双指张合缩放、轻扭微调画面倾角；歌词始终保持在音浪前方，轻点歌词跳转。";
  const PARTICLE_MENU_HINT =
    "单指拖动旋转粒子球，双指张合缩放；约一万个光点按封面取色，" +
    "鼓点会把球体向外炸开。视角连续旋转不镜像。歌词始终在粒子前方，轻点歌词跳转。";

  /* ------------------------------------------------------------------ */
  /* Effect switching                                                   */
  /* ------------------------------------------------------------------ */

  function applyEffect(effect, persist) {
    const next = effect === EFFECT_TERRAIN ? EFFECT_TERRAIN : EFFECT_PARTICLES;
    state.effect = next;
    state.pointers.clear();
    state.dragStart = null;
    state.pinchDistance = 0;
    document.body.classList.toggle("effect-particles", next === EFFECT_PARTICLES);
    if (ui.value) {
      ui.value.textContent = next === EFFECT_PARTICLES ? "粒子球" : "音浪地形";
    }
    if (ui.options) {
      ui.options.querySelectorAll("[data-effect]").forEach(button => {
        button.setAttribute(
          "aria-pressed",
          button.dataset.effect === next ? "true" : "false"
        );
      });
    }
    if (ui.hint) {
      ui.hint.textContent = next === EFFECT_PARTICLES
        ? "约一万个粒子按当前专辑封面逐点取色，随鼓点向外炸开。"
        : "保留原版 Sonic Topography 3D 地形，随鼓点起伏。";
    }
    if (ui.menuHint) {
      ui.menuHint.textContent = next === EFFECT_PARTICLES
        ? PARTICLE_MENU_HINT
        : DEFAULT_MENU_HINT;
    }
    if (persist) saveEffect(next);
    if (next === EFFECT_PARTICLES) {
      resize();
      start();
    } else {
      stop();
      clearCanvas();
    }
  }

  if (ui.options) {
    ui.options.addEventListener("click", event => {
      const button = event.target.closest("[data-effect]");
      if (!button) return;
      applyEffect(button.dataset.effect, true);
    });
  }

  if (ui.beatRange) {
    const syncBeat = () => {
      const value = Number(ui.beatRange.value);
      state.beatIntensity = clamp((Number.isFinite(value) ? value : 65) / 100, 0, 1);
    };
    ui.beatRange.addEventListener("input", syncBeat);
    syncBeat();
  }

  if (ui.resetTilt) {
    ui.resetTilt.addEventListener("click", () => {
      state.userYaw = 0;
      state.userTilt = 0;
      state.zoom = 1;
    });
  }

  /* ------------------------------------------------------------------ */
  /* Audio / artwork hooks                                              */
  /* ------------------------------------------------------------------ */

  function ingestAudio(payload) {
    if (!payload) return;
    const bands = payload.bands;
    if (Array.isArray(bands) || ArrayBuffer.isView(bands)) {
      for (let i = 0; i < 8; i++) {
        const value = Number(bands[i]);
        state.audioTarget.bands[i] = Number.isFinite(value) ? clamp(value, 0, 1) : 0;
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

  function setAccent(hex) {
    const value = String(hex || "").replace("#", "");
    if (!/^[0-9a-f]{6}$/i.test(value)) return;
    state.accent = [
      parseInt(value.slice(0, 2), 16),
      parseInt(value.slice(2, 4), 16),
      parseInt(value.slice(4, 6), 16)
    ];
    if (!state.artwork && state.pixels === null) {
      rebuildFromAccent();
    }
  }

  function setArtwork(payload) {
    const data = payload && typeof payload.data === "string" ? payload.data : "";
    if (!data) {
      state.artwork = null;
      state.pixels = null;
      rebuildFromAccent();
      return;
    }
    const image = new Image();
    image.onload = () => {
      state.artwork = image;
      sampleArtwork(image);
    };
    image.onerror = () => {
      state.artwork = null;
      state.pixels = null;
      rebuildFromAccent();
    };
    image.src = data;
  }

  function sampleArtwork(image) {
    const size = 64;
    const sampling = document.createElement("canvas");
    sampling.width = size;
    sampling.height = size;
    const sctx = sampling.getContext("2d", { willReadFrequently: true });
    if (!sctx) return;
    const side = Math.min(image.width, image.height);
    const sx = (image.width - side) * 0.5;
    const sy = (image.height - side) * 0.5;
    sctx.drawImage(image, sx, sy, side, side, 0, 0, size, size);
    let pixels;
    try {
      pixels = sctx.getImageData(0, 0, size, size).data;
    } catch (error) {
      state.pixels = null;
      rebuildFromAccent();
      return;
    }
    state.pixels = pixels;
    state.pixelSize = size;

    const top = sampleAverage(pixels, size, 0, 0, size, 18);
    const mid = sampleAverage(pixels, size, 0, 23, size, 18);
    const bottom = sampleAverage(pixels, size, 0, 46, size, 18);
    // The stage stays mostly dark so the dotted shell keeps its contrast;
    // the cover still tints the atmosphere around it.
    state.coverTop = mixColor(top, [6, 5, 4], 0.62);
    state.coverMid = mixColor(mid, [6, 4, 4], 0.68);
    state.coverBottom = mixColor(bottom, [3, 3, 3], 0.86);
    // A vivid average drives the glow and the lyrics-adjacent rim.
    state.accent = mixColor(vibrantAverage(pixels, size), state.accent, 0.16);
    buildField();
  }

  function sampleAverage(pixels, size, x0, y0, w, h) {
    let r = 0;
    let g = 0;
    let b = 0;
    let count = 0;
    for (let y = y0; y < Math.min(size, y0 + h); y++) {
      for (let x = x0; x < Math.min(size, x0 + w); x++) {
        const index = (y * size + x) * 4;
        if (pixels[index + 3] < 40) continue;
        r += pixels[index];
        g += pixels[index + 1];
        b += pixels[index + 2];
        count++;
      }
    }
    return count ? [r / count, g / count, b / count] : [24, 14, 9];
  }

  function vibrantAverage(pixels, size) {
    let r = 0;
    let g = 0;
    let b = 0;
    let weight = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      if (pixels[i + 3] < 80) continue;
      const red = pixels[i];
      const green = pixels[i + 1];
      const blue = pixels[i + 2];
      const max = Math.max(red, green, blue);
      const min = Math.min(red, green, blue);
      const saturation = max > 0 ? (max - min) / max : 0;
      const luma = (red * 0.299 + green * 0.587 + blue * 0.114) / 255;
      const w = 0.25 + saturation * 1.4 + luma * 0.25;
      r += red * w;
      g += green * w;
      b += blue * w;
      weight += w;
    }
    return weight ? [r / weight, g / weight, b / weight] : [255, 154, 77];
  }

  function mixColor(a, b, t) {
    return [
      a[0] + (b[0] - a[0]) * t,
      a[1] + (b[1] - a[1]) * t,
      a[2] + (b[2] - a[2]) * t
    ];
  }

  function coverSample(u, v) {
    if (!state.pixels) return null;
    const size = state.pixelSize;
    const x = clamp(Math.floor(u * size), 0, size - 1);
    const y = clamp(Math.floor(v * size), 0, size - 1);
    const index = (y * size + x) * 4;
    if (state.pixels[index + 3] < 40) return null;
    return [state.pixels[index], state.pixels[index + 1], state.pixels[index + 2]];
  }

  function particleColor(u, v) {
    let color = coverSample(u, v);
    if (!color) {
      const t = clamp(v, 0, 1);
      color = mixColor(
        mixColor(state.accent, [255, 255, 255], 0.28),
        [44, 22, 12],
        t * 0.72
      );
    }
    const avg = (color[0] + color[1] + color[2]) / 3;
    // Push away from grey and guarantee enough luminance to survive the scrim,
    // while retaining the artwork's hue and pattern.
    const saturation = 1.28;
    let r = avg + (color[0] - avg) * saturation;
    let g = avg + (color[1] - avg) * saturation;
    let b = avg + (color[2] - avg) * saturation;
    const luma = r * 0.299 + g * 0.587 + b * 0.114;
    const gain = luma < 68 ? 1.52 : luma < 110 ? 1.20 : 1.04;
    const warm = 0.04 + (1 - v) * 0.05;
    r = clamp(r * gain + 13 + 45 * warm, 0, 255);
    g = clamp(g * gain + 11 + 21 * warm, 0, 255);
    b = clamp(b * gain + 9, 0, 255);
    return [r, g, b];
  }

  function rebuildFromAccent() {
    state.coverTop = mixColor(mixColor(state.accent, [255, 185, 115], 0.40),
      [6, 5, 4], 0.58);
    state.coverMid = mixColor(mixColor(state.accent, [47, 21, 11], 0.35),
      [5, 4, 3], 0.60);
    state.coverBottom = [5, 4, 3];
    buildField();
  }

  /* ------------------------------------------------------------------ */
  /* WebGL field                                                        */
  /* ------------------------------------------------------------------ */

  const BG_VERTEX = `
    attribute vec2 aPosition;
    varying vec2 vUv;
    void main() {
      vUv = aPosition * 0.5 + 0.5;
      gl_Position = vec4(aPosition, 0.0, 1.0);
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
      vec2 q = (vUv - uCenter) * vec2(uAspect, 1.0) * 2.0;
      float r = length(q);
      vec3 color = uBottom * 0.14 + vec3(0.006, 0.005, 0.004);
      float center = exp(-r * r * 1.15);
      color += uMid * (0.05 + center * 0.22);
      color += uTop * center * 0.07;
      float pulse = 0.045 + uLevel * 0.045 + uBurst * 0.04;
      color += uAccent * exp(-r * r * 2.4) * pulse;
      vec2 qCool = q - vec2(-0.85, 0.65);
      color += uTop * exp(-dot(qCool, qCool) * 2.6) * 0.05;
      vec2 qWarm = q - vec2(0.95, -0.15);
      color += uMid * exp(-dot(qWarm, qWarm) * 3.2) * 0.045;
      float vignette = 1.0 - smoothstep(0.35, 1.55, r);
      color *= mix(0.20, 1.0, vignette);
      color += vec3(0.005, 0.004, 0.003);
      color += (hash21(gl_FragCoord.xy + fract(uTime) * 71.0) - 0.5) * 0.008;
      gl_FragColor = vec4(max(color, vec3(0.0)), 1.0);
    }
  `;

  const POINT_VERTEX = `
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
      else if (index < 2.0) return uB1;
      else if (index < 3.0) return uB2;
      else if (index < 4.0) return uB3;
      else if (index < 5.0) return uB4;
      else if (index < 6.0) return uB5;
      else if (index < 7.0) return uB6;
      return uB7;
    }

    void main() {
      bool halo = aKind > 0.5;
      float band = bandValue(aPosition.y);
      float twinkle = 0.5 + 0.5 * sin(uTime * (0.62 + aSeed.z * 0.85)
                           + aSeed.x * 6.2831);
      float energy = clamp(uLevel * 1.15 + uB0 * 0.38 + uB1 * 0.20, 0.0, 1.0);
      float breathe = energy * (0.10 + 0.10 * twinkle) +
                      band * (0.13 + twinkle * 0.09);
      float wave = sin(aPosition.y * 8.0 + uTime * (0.9 + energy * 1.4) + aSeed.x * 1.7);
      float ripple = wave * (0.007 + uBurst * 0.060 + energy * 0.035);
      float flare = smoothstep(0.15, 1.0, aSeed.x) * energy * 0.18;
      float impulse = uBurst * (0.42 + 0.58 * smoothstep(-0.85, 0.90, aSeed.z));
      float spread = impulse *
                     (0.62 + aSeed.x * 0.34 + 0.18 * smoothstep(-0.30, 1.0, aPosition.y)) +
                     flare;
      float radius = 1.0 + aSeed.z * 0.015 + breathe + ripple + spread;
      vec3 point = aPosition * radius;
      vec3 outward = normalize(aPosition + aSeed * 0.28);
      point += outward * uBurst * (0.07 + 0.32 * abs(aSeed.z));
      vec3 tangent = normalize(cross(aPosition, vec3(0.0, 1.0, 0.0)) + vec3(0.0, 0.0, 0.0001));
      point += tangent * sin(aSeed.y * 6.2831 + uTime * 0.5 + aPosition.y * 3.0) *
               (0.005 + uBurst * 0.060 + energy * 0.018);

      float cy = cos(uYaw);
      float sy = sin(uYaw);
      point = vec3(cy * point.x + sy * point.z, point.y,
                   -sy * point.x + cy * point.z);
      float ct = cos(uTilt);
      float st = sin(uTilt);
      point = vec3(point.x, ct * point.y - st * point.z,
                   st * point.y + ct * point.z);

      float cameraZ = 3.2;
      float viewZ = max(cameraZ - point.z, 0.75);
      float perspective = cameraZ / viewZ;
      vec2 screen = uCenter + vec2(point.x * uRadius * perspective,
                                   -point.y * uRadius * perspective);
      gl_Position = vec4(screen.x / uResolution.x * 2.0 - 1.0,
                         1.0 - screen.y / uResolution.y * 2.0, 0.0, 1.0);

      float depth = smoothstep(-0.75, 0.80, point.z);
      float size = abs(aSize) * uDpr * perspective *
        (0.90 + band * 0.34 + uLevel * 0.36 + uBurst * 0.24 + twinkle * 0.10);
      gl_PointSize = clamp(size, 1.0, 42.0 * uDpr);
      vColor = aColor * (0.78 + depth * 0.48 + twinkle * 0.16 + uBurst * 0.12);
      vAlpha = (0.14 + depth * 0.86) *
               (0.86 + band * 0.14) *
               (0.86 + 0.14 * twinkle) *
               (0.64 + 0.36 * uPlaying);
      if (halo) {
        gl_PointSize *= 1.45;
        vAlpha *= 0.22;
        vColor = mix(vColor, vec3(1.0), 0.10);
      }
      vKind = aKind;
    }
  `;

  const POINT_FRAGMENT = `
    precision mediump float;
    varying mediump vec3 vColor;
    varying mediump float vAlpha;
    varying mediump float vKind;
    void main() {
      vec2 q = gl_PointCoord * 2.0 - 1.0;
      float d = dot(q, q);
      if (d > 1.0) discard;
      float core = pow(1.0 - d, 1.55);
      float halo = exp(-d * 3.4);
      vec3 color = vColor * (0.72 + core * 0.72);
      color += vec3(0.10, 0.08, 0.06) * core * core;
      float alpha = vAlpha * (0.16 + core * 0.84);
      if (vKind > 0.5) {
        alpha = vAlpha * halo;
      }
      gl_FragColor = vec4(color, alpha);
    }
  `;

  function compileShader(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const log = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error(log || "WebGL shader compilation failed");
    }
    return shader;
  }

  function linkProgram(gl, vertexSource, fragmentSource, bindings) {
    const vertex = compileShader(gl, gl.VERTEX_SHADER, vertexSource);
    const fragment = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
    const program = gl.createProgram();
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    if (bindings) {
      Object.keys(bindings).forEach(name => {
        gl.bindAttribLocation(program, bindings[name], name);
      });
    }
    gl.linkProgram(program);
    gl.deleteShader(vertex);
    gl.deleteShader(fragment);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      const log = gl.getProgramInfoLog(program);
      gl.deleteProgram(program);
      throw new Error(log || "WebGL program link failed");
    }
    return program;
  }

  function uniformSet(gl, program, names) {
    const result = {};
    names.forEach(name => {
      result[name] = gl.getUniformLocation(program, name);
    });
    return result;
  }

  function ensureRenderer() {
    if (state.glState) return true;
    if (state.glLost || state.glUnavailable) return false;
    let gl = null;
    try {
      gl = canvas.getContext("webgl", {
        alpha: false,
        antialias: false,
        depth: false,
        stencil: false,
        premultipliedAlpha: false,
        preserveDrawingBuffer: false,
        powerPreference: "high-performance"
      }) || canvas.getContext("experimental-webgl");
    } catch (error) {
      gl = null;
    }
    if (!gl) {
      state.glUnavailable = true;
      setFallbackBackground();
      return false;
    }
    try {
      const bgProgram = linkProgram(gl, BG_VERTEX, BG_FRAGMENT, {
        aPosition: 0
      });
      const pointProgram = linkProgram(gl, POINT_VERTEX, POINT_FRAGMENT, {
        aPosition: 0,
        aColor: 1,
        aSeed: 2,
        aSize: 3,
        aKind: 4
      });
      const quadBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuffer);
      gl.bufferData(gl.ARRAY_BUFFER,
        new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      const fieldBuffer = gl.createBuffer();
      const pointUniforms = uniformSet(gl, pointProgram, [
        "uResolution", "uCenter", "uRadius", "uDpr", "uYaw", "uTilt",
        "uTime", "uLevel", "uBurst", "uPlaying"
      ]);
      pointUniforms.bands = [];
      for (let i = 0; i < 8; i++) {
        pointUniforms.bands.push(gl.getUniformLocation(pointProgram, "uB" + i));
      }
      state.glState = {
        gl,
        bgProgram,
        pointProgram,
        quadBuffer,
        fieldBuffer,
        bgUniforms: uniformSet(gl, bgProgram, [
          "uTop", "uMid", "uBottom", "uAccent", "uCenter", "uAspect",
          "uLevel", "uBurst", "uTime"
        ]),
        pointUniforms
      };
      state.gl = gl;
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.CULL_FACE);
      gl.disable(gl.BLEND);
      gl.clearColor(0, 0, 0, 1);
      uploadField();
      return true;
    } catch (error) {
      console.error("[CatParticles] WebGL initialisation failed:", error);
      state.glUnavailable = true;
      state.glState = null;
      setFallbackBackground();
      return false;
    }
  }

  function setFallbackBackground() {
    const mid = state.coverMid;
    const accent = state.accent;
    canvas.style.background =
      "radial-gradient(ellipse at 50% 46%, rgba(" +
      (accent[0] | 0) + "," + (accent[1] | 0) + "," + (accent[2] | 0) +
      ",.16) 0%, rgba(" +
      (mid[0] | 0) + "," + (mid[1] | 0) + "," + (mid[2] | 0) +
      ",.42) 38%, #060504 78%)";
  }

  function uploadField() {
    const glState = state.glState;
    const field = state.field;
    if (!glState || !field) return;
    const gl = glState.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, glState.fieldBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, field.data, gl.STATIC_DRAW);
  }

  function buildField() {
    const data = new Float32Array(PARTICLE_COUNT * 11);
    let offset = 0;
    for (let ring = 0; ring < RINGS; ring++) {
      const v = (ring + 0.5) / RINGS;
      const basePhi = v * Math.PI;
      for (let meridian = 0; meridian < MERIDIANS; meridian++) {
        const baseTheta = ((meridian + 0.5) / MERIDIANS) * TAU;
        // Keep each particle tied to its latitude ring; the small jitter
        // breaks the wireframe regularity without destroying the rings.
        const phi = clamp(
          basePhi + (Math.random() - 0.5) * (Math.PI / RINGS) * 0.55,
          0.002,
          Math.PI - 0.002
        );
        const theta = baseTheta +
          (Math.random() - 0.5) * (TAU / MERIDIANS) * 0.55;
        const sinPhi = Math.sin(phi);
        const cosPhi = Math.cos(phi);
        const x = sinPhi * Math.cos(theta);
        const y = cosPhi;
        const z = sinPhi * Math.sin(theta);

        // Non-mirrored globe mapping: at yaw 0 the cover's right side is on
        // the right of the screen and its left side is on the left. The
        // previous Canvas mapping sampled the opposite direction, which made
        // the artwork read as a mirror image while turning the sphere.
        let u = 0.75 - baseTheta / TAU;
        u -= Math.floor(u);
        const color = particleColor(u, v);
        const luma = color[0] * 0.299 + color[1] * 0.587 + color[2] * 0.114;
        const emphasized = meridian % 9 === 0 || ring % 13 === 0;
        let size = 0.78 + Math.random() * 0.68 + (emphasized ? 0.15 : 0);
        let kind = 0;
        if (luma > 140) size += 0.08 + Math.random() * 0.15;
        if (luma > 148 && Math.random() < 0.10) {
          kind = 1;
          size = 1.8 + Math.random() * 1.8;
        }
        const shade = 0.86 + Math.random() * 0.30;
        const sx = Math.random() * 2 - 1;
        const sy = Math.random() * 2 - 1;
        const sz = Math.random() * 2 - 1;
        data[offset++] = x;
        data[offset++] = y;
        data[offset++] = z;
        data[offset++] = clamp(color[0] * shade / 255, 0, 1);
        data[offset++] = clamp(color[1] * shade / 255, 0, 1);
        data[offset++] = clamp(color[2] * shade / 255, 0, 1);
        data[offset++] = sx;
        data[offset++] = sy;
        data[offset++] = sz;
        data[offset++] = size;
        data[offset++] = kind;
      }
    }
    state.field = { data, count: PARTICLE_COUNT };
    uploadField();
  }

  /* ------------------------------------------------------------------ */
  /* Canvas / WebGL sizing                                              */
  /* ------------------------------------------------------------------ */

  function resize() {
    if (!canvas) return;
    const width = Math.max(1, window.innerWidth);
    const height = Math.max(1, window.innerHeight);
    const dpr = Math.min(window.devicePixelRatio || 1, 2.0);
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
    state.centerX = width * 0.5;
    state.centerY = height * (height > width ? 0.455 : 0.49);
    state.radius = Math.min(width, height) * (height > width ? 0.365 : 0.42);
    const glState = state.glState;
    if (glState) {
      glState.gl.viewport(0, 0, canvas.width, canvas.height);
    }
  }

  function clearCanvas() {
    if (!state.glState) return;
    const gl = state.glState.gl;
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
  }

  /* ------------------------------------------------------------------ */
  /* Animation                                                          */
  /* ------------------------------------------------------------------ */

  function start() {
    if (state.raf || state.effect !== EFFECT_PARTICLES || !state.visible) return;
    resize();
    if (!ensureRenderer()) return;
    state.lastTime = 0;
    state.lastFrameAt = 0;
    state.frameAccumulator = frameInterval;
    state.raf = window.requestAnimationFrame(frame);
  }

  function stop() {
    if (state.raf) {
      window.cancelAnimationFrame(state.raf);
      state.raf = 0;
    }
  }

  function tickIfNeeded() {
    if (state.effect === EFFECT_PARTICLES && state.visible) start();
  }

  function frame(now) {
    state.raf = 0;
    if (state.effect !== EFFECT_PARTICLES || !state.visible) return;
    if (!state.glState) {
      if (!ensureRenderer()) return;
    }
    const elapsedSinceFrame = state.lastFrameAt > 0
      ? Math.min(now - state.lastFrameAt, 100)
      : 0;
    state.lastFrameAt = now;
    if (frameInterval > 0) {
      state.frameAccumulator += elapsedSinceFrame;
      if (state.frameAccumulator < frameInterval * 0.92) {
        state.raf = window.requestAnimationFrame(frame);
        return;
      }
      state.frameAccumulator = Math.max(0, state.frameAccumulator - frameInterval);
    } else {
      state.frameAccumulator = 0;
    }
    const dt = state.lastTime ? clamp((now - state.lastTime) / 1000, 0.008, 0.05) : 0.016;
    state.lastTime = now;
    state.time += dt;
    state.drawnFrames += 1;
    updateAudio(dt);
    updateMotion(dt);
    render();
    state.raf = window.requestAnimationFrame(frame);
  }

  function updateAudio(dt) {
    const target = state.audioTarget;
    const smoothing = 1 - Math.pow(0.001, dt);
    state.level += (target.level - state.level) * smoothing * 0.70;
    let sum = 0;
    for (let i = 0; i < 8; i++) {
      state.bands[i] += (target.bands[i] - state.bands[i]) * smoothing * 0.82;
      sum += state.bands[i];
    }
    if (!target.playing) {
      state.level += (0 - state.level) * smoothing * 0.5;
      for (let i = 0; i < 8; i++) state.bands[i] *= 1 - smoothing * 0.4;
      sum = 0;
    }
    const low = (state.bands[0] + state.bands[1] + state.bands[2]) / 3;
    const beat = target.playing
      ? Math.max(target.beat, low * 0.88, sum / 8 * 0.65)
      : 0;
    state.beatEnergy = Math.max(state.beatEnergy * Math.pow(0.03, dt), beat);
    if (target.playing && (target.beat > 0.30 || low > 0.40) &&
      state.time - state.lastBeatAt > 0.14) {
      state.burst = Math.max(
        state.burst,
        (0.55 + beat * 0.55) * (0.35 + state.beatIntensity * 0.65)
      );
      state.lastBeatAt = state.time;
    }
    // A spring envelope: the shell blows open, stays airy for a moment, then
    // pulls back in before the next beat instead of collapsing instantly.
    state.burst = Math.max(
      state.burst * Math.pow(0.015, dt),
      state.beatEnergy * 0.22 * state.beatIntensity
    );
  }

  function updateMotion(dt) {
    if (state.time - state.lastDragAt > 2.2) {
      state.yaw += dt * (0.045 + state.level * 0.11);
    }
    const idle = Math.sin(state.time * 0.31) * 0.035;
    state.tilt = -0.15 + idle + state.userTilt;
    state.zoom = clamp(state.zoom, 0.62, 1.95);
  }

  function unitColor(color, scale) {
    return [
      clamp(color[0] / 255 * scale, 0, 1),
      clamp(color[1] / 255 * scale, 0, 1),
      clamp(color[2] / 255 * scale, 0, 1)
    ];
  }

  function render() {
    const glState = state.glState;
    const field = state.field;
    if (!glState || !field || state.glLost) return;
    const gl = glState.gl;
    const width = canvas.width;
    const height = canvas.height;
    gl.viewport(0, 0, width, height);
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);

    // Atmosphere: a dark cover-tinted field with a soft accent bloom.
    gl.disable(gl.BLEND);
    gl.useProgram(glState.bgProgram);
    gl.bindBuffer(gl.ARRAY_BUFFER, glState.quadBuffer);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.disableVertexAttribArray(1);
    gl.disableVertexAttribArray(2);
    gl.disableVertexAttribArray(3);
    gl.disableVertexAttribArray(4);
    const bu = glState.bgUniforms;
    const top = unitColor(state.coverTop, 0.92);
    const mid = unitColor(state.coverMid, 1.0);
    const bottom = unitColor(state.coverBottom, 0.92);
    const accent = unitColor(state.accent, 0.62);
    gl.uniform3f(bu.uTop, top[0], top[1], top[2]);
    gl.uniform3f(bu.uMid, mid[0], mid[1], mid[2]);
    gl.uniform3f(bu.uBottom, bottom[0], bottom[1], bottom[2]);
    gl.uniform3f(bu.uAccent, accent[0], accent[1], accent[2]);
    gl.uniform2f(bu.uCenter,
      state.centerX / state.width,
      1 - state.centerY / state.height);
    gl.uniform1f(bu.uAspect, width / height);
    gl.uniform1f(bu.uLevel, state.level);
    gl.uniform1f(bu.uBurst, state.burst);
    gl.uniform1f(bu.uTime, state.time);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

    // The dotted shell.
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE);
    gl.useProgram(glState.pointProgram);
    gl.bindBuffer(gl.ARRAY_BUFFER, glState.fieldBuffer);
    gl.enableVertexAttribArray(0);
    gl.enableVertexAttribArray(1);
    gl.enableVertexAttribArray(2);
    gl.enableVertexAttribArray(3);
    gl.enableVertexAttribArray(4);
    const stride = 11 * 4;
    gl.vertexAttribPointer(0, 3, gl.FLOAT, false, stride, 0);
    gl.vertexAttribPointer(1, 3, gl.FLOAT, false, stride, 3 * 4);
    gl.vertexAttribPointer(2, 3, gl.FLOAT, false, stride, 6 * 4);
    gl.vertexAttribPointer(3, 1, gl.FLOAT, false, stride, 9 * 4);
    gl.vertexAttribPointer(4, 1, gl.FLOAT, false, stride, 10 * 4);

    const pu = glState.pointUniforms;
    gl.uniform2f(pu.uResolution, width, height);
    gl.uniform2f(pu.uCenter,
      state.centerX * state.dpr,
      state.centerY * state.dpr);
    gl.uniform1f(pu.uRadius, state.radius * state.zoom * state.dpr);
    gl.uniform1f(pu.uDpr, state.dpr);
    gl.uniform1f(pu.uYaw, state.yaw + state.userYaw);
    gl.uniform1f(pu.uTilt, state.tilt);
    gl.uniform1f(pu.uTime, state.time);
    gl.uniform1f(pu.uLevel, state.level);
    gl.uniform1f(pu.uBurst, state.burst);
    gl.uniform1f(pu.uPlaying, state.audioTarget.playing ? 1 : 0);
    for (let i = 0; i < 8; i++) {
      gl.uniform1f(pu.bands[i], state.bands[i]);
    }
    gl.drawArrays(gl.POINTS, 0, field.count);
    gl.disable(gl.BLEND);
  }

  /* ------------------------------------------------------------------ */
  /* Gestures: drag rotates the sphere, pinch changes its scale         */
  /* ------------------------------------------------------------------ */

  function pointerBlocked(target) {
    return target && target.closest &&
      target.closest("button, input, .menu, .menu-scrim, .topbar, .bottom-wrap, .calibration, .lyrics-loading, .lyrics-empty, .plain-scroll");
  }

  window.addEventListener("pointerdown", event => {
    if (state.effect !== EFFECT_PARTICLES) return;
    if (pointerBlocked(event.target)) return;
    state.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (state.pointers.size === 1) {
      state.lastDragAt = state.time;
      state.dragStart = { x: event.clientX, y: event.clientY };
    } else if (state.pointers.size === 2) {
      const points = Array.from(state.pointers.values());
      state.pinchDistance = Math.hypot(points[0].x - points[1].x,
        points[0].y - points[1].y);
    }
  }, { passive: true, capture: true });

  window.addEventListener("pointermove", event => {
    if (state.effect !== EFFECT_PARTICLES) return;
    const point = state.pointers.get(event.pointerId);
    if (!point) return;
    point.x = event.clientX;
    point.y = event.clientY;
    if (state.pointers.size === 1) {
      const dx = event.clientX - (state.dragStart ? state.dragStart.x : event.clientX);
      const dy = event.clientY - (state.dragStart ? state.dragStart.y : event.clientY);
      if (state.dragStart) {
        state.dragStart = { x: event.clientX, y: event.clientY };
        // Dragging right spins the visible surface right, so the globe turns
        // with the finger instead of showing a mirrored motion.
        state.userYaw += dx * 0.006;
        state.userTilt = clamp(state.userTilt + dy * 0.0032, -0.62, 0.62);
        state.lastDragAt = state.time;
      }
    } else if (state.pointers.size === 2) {
      const points = Array.from(state.pointers.values());
      const distance = Math.hypot(points[0].x - points[1].x,
        points[0].y - points[1].y);
      if (state.pinchDistance > 0) {
        state.zoom = clamp(state.zoom * (distance / state.pinchDistance), 0.62, 1.95);
      }
      state.pinchDistance = distance;
    }
  }, { passive: true, capture: true });

  function endPointer(event) {
    state.pointers.delete(event.pointerId);
    if (state.pointers.size < 2) state.pinchDistance = 0;
    if (state.pointers.size === 1) {
      const point = Array.from(state.pointers.values())[0];
      state.dragStart = { x: point.x, y: point.y };
    } else if (state.pointers.size === 0) {
      state.dragStart = null;
    }
  }

  window.addEventListener("pointerup", endPointer, { passive: true, capture: true });
  window.addEventListener("pointercancel", endPointer, { passive: true, capture: true });

  /* ------------------------------------------------------------------ */
  /* Bridge hooks                                                        */
  /* ------------------------------------------------------------------ */

  const catmusic = window.catmusic || (window.catmusic = {});
  const originalAudio = typeof catmusic.audio === "function" ? catmusic.audio : null;
  const originalPalette = typeof catmusic.palette === "function" ? catmusic.palette : null;
  const originalPlayback = typeof catmusic.playback === "function" ? catmusic.playback : null;

  catmusic.audio = function (payload) {
    if (originalAudio) {
      try { originalAudio(payload); } catch (error) { /* keep the field alive */ }
    }
    if (payload && typeof payload === "object") ingestAudio(payload);
  };

  catmusic.palette = function (payload) {
    if (originalPalette) {
      try { originalPalette(payload); } catch (error) { /* noop */ }
    }
    if (payload && payload.accent) setAccent(payload.accent);
  };

  catmusic.playback = function (payload) {
    if (originalPlayback) {
      try { originalPlayback(payload); } catch (error) { /* noop */ }
    }
    if (payload && typeof payload.playing === "boolean") {
      state.audioTarget.playing = payload.playing;
    }
  };

  catmusic.artwork = function (payload) {
    setArtwork(payload);
  };

  /* ------------------------------------------------------------------ */
  /* Lifecycle                                                          */
  /* ------------------------------------------------------------------ */

  document.addEventListener("visibilitychange", () => {
    state.visible = !document.hidden;
    if (state.visible) start();
    else stop();
  });

  window.addEventListener("resize", () => {
    resize();
    if (state.effect === EFFECT_PARTICLES) start();
  });

  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", () => {
      resize();
      if (state.effect === EFFECT_PARTICLES) start();
    });
  }

  canvas.addEventListener("webglcontextlost", event => {
    event.preventDefault();
    state.glLost = true;
    state.glState = null;
    state.gl = null;
    stop();
  });

  canvas.addEventListener("webglcontextrestored", () => {
    state.glLost = false;
    if (state.effect === EFFECT_PARTICLES) start();
  });

  rebuildFromAccent();
  resize();
  applyEffect(state.effect, false);
  if (state.effect === EFFECT_PARTICLES) start();

  // Expose a tiny test surface for the Android harness and for debugging in
  // chrome://inspect. It is intentionally read-only apart from setEffect.
  window.CatParticles = {
    getEffect: () => state.effect,
    setEffect: effect => applyEffect(effect, true),
    resetView: () => {
      state.yaw = 0;
      state.userYaw = 0;
      state.userTilt = 0;
      state.zoom = 1;
      state.lastDragAt = state.time + 10;
    },
    getDebug: () => ({
      effect: state.effect,
      particles: state.field ? state.field.count : 0,
      renderer: state.glState ? "webgl" : "none",
      level: state.level,
      burst: state.burst,
      accent: state.accent,
      artwork: !!state.artwork,
      yaw: state.yaw + state.userYaw,
      userYaw: state.userYaw,
      userTilt: state.userTilt,
      zoom: state.zoom,
      pointers: state.pointers.size,
      targetFps: targetFps || "unlimited",
      drawnFrames: state.drawnFrames,
      width: state.width,
      height: state.height
    })
  };
})();
