(() => {
    "use strict";

    /* ------------------------------------------------------------------ */
    /* Constants and helpers                                              */
    /* ------------------------------------------------------------------ */

    const SVG_NS = "http://www.w3.org/2000/svg";
    const SOURCE_ORDER = [
        "netease", "qq", "migu", "kuwo", "qianqian", "kugou", "joox", "fivesing",
        "jamendo"
    ];
    const SOURCE_META = {
        netease: { label: "网易云", cls: "netease" },
        qq: { label: "QQ音乐", cls: "qq" },
        migu: { label: "咪咕", cls: "migu" },
        kuwo: { label: "酷我", cls: "kuwo" },
        qianqian: { label: "千千", cls: "qianqian" },
        kugou: { label: "酷狗", cls: "kugou" },
        joox: { label: "JOOX", cls: "joox" },
        fivesing: { label: "5sing", cls: "fivesing" },
        jamendo: { label: "Jamendo", cls: "jamendo" }
    };
    const STORAGE_SOURCES = "musicdl.sources.v3";
    const STORAGE_RECENT = "musicdl.recent.v1";
    const STORAGE_DEFAULT_QUALITY = "catmusic.defaultQuality.v2";
    const PARAMS = new URLSearchParams(window.location.search);
    const DEMO = !(window.Native && typeof window.Native.search === "function")
        && PARAMS.get("demo") === "1";
    const DEMO_AUDIO = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3";

    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    function iconNode(symbolId) {
        const svg = document.createElementNS(SVG_NS, "svg");
        svg.setAttribute("class", "icon");
        svg.setAttribute("aria-hidden", "true");
        const use = document.createElementNS(SVG_NS, "use");
        use.setAttribute("href", "#" + symbolId);
        svg.appendChild(use);
        return svg;
    }

    function useIcon(button, symbolId) {
        const use = button.querySelector("use");
        if (use) use.setAttribute("href", "#" + symbolId);
    }

    function parseJson(text) {
        try {
            return JSON.parse(text);
        } catch (error) {
            return null;
        }
    }

    function cleanText(value) {
        return typeof value === "string" ? value.trim() : "";
    }

    function formatTime(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
        const whole = Math.floor(seconds);
        const hours = Math.floor(whole / 3600);
        const minutes = Math.floor((whole % 3600) / 60);
        const secs = whole % 60;
        if (hours > 0) {
            return hours + ":" + String(minutes).padStart(2, "0") + ":" +
                String(secs).padStart(2, "0");
        }
        return minutes + ":" + String(secs).padStart(2, "0");
    }

    function formatBytes(bytes) {
        if (!Number.isFinite(bytes) || bytes <= 0) return "";
        const units = ["B", "KB", "MB", "GB"];
        let value = bytes;
        let index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        const digits = index === 0 ? 0 : value >= 100 ? 0 : 1;
        return value.toFixed(digits).replace(/\.0$/, "") + " " + units[index];
    }

    const QUALITY_META = {
        standard: { label: "标准", hint: "128K" },
        high: { label: "较高", hint: "320K" },
        lossless: { label: "无损", hint: "FLAC" }
    };

    const QUALITY_KBPS = {
        standard: { general: 128, kuwo: 100 },
        high: { general: 320, kuwo: 300 },
        lossless: {
            general: 900, netease: 1400, migu: 900, kuwo: 2000, qianqian: 900,
            kugou: 1000, joox: 1000, qq: 900, fivesing: 900, jamendo: 128
        }
    };

    function qualityTiersForSource(source) {
        return source === "jamendo" ? ["standard"] : ["standard", "high", "lossless"];
    }

    function qualityTierLabel(tier) {
        return QUALITY_META[tier] ? QUALITY_META[tier].label : "标准";
    }

    function qualityEstimateBytes(track, tier) {
        if (!track) return 0;
        const known = track.qualitySizes && Number(track.qualitySizes[tier]);
        if (known > 0) return known;
        const duration = Number(track.duration);
        if (!(duration > 0)) return 0;
        const table = QUALITY_KBPS[tier] || QUALITY_KBPS.standard;
        const kbps = table[track.source] || table.general;
        return Math.round(duration * kbps * 125);
    }

    function trackSizeInfo(track) {
        if (!track) return null;
        const tier = state.defaultQuality || "lossless";
        const declared = Number(track.size);
        // A size that was resolved from the real URL wins over the API's tier
        // metadata; this is the same number musicdl prints in its Filesize
        // column.
        if (declared > 0 && track.sizeExact !== false) {
            return {bytes: declared, exact: true, label: formatBytes(declared)};
        }
        const tierSize = track.qualitySizes && Number(track.qualitySizes[tier]);
        if (tierSize > 0) {
            return {bytes: tierSize, exact: false, label: "约 " + formatBytes(tierSize)};
        }
        if (declared > 0) {
            return {bytes: declared, exact: false, label: "约 " + formatBytes(declared)};
        }
        const duration = Number(track.duration);
        if (duration > 0) {
            // Show the size of the tier that will actually be downloaded. With
            // the default now lossless this matches musicdl's large search
            // rows even when a source does not disclose its file size.
            const table = QUALITY_KBPS[tier] || QUALITY_KBPS.standard;
            const kbps = table[track.source] || table.general;
            const estimated = Math.round(duration * kbps * 125);
            return {
                bytes: estimated,
                exact: false,
                label: "约 " + formatBytes(estimated)
            };
        }
        return null;
    }

    function clamp(value, min, max) {
        return Math.min(max, Math.max(min, value));
    }

    function safeLocalStorageGet(key) {
        try {
            return window.localStorage.getItem(key);
        } catch (error) {
            return null;
        }
    }

    function safeLocalStorageSet(key, value) {
        try {
            window.localStorage.setItem(key, value);
        } catch (error) {
            /* Local storage can be unavailable; the app still works. */
        }
    }

    function sourceLabel(source) {
        return SOURCE_META[source] ? SOURCE_META[source].label : source;
    }

    function selectedSourceLabels() {
        return state.sources.map(sourceLabel).join(" / ");
    }

    function paletteFor(track) {
        const palettes = [
            ["#394737", "#141a13"],
            ["#3b3548", "#151218"],
            ["#4b3c31", "#19130f"],
            ["#304653", "#111a1f"],
            ["#4a303c", "#191116"],
            ["#2e3f48", "#101619"],
            ["#463d2b", "#17130d"]
        ];
        const text = (track.title || "") + "|" + (track.artist || "") + "|" + (track.source || "");
        let hash = 0;
        for (let i = 0; i < text.length; i++) {
            hash = ((hash << 5) - hash + text.charCodeAt(i)) | 0;
        }
        return palettes[Math.abs(hash) % palettes.length];
    }

    /* ------------------------------------------------------------------ */
    /* DOM                                                                */
    /* ------------------------------------------------------------------ */

    const els = {
        app: $("#app"),
        main: $("#main"),
        brandBtn: $("#brandBtn"),
        aboutBtn: $("#aboutBtn"),
        searchAgainBtn: $("#searchAgainBtn"),
        offlineDot: $("#offlineDot"),
        hero: $("#hero"),
        searchForm: $("#searchForm"),
        searchInput: $("#searchInput"),
        searchBtn: $("#searchBtn"),
        sourceStrip: $("#sourceStrip"),
        recentRow: $("#recentRow"),
        recentChips: $("#recentChips"),
        discover: $("#discover"),
        moodGrid: $("#moodGrid"),
        resultsSection: $("#resultsSection"),
        resultsTitle: $("#resultsTitle"),
        resultsSub: $("#resultsSub"),
        resultCount: $("#resultCount"),
        defaultQualityOptions: $("#defaultQualityOptions"),
        sourceErrors: $("#sourceErrors"),
        trackList: $("#trackList"),
        emptyState: $("#emptyState"),
        emptyCopy: $("#emptyCopy"),
        emptyRetry: $("#emptyRetry"),
        endCap: $("#endCap"),
        footer: $("#footer"),
        footerAbout: $("#footerAbout"),
        miniPlayer: $("#miniPlayer"),
        miniProgress: $("#miniProgress"),
        miniOpen: $("#miniOpen"),
        miniCover: $("#miniCover"),
        miniTitle: $("#miniTitle"),
        miniArtist: $("#miniArtist"),
        miniPlay: $("#miniPlay"),
        miniNext: $("#miniNext"),
        player: $("#player"),
        playerBackdrop: $("#playerBackdrop"),
        closePlayer: $("#closePlayer"),
        playerSource: $("#playerSource"),
        playerDownload: $("#playerDownload"),
        playerArt: $("#playerArt"),
        playerArtImage: $("#playerArtImage"),
        artLoading: $("#artLoading"),
        playerTitle: $("#playerTitle"),
        playerArtist: $("#playerArtist"),
        lyricsToggle: $("#lyricsToggle"),
        playerError: $("#playerError"),
        playerErrorText: $("#playerErrorText"),
        seekBar: $("#seekBar"),
        currentTime: $("#currentTime"),
        totalTime: $("#totalTime"),
        qualityBadge: $("#qualityBadge"),
        prevBtn: $("#prevBtn"),
        playBtn: $("#playBtn"),
        nextBtn: $("#nextBtn"),
        queueCaption: $("#queueCaption"),
        lyricsPanel: $("#lyricsPanel"),
        closeLyrics: $("#closeLyrics"),
        lyricsScroll: $("#lyricsScroll"),
        scrim: $("#scrim"),
        aboutSheet: $("#aboutSheet"),
        closeAbout: $("#closeAbout"),
        saveScrim: $("#saveScrim"),
        saveSheet: $("#saveSheet"),
        saveCover: $("#saveCover"),
        saveTitle: $("#saveTitle"),
        saveArtist: $("#saveArtist"),
        saveSize: $("#saveSize"),
        saveQualityOptions: $("#saveQualityOptions"),
        saveQualityNote: $("#saveQualityNote"),
        saveOptions: $("#saveOptions"),
        saveModeLibrary: $("#saveModeLibrary"),
        saveModePublic: $("#saveModePublic"),
        saveLyricsRow: $("#saveLyricsRow"),
        saveLyrics: $("#saveLyrics"),
        saveLyricsHint: $("#saveLyricsHint"),
        saveProgress: $("#saveProgress"),
        saveProgressText: $("#saveProgressText"),
        saveProgressPct: $("#saveProgressPct"),
        saveProgressBar: $("#saveProgressBar"),
        saveCancel: $("#saveCancel"),
        saveConfirm: $("#saveConfirm"),
        closeSave: $("#closeSave"),
        toast: $("#toast"),
        audio: $("#audio")
    };

    /* ------------------------------------------------------------------ */
    /* State                                                              */
    /* ------------------------------------------------------------------ */

    const state = {
        sources: [],
        recent: [],
        defaultQuality: "lossless",
        query: "",
        results: [],
        activeRequest: null,
        searchSeq: 0,
        searchTimer: 0,
        isSearching: false,
        queue: [],
        queueIndex: -1,
        current: null,
        playerTrack: null,
        playerOpen: false,
        lyricsOpen: false,
        aboutOpen: false,
        resolving: false,
        loadToken: 0,
        playControl: 0,
        resolveSeq: 0,
        resolvePromises: new Map(),
        pendingResolves: new Map(),
        resolvedCache: new Map(),
        qualityCache: new Map(),
        downloaded: new Set(),
        downloadBusy: new Set(),
        downloadTimers: new Map(),
        isSeeking: false,
        lyrics: [],
        lyricIndex: -1,
        audioBroken: false,
        isDemo: DEMO,
        demoAutoPlayed: false,
        toastTimer: 0,
        scrimTimer: 0,
        lastFocused: null,
        saveOpen: false,
        saveBusy: false,
        saveTrack: null,
        saveResolved: null,
        saveMode: "library",
        saveQuality: "lossless",
        saveQualityBusy: false,
        saveUnavailableQualities: new Set(),
        saveLyricsAvailable: false,
        saveRequestId: "",
        saveLastFocused: null,
        saveScrimTimer: 0,
        saveSeq: 0,
        bridge: null
    };

    state.bridge = window.Native && typeof window.Native.search === "function"
        ? window.Native : null;

    window.AppNative = {
        onSearchResult(requestId, jsonText) {
            handleSearchResponse(String(requestId), jsonText);
        },
        onResolveResult(requestId, jsonText) {
            handleResolveResponse(String(requestId), jsonText);
        },
        onDownloadQueued(uiId) {
            handleDownloadQueued(String(uiId || ""));
        },
        onLibraryProgress(requestId, phase, received, total) {
            handleLibraryProgress(
                String(requestId || ""),
                String(phase || ""),
                Number(received) || 0,
                Number(total) || 0
            );
        },
        onLibraryResult(requestId, jsonText) {
            handleLibraryResult(String(requestId || ""), jsonText);
        },
        onLibraryError(requestId, message) {
            handleLibraryError(String(requestId || ""), String(message || ""));
        },
        onToast(message) {
            const text = String(message || "");
            if (text.indexOf("下载") !== -1) {
                clearDownloadBusy();
            }
            showToast(text);
        },
        handleBack() {
            return handleBack();
        }
    };

    /* ------------------------------------------------------------------ */
    /* Initialisation                                                     */
    /* ------------------------------------------------------------------ */

    function init() {
        state.sources = loadSources();
        state.recent = loadRecent();
        state.defaultQuality = loadDefaultQuality();

        if (!state.bridge && DEMO) {
            installDemoBridge();
        }

        renderSources();
        renderRecent();
        renderDefaultQuality();
        updateOnlineState();
        updateDownloadButtons();
        bindEvents();
        configureAudio();
        configureMediaSession();
        setPlaybackUi(false);
        updateQueueControls();

        if (DEMO) {
            document.body.dataset.demo = "1";
            const initialQuery = cleanText(PARAMS.get("q"));
            if (initialQuery) {
                window.setTimeout(() => performSearch(initialQuery), 320);
            }
        }
    }

    function loadSources() {
        const saved = safeLocalStorageGet(STORAGE_SOURCES);
        if (saved) {
            const parsed = parseJson(saved);
            if (Array.isArray(parsed)) {
                const valid = SOURCE_ORDER.filter(source => parsed.indexOf(source) !== -1);
                if (valid.length) return valid;
            }
        }
        return SOURCE_ORDER.slice();
    }

    function saveSources() {
        safeLocalStorageSet(STORAGE_SOURCES, JSON.stringify(state.sources));
    }

    function loadDefaultQuality() {
        // v2 starts at lossless, matching musicdl's quality-first search and
        // download behaviour. A manual choice is still remembered afterwards.
        const saved = cleanText(safeLocalStorageGet(STORAGE_DEFAULT_QUALITY));
        return ["standard", "high", "lossless"].indexOf(saved) !== -1 ? saved : "lossless";
    }

    function saveDefaultQuality() {
        safeLocalStorageSet(STORAGE_DEFAULT_QUALITY, state.defaultQuality);
    }

    function renderDefaultQuality() {
        if (!els.defaultQualityOptions) return;
        els.defaultQualityOptions.textContent = "";
        ["standard", "high", "lossless"].forEach(quality => {
            const button = element(
                "button",
                "default-quality-option" +
                    (quality === state.defaultQuality ? " is-selected" : ""),
                qualityTierLabel(quality)
            );
            button.type = "button";
            button.dataset.defaultQuality = quality;
            button.setAttribute(
                "aria-pressed",
                quality === state.defaultQuality ? "true" : "false"
            );
            button.title = "之后下载的歌曲默认使用" + qualityTierLabel(quality) + "音质";
            els.defaultQualityOptions.appendChild(button);
        });
    }

    function setDefaultQuality(quality) {
        if (["standard", "high", "lossless"].indexOf(quality) === -1) return;
        if (state.defaultQuality === quality) return;
        state.defaultQuality = quality;
        saveDefaultQuality();
        renderDefaultQuality();
        state.results.forEach(refreshTrackSize);
        showToast("默认下载音质已设为「" + qualityTierLabel(quality) + "」");
    }

    function loadRecent() {
        const saved = safeLocalStorageGet(STORAGE_RECENT);
        if (!saved) return [];
        const parsed = parseJson(saved);
        if (!Array.isArray(parsed)) return [];
        return parsed
            .map(item => cleanText(item))
            .filter(item => item && item.length <= 80)
            .slice(0, 6);
    }

    function saveRecent() {
        safeLocalStorageSet(STORAGE_RECENT, JSON.stringify(state.recent));
    }

    function addRecent(query) {
        const value = cleanText(query);
        if (!value) return;
        state.recent = [value]
            .concat(state.recent.filter(item => item.toLowerCase() !== value.toLowerCase()))
            .slice(0, 6);
        saveRecent();
        renderRecent();
    }

    /* ------------------------------------------------------------------ */
    /* Rendering: sources and recents                                     */
    /* ------------------------------------------------------------------ */

    function renderSources() {
        els.sourceStrip.textContent = "";
        SOURCE_ORDER.forEach(source => {
            const meta = SOURCE_META[source];
            const selected = state.sources.indexOf(source) !== -1;
            const button = element("button", "source-chip");
            button.type = "button";
            button.dataset.source = source;
            button.setAttribute("aria-pressed", selected ? "true" : "false");
            button.setAttribute("aria-label", meta.label + (selected ? "，已选择" : "，未选择"));
            if (source === "qq") {
                button.title = "QQ 音乐仅显示可免费试听的结果";
            }
            const dot = element("span", "dot");
            dot.setAttribute("aria-hidden", "true");
            const label = element("span", "label", meta.label);
            button.appendChild(dot);
            button.appendChild(label);
            els.sourceStrip.appendChild(button);
        });
    }

    function renderRecent() {
        if (!state.recent.length) {
            els.recentRow.hidden = true;
            els.recentChips.textContent = "";
            return;
        }
        els.recentRow.hidden = false;
        els.recentChips.textContent = "";
        state.recent.forEach(query => {
            const button = element("button", "recent-chip", query);
            button.type = "button";
            button.dataset.query = query;
            els.recentChips.appendChild(button);
        });
    }

    function toggleSource(source) {
        if (!SOURCE_META[source]) return;
        const selected = state.sources.indexOf(source) !== -1;
        if (selected && state.sources.length === 1) {
            showToast("至少保留一个音乐来源。");
            return;
        }
        state.sources = selected
            ? state.sources.filter(item => item !== source)
            : SOURCE_ORDER.filter(item => item === source || state.sources.indexOf(item) !== -1);
        saveSources();
        renderSources();
        if (state.query && !els.resultsSection.hidden) {
            performSearch(state.query);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Events                                                             */
    /* ------------------------------------------------------------------ */

    function bindEvents() {
        els.searchForm.addEventListener("submit", event => {
            event.preventDefault();
            performSearch(els.searchInput.value);
        });

        els.searchInput.addEventListener("input", () => {
            if (els.searchForm.classList.contains("is-shaking")) {
                els.searchForm.classList.remove("is-shaking");
            }
        });

        els.sourceStrip.addEventListener("click", event => {
            const button = event.target.closest(".source-chip");
            if (!button) return;
            toggleSource(button.dataset.source);
        });

        els.defaultQualityOptions.addEventListener("click", event => {
            const button = event.target.closest("[data-default-quality]");
            if (!button) return;
            setDefaultQuality(button.dataset.defaultQuality || "");
        });

        els.recentChips.addEventListener("click", event => {
            const button = event.target.closest(".recent-chip");
            if (!button) return;
            performSearch(button.dataset.query || "");
        });

        els.moodGrid.addEventListener("click", event => {
            const card = event.target.closest(".mood-card[data-query]");
            if (!card) return;
            const query = card.dataset.query || "";
            els.searchInput.value = query;
            performSearch(query);
        });

        els.brandBtn.addEventListener("click", () => {
            if (!els.resultsSection.hidden) {
                goHome();
            } else {
                els.main.scrollTo({ top: 0, behavior: "smooth" });
            }
        });

        els.searchAgainBtn.addEventListener("click", () => {
            if (!els.resultsSection.hidden) {
                goHome();
                window.setTimeout(() => els.searchInput.focus(), 120);
            } else {
                els.searchInput.focus();
            }
        });

        els.aboutBtn.addEventListener("click", openAbout);
        els.footerAbout.addEventListener("click", openAbout);
        els.closeAbout.addEventListener("click", closeAbout);
        els.scrim.addEventListener("click", closeAbout);
        els.closeSave.addEventListener("click", () => closeSaveDialog(false));
        els.saveScrim.addEventListener("click", () => {
            if (!state.saveBusy) closeSaveDialog(false);
        });
        els.saveModeLibrary.addEventListener("click", () => setSaveMode("library"));
        els.saveModePublic.addEventListener("click", () => setSaveMode("public"));
        els.saveLyrics.addEventListener("change", () => {
            state.saveLyrics = !!els.saveLyrics.checked;
        });
        els.saveCancel.addEventListener("click", () => {
            if (!state.saveBusy) closeSaveDialog(false);
        });
        els.saveConfirm.addEventListener("click", confirmSave);

        els.aboutSheet.addEventListener("click", event => {
            const link = event.target.closest("[data-external]");
            if (!link) return;
            const url = link.dataset.external;
            if (!url) return;
            if (state.bridge && typeof state.bridge.openExternal === "function") {
                state.bridge.openExternal(url);
            } else {
                window.open(url, "_blank", "noopener");
            }
        });

        els.emptyRetry.addEventListener("click", () => {
            const query = state.query || els.searchInput.value;
            if (cleanText(query)) {
                performSearch(query);
            } else {
                els.searchInput.focus();
            }
        });

        els.miniOpen.addEventListener("click", openPlayerForCurrent);
        els.miniPlay.addEventListener("click", togglePlayback);
        els.miniNext.addEventListener("click", () => playQueueOffset(1));

        els.closePlayer.addEventListener("click", closePlayer);
        els.playBtn.addEventListener("click", handlePlayButton);
        els.prevBtn.addEventListener("click", () => {
            if (state.current && els.audio.currentTime > 3 &&
                state.current.uiId === (state.queue[state.queueIndex] || {}).uiId) {
                els.audio.currentTime = 0;
                return;
            }
            playQueueOffset(-1);
        });
        els.nextBtn.addEventListener("click", () => playQueueOffset(1));
        els.playerDownload.addEventListener("click", () => {
            const track = state.playerTrack;
            if (track) handleDownload(track, els.playerDownload);
        });

        els.lyricsToggle.addEventListener("click", toggleLyrics);
        els.closeLyrics.addEventListener("click", () => closeLyricsSheet(true));

        els.seekBar.addEventListener("input", handleSeekInput);
        els.seekBar.addEventListener("pointerdown", () => {
            state.isSeeking = true;
        });
        window.addEventListener("pointerup", () => {
            state.isSeeking = false;
        });
        els.seekBar.addEventListener("change", () => {
            state.isSeeking = false;
        });

        els.lyricsScroll.addEventListener("click", event => {
            const line = event.target.closest(".lyric-line");
            if (!line) return;
            const time = Number(line.dataset.time);
            if (!Number.isFinite(time)) return;
            if (Number.isFinite(els.audio.duration) && els.audio.duration > 0) {
                els.audio.currentTime = clamp(time, 0, els.audio.duration);
                updatePlaybackTime();
            }
        });

        window.addEventListener("online", updateOnlineState);
        window.addEventListener("offline", updateOnlineState);
        window.addEventListener("keydown", handleGlobalKeydown);
    }

    function handleGlobalKeydown(event) {
        if (event.key === "Escape") {
            if (state.aboutOpen) {
                closeAbout();
            } else if (state.lyricsOpen) {
                closeLyricsSheet(true);
            } else if (state.playerOpen) {
                closePlayer();
            }
            return;
        }
        if (event.code === "Space" && state.playerOpen) {
            const target = event.target;
            const tag = target && target.tagName ? target.tagName.toLowerCase() : "";
            if (tag === "input" || tag === "textarea" || tag === "button" || target.isContentEditable) {
                return;
            }
            if (state.current) {
                event.preventDefault();
                togglePlayback();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Search                                                             */
    /* ------------------------------------------------------------------ */

    function performSearch(rawQuery) {
        const query = cleanText(rawQuery);
        if (!query) {
            els.searchInput.focus();
            els.searchForm.classList.remove("is-shaking");
            void els.searchForm.offsetWidth;
            els.searchForm.classList.add("is-shaking");
            showToast("输入歌曲、歌手或专辑名。");
            return;
        }
        if (!state.sources.length) {
            showToast("请至少选择一个音乐来源。");
            return;
        }
        if (!state.bridge) {
            showToast("请在 CatMusic 的在线音乐页中使用。");
            return;
        }

        state.query = query;
        els.searchInput.value = query;
        els.searchInput.blur();
        addRecent(query);

        clearSearchTimer();
        const requestId = "s-" + (++state.searchSeq);
        state.activeRequest = requestId;
        state.isSearching = true;
        setSearchLoading(true);
        showSearchSkeleton(query);
        els.main.scrollTo({ top: 0, behavior: "auto" });

        if (navigator.onLine === false) {
            showToast("当前网络似乎已断开，正在尝试连接…");
        }

        state.searchTimer = window.setTimeout(() => {
            if (state.activeRequest !== requestId) return;
            state.activeRequest = null;
            showSearchFailure("请求超时了，请检查网络后重试。这是第三方接口，偶尔会慢一点。");
        }, 40000);

        try {
            state.bridge.search(requestId, query, state.sources.join(","));
        } catch (error) {
            if (state.activeRequest !== requestId) return;
            state.activeRequest = null;
            clearSearchTimer();
            showSearchFailure("搜索请求没有发出去，请稍后再试。");
        }
    }

    function showSearchSkeleton(query) {
        els.discover.hidden = true;
        els.hero.hidden = true;
        els.searchAgainBtn.hidden = false;
        els.resultsSection.hidden = false;
        els.recentRow.hidden = true;
        els.resultsTitle.textContent = "正在搜索";
        els.resultsSub.textContent = "“" + query + "” · " + selectedSourceLabels();
        els.resultCount.hidden = true;
        els.sourceErrors.hidden = true;
        els.sourceErrors.textContent = "";
        els.emptyState.hidden = true;
        els.trackList.hidden = false;
        els.endCap.hidden = true;
        els.trackList.textContent = "";

        const fragment = document.createDocumentFragment();
        for (let i = 0; i < 6; i++) {
            const row = element("div", "track loading-track");
            const cover = element("div", "skeleton skeleton-cover");
            const lines = element("div", "skeleton-lines");
            const first = element("div", "skeleton skeleton-line primary");
            const second = element("div", "skeleton skeleton-line secondary");
            lines.appendChild(first);
            lines.appendChild(second);
            row.appendChild(cover);
            row.appendChild(lines);
            row.style.animationDelay = (i * 45) + "ms";
            fragment.appendChild(row);
        }
        els.trackList.appendChild(fragment);
    }

    function clearSearchTimer() {
        if (state.searchTimer) {
            window.clearTimeout(state.searchTimer);
            state.searchTimer = 0;
        }
    }

    function setSearchLoading(loading) {
        els.searchForm.classList.toggle("is-searching", !!loading);
        els.searchForm.setAttribute("aria-busy", loading ? "true" : "false");
        els.searchBtn.setAttribute("aria-label", loading ? "正在搜索" : "搜索");
    }

    function handleSearchResponse(requestId, jsonText) {
        if (requestId !== state.activeRequest) return;
        clearSearchTimer();
        state.activeRequest = null;
        state.isSearching = false;
        setSearchLoading(false);

        const data = parseJson(jsonText);
        if (!data) {
            showSearchFailure("搜索返回的数据无法解析，请稍后再试。");
            return;
        }

        let rawTracks = Array.isArray(data.results) ? data.results : [];
        const messages = [];
        const notices = [];
        if (Array.isArray(data.errors)) {
            data.errors.forEach(item => {
                if (item && item.message) {
                    messages.push(String(item.message));
                }
            });
        }
        if (data.error && !messages.length) {
            messages.push(String(data.error));
        }

        let hiddenPaidQQ = 0;
        const normalized = [];
        const seenUiIds = new Set();
        rawTracks.forEach((raw, index) => {
            const track = normalizeTrack(raw, index);
            if (!track) return;
            if (track.source === "qq" && track.paid) {
                hiddenPaidQQ += 1;
                return;
            }
            if (seenUiIds.has(track.uiId)) return;
            seenUiIds.add(track.uiId);
            normalized.push(track);
        });

        state.results = normalized;
        els.resultsTitle.textContent = "搜索结果";
        els.resultsSub.textContent = "“" + state.query + "” · " + selectedSourceLabels();

        renderSourceMessages(messages, notices);
        updateDownloadButtons();

        if (!normalized.length) {
            els.resultCount.hidden = true;
            renderEmptyState(messages, hiddenPaidQQ);
        } else {
            els.emptyState.hidden = true;
            els.trackList.hidden = false;
            els.endCap.hidden = false;
            els.resultCount.hidden = false;
            els.resultCount.textContent = String(normalized.length);
            renderTracks(normalized);
        }

        updateCurrentRows();

        if (state.isDemo && !state.demoAutoPlayed &&
            PARAMS.get("player") === "1" && normalized.length) {
            state.demoAutoPlayed = true;
            window.setTimeout(() => {
                if (state.results[0]) {
                    playFromResults(state.results[0]);
                }
            }, 420);
        }
    }

    function showSearchFailure(message) {
        clearSearchTimer();
        state.isSearching = false;
        state.activeRequest = null;
        setSearchLoading(false);
        els.discover.hidden = true;
        els.resultsSection.hidden = false;
        els.resultsTitle.textContent = "搜索结果";
        els.resultsSub.textContent = "“" + (state.query || "") + "”";
        els.resultCount.hidden = true;
        els.trackList.hidden = true;
        els.trackList.textContent = "";
        els.endCap.hidden = true;
        renderSourceMessages([message], []);
        renderEmptyState([message], 0);
    }

    function renderSourceMessages(messages, notices) {
        const all = [];
        (messages || []).forEach(message => {
            const value = cleanText(message);
            if (value) all.push({ text: value, type: "error" });
        });
        (notices || []).forEach(message => {
            const value = cleanText(message);
            if (value) all.push({ text: value, type: "notice" });
        });

        els.sourceErrors.textContent = "";
        if (!all.length) {
            els.sourceErrors.hidden = true;
            return;
        }

        all.slice(0, 4).forEach(item => {
            const line = element("div", "error-line" + (item.type === "notice" ? " notice" : ""));
            line.appendChild(iconNode(item.type === "notice" ? "i-info" : "i-alert"));
            line.appendChild(element("span", "", item.text));
            els.sourceErrors.appendChild(line);
        });
        els.sourceErrors.hidden = false;
    }

    function renderEmptyState(messages, hiddenPaidQQ) {
        els.trackList.hidden = true;
        els.trackList.textContent = "";
        els.emptyState.hidden = false;
        els.endCap.hidden = true;
        let copy = "换个关键词，或者打开更多音乐来源再试试。";
        if (messages && messages.length) {
            copy = "音乐接口暂时没有回应。可以稍后重试，或者切换一个来源。";
        } else if (hiddenPaidQQ > 0) {
            copy = "这些结果需要付费，暂时无法播放。试试换一个关键词或来源。";
        }
        els.emptyCopy.textContent = copy;
    }

    function normalizeTrack(raw, index) {
        if (!raw || typeof raw !== "object") return null;
        const source = typeof raw.source === "string" ? raw.source : "";
        if (!SOURCE_META[source]) return null;
        const title = cleanText(raw.title);
        const id = cleanText(raw.id);
        if (!title || !id) return null;

        return {
            uiId: cleanText(raw.uiId) || source + ":" + id + ":" + index,
            source: source,
            sourceName: sourceLabel(source),
            id: id,
            index: Number(raw.index) || index + 1,
            keyword: cleanText(raw.keyword) || state.query,
            title: title,
            artist: cleanText(raw.artist) || "未知歌手",
            album: cleanText(raw.album),
            cover: cleanText(raw.cover),
            duration: Number(raw.duration) > 0 ? Number(raw.duration) : 0,
            paid: !!raw.paid,
            payLabel: cleanText(raw.payLabel),
            audioHint: cleanText(raw.audioHint),
            lrcUrl: cleanText(raw.lrcUrl),
            songType: cleanText(raw.songType),
            extHint: cleanText(raw.extHint),
            copyrightId: cleanText(raw.copyrightId),
            lyricUrl: cleanText(raw.lyricUrl),
            mediaMid: cleanText(raw.mediaMid),
            standardHash: cleanText(raw.standardHash),
            hqHash: cleanText(raw.hqHash),
            sqHash: cleanText(raw.sqHash),
            miguFormats: Array.isArray(raw.miguFormats) ? raw.miguFormats : null,
            bestQuality: cleanText(raw.bestQuality),
            bestExt: cleanText(raw.bestExt),
            size: Number(raw.size) > 0 ? Number(raw.size) : 0,
            sizeExact: !!raw.sizeExact && Number(raw.size) > 0,
            qualitySizes: raw.qualitySizes && typeof raw.qualitySizes === "object"
                ? raw.qualitySizes : null
        };
    }

    /* ------------------------------------------------------------------ */
    /* Result rendering                                                   */
    /* ------------------------------------------------------------------ */

    function renderTracks(tracks) {
        els.trackList.textContent = "";
        const fragment = document.createDocumentFragment();
        tracks.forEach((track, index) => {
            fragment.appendChild(buildTrackRow(track, index));
        });
        els.trackList.appendChild(fragment);
        updateDownloadButtons();
    }

    function refreshTrackSize(track) {
        if (!track || !track.uiId) return;
        const row = Array.from(document.querySelectorAll(".track"))
            .find(item => item.dataset.uiId === track.uiId);
        if (!row) return;
        const meta = row.querySelector(".track-meta");
        if (!meta) return;
        const info = trackSizeInfo(track);
        let badge = meta.querySelector(".track-size");
        if (!info) {
            if (badge) badge.remove();
            return;
        }
        if (!badge) {
            badge = element("span", "track-size");
            meta.appendChild(badge);
        }
        const lossless = track.bestQuality === "lossless" ||
            (track.qualitySizes && Number(track.qualitySizes.lossless) > 0 &&
                !track.bestQuality);
        badge.textContent = (lossless ? "无损 · " : "") + info.label;
        badge.dataset.exact = info.exact ? "1" : "0";
    }

    function applyResolvedSize(track, resolved) {
        if (!track || !resolved) return;
        const bytes = Number(resolved.size);
        if (!(bytes > 0)) return;
        track.size = bytes;
        track.sizeExact = resolved.sizeExact !== false;
        if (resolved.quality) track.bestQuality = resolved.quality;
        if (resolved.ext) track.bestExt = resolved.ext;
        const result = state.results.find(item => item.uiId === track.uiId);
        if (result) {
            result.size = track.size;
            result.sizeExact = track.sizeExact;
        }
        const cached = state.resolvedCache.get(track.uiId);
        if (cached) {
            cached.size = track.size;
            cached.sizeExact = track.sizeExact;
        }
        refreshTrackSize(track);
    }

    function buildCover(track, extraClass) {
        const cover = element("div", "cover " + (extraClass || ""));
        const colors = paletteFor(track);
        cover.style.setProperty("--a", colors[0]);
        cover.style.setProperty("--b", colors[1]);
        const fallback = element("div", "cover-fallback");
        fallback.appendChild(iconNode("i-music"));
        cover.appendChild(fallback);
        if (track.cover) {
            const image = document.createElement("img");
            image.alt = "";
            image.loading = "lazy";
            image.referrerPolicy = "no-referrer";
            image.decoding = "async";
            image.addEventListener("error", () => image.remove());
            image.src = track.cover;
            cover.appendChild(image);
        }
        return cover;
    }

    function buildTrackRow(track, index) {
        const row = element("div", "track");
        row.tabIndex = 0;
        row.setAttribute("role", "button");
        row.setAttribute("aria-label", track.title + "， " + track.artist + "，点击播放");
        row.dataset.uiId = track.uiId;
        row.style.animationDelay = Math.min(index * 26, 240) + "ms";
        if (track.paid) row.classList.add("paid-track");

        row.appendChild(buildCover(track, "track-cover"));

        const copy = element("div", "track-copy");
        const titleLine = element("div", "track-title");
        const title = element("h3", "", track.title);
        title.title = track.title;
        titleLine.appendChild(title);
        if (track.payLabel) {
            titleLine.appendChild(element("span", "pay-tag", track.payLabel));
        }
        copy.appendChild(titleLine);

        const meta = element("div", "track-meta");
        const artist = element("span", "track-artist", track.artist);
        artist.title = track.artist;
        meta.appendChild(artist);
        const source = element("span", "track-source", sourceLabel(track.source));
        source.dataset.source = track.source;
        meta.appendChild(source);
        if (track.duration > 0) {
            meta.appendChild(element("span", "meta-dot"));
            meta.appendChild(element("span", "track-album", formatTime(track.duration)));
        }
        const sizeInfo = trackSizeInfo(track);
        if (sizeInfo) {
            const lossless = track.bestQuality === "lossless" ||
                (track.qualitySizes && Number(track.qualitySizes.lossless) > 0 &&
                    !track.bestQuality);
            meta.appendChild(element(
                "span",
                "track-size",
                (lossless ? "无损 · " : "") + sizeInfo.label
            ));
        }
        copy.appendChild(meta);
        row.appendChild(copy);

        const actions = element("div", "track-actions");
        const indicator = element("span", "track-play-indicator");
        indicator.appendChild(element("i"));
        indicator.appendChild(element("i"));
        indicator.appendChild(element("i"));
        indicator.setAttribute("aria-hidden", "true");
        actions.appendChild(indicator);

        const download = element("button", "track-download");
        download.type = "button";
        download.dataset.downloadFor = track.uiId;
        download.setAttribute("aria-label", "保存 " + track.title);
        download.title = "选择保存位置";
        download.appendChild(iconNode("i-download"));
        actions.appendChild(download);
        row.appendChild(actions);

        row.addEventListener("click", event => {
            if (event.target.closest(".track-download")) return;
            playFromResults(track);
        });
        row.addEventListener("keydown", event => {
            if (event.target !== row) return;
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                playFromResults(track);
            }
        });
        download.addEventListener("click", event => {
            event.stopPropagation();
            handleDownload(track, download);
        });

        return row;
    }

    function goHome() {
        clearSearchTimer();
        state.activeRequest = null;
        state.isSearching = false;
        setSearchLoading(false);
        state.query = "";
        els.searchInput.value = "";
        els.resultsSection.hidden = true;
        els.discover.hidden = false;
        els.hero.hidden = false;
        els.searchAgainBtn.hidden = true;
        els.main.scrollTo({ top: 0, behavior: "smooth" });
        renderRecent();
    }

    /* ------------------------------------------------------------------ */
    /* Resolve / playback                                                 */
    /* ------------------------------------------------------------------ */

    function requestResolve(track, force) {
        const key = track.uiId;
        if (!force && state.resolvedCache.has(key)) {
            return Promise.resolve(state.resolvedCache.get(key));
        }
        if (state.resolvePromises.has(key)) {
            return state.resolvePromises.get(key);
        }
        if (!state.bridge) {
            return Promise.reject(new Error("播放功能需要在 Android 应用中使用。"));
        }

        const requestId = "r-" + (++state.resolveSeq);
        const promise = new Promise((resolve, reject) => {
            const timer = window.setTimeout(() => {
                if (!state.pendingResolves.has(requestId)) return;
                state.pendingResolves.delete(requestId);
                reject(new Error("获取播放地址超时，请稍后重试。"));
            }, 36000);

            state.pendingResolves.set(requestId, {
                resolve: resolve,
                reject: reject,
                timer: timer,
                track: track
            });

            try {
                state.bridge.resolve(requestId, JSON.stringify(track));
            } catch (error) {
                window.clearTimeout(timer);
                state.pendingResolves.delete(requestId);
                reject(new Error("无法连接音乐接口，请稍后再试。"));
            }
        });

        state.resolvePromises.set(key, promise);
        const cleanup = () => state.resolvePromises.delete(key);
        promise.then(cleanup, cleanup);
        return promise;
    }

    function requestQualityResolve(track, quality) {
        const cacheKey = track.uiId + "@" + quality;
        if (state.qualityCache.has(cacheKey)) {
            return Promise.resolve(state.qualityCache.get(cacheKey));
        }
        if (!state.bridge || typeof state.bridge.resolveQuality !== "function") {
            return Promise.reject(new Error("当前版本不支持音质选择，请更新应用。"));
        }
        const requestId = "q-" + (++state.resolveSeq);
        return new Promise((resolve, reject) => {
            const timer = window.setTimeout(() => {
                if (!state.pendingResolves.has(requestId)) return;
                state.pendingResolves.delete(requestId);
                reject(new Error("获取该音质超时，请稍后再试。"));
            }, 36000);
            state.pendingResolves.set(requestId, {
                resolve: resolve,
                reject: reject,
                timer: timer,
                track: track,
                quality: quality,
                updateTrackSize: false
            });
            try {
                state.bridge.resolveQuality(requestId, JSON.stringify(track), quality);
            } catch (error) {
                window.clearTimeout(timer);
                state.pendingResolves.delete(requestId);
                reject(new Error("无法连接音乐接口，请稍后再试。"));
            }
        });
    }

    function handleResolveResponse(requestId, jsonText) {
        const pending = state.pendingResolves.get(requestId);
        if (!pending) return;
        state.pendingResolves.delete(requestId);
        window.clearTimeout(pending.timer);

        const data = parseJson(jsonText);
        if (!data || !data.ok || !data.track) {
            const message = data && data.error ? String(data.error) : "暂时无法获取播放地址。";
            pending.reject(new Error(message));
            return;
        }
        const track = data.track;
        const url = cleanText(track.url);
        if (!/^https?:\/\//i.test(url)) {
            pending.reject(new Error("播放地址无效，换一首歌试试。"));
            return;
        }

        const merged = Object.assign({}, pending.track, track, {
            uiId: pending.track.uiId,
            source: pending.track.source,
            sourceName: sourceLabel(pending.track.source),
            url: url
        });
        merged._resolvedAt = Date.now();
        if (pending.quality) {
            state.qualityCache.set(
                merged.uiId + "@" + pending.quality,
                merged
            );
        } else {
            state.resolvedCache.set(merged.uiId, merged);
            applyResolvedSize(pending.track, merged);
        }
        pending.resolve(merged);
    }

    function playFromResults(track) {
        if (!track) return;
        if (track.source === "qq" && track.paid) {
            showToast("这首 QQ 音乐需要付费，暂不支持播放。");
            return;
        }
        let index = state.results.findIndex(item => item.uiId === track.uiId);
        if (index < 0) {
            index = state.results.indexOf(track);
        }
        if (index < 0) return;
        state.queue = state.results.slice();
        state.queueIndex = index;
        beginPlayAt(index, { showOverlay: true, forceResolve: false });
    }

    function playQueueOffset(delta) {
        if (!state.queue.length) return;
        let index = state.queueIndex;
        if (index < 0) {
            index = state.current
                ? state.queue.findIndex(item => item.uiId === state.current.uiId)
                : 0;
            if (index < 0) index = 0;
        }
        index = (index + delta + state.queue.length) % state.queue.length;
        beginPlayAt(index, { showOverlay: state.playerOpen, forceResolve: false });
    }

    async function beginPlayAt(index, options) {
        const opts = options || {};
        const track = state.queue[index];
        if (!track) return;
        state.queueIndex = index;
        state.playerTrack = track;
        state.audioBroken = false;
        const showOverlay = !!opts.showOverlay;
        const token = ++state.loadToken;

        if (showOverlay) {
            if (!state.playerOpen) {
                openPlayerShell(track);
            } else {
                preparePlayerForTrack(track);
            }
        }

        els.audio.pause();
        hidePlayerError();
        setResolving(true);
        setPlayerError("");
        if (showOverlay) {
            resetPlayerProgress(track);
            els.artLoading.hidden = false;
        }

        try {
            const resolved = await requestResolve(track, !!opts.forceResolve);
            if (token !== state.loadToken) return;
            if (!state.playerTrack || state.playerTrack.uiId !== track.uiId) return;

            state.current = resolved;
            state.playerTrack = resolved;
            state.audioBroken = false;
            applyResolvedTrack(resolved, showOverlay || state.playerOpen);
            els.audio.src = resolved.url;
            els.audio.load();
            setResolving(false);
            updateQueueControls();
            updateMiniPlayer(resolved);
            const currentIndex = state.queue.findIndex(item => item.uiId === resolved.uiId);
            if (currentIndex >= 0) state.queueIndex = currentIndex;
            updateCurrentRows();
            updateDownloadButtons();
            playAudio();
        } catch (error) {
            if (token !== state.loadToken) return;
            setResolving(false);
            const message = error && error.message ? error.message : "暂时无法播放这首歌。";
            if (showOverlay || state.playerOpen) {
                setPlayerError(message);
            } else {
                showToast(message);
            }
        }
    }

    function playAudio() {
        if (!state.current) {
            handlePlayButton();
            return;
        }
        // Keep the local queue running while browsing Online; pause it only
        // when this HTML5 preview really starts.
        try {
            if (state.bridge && typeof state.bridge.pauseLocalPlayback === "function") {
                state.bridge.pauseLocalPlayback();
            }
        } catch (error) {
            /* A missing bridge must not block online playback. */
        }
        const attempt = ++state.playControl;
        els.audio.play().then(() => {
            if (attempt === state.playControl) {
                setResolving(false);
            }
        }).catch(error => {
            if (attempt !== state.playControl) return;
            if (state.isDemo && error && error.name === "NotAllowedError") {
                setPlaybackUi(false);
                return;
            }
            const name = error && error.name ? error.name : "";
            let message = "播放失败，请稍后再试。";
            if (name === "NotAllowedError") {
                message = "请轻触播放按钮开始播放。";
            } else if (name === "NotSupportedError") {
                message = "当前设备无法解码这个音频格式。";
            }
            setResolving(false);
            setPlayerError(message);
            state.audioBroken = true;
            showToast(message);
            setPlaybackUi(false);
        });
    }

    function togglePlayback() {
        if (state.isResolving) return;
        if (!state.current) {
            if (state.playerTrack) {
                retryCurrentTrack();
            }
            return;
        }
        if (state.audioBroken) {
            retryCurrentTrack();
            return;
        }
        if (els.audio.paused) {
            playAudio();
        } else {
            els.audio.pause();
        }
    }

    function handlePlayButton() {
        if (state.isResolving) return;
        if (!state.current || !state.playerTrack ||
            state.current.uiId !== state.playerTrack.uiId ||
            !els.audio.src || state.audioBroken) {
            retryCurrentTrack();
            return;
        }
        togglePlayback();
    }

    function retryCurrentTrack() {
        const track = state.playerTrack || state.current;
        if (!track) return;
        let index = state.queueIndex;
        if (index < 0 || !state.queue[index]) {
            index = state.queue.findIndex(item => item.uiId === track.uiId);
        }
        if (index < 0) {
            state.queue = [track];
            index = 0;
        }
        state.audioBroken = false;
        if (track.uiId) state.resolvedCache.delete(track.uiId);
        beginPlayAt(index, {
            showOverlay: state.playerOpen,
            forceResolve: true
        });
    }

    function applyResolvedTrack(track, updateOverlay) {
        els.playerTitle.textContent = track.title || "未知歌曲";
        const artistLine = [track.artist || "未知歌手", track.album || ""]
            .filter(Boolean).join(" · ");
        els.playerArtist.textContent = artistLine;
        els.playerSource.textContent = sourceLabel(track.source) + " · NOW PLAYING";
        applyPlayerArtwork(track);

        if (track.qualityLabel) {
            els.qualityBadge.textContent = track.qualityLabel;
            els.qualityBadge.hidden = false;
        } else {
            els.qualityBadge.hidden = true;
        }

        setLyrics(track.lrc || "");
        els.playerDownload.dataset.downloadFor = track.uiId;
        updateDownloadButtons();
        updateMediaSession(track);

        if (updateOverlay && state.playerOpen) {
            els.playerError.hidden = true;
            updatePlayerTimeFromAudio();
            if (state.lyricsOpen) {
                updateActiveLyric(els.audio.currentTime, true);
            }
        }
        updateMiniPlayer(track);
        updateQueueCaption();
    }

    function updateCurrentRows() {
        const currentId = state.current ? state.current.uiId : "";
        const paused = els.audio.paused;
        $$(".track", els.trackList).forEach(row => {
            const active = currentId && row.dataset.uiId === currentId;
            row.classList.toggle("is-current", !!active);
            row.classList.toggle("paused", !!active && paused);
            row.setAttribute("aria-label",
                (row.querySelector(".track-title h3") || {}).textContent
                    ? (row.querySelector(".track-title h3").textContent + "，" +
                        (active ? "正在播放，点击打开播放器" : "点击播放"))
                    : "");
        });
    }

    function updateQueueControls() {
        const hasQueue = state.queue.length > 0;
        els.prevBtn.disabled = !hasQueue;
        els.nextBtn.disabled = !hasQueue;
        els.miniNext.disabled = !hasQueue;
        els.playBtn.disabled = state.isResolving || !state.playerTrack;
        updateQueueCaption();
    }

    function updateQueueCaption() {
        if (!state.playerTrack) {
            els.queueCaption.textContent = "";
            return;
        }
        const source = sourceLabel(state.playerTrack.source);
        if (state.queue.length && state.queueIndex >= 0) {
            els.queueCaption.textContent =
                (state.queueIndex + 1) + " / " + state.queue.length + " · " + source;
        } else {
            els.queueCaption.textContent = source;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Player UI                                                          */
    /* ------------------------------------------------------------------ */

    function openPlayerShell(track) {
        state.playerOpen = true;
        state.lastFocused = document.activeElement;
        document.body.classList.add("player-open");
        els.player.classList.add("is-open");
        els.player.setAttribute("aria-hidden", "false");
        preparePlayerForTrack(track);
        setResolving(false);
        updateQueueControls();
        window.setTimeout(() => {
            if (state.playerOpen) els.closePlayer.focus({ preventScroll: true });
        }, 420);
    }

    function preparePlayerForTrack(track) {
        state.playerTrack = track;
        els.playerTitle.textContent = track.title || "未知歌曲";
        els.playerArtist.textContent = [track.artist, track.album].filter(Boolean).join(" · ");
        els.playerSource.textContent = sourceLabel(track.source) + " · NOW PLAYING";
        els.qualityBadge.hidden = true;
        els.qualityBadge.textContent = "";
        els.playerError.hidden = true;
        els.playerErrorText.textContent = "";
        els.playerDownload.dataset.downloadFor = track.uiId || "";
        applyPlayerArtwork(track);
        setLyrics("");
        els.lyricsToggle.disabled = true;
        updateDownloadButtons();
        closeLyricsSheet(false);
        resetPlayerProgress(track);
        if (!state.current || state.current.uiId !== track.uiId) {
            els.audio.pause();
        }
    }

    function resetPlayerProgress(track) {
        state.lyricIndex = -1;
        els.currentTime.textContent = "0:00";
        els.totalTime.textContent = track && track.duration > 0 ? formatTime(track.duration) : "0:00";
        els.seekBar.value = "0";
        els.seekBar.style.setProperty("--progress", "0%");
        els.seekBar.disabled = true;
        els.miniProgress.style.width = "0%";
        $$(".lyric-line", els.lyricsScroll).forEach(line => line.classList.remove("is-active"));
    }

    function openPlayerForCurrent() {
        if (!state.current) return;
        if (!state.playerOpen) {
            state.playerOpen = true;
            state.lastFocused = document.activeElement;
            document.body.classList.add("player-open");
            els.player.classList.add("is-open");
            els.player.setAttribute("aria-hidden", "false");
        }
        els.playerTitle.textContent = state.current.title || "未知歌曲";
        els.playerArtist.textContent = [state.current.artist, state.current.album]
            .filter(Boolean).join(" · ");
        els.playerSource.textContent = sourceLabel(state.current.source) + " · NOW PLAYING";
        applyPlayerArtwork(state.current);
        els.playerDownload.dataset.downloadFor = state.current.uiId;
        setLyrics(state.current.lrc || "");
        if (state.current.qualityLabel) {
            els.qualityBadge.textContent = state.current.qualityLabel;
            els.qualityBadge.hidden = false;
        } else {
            els.qualityBadge.hidden = true;
        }
        els.playerError.hidden = !state.audioBroken;
        if (state.audioBroken) {
            els.playerErrorText.textContent = "音频加载失败，点按播放按钮重试。";
        }
        setResolving(false);
        updateQueueControls();
        updateDownloadButtons();
        updatePlayerTimeFromAudio();
        setPlaybackUi(!els.audio.paused);
    }

    function closePlayer() {
        if (!state.playerOpen) return;
        if (state.isResolving) {
            state.loadToken++;
            setResolving(false);
        }
        closeLyricsSheet(false);
        state.playerOpen = false;
        els.player.classList.remove("is-open");
        els.player.setAttribute("aria-hidden", "true");
        document.body.classList.remove("player-open");
        if (state.lastFocused && typeof state.lastFocused.focus === "function") {
            const target = state.lastFocused;
            state.lastFocused = null;
            window.setTimeout(() => {
                try {
                    target.focus({ preventScroll: true });
                } catch (error) {
                    /* Element may no longer exist. */
                }
            }, 80);
        }
    }

    function setResolving(resolving) {
        state.resolving = !!resolving;
        els.artLoading.hidden = !state.resolving;
        els.playBtn.classList.toggle("is-loading", state.resolving);
        els.playBtn.disabled = state.resolving;
        els.player.setAttribute("aria-busy", state.resolving ? "true" : "false");
        if (state.resolving) {
            els.lyricsToggle.disabled = true;
            els.seekBar.disabled = true;
        } else {
            if (state.playerTrack) els.playBtn.disabled = false;
            els.seekBar.disabled = !(Number.isFinite(els.audio.duration) && els.audio.duration > 0);
            els.lyricsToggle.disabled = state.lyrics.length === 0;
        }
        updateQueueControls();
    }

    function setPlayerError(message) {
        if (!message) {
            els.playerError.hidden = true;
            els.playerErrorText.textContent = "";
            return;
        }
        els.playerErrorText.textContent = message;
        els.playerError.hidden = false;
    }

    function hidePlayerError() {
        els.playerError.hidden = true;
        els.playerErrorText.textContent = "";
    }

    function setPlaybackUi(playing) {
        const mainUse = els.playBtn.querySelector("use");
        if (mainUse) mainUse.setAttribute("href", playing ? "#i-pause" : "#i-play");
        const miniUse = els.miniPlay.querySelector("use");
        if (miniUse) miniUse.setAttribute("href", playing ? "#i-pause" : "#i-play");
        els.playBtn.setAttribute("aria-label", playing ? "暂停" : "播放");
        els.miniPlay.setAttribute("aria-label", playing ? "暂停" : "播放");
        els.player.classList.toggle("is-playing", !!playing);
        updateCurrentRows();
        try {
            if ("mediaSession" in navigator) {
                navigator.mediaSession.playbackState = playing ? "playing" : "paused";
            }
        } catch (error) {
            /* Media Session is optional. */
        }
    }

    function applyPlayerArtwork(track) {
        const colors = paletteFor(track);
        els.playerArt.style.setProperty("--a", colors[0]);
        els.playerArt.style.setProperty("--b", colors[1]);

        els.playerArtImage.onerror = () => {
            els.playerArtImage.hidden = true;
        };
        if (track.cover) {
            els.playerArtImage.src = track.cover;
            els.playerArtImage.hidden = false;
        } else {
            els.playerArtImage.removeAttribute("src");
            els.playerArtImage.hidden = true;
        }

        els.playerBackdrop.textContent = "";
        if (track.cover) {
            const image = document.createElement("img");
            image.alt = "";
            image.setAttribute("aria-hidden", "true");
            image.referrerPolicy = "no-referrer";
            image.addEventListener("error", () => image.remove());
            image.src = track.cover;
            els.playerBackdrop.appendChild(image);
        }
    }

    function buildCoverInto(container, track) {
        container.textContent = "";
        const colors = paletteFor(track);
        container.style.setProperty("--a", colors[0]);
        container.style.setProperty("--b", colors[1]);
        const fallback = element("div", "cover-fallback");
        fallback.appendChild(iconNode("i-music"));
        container.appendChild(fallback);
        if (track && track.cover) {
            const image = document.createElement("img");
            image.alt = "";
            image.referrerPolicy = "no-referrer";
            image.addEventListener("error", () => image.remove());
            image.src = track.cover;
            container.appendChild(image);
        }
    }

    function updateMiniPlayer(track) {
        if (!track) return;
        buildCoverInto(els.miniCover, track);
        els.miniTitle.textContent = track.title || "未命名歌曲";
        els.miniArtist.textContent = [track.artist, sourceLabel(track.source)]
            .filter(Boolean).join(" · ");
        if (els.miniPlayer.hidden) {
            els.miniPlayer.hidden = false;
            window.requestAnimationFrame(() => {
                els.miniPlayer.classList.add("is-visible");
            });
        } else {
            els.miniPlayer.classList.add("is-visible");
        }
        updateDownloadButtons();
    }

    /* ------------------------------------------------------------------ */
    /* Progress / audio events                                            */
    /* ------------------------------------------------------------------ */

    function configureAudio() {
        const audio = els.audio;
        audio.volume = 1;

        audio.addEventListener("loadedmetadata", () => {
            updatePlayerTimeFromAudio();
            updateDuration();
        });
        audio.addEventListener("durationchange", updateDuration);
        audio.addEventListener("timeupdate", updatePlaybackTime);
        audio.addEventListener("play", () => {
            setResolving(false);
            setPlaybackUi(true);
        });
        audio.addEventListener("pause", () => setPlaybackUi(false));
        audio.addEventListener("ended", () => {
            if (state.queue.length) {
                playQueueOffset(1);
            } else {
                setPlaybackUi(false);
            }
        });
        audio.addEventListener("error", () => {
            if (!state.current || !audio.currentSrc) return;
            state.audioBroken = true;
            setResolving(false);
            const message = "音频加载失败，可能是链接已过期。点按播放按钮重试。";
            if (state.playerOpen) {
                setPlayerError(message);
            } else {
                showToast("音频加载失败，点按播放重试。");
            }
            setPlaybackUi(false);
        });
    }

    function updateDuration() {
        const duration = Number.isFinite(els.audio.duration) && els.audio.duration > 0
            ? els.audio.duration
            : (state.current && state.current.duration > 0 ? state.current.duration : 0);
        els.totalTime.textContent = duration > 0 ? formatTime(duration) : "0:00";
        els.seekBar.disabled = state.resolving || duration <= 0;
    }

    function updatePlayerTimeFromAudio() {
        const current = Number.isFinite(els.audio.currentTime) ? els.audio.currentTime : 0;
        const fallbackDuration = state.current && state.current.duration > 0
            ? state.current.duration
            : (state.playerTrack && state.playerTrack.duration > 0
                ? state.playerTrack.duration : 0);
        const duration = Number.isFinite(els.audio.duration) && els.audio.duration > 0
            ? els.audio.duration
            : fallbackDuration;
        els.currentTime.textContent = formatTime(current);
        els.totalTime.textContent = duration > 0 ? formatTime(duration) : "0:00";
        const fraction = duration > 0 ? clamp(current / duration, 0, 1) : 0;
        els.seekBar.value = String(Math.round(fraction * 1000));
        els.seekBar.style.setProperty("--progress", (fraction * 100).toFixed(2) + "%");
        els.miniProgress.style.width = (fraction * 100).toFixed(2) + "%";
        els.seekBar.disabled = state.resolving || duration <= 0;
        updateActiveLyric(current);
    }

    function updatePlaybackTime() {
        if (!state.isSeeking) {
            updatePlayerTimeFromAudio();
        } else {
            const duration = Number.isFinite(els.audio.duration) ? els.audio.duration : 0;
            const current = Number(els.seekBar.value) / 1000 * duration;
            els.currentTime.textContent = formatTime(current);
            updateActiveLyric(current);
        }
    }

    function handleSeekInput() {
        const duration = Number.isFinite(els.audio.duration) ? els.audio.duration : 0;
        if (duration <= 0) return;
        const value = Number(els.seekBar.value);
        const target = clamp(value / 1000 * duration, 0, duration);
        els.audio.currentTime = target;
        els.currentTime.textContent = formatTime(target);
        els.seekBar.style.setProperty("--progress", (value / 10).toFixed(2) + "%");
        els.miniProgress.style.width = (value / 10).toFixed(2) + "%";
        updateActiveLyric(target);
    }

    /* ------------------------------------------------------------------ */
    /* Lyrics                                                             */
    /* ------------------------------------------------------------------ */

    function parseLrc(raw) {
        if (!raw) return [];
        const text = String(raw).replace(/\r/g, "");
        let offset = 0;
        const offsetMatch = /\[offset:([+-]?\d+)\]/i.exec(text);
        if (offsetMatch) {
            const value = Number(offsetMatch[1]);
            if (Number.isFinite(value)) offset = value / 1000;
        }

        const result = [];
        const seen = new Set();
        const timeTag = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;
        const lines = text.split("\n");

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            if (!line) continue;
            timeTag.lastIndex = 0;
            const times = [];
            let match;
            while ((match = timeTag.exec(line)) !== null) {
                const minutes = Number(match[1]) || 0;
                const seconds = Number(match[2]) || 0;
                const fraction = match[3] ? Number((match[3] + "000").slice(0, 3)) / 1000 : 0;
                let time = minutes * 60 + seconds + fraction + offset;
                if (time < 0) time = 0;
                times.push(time);
            }
            if (!times.length) continue;
            const content = line
                .replace(timeTag, "")
                .replace(/<[^>]*>/g, "")
                .trim();
            if (!content) continue;
            times.forEach(time => {
                const key = time.toFixed(2) + "|" + content;
                if (seen.has(key)) return;
                seen.add(key);
                result.push({ time: time, text: content });
            });
        }

        result.sort((a, b) => a.time - b.time);
        return result.slice(0, 500);
    }

    function setLyrics(raw) {
        state.lyrics = parseLrc(raw);
        state.lyricIndex = -1;
        els.lyricsScroll.textContent = "";
        if (!state.lyrics.length) {
            els.lyricsToggle.disabled = true;
            els.lyricsScroll.appendChild(element("p", "lyrics-empty", "暂无歌词"));
            if (state.lyricsOpen) closeLyricsSheet(false);
            return;
        }

        const fragment = document.createDocumentFragment();
        state.lyrics.forEach((line, index) => {
            const button = element("button", "lyric-line", line.text);
            button.type = "button";
            button.dataset.time = String(line.time);
            button.dataset.index = String(index);
            fragment.appendChild(button);
        });
        els.lyricsScroll.appendChild(fragment);
        els.lyricsToggle.disabled = state.resolving;
        updateActiveLyric(els.audio.currentTime, true);
    }

    function updateActiveLyric(time, force) {
        if (!state.lyrics.length) {
            els.lyricsScroll.classList.add("has-lyrics");
            return;
        }
        let index = -1;
        for (let i = 0; i < state.lyrics.length; i++) {
            if (time + 0.12 >= state.lyrics[i].time) index = i;
            else break;
        }
        if (index === state.lyricIndex && !force) return;
        state.lyricIndex = index;
        const lines = $$(".lyric-line", els.lyricsScroll);
        lines.forEach((line, i) => line.classList.toggle("is-active", i === index));
        if (index >= 0 && lines[index] && state.lyricsOpen) {
            const line = lines[index];
            const target = line.offsetTop - els.lyricsScroll.clientHeight / 2 +
                line.offsetHeight / 2;
            els.lyricsScroll.scrollTo({
                top: Math.max(0, target),
                behavior: force ? "auto" : "smooth"
            });
        }
    }

    function toggleLyrics() {
        if (!state.lyrics.length) {
            showToast("这首歌暂时没有歌词。");
            return;
        }
        if (state.lyricsOpen) {
            closeLyricsSheet(true);
        } else {
            openLyricsSheet();
        }
    }

    function openLyricsSheet() {
        if (!state.lyrics.length) return;
        state.lyricsOpen = true;
        els.player.classList.add("is-lyrics");
        els.lyricsPanel.classList.add("is-open");
        els.lyricsPanel.setAttribute("aria-hidden", "false");
        window.setTimeout(() => {
            updateActiveLyric(els.audio.currentTime, true);
            els.closeLyrics.focus({ preventScroll: true });
        }, 80);
    }

    function closeLyricsSheet(restoreFocus) {
        state.lyricsOpen = false;
        els.player.classList.remove("is-lyrics");
        els.lyricsPanel.classList.remove("is-open");
        els.lyricsPanel.setAttribute("aria-hidden", "true");
        if (restoreFocus && state.playerOpen) {
            els.lyricsToggle.focus({ preventScroll: true });
        }
    }

    /* ------------------------------------------------------------------ */
    /* Download                                                           */
    /* ------------------------------------------------------------------ */

    async function handleDownload(track, button) {
        if (!track) return;
        if (state.downloaded.has(track.uiId)) {
            showToast("这首歌已经在下载队列里了。");
            return;
        }
        if (state.downloadBusy.has(track.uiId)) return;
        if (track.source === "qq" && track.paid) {
            showToast("这首 QQ 音乐需要付费，暂不支持下载。");
            return;
        }
        if (!state.bridge) {
            showToast("下载功能需要在 Android 应用中使用。");
            return;
        }

        setDownloadBusy(track.uiId, true);
        try {
            const preferredQuality = track.source === "jamendo"
                ? "standard"
                : state.defaultQuality;
            let resolved;
            try {
                resolved = await requestQualityResolve(track, preferredQuality);
            } catch (qualityError) {
                // Older bridges or a temporarily unavailable tier should still
                // allow the standard save path.
                resolved = await requestResolve(track);
            }
            if (!resolved || !/^https?:\/\//i.test(resolved.url || "")) {
                throw new Error("播放地址无效，换一首歌试试。");
            }
            setDownloadBusy(track.uiId, false);
            openSaveDialog(track, resolved, preferredQuality);
        } catch (error) {
            setDownloadBusy(track.uiId, false);
            showToast(error && error.message ? error.message : "下载准备失败，请稍后再试。");
        }
    }

    function openSaveDialog(track, resolved, preferredQuality) {
        if (state.saveOpen) return;
        state.saveOpen = true;
        state.saveBusy = false;
        state.saveQualityBusy = false;
        state.saveTrack = track;
        state.saveResolved = resolved;
        state.saveMode = "library";
        state.saveQuality = resolved.quality
            || (track.source === "jamendo" ? "standard" : "high");
        state.saveUnavailableQualities = new Set();
        if (preferredQuality && resolved.quality &&
            resolved.quality !== preferredQuality) {
            state.saveUnavailableQualities.add(preferredQuality);
        }
        state.saveLastFocused = document.activeElement;
        const lrc = typeof resolved.lrc === "string" ? resolved.lrc.trim() : "";
        state.saveLyricsAvailable = lrc.length > 0;
        state.saveLyrics = state.saveLyricsAvailable;

        els.saveTitle.textContent = track.title || "未知歌曲";
        els.saveArtist.textContent = [track.artist || "未知歌手", track.album || ""]
            .filter(Boolean).join(" · ");
        els.saveCover.classList.toggle("has-image", !!track.cover);
        els.saveCover.style.backgroundImage = track.cover
            ? 'url("' + String(track.cover).replace(/["\\\n\r]/g, "") + '")'
            : "";
        updateSaveSizeDisplay(resolved);
        renderSaveQualities();
        els.saveLyrics.checked = state.saveLyrics;
        els.saveLyrics.disabled = !state.saveLyricsAvailable;
        els.saveLyricsRow.classList.toggle("is-unavailable", !state.saveLyricsAvailable);
        els.saveLyricsHint.textContent = state.saveLyricsAvailable
            ? "播放页可直接查看" : "该来源暂未提供歌词";
        setSaveBusy(false);
        setSaveMode("library");
        els.saveProgress.hidden = true;
        els.saveProgressText.textContent = "准备下载…";
        els.saveProgressPct.textContent = "";
        els.saveProgressBar.style.width = "0%";
        document.body.classList.add("sheet-open");
        if (state.saveScrimTimer) {
            window.clearTimeout(state.saveScrimTimer);
            state.saveScrimTimer = 0;
        }
        els.saveScrim.hidden = false;
        state.saveScrimTimer = 0;
        els.saveSheet.classList.add("is-open");
        els.saveSheet.setAttribute("aria-hidden", "false");
        window.requestAnimationFrame(() => els.saveScrim.classList.add("is-visible"));
        window.setTimeout(() => els.saveModeLibrary.focus({ preventScroll: true }), 80);
    }

    function updateSaveSizeDisplay(resolved) {
        const track = state.saveTrack;
        const exactSize = resolved && Number(resolved.size) > 0
            ? { bytes: Number(resolved.size), exact: resolved.sizeExact !== false }
            : trackSizeInfo(track);
        els.saveSize.textContent = exactSize
            ? (exactSize.exact ? "文件大小 " : "预计占用 约 ") + formatBytes(exactSize.bytes)
            : "文件大小将在下载后显示";
    }

    function qualityChipSubtitle(track, tier, resolved) {
        let bytes = 0;
        let exact = false;
        if (resolved && resolved.quality === tier && Number(resolved.size) > 0) {
            bytes = Number(resolved.size);
            exact = resolved.sizeExact !== false;
        } else if (track && track.qualitySizes &&
            Number(track.qualitySizes[tier]) > 0) {
            bytes = Number(track.qualitySizes[tier]);
            exact = true;
        } else {
            bytes = qualityEstimateBytes(track, tier);
        }
        if (bytes > 0) return (exact ? "" : "约 ") + formatBytes(bytes);
        return QUALITY_META[tier] ? QUALITY_META[tier].hint : "";
    }

    function renderSaveQualities() {
        if (!state.saveTrack) return;
        const track = state.saveTrack;
        const resolved = state.saveResolved;
        const tiers = qualityTiersForSource(track.source);
        els.saveQualityOptions.textContent = "";
        tiers.forEach(tier => {
            const button = element("button", "quality-chip");
            button.type = "button";
            button.dataset.quality = tier;
            const isSelected = state.saveQuality === tier;
            button.classList.toggle("is-selected", isSelected);
            button.classList.toggle(
                "is-loading",
                state.saveQualityBusy && isSelected
            );
            button.disabled = state.saveBusy || state.saveQualityBusy ||
                state.saveUnavailableQualities.has(tier);
            button.appendChild(element(
                "strong", "", QUALITY_META[tier] ? QUALITY_META[tier].label : tier
            ));
            button.appendChild(element(
                "small", "", qualityChipSubtitle(track, tier, resolved)
            ));
            button.addEventListener("click", () => selectSaveQuality(tier));
            els.saveQualityOptions.appendChild(button);
        });
        if (state.saveQualityBusy) {
            els.saveQualityNote.textContent = "正在获取…";
        } else if (resolved && resolved.qualityLabel) {
            els.saveQualityNote.textContent = resolved.qualityLabel;
        } else {
            els.saveQualityNote.textContent = qualityTierLabel(state.saveQuality);
        }
        const locked = state.saveBusy || state.saveQualityBusy;
        els.closeSave.disabled = locked;
        els.saveCancel.disabled = locked;
        els.saveModeLibrary.disabled = locked;
        els.saveModePublic.disabled = locked;
        els.saveLyrics.disabled = locked || !state.saveLyricsAvailable;
        els.saveConfirm.disabled = locked;
    }

    async function selectSaveQuality(tier) {
        if (!state.saveOpen || state.saveBusy || state.saveQualityBusy) return;
        if (tier === state.saveQuality || !state.saveTrack) return;
        if (state.saveUnavailableQualities.has(tier)) return;
        const track = state.saveTrack;
        const previousQuality = state.saveQuality;
        const previousResolved = state.saveResolved;
        state.saveQualityBusy = true;
        els.saveConfirm.disabled = true;
        renderSaveQualities();
        try {
            const resolved = await requestQualityResolve(track, tier);
            const actual = resolved.quality || tier;
            const merged = Object.assign({}, resolved, {
                lrc: (typeof resolved.lrc === "string" && resolved.lrc.trim())
                    ? resolved.lrc
                    : (previousResolved && previousResolved.lrc) || ""
            });
            state.saveResolved = merged;
            state.saveQuality = actual;
            if (actual !== tier) {
                state.saveUnavailableQualities.add(tier);
                showToast(
                    qualityTierLabel(tier) + "音质暂不可用，已切换为" +
                    qualityTierLabel(actual)
                );
            }
            updateSaveSizeDisplay(merged);
        } catch (error) {
            state.saveResolved = previousResolved;
            state.saveQuality = previousQuality;
            state.saveUnavailableQualities.add(tier);
            showToast(error && error.message ? error.message : "该音质暂时不可用。");
        } finally {
            state.saveQualityBusy = false;
            els.saveConfirm.disabled = state.saveBusy;
            renderSaveQualities();
        }
    }

    function closeSaveDialog(restoreFocus) {
        if (!state.saveOpen || state.saveBusy || state.saveQualityBusy) return;
        state.saveOpen = false;
        state.saveQualityBusy = false;
        state.saveTrack = null;
        state.saveResolved = null;
        state.saveRequestId = "";
        els.saveSheet.classList.remove("is-open");
        els.saveSheet.setAttribute("aria-hidden", "true");
        els.saveScrim.classList.remove("is-visible");
        document.body.classList.remove("sheet-open");
        if (state.saveScrimTimer) window.clearTimeout(state.saveScrimTimer);
        state.saveScrimTimer = window.setTimeout(() => {
            els.saveScrim.hidden = true;
            state.saveScrimTimer = 0;
        }, 320);
        if (restoreFocus && state.saveLastFocused &&
            typeof state.saveLastFocused.focus === "function") {
            const target = state.saveLastFocused;
            window.setTimeout(() => {
                try {
                    target.focus({ preventScroll: true });
                } catch (error) {
                    /* The result row may have been re-rendered. */
                }
            }, 60);
        }
        state.saveLastFocused = null;
    }

    function setSaveMode(mode) {
        if (state.saveBusy) return;
        state.saveMode = mode === "public" ? "public" : "library";
        const isLibrary = state.saveMode === "library";
        els.saveModeLibrary.classList.toggle("is-selected", isLibrary);
        els.saveModePublic.classList.toggle("is-selected", !isLibrary);
        els.saveLyricsRow.classList.toggle("is-hidden", !isLibrary);
        els.saveConfirm.textContent = isLibrary ? "存入播放器" : "仅保存文件";
    }

    function setSaveBusy(busy) {
        state.saveBusy = busy;
        if (!busy && state.saveOpen) {
            els.saveConfirm.textContent =
                state.saveMode === "library" ? "存入播放器" : "仅保存文件";
        }
        if (state.saveOpen) renderSaveQualities();
    }

    function confirmSave() {
        if (!state.saveOpen || state.saveBusy || state.saveQualityBusy ||
            !state.saveTrack || !state.saveResolved) return;
        const track = state.saveTrack;
        const resolved = state.saveResolved;
        if (!state.bridge) {
            showToast("保存功能需要在 Android 应用中使用。");
            return;
        }

        if (state.saveMode === "public") {
            try {
                state.bridge.download(JSON.stringify(resolved));
                armDownloadTimeout(track.uiId);
                closeSaveDialog(false);
            } catch (error) {
                showToast("无法开始下载，请稍后再试。");
            }
            return;
        }

        if (typeof state.bridge.downloadToLibrary !== "function") {
            showToast("当前版本不支持存入播放器，请更新应用。");
            return;
        }
        const requestId = "lib-" + (++state.saveSeq) + "-" + Date.now();
        state.saveRequestId = requestId;
        setSaveBusy(true);
        setDownloadBusy(track.uiId, true);
        els.saveProgress.hidden = false;
        els.saveProgressText.textContent = "准备下载…";
        els.saveProgressPct.textContent = "";
        els.saveProgressBar.style.width = "0%";
        try {
            state.bridge.downloadToLibrary(
                requestId,
                JSON.stringify(resolved),
                !!(state.saveLyrics && state.saveLyricsAvailable)
            );
        } catch (error) {
            handleLibraryError(requestId, "无法开始保存，请稍后再试。");
        }
    }

    function handleLibraryProgress(requestId, phase, received, total) {
        if (!state.saveBusy || requestId !== state.saveRequestId) return;
        const label = phase === "cover" ? "正在保存封面"
            : phase === "import" ? "正在加入音乐馆"
            : "正在下载音频";
        let text = label + "…";
        if (phase === "audio" && received > 0) {
            text += " " + formatBytes(received);
            if (total > 0) text += " / " + formatBytes(total);
        }
        els.saveProgressText.textContent = text;
        els.saveProgress.hidden = false;
        if (total > 0 && received > 0) {
            const percent = clamp(Math.round(received / total * 100), 0, 100);
            els.saveProgressBar.style.width = percent + "%";
            els.saveProgressPct.textContent = percent + "%";
        } else {
            els.saveProgressBar.style.width = phase === "import" ? "100%" : "0%";
            els.saveProgressPct.textContent = phase === "import" ? "处理中" : "";
        }
    }

    function handleLibraryResult(requestId, jsonText) {
        if (requestId !== state.saveRequestId) return;
        const data = parseJson(jsonText) || {};
        const track = state.saveTrack;
        state.saveBusy = false;
        setSaveBusy(false);
        if (track) {
            clearDownloadTimer(track.uiId);
            state.downloadBusy.delete(track.uiId);
            updateDownloadButtons();
        }
        if (!data.ok) {
            handleLibraryError(requestId, data.error || "保存失败，请稍后再试。");
            return;
        }
        if (track) state.downloaded.add(track.uiId);
        updateDownloadButtons();
        state.saveRequestId = "";
        closeSaveDialog(false);
        let message = "已存入播放器";
        if (data.alreadyExists) {
            message = "这首歌已在播放器中";
        } else if (data.lyricsIncluded) {
            message = "已存入播放器 · 歌词已加入";
        } else if (data.lyricsRequested) {
            message = "已存入播放器 · 该来源暂无歌词";
        }
        showToast(message);
    }

    function handleLibraryError(requestId, message) {
        if (requestId !== state.saveRequestId) return;
        const track = state.saveTrack;
        state.saveRequestId = "";
        state.saveBusy = false;
        setSaveBusy(false);
        if (track) {
            clearDownloadTimer(track.uiId);
            state.downloadBusy.delete(track.uiId);
            updateDownloadButtons();
        }
        const text = cleanText(message) || "保存失败，请稍后再试。";
        els.saveProgress.hidden = false;
        els.saveProgressText.textContent = text;
        els.saveProgressPct.textContent = "";
        els.saveProgressBar.style.width = "0%";
        showToast(text);
    }

    function setDownloadBusy(uiId, busy) {
        if (!uiId) return;
        if (busy) state.downloadBusy.add(uiId);
        else state.downloadBusy.delete(uiId);
        updateDownloadButtons();
    }

    function armDownloadTimeout(uiId) {
        clearDownloadTimer(uiId);
        const timer = window.setTimeout(() => {
            if (!state.downloadBusy.has(uiId)) return;
            state.downloadBusy.delete(uiId);
            updateDownloadButtons();
            showToast("下载请求没有响应，请稍后再试。");
        }, 9000);
        state.downloadTimers.set(uiId, timer);
    }

    function clearDownloadTimer(uiId) {
        const timer = state.downloadTimers.get(uiId);
        if (timer) {
            window.clearTimeout(timer);
            state.downloadTimers.delete(uiId);
        }
    }

    function clearDownloadBusy() {
        state.downloadBusy.clear();
        state.downloadTimers.forEach(timer => window.clearTimeout(timer));
        state.downloadTimers.clear();
        updateDownloadButtons();
    }

    function handleDownloadQueued(uiId) {
        if (!uiId) return;
        clearDownloadTimer(uiId);
        state.downloadBusy.delete(uiId);
        state.downloaded.add(uiId);
        updateDownloadButtons();
        showToast("已加入下载队列 · 保存到 音乐/CatMusic");
    }

    function updateDownloadButtons() {
        $$("[data-download-for]").forEach(button => {
            const uiId = button.dataset.downloadFor || "";
            const done = state.downloaded.has(uiId);
            const busy = state.downloadBusy.has(uiId);
            button.classList.toggle("is-done", done);
            button.classList.toggle("is-busy", busy);
            button.disabled = done;
            useIcon(button, done ? "i-check" : "i-download");
            if (button === els.playerDownload) {
                if (!uiId) button.disabled = true;
                button.setAttribute("aria-label",
                    done ? "已加入下载" : busy ? "正在准备下载" : "下载当前歌曲");
            } else {
                button.setAttribute("aria-label",
                    done ? "已保存" : busy ? "正在准备保存" : "保存歌曲");
            }
            button.title = done ? "已保存" : "选择保存位置";
        });
    }

    /* ------------------------------------------------------------------ */
    /* About / misc                                                       */
    /* ------------------------------------------------------------------ */

    function openAbout() {
        if (state.aboutOpen) return;
        state.aboutOpen = true;
        state.lastFocused = document.activeElement;
        document.body.classList.add("sheet-open");
        if (state.scrimTimer) {
            window.clearTimeout(state.scrimTimer);
            state.scrimTimer = 0;
        }
        els.scrim.hidden = false;
        els.aboutSheet.classList.add("is-open");
        els.aboutSheet.setAttribute("aria-hidden", "false");
        window.requestAnimationFrame(() => els.scrim.classList.add("is-visible"));
        window.setTimeout(() => els.closeAbout.focus({ preventScroll: true }), 80);
    }

    function closeAbout() {
        if (!state.aboutOpen) return;
        state.aboutOpen = false;
        els.aboutSheet.classList.remove("is-open");
        els.aboutSheet.setAttribute("aria-hidden", "true");
        els.scrim.classList.remove("is-visible");
        document.body.classList.remove("sheet-open");
        if (state.scrimTimer) window.clearTimeout(state.scrimTimer);
        state.scrimTimer = window.setTimeout(() => {
            els.scrim.hidden = true;
            state.scrimTimer = 0;
        }, 320);
        if (state.lastFocused && typeof state.lastFocused.focus === "function") {
            const target = state.lastFocused;
            state.lastFocused = null;
            window.setTimeout(() => {
                try {
                    target.focus({ preventScroll: true });
                } catch (error) {
                    /* Ignore stale focus targets. */
                }
            }, 60);
        }
    }

    function updateOnlineState() {
        const online = navigator.onLine !== false;
        els.offlineDot.classList.toggle("is-offline", !online);
        els.offlineDot.title = online ? "在线" : "离线";
        els.offlineDot.setAttribute("aria-label", online ? "在线" : "离线");
    }

    function showToast(message) {
        const text = cleanText(String(message || ""));
        if (!text) return;
        if (state.toastTimer) window.clearTimeout(state.toastTimer);
        els.toast.textContent = text;
        els.toast.classList.add("is-visible");
        state.toastTimer = window.setTimeout(() => {
            els.toast.classList.remove("is-visible");
            state.toastTimer = 0;
        }, 2800);
    }

    function updateMediaSession(track) {
        if (!("mediaSession" in navigator) || typeof MediaMetadata === "undefined") return;
        try {
            const artwork = track.cover
                ? [{ src: track.cover, sizes: "500x500", type: "image/jpeg" }]
                : [];
            navigator.mediaSession.metadata = new MediaMetadata({
                title: track.title || "",
                artist: track.artist || "",
                album: track.album || "",
                artwork: artwork
            });
        } catch (error) {
            /* Optional enhancement. */
        }
    }

    function configureMediaSession() {
        if (!("mediaSession" in navigator)) return;
        try {
            navigator.mediaSession.setActionHandler("play", () => {
                if (state.current) playAudio();
            });
            navigator.mediaSession.setActionHandler("pause", () => els.audio.pause());
            navigator.mediaSession.setActionHandler("previoustrack", () => playQueueOffset(-1));
            navigator.mediaSession.setActionHandler("nexttrack", () => playQueueOffset(1));
            navigator.mediaSession.setActionHandler("seekto", details => {
                if (Number.isFinite(details.seekTime) &&
                    Number.isFinite(els.audio.duration)) {
                    els.audio.currentTime = clamp(details.seekTime, 0, els.audio.duration);
                }
            });
        } catch (error) {
            /* Media Session is an optional enhancement. */
        }
    }

    /* ------------------------------------------------------------------ */
    /* Hardware back                                                      */
    /* ------------------------------------------------------------------ */

    function handleBack() {
        if (state.saveOpen) {
            if (!state.saveBusy && !state.saveQualityBusy) closeSaveDialog(true);
            return true;
        }
        if (state.aboutOpen) {
            closeAbout();
            return true;
        }
        if (state.lyricsOpen) {
            closeLyricsSheet(true);
            return true;
        }
        if (state.playerOpen) {
            closePlayer();
            return true;
        }
        if (!els.resultsSection.hidden) {
            goHome();
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Demo bridge (browser preview only)                                 */
    /* ------------------------------------------------------------------ */

    const DEMO_LYRICS = [
        "[ti:晴天]",
        "[ar:周杰伦]",
        "[00:00.00]晴天 - 周杰伦",
        "[00:12.00]故事的小黄花",
        "[00:16.50]从出生那年就飘着",
        "[00:21.00]童年的荡秋千",
        "[00:25.00]随记忆一直晃到现在",
        "[00:31.00]吹着前奏望着天空",
        "[00:35.00]我想起花瓣试着掉落",
        "[00:40.00]为你翘课的那一天",
        "[00:44.00]花落的那一天",
        "[00:48.00]教室的那一间",
        "[00:52.00]我怎么看不见",
        "[00:56.00]消失的下雨天",
        "[01:00.00]我好想再淋一遍",
        "[01:10.00]没想到失去的勇气我还留着",
        "[01:18.00]好想再问一遍",
        "[01:22.00]你会等待还是离开",
        "[01:30.00]刮风这天 我试过握着你手",
        "[01:37.00]但偏偏 雨渐渐大到我看你不见",
        "[01:45.00]还要多久 我才能在你身边",
        "[01:53.00]等到放晴的那天",
        "[02:00.00]也许我会比较好一点"
    ].join("\n");

    function demoDataset() {
        const items = [
            {
                source: "netease", id: "186016", title: "晴天", artist: "周杰伦",
                album: "叶惠美", duration: 269,
                cover: "https://api.qijieya.cn/meting/?server=netease&type=pic&id=109951166537300832"
            },
            {
                source: "kuwo", id: "MUSIC_228908", title: "晴天", artist: "周杰伦",
                album: "叶惠美", duration: 269,
                cover: "https://img4.kuwo.cn/star/albumcover/800/s3s94/93/211513640.jpg"
            },
            {
                source: "joox", id: "Z4E4267CE4DA9E", title: "擱淺", artist: "周杰倫",
                album: "七里香", duration: 240, index: 1
            },
            {
                source: "netease", id: "210049", title: "布拉格广场", artist: "蔡依林/周杰伦",
                album: "看我72变", duration: 260,
                cover: "https://api.qijieya.cn/meting/?server=netease&type=pic&id=109951172453712025"
            },
            {
                source: "qq", id: "000BEsDj0UUhf8", title: "晴天 (交响改编版)", artist: "维也纳碎片",
                album: "晴天", duration: 119, paid: false
            },
            {
                source: "kuwo", id: "MUSIC_118980", title: "夜曲", artist: "周杰伦",
                album: "十一月的萧邦", duration: 226,
                cover: "https://kwimg4.kuwo.cn/star/albumcover/800/s4s11/89/774616642.jpg"
            },
            {
                source: "joox", id: "ZA283D7FDC12B9", title: "楓", artist: "周杰倫",
                album: "十一月的蕭邦", duration: 275, index: 3
            },
            {
                source: "netease", id: "509781655", title: "想你就写信 (Live)", artist: "周杰伦/李硕/张鑫",
                album: "中国新歌声第二季 第13期", duration: 509, payLabel: "VIP",
                cover: "https://api.qijieya.cn/meting/?server=netease&type=pic&id=109951163038292176"
            },
            {
                source: "kuwo", id: "MUSIC_324244", title: "青花瓷", artist: "周杰伦",
                album: "我很忙", duration: 239,
                cover: "https://kwimg3.kuwo.cn/star/albumcover/800/7/83/4087363627.jpg"
            },
            {
                source: "qq", id: "0039MnYb0qxYhV", title: "安静", artist: "周杰伦",
                album: "范特西", duration: 333, paid: true, payLabel: "付费"
            },
            {
                source: "joox", id: "Z31FCD544981D4", title: "安靜", artist: "周杰倫",
                album: "范特西", duration: 334, index: 14
            },
            {
                source: "kuwo", id: "MUSIC_94237", title: "七里香", artist: "周杰伦",
                album: "七里香", duration: 299,
                cover: "https://kwimg1.kuwo.cn/star/albumcover/800/s4s81/2/3200337129.jpg"
            },
            {
                source: "kugou", id: "6D5DB4310E1573FFB6152712E68E009C",
                title: "晴天 (交响改编版)", artist: "维也纳碎片", album: "晴天",
                duration: 119
            },
            {
                source: "fivesing", id: "2104925", title: "周杰伦", artist: "傲艺",
                album: "原创", duration: 0, songType: "yc"
            },
            {
                source: "jamendo", id: "751965", title: "Ching Tin Yu - 晴天雨",
                artist: "Dylan Tinlun Chan", album: "Tin Hei - 天氣", duration: 220,
                cover: "https://usercontent.jamendo.com?type=album&id=87983&width=300&trackid=751965"
            }
        ];
        return items;
    }

    function installDemoBridge() {
        state.isDemo = true;
        const mock = {
            search(requestId, keyword, sources) {
                const enabled = String(sources || "").split(",").filter(Boolean);
                window.setTimeout(() => {
                    const results = demoDataset()
                        .filter(item => !enabled.length || enabled.indexOf(item.source) !== -1)
                        .map((item, index) => Object.assign({}, item, {
                            uiId: item.source + ":" + item.id + ":" + (item.index || index + 1),
                            sourceName: sourceLabel(item.source),
                            keyword: keyword,
                            index: item.index || index + 1
                        }));
                    window.AppNative.onSearchResult(requestId, JSON.stringify({
                        ok: true,
                        results: results,
                        errors: []
                    }));
                }, 620);
            },
            resolve(requestId, trackJson) {
                const track = parseJson(trackJson) || {};
                window.setTimeout(() => {
                    window.AppNative.onResolveResult(requestId, JSON.stringify({
                        ok: true,
                        track: Object.assign({}, track, {
                            url: DEMO_AUDIO,
                            ext: "mp3",
                            mime: "audio/mpeg",
                            qualityLabel: "MP3 320 · PREVIEW",
                            lrc: DEMO_LYRICS
                        })
                    }));
                }, 480);
            },
            download(trackJson) {
                const track = parseJson(trackJson) || {};
                window.setTimeout(() => {
                    window.AppNative.onDownloadQueued(track.uiId || "");
                    window.AppNative.onToast("演示模式：这里会开始下载，但预览页不会保存文件。");
                }, 260);
            },
            openExternal(url) {
                window.open(url, "_blank", "noopener");
            },
            toast(message) {
                window.AppNative.onToast(message);
            }
        };
        state.bridge = mock;
        window.Native = mock;
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
