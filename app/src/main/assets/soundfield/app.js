/*
 * CatMusic sound field UI.
 *
 * The page owns the full-screen lyric experience. Kotlin sends transport,
 * timing, artwork and locally decoded audio bands through the small bridge;
 * everything the user sees is rendered here, below the system status bar safe
 * area. There are no remote resources and no microphone access.
 */
(() => {
  "use strict";

  const byId = id => document.getElementById(id);
  const root = document.documentElement;
  const body = document.body;
  const params = new URLSearchParams(window.location.search);

  const dom = {
    boot: byId("boot"),
    trackMeta: byId("track-meta"),
    trackTitle: byId("track-title"),
    trackArtist: byId("track-artist"),
    trackDivider: byId("track-divider"),
    closePage: byId("close-page"),
    openMenu: byId("open-menu"),
    menuLayer: byId("menu-layer"),
    menuScrim: byId("menu-scrim"),
    menuPanel: byId("menu-panel"),
    closeMenu: byId("close-menu"),
    fieldHint: byId("field-hint"),
    effectName: byId("effect-name"),
    effectDescription: byId("effect-description"),
    effectOptions: Array.from(document.querySelectorAll("[data-effect]")),
    beatRange: byId("beat-range"),
    beatValue: byId("beat-value"),
    linesRange: byId("lines-range"),
    linesValue: byId("lines-value"),
    gyroSensitivityRange: byId("gyro-sensitivity-range"),
    gyroSensitivityValue: byId("gyro-sensitivity-value"),
    gyroValue: byId("gyro-value"),
    menuOffset: byId("menu-offset"),
    lyricsStage: byId("lyrics-stage"),
    lyricsPlane: byId("lyrics-plane"),
    lyricsTrack: byId("lyrics-track"),
    lyricsLoading: byId("lyrics-loading"),
    lyricsEmpty: byId("lyrics-empty"),
    plainLyrics: byId("plain-lyrics"),
    plainInner: byId("plain-inner"),
    lyricSeekHud: byId("lyric-seek-hud"),
    lyricSeekTime: byId("lyric-seek-time"),
    lyricSeekHint: byId("lyric-seek-hint"),
    lyricSeekReturn: byId("lyric-seek-return"),
    importEmpty: byId("import-empty"),
    progressArea: byId("progress-area"),
    progressFill: byId("progress-fill"),
    progressThumb: byId("progress-thumb"),
    positionTime: byId("position-time"),
    durationTime: byId("duration-time"),
    playButton: byId("play-button"),
    playIcon: byId("play-icon"),
    pauseIcon: byId("pause-icon"),
    fieldToggle: byId("field-toggle"),
    calibration: byId("calibration"),
    offsetValue: byId("offset-value"),
    offsetMinus: byId("offset-minus"),
    offsetPlus: byId("offset-plus"),
    offsetReset: byId("offset-reset"),
    calibrationDone: byId("calibration-done"),
    toast: byId("toast")
  };

  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const finite = (value, fallback) => {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  };

  function readPreference(key, legacyKey, fallback) {
    try {
      const value = window.localStorage.getItem(key);
      if (value !== null) return value;
      if (legacyKey) {
        const legacy = window.localStorage.getItem(legacyKey);
        if (legacy !== null) return legacy;
      }
    } catch (error) {
      /* localStorage can be unavailable in a private WebView. */
    }
    return fallback;
  }

  function writePreference(key, value) {
    try {
      window.localStorage.setItem(key, String(value));
    } catch (error) {
      /* noop */
    }
  }

  const PREF = {
    beat: "catmusic.soundfield.beat.v2",
    lines: "catmusic.soundfield.lyricLines.v2",
    gyroSensitivity: "catmusic.soundfield.gyroSensitivity.v2",
    gyro: "catmusic.soundfield.gyro.v2"
  };
  const LEGACY = {
    beat: "catmusic.lyric.beat",
    lines: "catmusic.lyric.lines",
    gyroSensitivity: "catmusic.lyric.gyroSensitivity",
    gyro: "catmusic.lyric.gyro"
  };

  const EFFECTS = {
    orbit: {
      label: "星潮",
      hint: "左右拖动可连续旋转封面星潮 360°，上下拖动倾斜视角，双指张合缩放；上下拖动歌词预览跳转，轻点歌词跳转。",
      description: "约一万个按纬线整齐排列的光点按当前封面逐点取色，封面更换时同步换色。低频让球壳呼吸，鼓点将它炸成空心云团。"
    },
    tide: {
      label: "回声",
      hint: "单指拖动环绕回声波面，双指张合缩放；波动跟随低频，鼓点向外推出环状涟漪。上下拖动歌词预览跳转，轻点歌词跳转。",
      description: "多层透视光带组成一片声波水面。低频抬起波谷，节拍向外推开光圈，光带颜色同样取自封面。"
    }
  };

  const state = {
    lines: [],
    hasTiming: false,
    hasLyrics: false,
    loading: true,
    missing: true,
    playing: false,
    positionMs: 0,
    positionClock: performance.now(),
    durationMs: 0,
    offsetMs: 0,
    activeIndex: -1,
    lastAnchor: -1,
    menuOpen: false,
    calibrating: false,
    lineCount: clamp(Math.round(finite(readPreference(PREF.lines, LEGACY.lines, 7), 7)), 3, 10),
    beatIntensity: clamp(finite(readPreference(PREF.beat, LEGACY.beat, 65), 65), 0, 100),
    gyroSensitivity: clamp(
      finite(readPreference(PREF.gyroSensitivity, LEGACY.gyroSensitivity, 100), 100),
      0,
      200
    ),
    gyroRequested: readPreference(PREF.gyro, LEGACY.gyro, "0") === "1",
    gyroEnabled: false,
    gyroAvailable: true,
    gyroBaseline: null,
    gyroLastAt: 0,
    gyroTargetYaw: 0,
    gyroTargetPitch: 0,
    gyroAppliedYaw: 0,
    gyroAppliedPitch: 0,
    gyroPendingTimer: 0,
    lastToggleAt: 0,
    lastAudioAt: 0,
    audioBands: new Float32Array(8),
    audioLevel: 0,
    audioBeat: 0,
    pulse: 0,
    audioClock: performance.now(),
    seeking: false,
    seekPointerId: null,
    seekMs: 0,
    seekStartX: 0,
    seekStartY: 0,
    seekStarted: false,
    lyricSeekPointerId: null,
    lyricSeekItem: null,
    lyricSeekStartX: 0,
    lyricSeekStartY: 0,
    lyricSeekStartIndex: 0,
    lyricSeekIndex: -1,
    lyricSeekMoved: false,
    lyricSeekReturnTimer: 0,
    ignoreLyricClickUntil: 0,
    bootDone: false,
    readySent: false,
    lastProgressKey: "",
    lastTransform: "",
    lastPulseValue: -1,
    lastTickAt: performance.now(),
    demo: null,
    demoLastAudio: 0,
    toastTimer: 0,
    landscape: false,
    compact: false,
    nativeAvailable: !!window.CatMusicBridge
  };

  const field = window.CatField;

  function boostFieldFrameRate() {
    if (field && typeof field.boostFrameRate === "function") field.boostFrameRate();
  }

  function callNative(name, value) {
    const bridge = window.CatMusicBridge;
    if (!bridge || typeof bridge[name] !== "function") return false;
    try {
      if (arguments.length > 1) bridge[name](value);
      else bridge[name]();
      return true;
    } catch (error) {
      console.warn("[CatMusic] bridge call failed:", name, error);
      return false;
    }
  }

  function showToast(message) {
    if (!dom.toast) return;
    dom.toast.textContent = message;
    dom.toast.classList.add("visible");
    clearTimeout(state.toastTimer);
    state.toastTimer = window.setTimeout(() => {
      dom.toast.classList.remove("visible");
    }, 1900);
  }

  function formatTime(ms) {
    const total = Math.max(0, Math.round(finite(ms, 0) / 1000));
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
  }

  function formatOffset(ms, withUnit) {
    const seconds = finite(ms, 0) / 1000;
    const sign = seconds < 0 ? "−" : "+";
    const value = Math.abs(seconds).toFixed(1);
    return sign + value + (withUnit ? " 秒" : "s");
  }

  function currentPosition() {
    if (state.seeking) return state.seekMs;
    let position = state.positionMs;
    if (state.playing) position += performance.now() - state.positionClock;
    if (state.durationMs > 0) position = Math.min(position, state.durationMs);
    return Math.max(0, position);
  }

  function timelinePosition(event) {
    const rect = dom.progressArea.getBoundingClientRect();
    if (rect.width <= 0) return 0;
    const ratio = clamp((event.clientX - rect.left) / rect.width, 0, 1);
    return ratio * Math.max(1, state.durationMs);
  }

  /* ------------------------------------------------------------------ */
  /* Transport                                                           */
  /* ------------------------------------------------------------------ */

  function updatePlayIcon() {
    if (!dom.playIcon || !dom.pauseIcon) return;
    if (state.playing) {
      dom.playIcon.setAttribute("hidden", "");
      dom.pauseIcon.removeAttribute("hidden");
      dom.playButton.setAttribute("aria-label", "暂停");
      dom.playButton.setAttribute("data-playing", "true");
    } else {
      dom.playIcon.removeAttribute("hidden");
      dom.pauseIcon.setAttribute("hidden", "");
      dom.playButton.setAttribute("aria-label", "播放");
      dom.playButton.removeAttribute("data-playing");
    }
  }

  function updateProgress(position) {
    if (!dom.progressFill || !dom.progressThumb) return;
    const ratio = state.durationMs > 0
      ? clamp(position / state.durationMs, 0, 1)
      : 0;
    const key = ratio.toFixed(4);
    if (key !== state.lastProgressKey) {
      state.lastProgressKey = key;
      dom.progressFill.style.transform =
        "translateY(-50%) scaleX(" + ratio.toFixed(5) + ")";
      dom.progressThumb.style.left = (ratio * 100).toFixed(3) + "%";
      dom.positionTime.textContent = formatTime(position);
    }
    if (dom.durationTime.textContent !== formatTime(state.durationMs)) {
      dom.durationTime.textContent = formatTime(state.durationMs);
    }
  }

  function setLocalPosition(position) {
    state.positionMs = Math.max(0, finite(position, 0));
    state.positionClock = performance.now();
    updateProgress(currentPosition());
  }

  function seekTo(position) {
    const value = Math.max(0, Math.round(finite(position, 0)));
    setLocalPosition(value);
    callNative("seek", value);
    updateActive(true);
  }

  function onSeekDown(event) {
    if (state.durationMs <= 0) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (state.seekPointerId !== null) return;
    event.preventDefault();
    state.seekPointerId = event.pointerId;
    state.seeking = true;
    state.seekStarted = true;
    state.seekStartX = event.clientX;
    state.seekStartY = event.clientY;
    state.seekMs = timelinePosition(event);
    dom.progressArea.classList.add("dragging");
    if (dom.progressArea.setPointerCapture) {
      try {
        dom.progressArea.setPointerCapture(event.pointerId);
      } catch (error) {
        /* Some WebViews do not allow capture on the first touch. */
      }
    }
    updateProgress(state.seekMs);
    updateActive(true);
  }

  function onSeekMove(event) {
    if (state.seekPointerId !== event.pointerId || !state.seeking) return;
    event.preventDefault();
    state.seekMs = timelinePosition(event);
    updateProgress(state.seekMs);
    updateActive(true);
  }

  function finishSeek(event, commit) {
    if (state.seekPointerId !== event.pointerId) return;
    if (dom.progressArea.releasePointerCapture) {
      try {
        dom.progressArea.releasePointerCapture(event.pointerId);
      } catch (error) {
        /* noop */
      }
    }
    state.seekPointerId = null;
    state.seeking = false;
    dom.progressArea.classList.remove("dragging");
    if (commit && state.seekStarted) {
      const value = Math.round(state.seekMs);
      state.positionMs = value;
      state.positionClock = performance.now();
      updateProgress(value);
      updateActive(true);
      callNative("seek", value);
    }
    state.seekStarted = false;
    updateProgress(currentPosition());
  }

  function onSeekUp(event) {
    finishSeek(event, true);
  }

  function onSeekCancel(event) {
    finishSeek(event, false);
  }

  /* ------------------------------------------------------------------ */
  /* Lyrics                                                              */
  /* ------------------------------------------------------------------ */

  function setLyricsView(view) {
    const timed = view === "timed";
    const plain = view === "plain";
    dom.lyricsLoading.hidden = view !== "loading";
    dom.lyricsEmpty.hidden = view !== "empty";
    dom.lyricsPlane.hidden = !timed;
    dom.plainLyrics.hidden = !plain;
  }

  function clearLyricDom() {
    clearLyricSeekPreview(false);
    dom.lyricsTrack.replaceChildren();
    dom.plainInner.replaceChildren();
    state.lines = [];
    state.activeIndex = -1;
    state.lastAnchor = -1;
  }

  function applyLyrics(payload) {
    if (!payload || typeof payload !== "object") return;
    boostFieldFrameRate();
    if (payload.loading) {
      state.loading = true;
      state.hasLyrics = false;
      state.hasTiming = false;
      state.missing = false;
      clearLyricDom();
      setLyricsView("loading");
      updateCalibrationAvailability();
      return;
    }

    const lines = (Array.isArray(payload.lines) ? payload.lines : [])
      .map(line => ({
        t: Math.max(0, finite(line && line.t, 0)),
        text: String((line && line.text) || ""),
        tr: line && line.tr ? String(line.tr) : ""
      }))
      .filter(line => line.text.length > 0);

    state.loading = false;
    state.lines = lines;
    state.hasLyrics = lines.length > 0;
    state.hasTiming = !!(payload.hasTiming && lines.length > 0);
    state.missing = !state.hasLyrics;
    state.activeIndex = -1;
    state.lastAnchor = -1;

    if (!state.hasLyrics) {
      dom.lyricsTrack.replaceChildren();
      dom.plainInner.replaceChildren();
      setLyricsView("empty");
      updateCalibrationAvailability();
      return;
    }

    if (state.hasTiming) {
      buildTimedLyrics(lines);
      setLyricsView("timed");
    } else {
      buildPlainLyrics(lines);
      setLyricsView("plain");
    }
    updateCalibrationAvailability();
  }

  function buildTimedLyrics(lines) {
    const fragment = document.createDocumentFragment();
    for (let index = 0; index < lines.length; index++) {
      const line = lines[index];
      const item = document.createElement("div");
      item.className = "lyric-line";
      item.dataset.index = String(index);
      item.setAttribute("role", "button");
      item.setAttribute("tabindex", "0");
      item.setAttribute("aria-label", (line.text || "") + "，跳转到这一句");

      const text = document.createElement("div");
      text.className = "lyric-text";
      text.textContent = line.text;
      item.appendChild(text);

      if (line.tr) {
        const translation = document.createElement("div");
        translation.className = "lyric-translation";
        translation.textContent = line.tr;
        item.appendChild(translation);
      }

      const activate = event => {
        if (!state.hasTiming || state.calibrating) return;
        if (performance.now() < state.ignoreLyricClickUntil) {
          if (event && event.cancelable) event.preventDefault();
          return;
        }
        if (state.lyricSeekIndex >= 0) {
          if (event && event.cancelable) event.preventDefault();
          commitLyricSeekPreview();
          return;
        }
        seekTo(line.t + state.offsetMs);
      };
      item.addEventListener("click", activate);
      item.addEventListener("pointerdown", onLyricSeekDown);
      item.addEventListener("pointermove", onLyricSeekMove, { passive: false });
      item.addEventListener("pointerup", onLyricSeekUp);
      item.addEventListener("pointercancel", onLyricSeekCancel);
      item.addEventListener("keydown", event => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          activate(event);
        }
      });
      fragment.appendChild(item);
    }
    dom.lyricsTrack.replaceChildren(fragment);
    dom.lyricsTrack.classList.add("no-transition");
    updateActive(false);
    requestAnimationFrame(() => {
      updateMetrics();
      scrollToActive(false);
      requestAnimationFrame(() => dom.lyricsTrack.classList.remove("no-transition"));
    });
  }

  function buildPlainLyrics(lines) {
    const fragment = document.createDocumentFragment();
    lines.forEach(line => {
      const item = document.createElement("div");
      item.className = "plain-line";
      item.textContent = line.text;
      if (line.tr) {
        const translation = document.createElement("small");
        translation.textContent = line.tr;
        item.appendChild(translation);
      }
      fragment.appendChild(item);
    });
    dom.plainInner.replaceChildren(fragment);
    dom.plainLyrics.scrollTop = 0;
    updateMetrics();
  }

  function activeIndexFor(position) {
    if (!state.hasTiming || state.lines.length === 0) return -1;
    const time = position - state.offsetMs;
    if (time < state.lines[0].t) return -1;
    let low = 0;
    let high = state.lines.length - 1;
    let found = 0;
    while (low <= high) {
      const middle = (low + high) >> 1;
      if (state.lines[middle].t <= time) {
        found = middle;
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return found;
  }

  function lyricSeekTimeMs(index) {
    if (state.lines.length === 0) return 0;
    const line = state.lines[clamp(index, 0, state.lines.length - 1)];
    if (!line) return 0;
    return Math.max(0, line.t + state.offsetMs);
  }

  function lyricLinePitch() {
    const items = dom.lyricsTrack.children;
    const center = state.lyricSeekIndex >= 0
      ? state.lyricSeekIndex
      : (state.activeIndex >= 0 ? state.activeIndex : 0);
    const item = items[clamp(center, 0, items.length - 1)];
    const height = item ? item.getBoundingClientRect().height : 44;
    return clamp(height || 44, 28, 88);
  }

  function showLyricSeekHud() {
    if (!dom.lyricSeekHud) return;
    dom.lyricSeekHud.hidden = false;
    requestAnimationFrame(() => {
      if (dom.lyricSeekHud) dom.lyricSeekHud.classList.add("visible");
    });
  }

  function hideLyricSeekHud() {
    if (!dom.lyricSeekHud) return;
    dom.lyricSeekHud.classList.remove("visible");
    dom.lyricSeekHud.hidden = true;
  }

  function idleLyricSeekHud() {
    if (dom.lyricSeekHint) dom.lyricSeekHint.textContent = "点击跳转";
    if (dom.lyricSeekReturn) dom.lyricSeekReturn.textContent = "3 秒后返回";
  }

  function setLyricSeekPreview(index, dragging) {
    if (!state.hasTiming || state.lines.length === 0) return;
    const next = clamp(Math.round(index), 0, state.lines.length - 1);
    state.lyricSeekIndex = next;
    if (dom.lyricSeekTime) {
      dom.lyricSeekTime.textContent = formatTime(lyricSeekTimeMs(next));
    }
    if (dom.lyricSeekHint) {
      dom.lyricSeekHint.textContent = dragging
        ? "上滑快进 · 下滑回退"
        : "点击跳转";
    }
    if (dom.lyricSeekReturn) {
      dom.lyricSeekReturn.textContent = dragging ? "松手后点击" : "3 秒后返回";
    }
    showLyricSeekHud();
    updateLineWindow();
    scrollToActive(false);
  }

  function clearLyricSeekPreview(restore) {
    clearTimeout(state.lyricSeekReturnTimer);
    state.lyricSeekReturnTimer = 0;
    state.lyricSeekPointerId = null;
    state.lyricSeekItem = null;
    state.lyricSeekMoved = false;
    const hadPreview = state.lyricSeekIndex >= 0;
    state.lyricSeekIndex = -1;
    hideLyricSeekHud();
    if (restore !== false && hadPreview) updateActive(true);
  }

  function scheduleLyricSeekReturn() {
    clearTimeout(state.lyricSeekReturnTimer);
    state.lyricSeekReturnTimer = window.setTimeout(() => {
      if (state.lyricSeekPointerId !== null) return;
      if (state.lyricSeekIndex < 0) return;
      clearLyricSeekPreview(true);
    }, 3000);
  }

  function commitLyricSeekPreview() {
    if (state.lyricSeekIndex < 0) return;
    const target = lyricSeekTimeMs(state.lyricSeekIndex);
    clearLyricSeekPreview(false);
    seekTo(target);
  }

  function onLyricSeekDown(event) {
    if (!state.hasTiming || state.calibrating || state.menuOpen) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (state.lyricSeekPointerId !== null) return;
    // Pause the return countdown while a finger is down so a tap landing near
    // the deadline still commits instead of being cleared first.
    clearTimeout(state.lyricSeekReturnTimer);
    state.lyricSeekReturnTimer = 0;
    state.lyricSeekPointerId = event.pointerId;
    state.lyricSeekItem = event.currentTarget;
    state.lyricSeekStartX = event.clientX;
    state.lyricSeekStartY = event.clientY;
    state.lyricSeekStartIndex = state.lyricSeekIndex >= 0
      ? state.lyricSeekIndex
      : (state.activeIndex >= 0 ? state.activeIndex : 0);
    state.lyricSeekMoved = false;
    if (state.lyricSeekItem && state.lyricSeekItem.setPointerCapture) {
      try {
        state.lyricSeekItem.setPointerCapture(event.pointerId);
      } catch (error) {
        /* Some WebViews reject capture on the initial touch. */
      }
    }
  }

  function onLyricSeekMove(event) {
    if (state.lyricSeekPointerId !== event.pointerId) return;
    const dx = event.clientX - state.lyricSeekStartX;
    const dy = event.clientY - state.lyricSeekStartY;
    if (!state.lyricSeekMoved) {
      if (Math.abs(dy) <= 8 || Math.abs(dy) <= Math.abs(dx)) return;
      state.lyricSeekMoved = true;
      setLyricSeekPreview(state.lyricSeekStartIndex, true);
    }
    if (event.cancelable) event.preventDefault();
    const steps = Math.round(-dy / lyricLinePitch());
    const next = clamp(
      state.lyricSeekStartIndex + steps,
      0,
      state.lines.length - 1
    );
    if (next !== state.lyricSeekIndex) setLyricSeekPreview(next, true);
  }

  function finishLyricSeek(event, commit) {
    if (state.lyricSeekPointerId !== event.pointerId) return;
    if (state.lyricSeekItem && state.lyricSeekItem.releasePointerCapture) {
      try {
        state.lyricSeekItem.releasePointerCapture(event.pointerId);
      } catch (error) {
        /* noop */
      }
    }
    const moved = state.lyricSeekMoved;
    state.lyricSeekPointerId = null;
    state.lyricSeekItem = null;
    state.lyricSeekMoved = false;
    if (moved) {
      state.ignoreLyricClickUntil = performance.now() + 600;
      if (event.cancelable) event.preventDefault();
      if (commit && state.lyricSeekIndex >= 0) {
        idleLyricSeekHud();
        scheduleLyricSeekReturn();
      }
      return;
    }
    if (commit && state.lyricSeekIndex >= 0) {
      state.ignoreLyricClickUntil = performance.now() + 600;
      if (event.cancelable) event.preventDefault();
      commitLyricSeekPreview();
    }
  }

  function onLyricSeekUp(event) {
    finishLyricSeek(event, true);
  }

  function onLyricSeekCancel(event) {
    if (state.lyricSeekPointerId !== event.pointerId) return;
    const wasMoved = state.lyricSeekMoved;
    state.lyricSeekPointerId = null;
    state.lyricSeekItem = null;
    state.lyricSeekMoved = false;
    if (wasMoved && state.lyricSeekIndex >= 0) {
      state.ignoreLyricClickUntil = performance.now() + 600;
      idleLyricSeekHud();
      scheduleLyricSeekReturn();
    }
  }

  function updateLineWindow() {
    if (!state.hasTiming) return;
    const items = dom.lyricsTrack.children;
    if (!items.length) return;
    const center = state.lyricSeekIndex >= 0
      ? state.lyricSeekIndex
      : (state.activeIndex >= 0 ? state.activeIndex : 0);
    const half = Math.floor((state.lineCount - 1) / 2);
    const first = center - half;
    const last = first + state.lineCount - 1;

    for (let index = 0; index < items.length; index++) {
      const item = items[index];
      let className = "lyric-line";
      if (index === state.lyricSeekIndex) className += " seek-target";
      else if (index === state.activeIndex) className += " active";
      else if (Math.abs(index - center) <= 1) className += " near";
      else if (Math.abs(index - center) === 2) className += " far";
      else className += " faint";
      if (index < first || index > last) className += " off";
      if (item.className !== className) item.className = className;
    }
  }

  function scrollToActive(smooth) {
    if (!state.hasTiming) return;
    const items = dom.lyricsTrack.children;
    if (!items.length) return;
    const anchor = state.lyricSeekIndex >= 0
      ? state.lyricSeekIndex
      : (state.activeIndex >= 0 ? state.activeIndex : 0);
    const item = items[clamp(anchor, 0, items.length - 1)];
    if (!item) return;
    const offset = -(item.offsetTop + item.offsetHeight / 2);
    if (!smooth) {
      dom.lyricsTrack.classList.add("no-transition");
      dom.lyricsTrack.style.transform = "translate3d(0," + offset + "px,0)";
      // Force layout before re-enabling the transition.
      void dom.lyricsTrack.offsetHeight;
      requestAnimationFrame(() => dom.lyricsTrack.classList.remove("no-transition"));
    } else {
      dom.lyricsTrack.style.transform = "translate3d(0," + offset + "px,0)";
    }
    state.lastAnchor = anchor;
  }

  function updateActive(forceScroll) {
    if (!state.hasTiming) return;
    const index = activeIndexFor(currentPosition());
    if (index !== state.activeIndex) {
      state.activeIndex = index;
      boostFieldFrameRate();
      updateLineWindow();
      scrollToActive(true);
    } else if (forceScroll) {
      updateLineWindow();
      scrollToActive(false);
    }
  }

  function applyOffset(value, notify) {
    const offset = clamp(Math.round(finite(value, 0)), -5000, 5000);
    if (offset === state.offsetMs) {
      updateOffsetLabels();
      return;
    }
    state.offsetMs = offset;
    updateOffsetLabels();
    updateActive(true);
    if (notify) callNative("setOffset", offset);
  }

  function updateOffsetLabels() {
    const full = formatOffset(state.offsetMs, true);
    const compact = formatOffset(state.offsetMs, false);
    if (dom.offsetValue) dom.offsetValue.textContent = full;
    if (dom.menuOffset) dom.menuOffset.textContent = compact;
  }

  function updateCalibrationAvailability() {
    const button = dom.menuPanel.querySelector('[data-action="calibrate"]');
    if (button) button.classList.toggle("disabled", !state.hasTiming);
  }

  function enterCalibration() {
    if (!state.hasTiming) {
      showToast("当前歌词没有时间轴");
      return;
    }
    closeMenu(false);
    state.calibrating = true;
    body.classList.add("calibrating");
    dom.calibration.hidden = false;
    updateOffsetLabels();
    requestAnimationFrame(() => {
      updateMetrics();
      scrollToActive(false);
    });
  }

  function exitCalibration() {
    state.calibrating = false;
    body.classList.remove("calibrating");
    dom.calibration.hidden = true;
    requestAnimationFrame(() => {
      updateMetrics();
      scrollToActive(false);
      updateActive(false);
    });
  }

  /* ------------------------------------------------------------------ */
  /* Layout                                                              */
  /* ------------------------------------------------------------------ */

  function setOrientationClass() {
    const width = Math.max(1, window.innerWidth);
    const height = Math.max(1, window.innerHeight);
    const landscape = width > height * 1.02;
    const compact = landscape && height < 430;
    state.landscape = landscape;
    state.compact = compact;
    root.classList.toggle("layout-landscape", landscape);
    root.classList.toggle("layout-compact", compact);
    body.classList.toggle("layout-landscape", landscape);
    body.classList.toggle("layout-compact", compact);
    root.style.setProperty("--app-vh", (height / 100) + "px");
  }

  function updateMenuHeight() {
    if (!dom.menuPanel) return;
    const styles = window.getComputedStyle(root);
    const top = parseFloat(styles.getPropertyValue("--safe-top")) || 0;
    const bottom = parseFloat(styles.getPropertyValue("--safe-bottom")) || 0;
    const available = Math.max(220, window.innerHeight - Math.max(top, bottom) * 2 - 18);
    dom.menuPanel.style.maxHeight = Math.round(available) + "px";
  }

  function updateMetrics() {
    setOrientationClass();
    updateMenuHeight();
    if (!dom.lyricsStage) return;
    const rect = dom.lyricsStage.getBoundingClientRect();
    if (rect.height <= 0) return;
    const landscape = state.landscape;
    const count = state.lineCount;
    const rowHeight = rect.height / (count + 0.68);
    const lineSize = landscape
      ? clamp(rowHeight * 0.44, 12.5, 27)
      : clamp(rowHeight * 0.34, 16, 33);
    const translationSize = clamp(lineSize * (landscape ? 0.50 : 0.48), 8, 17);
    const padding = clamp(rowHeight * (landscape ? 0.055 : 0.085), 1.5, 9);
    const gap = clamp(lineSize * 0.105, 2, 5);
    root.style.setProperty("--line-size", lineSize.toFixed(2) + "px");
    root.style.setProperty("--translation-size", translationSize.toFixed(2) + "px");
    root.style.setProperty("--line-pad-y", padding.toFixed(2) + "px");
    root.style.setProperty("--translation-gap", gap.toFixed(2) + "px");
    if (state.hasTiming) {
      updateLineWindow();
      scrollToActive(false);
    }
  }

  let layoutScheduled = false;
  function scheduleLayout() {
    if (layoutScheduled) return;
    layoutScheduled = true;
    requestAnimationFrame(() => {
      layoutScheduled = false;
      updateMetrics();
    });
  }

  /* ------------------------------------------------------------------ */
  /* Effect + audio                                                      */
  /* ------------------------------------------------------------------ */

  function updateEffectCopy(effect) {
    const info = EFFECTS[effect] || EFFECTS.orbit;
    if (dom.effectName) dom.effectName.textContent = info.label;
    if (dom.fieldHint) dom.fieldHint.textContent = info.hint;
    if (dom.effectDescription) dom.effectDescription.textContent = info.description;
    dom.effectOptions.forEach(button => {
      button.setAttribute(
        "aria-pressed",
        button.dataset.effect === effect ? "true" : "false"
      );
    });
  }

  function setEffect(effect) {
    const next = effect === "tide" ? "tide" : "orbit";
    if (field) field.setEffect(next);
    updateEffectCopy(next);
  }

  function ingestAudio(payload) {
    if (!payload || typeof payload !== "object") return;
    const bands = payload.bands;
    if (Array.isArray(bands) || ArrayBuffer.isView(bands)) {
      for (let index = 0; index < 8; index++) {
        const value = Number(bands[index]);
        state.audioBands[index] = Number.isFinite(value)
          ? clamp(value, 0, 1)
          : 0;
      }
    }
    state.audioBeat = clamp(finite(payload.beat, 0), 0, 1);
    state.audioLevel = clamp(finite(payload.level, 0), 0, 1);
    state.lastAudioAt = performance.now();
    if (typeof payload.playing === "boolean") {
      const now = performance.now();
      if (payload.playing !== state.playing && now - state.lastToggleAt > 900) {
        if (!payload.playing) state.positionMs = currentPosition();
        state.playing = payload.playing;
        state.positionClock = now;
        updatePlayIcon();
      }
    }
    if (field) field.setAudio(payload);
  }

  function updateAudioUi(now) {
    const dt = clamp((now - state.lastTickAt) / 1000, 0.008, 0.05);
    state.lastTickAt = now;
    let sum = 0;
    for (let index = 0; index < 8; index++) sum += state.audioBands[index];
    const low = (state.audioBands[0] + state.audioBands[1] +
      state.audioBands[2]) / 3;
    const target = state.playing
      ? Math.max(state.audioBeat, low * 0.86, sum / 8 * 0.60)
      : 0;
    state.pulse = Math.max(state.pulse * Math.pow(0.02, dt), target * 0.92);
    const pulse = Math.round(state.pulse * 1000) / 1000;
    if (pulse !== state.lastPulseValue) {
      state.lastPulseValue = pulse;
      root.style.setProperty("--beat-pulse", pulse.toFixed(3));
    }
  }

  /* ------------------------------------------------------------------ */
  /* Gyroscope                                                           */
  /* ------------------------------------------------------------------ */

  function updateGyroDisplay(pending) {
    if (!dom.gyroValue) return;
    if (!state.gyroAvailable) dom.gyroValue.textContent = "不可用";
    else if (pending) dom.gyroValue.textContent = "开启中…";
    else dom.gyroValue.textContent = state.gyroEnabled ? "开" : "关";
  }

  function clearGyroMotion() {
    state.gyroTargetYaw = 0;
    state.gyroTargetPitch = 0;
    state.gyroBaseline = null;
    if (field) field.clearGyro();
  }

  function applyGyroState(payload) {
    if (!payload) return;
    clearTimeout(state.gyroPendingTimer);
    const available = payload.available !== false;
    const enabled = available && !!payload.enabled;
    state.gyroAvailable = available;
    state.gyroEnabled = enabled;
    state.gyroRequested = enabled;
    writePreference(PREF.gyro, enabled ? "1" : "0");
    if (enabled) {
      state.gyroBaseline = null;
      state.gyroLastAt = 0;
    } else {
      clearGyroMotion();
    }
    updateGyroDisplay(false);
    if (!available) showToast("此设备没有可用的方向传感器");
  }

  function toggleGyro() {
    if (!state.nativeAvailable) {
      state.gyroAvailable = false;
      updateGyroDisplay(false);
      showToast("当前环境不支持陀螺仪");
      return;
    }
    if (!state.gyroAvailable) {
      showToast("此设备没有可用的方向传感器");
      return;
    }
    const next = !state.gyroRequested;
    state.gyroRequested = next;
    if (!next) {
      state.gyroEnabled = false;
      clearGyroMotion();
      writePreference(PREF.gyro, "0");
    } else {
      updateGyroDisplay(true);
    }
    const called = callNative("setGyroEnabled", next);
    if (!called) {
      state.gyroRequested = false;
      state.gyroAvailable = false;
      clearGyroMotion();
      updateGyroDisplay(false);
      showToast("当前环境不支持陀螺仪");
      return;
    }
    clearTimeout(state.gyroPendingTimer);
    state.gyroPendingTimer = window.setTimeout(() => {
      if (!state.gyroEnabled && state.gyroRequested) updateGyroDisplay(false);
    }, 2600);
  }

  function onOrientation(payload) {
    if (!state.gyroEnabled || !payload) return;
    const pitch = finite(payload.p, NaN);
    const roll = finite(payload.r, NaN);
    if (!Number.isFinite(pitch) || !Number.isFinite(roll)) return;
    const now = performance.now();
    if (!state.gyroBaseline || now - state.gyroLastAt > 650) {
      state.gyroBaseline = { p: pitch, r: roll };
      state.gyroLastAt = now;
      return;
    }
    state.gyroLastAt = now;
    let deltaPitch = pitch - state.gyroBaseline.p;
    let deltaRoll = roll - state.gyroBaseline.r;
    deltaPitch = Math.atan2(Math.sin(deltaPitch), Math.cos(deltaPitch));
    deltaRoll = Math.atan2(Math.sin(deltaRoll), Math.cos(deltaRoll));
    const pitchDegrees = deltaPitch * 180 / Math.PI;
    const rollDegrees = deltaRoll * 180 / Math.PI;
    const sensitivity = clamp(state.gyroSensitivity / 100, 0, 2);
    const portrait = window.innerHeight >= window.innerWidth;
    const yaw = (portrait ? -rollDegrees * 0.55 : pitchDegrees * 0.55) * sensitivity;
    const tilt = (portrait ? pitchDegrees * 0.42 : -rollDegrees * 0.42) * sensitivity;
    state.gyroTargetYaw = clamp(yaw, -22, 22);
    state.gyroTargetPitch = clamp(tilt, -15, 15);
    if (field) field.setGyro(state.gyroTargetYaw, state.gyroTargetPitch);
  }

  /* ------------------------------------------------------------------ */
  /* UI tick                                                             */
  /* ------------------------------------------------------------------ */

  function updateLyricPlane(now, dt) {
    const response = 1 - Math.pow(0.006, dt);
    state.gyroAppliedYaw += (state.gyroTargetYaw - state.gyroAppliedYaw) * response;
    state.gyroAppliedPitch += (state.gyroTargetPitch - state.gyroAppliedPitch) * response;
    if (Math.abs(state.gyroAppliedYaw) < 0.01) state.gyroAppliedYaw = 0;
    if (Math.abs(state.gyroAppliedPitch) < 0.01) state.gyroAppliedPitch = 0;
    const breath = Math.sin(now * 0.00036) * 0.28;
    const rotateX = clamp(-state.gyroAppliedPitch * 0.56, -8.4, 8.4) + breath;
    const rotateY = clamp(state.gyroAppliedYaw * 0.64, -14, 14);
    const rotateZ = clamp(-state.gyroAppliedYaw * 0.17, -4, 4);
    const scale = 1 + state.pulse * 0.010;
    const transform =
      "rotateX(" + rotateX.toFixed(2) + "deg) " +
      "rotateY(" + rotateY.toFixed(2) + "deg) " +
      "rotateZ(" + rotateZ.toFixed(2) + "deg) " +
      "scale(" + scale.toFixed(4) + ")";
    if (transform !== state.lastTransform) {
      state.lastTransform = transform;
      dom.lyricsPlane.style.transform = transform;
    }
  }

  function runDemo(now) {
    if (!state.demo) return;
    const elapsed = (now - state.demo.started) / 1000;
    const cycle = elapsed % 52;
    state.positionMs = cycle * 1000;
    state.positionClock = now;
    state.durationMs = 52000;
    state.playing = true;
    if (now - state.demoLastAudio > 70) {
      state.demoLastAudio = now;
      const phase = elapsed * 0.72;
      const level = 0.10 + 0.19 * (0.5 + 0.5 * Math.sin(phase * 0.58));
      const bands = new Float32Array(8);
      for (let index = 0; index < 8; index++) {
        bands[index] = clamp(
          0.025 + level * 0.72 *
          (0.25 + 0.75 * Math.abs(Math.sin(phase * (0.68 + index * 0.13) + index * 0.9))),
          0,
          1
        );
      }
      const beat = Math.max(0, Math.sin(phase * 2.7)) * 0.78;
      ingestAudio({
        bands: Array.from(bands),
        beat: beat,
        level: level,
        playing: true
      });
    }
  }

  function uiTick(now) {
    const dt = clamp((now - state.lastTickAt) / 1000, 0.008, 0.05);
    if (state.demo) runDemo(now);
    updateAudioUi(now);
    const position = currentPosition();
    updateProgress(position);
    updateActive(false);
    updateLyricPlane(now, dt);
    state.lastTickAt = now;
    requestAnimationFrame(uiTick);
  }

  /* ------------------------------------------------------------------ */
  /* Menu                                                               */
  /* ------------------------------------------------------------------ */

  function setMenuOpen(open, restoreFocus) {
    if (open === state.menuOpen) return;
    state.menuOpen = open;
    boostFieldFrameRate();
    dom.openMenu.setAttribute("aria-expanded", open ? "true" : "false");
    if (open) {
      updateMenuHeight();
      updateCalibrationAvailability();
      dom.menuLayer.hidden = false;
      dom.menuPanel.scrollTop = 0;
      requestAnimationFrame(() => dom.closeMenu.focus({ preventScroll: true }));
    } else {
      dom.menuLayer.hidden = true;
      if (restoreFocus !== false) {
        requestAnimationFrame(() => dom.openMenu.focus({ preventScroll: true }));
      }
    }
  }

  function closeMenu(restoreFocus) {
    setMenuOpen(false, restoreFocus);
  }

  function handleMenuAction(action) {
    if (action === "gyro") {
      toggleGyro();
    } else if (action === "reset-view") {
      if (field) field.resetView();
      state.gyroTargetYaw = 0;
      state.gyroTargetPitch = 0;
    } else if (action === "calibrate") {
      if (!state.hasTiming) return;
      enterCalibration();
    } else if (action === "import") {
      closeMenu(false);
      if (!callNative("importLyrics")) showToast("当前环境无法导入歌词");
    } else if (action === "close-soundfield") {
      closeMenu(false);
      callNative("toggleAudioMode");
    } else if (action === "back-cover") {
      closeMenu(false);
      callNative("close");
    }
  }

  /* ------------------------------------------------------------------ */
  /* Bridge / init                                                       */
  /* ------------------------------------------------------------------ */

  function signalReady() {
    if (state.readySent) return;
    state.readySent = true;
    callNative("ready");
    if (state.gyroRequested && state.nativeAvailable) {
      window.setTimeout(() => {
        if (state.gyroRequested && !state.gyroEnabled) {
          const called = callNative("setGyroEnabled", true);
          if (!called) {
            state.gyroRequested = false;
            updateGyroDisplay(false);
          }
        }
      }, 320);
    }
  }

  function finishBoot() {
    if (state.bootDone) return;
    state.bootDone = true;
    dom.boot.classList.add("hidden");
    document.body.classList.add("stage-ready");
    window.setTimeout(signalReady, 220);
  }

  function bindEvents() {
    dom.closePage.addEventListener("click", () => callNative("close"));
    dom.openMenu.addEventListener("click", () => setMenuOpen(!state.menuOpen));
    dom.closeMenu.addEventListener("click", () => closeMenu());
    dom.menuScrim.addEventListener("click", () => closeMenu());

    dom.effectOptions.forEach(button => {
      button.addEventListener("click", () => setEffect(button.dataset.effect));
    });

    dom.menuPanel.addEventListener("click", event => {
      const action = event.target.closest("[data-action]");
      if (!action || action.classList.contains("disabled")) return;
      handleMenuAction(action.dataset.action);
    });

    dom.beatRange.addEventListener("input", () => {
      state.beatIntensity = clamp(
        Math.round(finite(dom.beatRange.value, 65)),
        0,
        100
      );
      dom.beatValue.textContent = Math.round(state.beatIntensity) + "%";
      writePreference(PREF.beat, Math.round(state.beatIntensity));
      if (field) field.setBeatIntensity(state.beatIntensity / 100);
    });

    dom.linesRange.addEventListener("input", () => {
      state.lineCount = clamp(
        Math.round(finite(dom.linesRange.value, 7)),
        3,
        10
      );
      dom.linesValue.textContent = state.lineCount + " 行";
      writePreference(PREF.lines, state.lineCount);
      updateMetrics();
    });

    dom.gyroSensitivityRange.addEventListener("input", () => {
      state.gyroSensitivity = clamp(
        Math.round(finite(dom.gyroSensitivityRange.value, 100)),
        0,
        200
      );
      dom.gyroSensitivityValue.textContent = Math.round(state.gyroSensitivity) + "%";
      writePreference(PREF.gyroSensitivity, Math.round(state.gyroSensitivity));
    });

    dom.playButton.addEventListener("click", () => {
      const position = currentPosition();
      const next = !state.playing;
      state.playing = next;
      state.lastToggleAt = performance.now();
      state.positionMs = position;
      state.positionClock = state.lastToggleAt;
      updatePlayIcon();
      callNative("toggle");
    });

    dom.fieldToggle.addEventListener("click", () => callNative("toggleAudioMode"));
    dom.importEmpty.addEventListener("click", () => {
      if (!callNative("importLyrics")) showToast("当前环境无法导入歌词");
    });

    dom.progressArea.addEventListener("pointerdown", onSeekDown);
    dom.progressArea.addEventListener("pointermove", onSeekMove);
    dom.progressArea.addEventListener("pointerup", onSeekUp);
    dom.progressArea.addEventListener("pointercancel", onSeekCancel);

    dom.offsetMinus.addEventListener("click", () => applyOffset(state.offsetMs - 100, true));
    dom.offsetPlus.addEventListener("click", () => applyOffset(state.offsetMs + 100, true));
    dom.offsetReset.addEventListener("click", () => {
      applyOffset(0, true);
      showToast("歌词偏移已归零");
    });
    dom.calibrationDone.addEventListener("click", exitCalibration);

    document.addEventListener("keydown", event => {
      if (event.key !== "Escape") return;
      if (state.menuOpen) {
        closeMenu();
        event.preventDefault();
      } else if (state.calibrating) {
        exitCalibration();
        event.preventDefault();
      }
    });

    window.addEventListener("resize", scheduleLayout);
    window.addEventListener("orientationchange", () => {
      scheduleLayout();
      window.setTimeout(scheduleLayout, 220);
    });
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", scheduleLayout);
    }
    if (window.ResizeObserver) {
      try {
        const observer = new ResizeObserver(scheduleLayout);
        observer.observe(dom.lyricsStage);
      } catch (error) {
        /* noop */
      }
    }

    document.addEventListener("catfield:reset", () => {
      state.gyroTargetYaw = 0;
      state.gyroTargetPitch = 0;
      showToast("声场视角已重置");
    });
  }

  function applyInitialPreferences() {
    dom.beatRange.value = String(Math.round(state.beatIntensity));
    dom.beatValue.textContent = Math.round(state.beatIntensity) + "%";
    dom.linesRange.value = String(state.lineCount);
    dom.linesValue.textContent = state.lineCount + " 行";
    dom.gyroSensitivityRange.value = String(Math.round(state.gyroSensitivity));
    dom.gyroSensitivityValue.textContent = Math.round(state.gyroSensitivity) + "%";
    if (field) field.setBeatIntensity(state.beatIntensity / 100);
    updateGyroDisplay(false);
    updateOffsetLabels();
    updateCalibrationAvailability();
    updateEffectCopy(field ? field.getEffect() : "orbit");
    if (params.get("effect") === "tide" && field) setEffect("tide");
  }

  function beginDemo() {
    if (params.get("demo") !== "1") return;
    const cover = document.createElement("canvas");
    cover.width = 160;
    cover.height = 160;
    const context = cover.getContext("2d");
    const gradient = context.createLinearGradient(0, 0, 160, 160);
    gradient.addColorStop(0, "#d89a42");
    gradient.addColorStop(0.24, "#d76d58");
    gradient.addColorStop(0.50, "#a24971");
    gradient.addColorStop(0.75, "#483d83");
    gradient.addColorStop(1, "#0a1730");
    context.fillStyle = gradient;
    context.fillRect(0, 0, 160, 160);
    context.globalCompositeOperation = "screen";
    const halo = context.createRadialGradient(86, 56, 2, 86, 56, 82);
    halo.addColorStop(0, "rgba(255, 226, 151, .98)");
    halo.addColorStop(0.25, "rgba(255, 176, 79, .78)");
    halo.addColorStop(1, "rgba(238, 119, 75, 0)");
    context.fillStyle = halo;
    context.fillRect(0, 0, 160, 160);
    context.fillStyle = "rgba(255, 196, 91, .98)";
    context.beginPath();
    context.arc(86, 56, 40, 0, Math.PI * 2);
    context.fill();
    context.fillStyle = "rgba(21, 180, 151, .62)";
    context.beginPath();
    context.moveTo(-12, 132);
    context.bezierCurveTo(31, 96, 84, 160, 174, 112);
    context.lineTo(174, 174);
    context.lineTo(-12, 174);
    context.closePath();
    context.fill();
    context.globalCompositeOperation = "source-over";
    const depth = context.createLinearGradient(0, 76, 0, 160);
    depth.addColorStop(0, "rgba(4, 8, 22, 0)");
    depth.addColorStop(1, "rgba(4, 8, 22, .44)");
    context.fillStyle = depth;
    context.fillRect(0, 0, 160, 160);
    context.strokeStyle = "rgba(241, 92, 141, .68)";
    context.lineWidth = 7;
    context.beginPath();
    context.arc(80, 82, 66, 0.48, 2.18);
    context.stroke();
    context.strokeStyle = "rgba(255, 222, 166, .52)";
    context.lineWidth = 1.4;
    for (let ring = 0; ring < 4; ring++) {
      context.beginPath();
      context.arc(80, 82, 24 + ring * 19, 0.55, 2.30);
      context.stroke();
    }
    context.fillStyle = "rgba(255, 241, 201, .92)";
    context.beginPath();
    context.arc(126, 39, 3.5, 0, Math.PI * 2);
    context.fill();
    context.fillStyle = "rgba(127, 231, 246, .64)";
    context.beginPath();
    context.arc(31, 43, 2.2, 0, Math.PI * 2);
    context.fill();
    const edgeShade = context.createRadialGradient(80, 78, 48, 80, 78, 112);
    edgeShade.addColorStop(0, "rgba(6, 9, 25, 0)");
    edgeShade.addColorStop(0.70, "rgba(6, 9, 25, .16)");
    edgeShade.addColorStop(1, "rgba(6, 9, 25, .54)");
    context.fillStyle = edgeShade;
    context.fillRect(0, 0, 160, 160);
    const data = cover.toDataURL("image/jpeg", 0.92);

    const demoLines = [
      { t: 0, text: "夜色沿着窗沿慢慢落下", tr: "Night settles softly along the window" },
      { t: 5200, text: "呼吸和鼓点一起变轻", tr: "Breath and drums grow light together" },
      { t: 10800, text: "我们在低频里拾起微光", tr: "We gather faint light in the low end" },
      { t: 16600, text: "让潮汐替你回答", tr: "Let the tide answer for you" },
      { t: 22400, text: "所有未说出口的名字", tr: "Every name left unspoken" },
      { t: 28200, text: "都变成了起伏的光", tr: "Turns into a rising glow" },
      { t: 34000, text: "此刻只需要闭上眼睛", tr: "For now, just close your eyes" },
      { t: 39800, text: "听世界在耳边发光", tr: "Hear the world glowing beside you" },
      { t: 45600, text: "等下一次潮声靠近", tr: "Until the next tide comes near" }
    ];
    if (field) {
      field.setAccent("#e6a475");
      field.setArtwork(data);
    }
    document.documentElement.style.setProperty("--accent", "#e6a475");
    document.documentElement.style.setProperty("--accent-rgb", "230, 164, 117");
    dom.trackTitle.textContent = "潮汐之后";
    dom.trackArtist.textContent = "CatMusic 合成演示";
    document.title = "潮汐之后 · CatMusic";
    applyLyrics({ hasTiming: true, lines: demoLines });
    state.demo = { started: performance.now() };
    state.demoLastAudio = 0;
    state.playing = true;
    state.durationMs = 52000;
    setLocalPosition(0);
    updatePlayIcon();
    if (params.get("menu") === "1") {
      window.setTimeout(() => setMenuOpen(true, false), 800);
    }
  }

  function init() {
    bindEvents();
    applyInitialPreferences();
    updateMetrics();
    updatePlayIcon();
    setLyricsView("loading");
    updateActive(false);
    requestAnimationFrame(uiTick);
    beginDemo();

    if (!window.CatMusicBridge) {
      state.nativeAvailable = false;
      state.gyroAvailable = false;
      updateGyroDisplay(false);
    }

    window.addEventListener("load", () => {
      window.setTimeout(finishBoot, 340);
    });
    window.setTimeout(finishBoot, 3800);
  }

  // The Kotlin layer calls these functions after the page's ready handshake.
  window.catmusic = {
    lyrics: payload => applyLyrics(payload),
    playback: payload => {
      if (!payload || typeof payload !== "object") return;
      state.durationMs = Math.max(0, finite(payload.duration, state.durationMs));
      const nextPlaying = !!payload.playing;
      if (!state.seeking) {
        state.positionMs = Math.max(0, finite(payload.position, state.positionMs));
        state.positionClock = performance.now();
      }
      if (nextPlaying !== state.playing) {
        state.playing = nextPlaying;
        boostFieldFrameRate();
        updatePlayIcon();
      }
      if (typeof payload.offset === "number") {
        const offset = clamp(Math.round(payload.offset), -5000, 5000);
        if (offset !== state.offsetMs) {
          state.offsetMs = offset;
          updateOffsetLabels();
        }
      }
      updateProgress(currentPosition());
      updateActive(false);
    },
    audio: payload => ingestAudio(payload),
    palette: payload => {
      if (!payload || !payload.accent) return;
      const raw = String(payload.accent).replace("#", "");
      if (!/^[0-9a-f]{6}$/i.test(raw)) return;
      document.documentElement.style.setProperty("--accent", "#" + raw);
      document.documentElement.style.setProperty(
        "--accent-rgb",
        [parseInt(raw.slice(0, 2), 16),
         parseInt(raw.slice(2, 4), 16),
         parseInt(raw.slice(4, 6), 16)].join(", ")
      );
      if (field) field.setAccent("#" + raw);
    },
    artwork: payload => {
      const data = payload && typeof payload.data === "string" ? payload.data : "";
      if (field) field.setArtwork(data || null);
    },
    track: payload => {
      if (!payload) return;
      const title = String(payload.title || "").trim();
      const artist = String(payload.artist || "").trim();
      dom.trackTitle.textContent = title || "正在聆听";
      dom.trackArtist.textContent = artist;
      dom.trackDivider.hidden = !(title && artist);
      dom.trackArtist.hidden = !artist;
      document.title = (title || "正在聆听") + " · CatMusic";
    },
    safeArea: payload => {
      if (!payload) return;
      root.style.setProperty("--safe-top", clamp(finite(payload.top, 0), 0, 180) + "px");
      root.style.setProperty("--safe-bottom", clamp(finite(payload.bottom, 0), 0, 180) + "px");
      root.style.setProperty("--safe-left", clamp(finite(payload.left, 0), 0, 180) + "px");
      root.style.setProperty("--safe-right", clamp(finite(payload.right, 0), 0, 180) + "px");
      scheduleLayout();
    },
    gyroState: payload => applyGyroState(payload),
    orientation: payload => onOrientation(payload),
    back: () => {
      if (state.menuOpen) {
        closeMenu();
        return true;
      }
      if (state.calibrating) {
        exitCalibration();
        return true;
      }
      return false;
    },
    debugState: () => ({
      effect: field ? field.getEffect() : "orbit",
      activeIndex: state.activeIndex,
      offsetMs: state.offsetMs,
      playing: state.playing,
      positionMs: Math.round(currentPosition()),
      durationMs: state.durationMs,
      hasTiming: state.hasTiming,
      lines: state.lines.length,
      lineCount: state.lineCount,
      menuOpen: state.menuOpen,
      calibrating: state.calibrating,
      gyro: {
        available: state.gyroAvailable,
        enabled: state.gyroEnabled,
        requested: state.gyroRequested,
        sensitivity: state.gyroSensitivity,
        target: [state.gyroTargetYaw, state.gyroTargetPitch]
      },
      audio: {
        level: state.audioLevel,
        beat: state.audioBeat,
        pulse: state.pulse,
        bands: Array.from(state.audioBands)
      },
      safe: {
        top: getComputedStyle(root).getPropertyValue("--safe-top").trim(),
        bottom: getComputedStyle(root).getPropertyValue("--safe-bottom").trim()
      },
      viewport: [window.innerWidth, window.innerHeight, window.devicePixelRatio],
      field: field ? field.getDebug() : null
    })
  };

  // A small read-only surface used by the local browser/device test harness.
  window.CatSoundField = {
    setEffect,
    openMenu: () => setMenuOpen(true),
    closeMenu: () => closeMenu(),
    demo: beginDemo,
    debugState: () => window.catmusic.debugState()
  };

  init();
})();
