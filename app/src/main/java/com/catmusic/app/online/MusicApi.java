package com.catmusic.app.online;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, Android-friendly port of musicdl's core search/resolve flow.
 *
 * The upstream musicdl project (https://github.com/CharlesPikachu/musicdl) is a
 * pure-Python downloader. Running its full dependency tree on Android is not
 * practical, so this class implements the same "search, then resolve a public
 * playback URL" contract for the most useful public sources: NetEase, QQ,
 * Kugou, Kuwo, JOOX, 5sing and Jamendo. Interfaces are a mix of the bridges
 * used by the author's MusicSquare companion demo, official web endpoints and
 * Jamendo's own CC catalogue.
 *
 * No DRM is removed or bypassed. Tracks without a publicly playable URL simply
 * resolve to an explanatory error.
 */
public final class MusicApi {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String JOOX_TOKEN = "f84ao9lMF_q7husBWRfgUw";
    private static final String JAMENDO_VERSION = "4rkl5f";
    private static final String QIANQIAN_APPID = "16073360";
    private static final String QIANQIAN_SECRET = "0b50b02fd0d73a9c4c8c3a781c30845f";
    private static final byte[] MIGU_CIPHER_KEY =
            "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".getBytes(StandardCharsets.UTF_8);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 14_000;
    private static final int MAX_PER_SOURCE = 10;
    private static final int MAX_RESULTS = 72;
    private static final int MAX_JSON_BYTES = 2_000_000;
    private static final long SEARCH_BUDGET_SECONDS = 24L;

    private static final Map<String, String> SOURCE_NAMES = new LinkedHashMap<>();
    private static final Set<String> VALID_SOURCES = new LinkedHashSet<>();

    static {
        SOURCE_NAMES.put("netease", "网易云");
        SOURCE_NAMES.put("qq", "QQ音乐");
        SOURCE_NAMES.put("migu", "咪咕");
        SOURCE_NAMES.put("kuwo", "酷我");
        SOURCE_NAMES.put("qianqian", "千千");
        SOURCE_NAMES.put("kugou", "酷狗");
        SOURCE_NAMES.put("joox", "JOOX");
        SOURCE_NAMES.put("fivesing", "5sing");
        SOURCE_NAMES.put("jamendo", "Jamendo");
        VALID_SOURCES.addAll(SOURCE_NAMES.keySet());
    }

    private MusicApi() {
    }

    /** Returns a JSON object: {ok, results:[...], errors:[...], elapsedMs}. */
    public static String search(String rawKeyword, String sourcesCsv) {
        long started = System.currentTimeMillis();
        JSONObject response = new JSONObject();
        try {
            String keyword = safeTrim(rawKeyword);
            if (keyword.isEmpty()) {
                return searchError("empty", "请输入歌曲、歌手或专辑名。");
            }
            if (keyword.length() > 80) {
                keyword = keyword.substring(0, 80);
            }
            List<String> sources = parseSources(sourcesCsv);
            if (sources.isEmpty()) {
                return searchError("sources", "请至少选择一个音乐来源。");
            }

            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(sources.size(), 9), r -> {
                        Thread t = new Thread(r, "musicdl-search");
                        t.setDaemon(true);
                        return t;
                    });
            Map<String, Future<JSONArray>> futures = new LinkedHashMap<>();
            final String query = keyword;
            for (String source : sources) {
                futures.put(source, pool.submit((Callable<JSONArray>) () -> searchSource(source, query)));
            }

            JSONArray results = new JSONArray();
            JSONArray errors = new JSONArray();
            List<JSONArray> buckets = new ArrayList<>();
            for (String source : sources) {
                JSONArray bucket = null;
                try {
                    bucket = futures.get(source).get(SEARCH_BUDGET_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    JSONObject error = new JSONObject();
                    error.put("source", source);
                    error.put("sourceName", SOURCE_NAMES.get(source));
                    error.put("message", friendlySearchError(source));
                    error.put("detail", shorten(e.toString(), 180));
                    errors.put(error);
                }
                buckets.add(bucket == null ? new JSONArray() : bucket);
            }
            pool.shutdownNow();

            // Interleave the sources so the list feels varied rather than
            // showing twelve NetEase tracks followed by twelve Kuwo tracks.
            int[] positions = new int[buckets.size()];
            boolean added;
            do {
                added = false;
                for (int i = 0; i < buckets.size() && results.length() < MAX_RESULTS; i++) {
                    JSONArray bucket = buckets.get(i);
                    if (positions[i] < bucket.length()) {
                        results.put(bucket.getJSONObject(positions[i]++));
                        added = true;
                    }
                }
            } while (added && results.length() < MAX_RESULTS);

            response.put("ok", true);
            response.put("results", results);
            response.put("errors", errors);
            response.put("keyword", keyword);
            response.put("elapsedMs", System.currentTimeMillis() - started);
            return response.toString();
        } catch (Exception e) {
            return searchError("internal", "搜索失败，请稍后再试。");
        }
    }

    /** Resolves one search result to a directly playable public URL. */
    public static String resolve(String trackJson) throws Exception {
        return resolve(trackJson, "auto");
    }

    /**
     * Resolves a result at a requested quality tier. The actual tier is echoed
     * back as {@code quality}; sources may fall back to a lower tier when a
     * lossless or high-quality public stream is unavailable.
     */
    public static String resolve(String trackJson, String requestedQuality) throws Exception {
        String quality = normalizeQuality(requestedQuality);
        JSONObject response = new JSONObject();
        try {
            JSONObject track = new JSONObject(trackJson);
            String source = safeTrim(track.optString("source"));
            if (!VALID_SOURCES.contains(source)) {
                response.put("ok", false);
                response.put("code", "source");
                response.put("error", "不支持的音乐来源。");
                return response.toString();
            }

            JSONObject resolved;
            switch (source) {
                case "netease":
                    resolved = resolveNetease(track, quality);
                    break;
                case "migu":
                    resolved = resolveMigu(track, quality);
                    break;
                case "qianqian":
                    resolved = resolveQianqian(track, quality);
                    break;
                case "kuwo":
                    resolved = resolveKuwo(track, quality);
                    break;
                case "joox":
                    resolved = resolveJoox(track, quality);
                    break;
                case "qq":
                    resolved = resolveQQ(track, quality);
                    break;
                case "kugou":
                    resolved = resolveKugou(track, quality);
                    break;
                case "fivesing":
                    resolved = resolveFiveSing(track, quality);
                    break;
                case "jamendo":
                    resolved = resolveJamendo(track, quality);
                    break;
                default:
                    response.put("ok", false);
                    response.put("code", "source");
                    response.put("error", "不支持的音乐来源。");
                    return response.toString();
            }
            response.put("ok", true);
            response.put("track", resolved);
            return response.toString();
        } catch (ApiException e) {
            response.put("ok", false);
            response.put("code", e.code);
            response.put("error", e.getMessage());
            return response.toString();
        } catch (Exception e) {
            response.put("ok", false);
            response.put("code", "resolve");
            response.put("error", "获取播放地址失败，请稍后重试。");
            response.put("detail", shorten(e.toString(), 180));
            return response.toString();
        }
    }

    // ---------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------

    private static JSONArray searchSource(String source, String keyword) throws Exception {
        switch (source) {
            case "netease":
                return searchNetease(keyword);
            case "migu":
                return searchMigu(keyword);
            case "qianqian":
                return searchQianqian(keyword);
            case "kuwo":
                return searchKuwo(keyword);
            case "joox":
                return searchJoox(keyword);
            case "qq":
                return searchQQ(keyword);
            case "kugou":
                return searchKugou(keyword);
            case "fivesing":
                return searchFiveSing(keyword);
            case "jamendo":
                return searchJamendo(keyword);
            default:
                return new JSONArray();
        }
    }

    private static JSONArray searchNetease(String keyword) throws Exception {
        Exception firstError = null;
        boolean directOk = false;
        boolean metingOk = false;

        try {
            JSONArray direct = searchNeteaseDirect(keyword);
            directOk = true;
            if (direct.length() > 0) return filterResolved(enrichNeteaseSizes(direct));
        } catch (Exception e) {
            firstError = e;
        }

        try {
            JSONArray fallback = searchNeteaseMeting(keyword);
            metingOk = true;
            if (fallback.length() > 0) return filterResolved(enrichNeteaseSizes(fallback));
        } catch (Exception e) {
            if (firstError == null) firstError = e;
        }

        if (directOk || metingOk) return new JSONArray();
        if (firstError instanceof ApiException) throw firstError;
        throw new ApiException("netease-search", "网易云搜索暂时不可用");
    }

    /** Official NetEase web search. Used first because it is independent of the Meting bridge. */
    private static JSONArray searchNeteaseDirect(String keyword) throws Exception {
        String url = "https://music.163.com/api/search/get/web?csrf_token=&s=" + enc(keyword)
                + "&type=1&offset=0&total=true&limit=" + MAX_PER_SOURCE;
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("Cookie", "os=pc; appver=2.10.13; NMTID=00O");
        HttpResult result = httpGet(url, headers, MAX_JSON_BYTES, "https://music.163.com/");
        if (!result.isSuccess()) {
            throw new ApiException("netease-search", "网易云搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONObject)) {
            throw new ApiException("netease-search", "网易云返回了意外的数据");
        }
        Object resultValue = ((JSONObject) parsed).opt("result");
        JSONArray raw = resultValue instanceof JSONObject
                ? ((JSONObject) resultValue).optJSONArray("songs") : null;
        if (raw == null) raw = new JSONArray();

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = clean(item.optString("name"));
            long idValue = item.optLong("id", 0L);
            if (title.isEmpty() || idValue <= 0L) continue;
            String id = String.valueOf(idValue);

            StringBuilder artists = new StringBuilder();
            JSONArray artistArray = item.optJSONArray("artists");
            if (artistArray != null) {
                for (int j = 0; j < artistArray.length(); j++) {
                    JSONObject artist = artistArray.optJSONObject(j);
                    if (artist == null) continue;
                    String name = clean(artist.optString("name"));
                    if (name.isEmpty()) continue;
                    if (artists.length() > 0) artists.append("/");
                    artists.append(name);
                }
            }
            String artist = artists.toString();
            JSONObject album = item.optJSONObject("album");
            String albumName = album == null ? "" : clean(album.optString("name"));
            long picId = album == null ? 0L : album.optLong("picId", 0L);
            String cover = picId > 0
                    ? "https://api.qijieya.cn/meting/?server=netease&type=pic&id=" + picId : "";
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack("netease", id, i + 1, keyword, title, artist,
                    albumName, cover);
            track.put("audioHint", "https://api.qijieya.cn/meting/?server=netease&type=url&id=" + id);
            track.put("lrcUrl", "https://api.qijieya.cn/meting/?server=netease&type=lrc&id=" + id);
            int durationMs = item.optInt("duration", 0);
            if (durationMs > 0) track.put("duration", durationMs / 1000);
            int fee = item.optInt("fee", 0);
            if (fee == 1) track.put("payLabel", "VIP");
            else if (fee == 4) track.put("payLabel", "购买专辑");
            tracks.put(track);
        }
        return tracks;
    }

    /** Meting bridge fallback, also used for artwork/lyrics endpoints. */
    private static JSONArray searchNeteaseMeting(String keyword) throws Exception {
        String url = "https://api.qijieya.cn/meting/?type=search&id=" + enc(keyword)
                + "&limit=" + MAX_PER_SOURCE + "&server=netease";
        HttpResult result = httpGet(url, null, MAX_JSON_BYTES, null);
        if (!result.isSuccess()) {
            throw new ApiException("netease-search", "网易云备用搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONArray)) {
            throw new ApiException("netease-search", "网易云备用接口返回了意外的数据");
        }
        JSONArray raw = (JSONArray) parsed;
        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = clean(item.optString("name"));
            String artist = clean(item.optString("artist"));
            String apiUrl = item.optString("url");
            String id = queryParam(apiUrl, "id");
            String cover = normalizeCover(item.optString("pic"));
            String lrcUrl = item.optString("lrc");
            if (title.isEmpty() || id.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack("netease", id, i + 1, keyword, title, artist, "", cover);
            track.put("audioHint", apiUrl);
            track.put("lrcUrl", lrcUrl);
            tracks.put(track);
        }
        return tracks;
    }

    /**
     * musicdl resolves every search row at its best quality and displays that
     * file's real size. The Meting bridge exposes the same lossless stream, so
     * probe the first few NetEase rows the same way; failures keep the normal
     * duration-based estimate.
     */
    private static JSONArray enrichNeteaseSizes(JSONArray tracks) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        long deadline = System.currentTimeMillis() + 9_000L;
        int limit = Math.min(6, tracks.length());
        for (int i = 0; i < limit; i++) {
            if (System.currentTimeMillis() > deadline) break;
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            String id = clean(track.optString("id"));
            if (id.isEmpty()) continue;
            int duration = track.optInt("duration", 0);
            boolean found = false;
            for (int bitrate : new int[]{999000, 320, 128}) {
                AudioProbe probe = probeAudio(neteaseUrl(id, bitrate), headers);
                if (!probe.ok || probe.totalBytes <= 0L) continue;
                try {
                    String ext = extension(probe.contentType, probe.finalUrl, "mp3");
                    track.put("size", probe.totalBytes);
                    track.put("sizeExact", true);
                    track.put("_resolved", true);
                    String tier = detectQuality(probe, duration, ext,
                            bitrate == 999000 ? "lossless" : bitrate == 320 ? "high" : "standard");
                    track.put("bestQuality", tier);
                    track.put("bestExt", "lossless".equals(tier) ? "flac" : ext);
                } catch (Exception ignored) {
                }
                found = true;
                break;
            }
            if (!found) {
                // Some tracks are blocked on the Meting bridge but still play
                // through NetEase's public outer URL.
                AudioProbe probe = probeAudio(
                        "https://music.163.com/song/media/outer/url?id=" + enc(id) + ".mp3",
                        headers);
                if (probe.ok && probe.totalBytes > 0L) {
                    try {
                        track.put("size", probe.totalBytes);
                        track.put("sizeExact", true);
                        track.put("_resolved", true);
                        track.put("bestQuality", "standard");
                        track.put("bestExt", "mp3");
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return tracks;
    }

    private static JSONArray filterResolved(JSONArray tracks) {
        JSONArray playable = new JSONArray();
        if (tracks == null) return playable;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null || !track.optBoolean("_resolved")) continue;
            track.remove("_resolved");
            playable.put(track);
        }
        return playable;
    }

    // ---------------------------------------------------------------------
    // Migu (ported from musicdl's MiguMusicClient)
    // ---------------------------------------------------------------------

    private static Map<String, String> miguHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", "https://h5.nf.migu.cn");
        headers.put("Referer", "https://h5.nf.migu.cn/");
        headers.put("ua", "Android_migu");
        headers.put("version", "6.8.8");
        headers.put("channel", "014021I");
        headers.put("subchannel", "014021I");
        return headers;
    }

    /**
     * Migu answers JSON with an optional XOR stream: a 0xAB 0xCD 0x01 magic,
     * one seed byte and bytes offset by (seed - repeating key). This mirrors
     * MiguMusicClient._decryptresp upstream.
     */
    private static String miguDecode(byte[] raw) throws Exception {
        if (raw == null || raw.length == 0) return "";
        if (raw.length > 4 && (raw[0] & 0xFF) == 0xAB
                && (raw[1] & 0xFF) == 0xCD && (raw[2] & 0xFF) == 0x01) {
            int seed = raw[3] & 0xFF;
            byte[] plain = new byte[raw.length - 4];
            for (int i = 0; i < plain.length; i++) {
                plain[i] = (byte) ((raw[i + 4] + seed
                        - MIGU_CIPHER_KEY[i % MIGU_CIPHER_KEY.length]) & 0xFF);
            }
            return new String(plain, StandardCharsets.UTF_8);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static long miguFormatBytes(JSONObject format) {
        long size = parsePositiveLong(format.optString("size"));
        if (size <= 0) size = parsePositiveLong(format.optString("androidSize"));
        if (size <= 0) size = parsePositiveLong(format.optString("iosSize"));
        if (size <= 0) size = parsePositiveLong(format.optString("isize"));
        if (size <= 0) size = parsePositiveLong(format.optString("asize"));
        return size;
    }

    private static String miguFormatFileType(JSONObject format) {
        String type = clean(format.optString("androidFileType"));
        if (type.isEmpty()) type = clean(format.optString("fileType"));
        if (type.isEmpty()) type = clean(format.optString("iosFileType"));
        String tone = clean(format.optString("formatType")).toUpperCase(Locale.ROOT);
        if (type.isEmpty() && ("SQ".equals(tone) || "ZQ".equals(tone)
                || "ZQ24".equals(tone) || "ZQ32".equals(tone))) {
            type = "flac";
        }
        if (type.isEmpty()) type = "mp3";
        return type.toLowerCase(Locale.ROOT);
    }

    private static JSONArray searchMigu(String keyword) throws Exception {
        JSONObject searchSwitch = new JSONObject();
        searchSwitch.put("song", 1);
        searchSwitch.put("album", 0);
        searchSwitch.put("singer", 0);
        searchSwitch.put("tagSong", 1);
        searchSwitch.put("mvSong", 0);
        searchSwitch.put("bestShow", 1);
        String url = "https://c.musicapp.migu.cn/v1.0/content/search_all.do?text="
                + enc(keyword) + "&pageNo=1&pageSize=20&isCopyright=1&sort=1&searchSwitch="
                + enc(searchSwitch.toString());
        HttpResult result = httpGet(url, miguHeaders(), MAX_JSON_BYTES,
                "https://h5.nf.migu.cn/");
        if (!result.isSuccess()) {
            throw new ApiException("migu-search", "咪咕搜索接口返回 HTTP " + result.status);
        }
        JSONObject root = parseObject(miguDecode(result.body));
        JSONObject songData = root == null ? null : root.optJSONObject("songResultData");
        JSONArray raw = songData == null ? null : songData.optJSONArray("result");
        if (raw == null) {
            throw new ApiException("migu-search", "咪咕返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = clean(item.optString("name"));
            String contentId = clean(item.optString("contentId"));
            String copyrightId = clean(item.optString("copyrightId"));
            if (title.isEmpty() || contentId.isEmpty() || copyrightId.isEmpty()) continue;

            StringBuilder artists = new StringBuilder();
            JSONArray singerArray = item.optJSONArray("singers");
            if (singerArray != null) {
                for (int j = 0; j < singerArray.length(); j++) {
                    JSONObject singer = singerArray.optJSONObject(j);
                    String name = singer == null ? "" : clean(singer.optString("name"));
                    if (name.isEmpty()) continue;
                    if (artists.length() > 0) artists.append("/");
                    artists.append(name);
                }
            }
            String artist = artists.toString();
            String album = "";
            JSONArray albumArray = item.optJSONArray("albums");
            if (albumArray != null && albumArray.length() > 0) {
                JSONObject firstAlbum = albumArray.optJSONObject(0);
                if (firstAlbum != null) album = clean(firstAlbum.optString("name"));
            }
            String cover = "";
            JSONArray imgItems = item.optJSONArray("imgItems");
            if (imgItems != null) {
                for (int j = imgItems.length() - 1; j >= 0; j--) {
                    JSONObject img = imgItems.optJSONObject(j);
                    String candidate = img == null ? "" : clean(img.optString("img"));
                    if (!candidate.isEmpty()) {
                        cover = normalizeCover(candidate);
                        break;
                    }
                }
            }
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONArray formats = new JSONArray();
            Set<String> formatSeen = new HashSet<>();
            boolean vip = false;
            for (String group : new String[]{"rateFormats", "newRateFormats", "audioFormats"}) {
                JSONArray sourceFormats = item.optJSONArray(group);
                if (sourceFormats == null) continue;
                for (int j = 0; j < sourceFormats.length(); j++) {
                    JSONObject format = sourceFormats.optJSONObject(j);
                    if (format == null) continue;
                    String tone = clean(format.optString("formatType")).toUpperCase(Locale.ROOT);
                    String resource = clean(format.optString("resourceType"));
                    if (tone.isEmpty() || "Z3D".equals(tone) || resource.isEmpty()) continue;
                    long bytes = miguFormatBytes(format);
                    String ext = miguFormatFileType(format);
                    String key = tone + "|" + resource + "|" + bytes + "|" + ext;
                    if (!formatSeen.add(key)) continue;
                    JSONObject candidate = new JSONObject();
                    candidate.put("formatType", tone);
                    candidate.put("resourceType", resource);
                    candidate.put("size", bytes);
                    candidate.put("fileType", ext);
                    int bit = format.optInt("androidBit", format.optInt("iosBit", 0));
                    if (bit > 0) candidate.put("bit", bit);
                    formats.put(candidate);
                    if (hasVipTag(format)) vip = true;
                }
            }

            JSONObject track = baseTrack("migu", contentId, i + 1, keyword, title, artist,
                    album, cover);
            track.put("copyrightId", copyrightId);
            track.put("miguFormats", formats);
            track.put("lyricUrl", clean(item.optString("lyricUrl")));
            if (vip) track.put("payLabel", "VIP");

            JSONObject qualitySizes = miguQualitySizes(formats);
            if (qualitySizes.length() > 0) {
                track.put("qualitySizes", qualitySizes);
                long lossless = qualitySizes.optLong("lossless", 0L);
                long high = qualitySizes.optLong("high", 0L);
                long standard = qualitySizes.optLong("standard", 0L);
                long shown = lossless > 0 ? lossless : (high > 0 ? high : standard);
                if (shown > 0) {
                    track.put("size", shown);
                    track.put("sizeExact", false);
                }
            }
            tracks.put(track);
        }

        // Probe the real accessible stream for the first few results, so the
        // row carries the same size a download will actually have. The rest
        // keep the API-declared size and are corrected when opened.
        long deadline = System.currentTimeMillis() + 9_000L;
        for (int i = 0; i < tracks.length(); i++) {
            if (System.currentTimeMillis() > deadline) break;
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            try {
                JSONObject resolved = resolveMiguInternal(track, "lossless", false);
                long actual = resolved.optLong("size", 0L);
                if (actual > 0L) {
                    track.put("size", actual);
                    track.put("sizeExact", true);
                }
                track.put("_resolved", true);
                copyResolvedHints(track, resolved);
            } catch (Exception ignored) {
                // Unresolvable rows are filtered out, as musicdl does.
            }
        }
        return filterResolved(tracks);
    }

    private static JSONObject miguQualitySizes(JSONArray formats) throws Exception {
        long lossless = 0L;
        long high = 0L;
        long standard = 0L;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || format.optLong("size", 0L) <= 0L) continue;
            String tone = format.optString("formatType").toUpperCase(Locale.ROOT);
            String ext = format.optString("fileType").toLowerCase(Locale.ROOT);
            long size = format.optLong("size", 0L);
            if ("flac".equals(ext) || "SQ".equals(tone) || "ZQ".equals(tone)
                    || "ZQ24".equals(tone) || "ZQ32".equals(tone)) {
                lossless = Math.max(lossless, size);
            } else if ("HQ".equals(tone)) {
                high = Math.max(high, size);
            } else if ("PQ".equals(tone) || "LQ".equals(tone)) {
                standard = Math.max(standard, size);
            }
        }
        JSONObject sizes = new JSONObject();
        if (standard > 0) sizes.put("standard", standard);
        if (high > 0) sizes.put("high", high);
        if (lossless > 0) sizes.put("lossless", lossless);
        return sizes;
    }

    private static boolean hasVipTag(JSONObject node) {
        for (String key : new String[]{"showTag", "showTagNew"}) {
            JSONArray tags = node.optJSONArray(key);
            if (tags == null) continue;
            for (int i = 0; i < tags.length(); i++) {
                String tag = clean(tags.optString(i)).toLowerCase(Locale.ROOT);
                if (tag.contains("vip") || tag.contains("svip")) return true;
            }
        }
        return false;
    }

    private static void copyResolvedHints(JSONObject track, JSONObject resolved) {
        try {
            int duration = resolved.optInt("duration", 0);
            if (duration > 0) track.put("duration", duration);
            String quality = resolved.optString("quality");
            if (!quality.isEmpty()) track.put("bestQuality", quality);
            String ext = resolved.optString("ext");
            if (!ext.isEmpty()) track.put("bestExt", ext);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Qianqian / Baidu (ported from musicdl's QianqianMusicClient)
    // ---------------------------------------------------------------------

    private static Map<String, String> qianqianHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Referer", "https://music.91q.com/player");
        headers.put("from", "web");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-dest", "empty");
        return headers;
    }

    private static String qianqianSignedQuery(Map<String, String> params) {
        Map<String, String> sorted = new TreeMap<>(params);
        sorted.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000L));
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (raw.length() > 0) raw.append('&');
            raw.append(entry.getKey()).append('=').append(entry.getValue());
        }
        String sign = md5Hex(raw.toString() + QIANQIAN_SECRET);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        if (query.length() > 0) query.append('&');
        query.append("sign=").append(enc(sign));
        return query.toString();
    }

    private static JSONArray searchQianqian(String keyword) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("word", keyword);
        params.put("type", "1");
        params.put("pageNo", "1");
        params.put("pageSize", String.valueOf(MAX_PER_SOURCE));
        params.put("appid", QIANQIAN_APPID);
        String url = "https://music.91q.com/v1/search?" + qianqianSignedQuery(params);
        HttpResult result = httpGet(url, qianqianHeaders(), MAX_JSON_BYTES,
                "https://music.91q.com/");
        if (!result.isSuccess()) {
            throw new ApiException("qianqian-search", "千千搜索接口返回 HTTP " + result.status);
        }
        JSONObject root = parseObject(result.text());
        JSONObject data = root == null ? null : root.optJSONObject("data");
        JSONArray raw = data == null ? null : data.optJSONArray("typeTrack");
        if (raw == null) {
            throw new ApiException("qianqian-search", "千千返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = clean(item.optString("title"));
            String id = clean(item.optString("TSID"));
            if (title.isEmpty() || id.isEmpty()) continue;
            String artist = joinJsonNames(item.optJSONArray("artist"), "name", "/");
            String album = clean(item.optString("albumTitle"));
            String cover = normalizeCover(item.optString("pic"));
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack("qianqian", id, i + 1, keyword, title, artist,
                    album, cover);
            track.put("lrcUrl", clean(item.optString("lyric")));
            tracks.put(track);
        }

        long deadline = System.currentTimeMillis() + 10_000L;
        for (int i = 0; i < tracks.length(); i++) {
            if (System.currentTimeMillis() > deadline) break;
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            try {
                JSONObject resolved = resolveQianqianInternal(track, "lossless", false);
                long actual = resolved.optLong("size", 0L);
                if (actual > 0L) {
                    track.put("size", actual);
                    track.put("sizeExact", true);
                }
                track.put("_resolved", true);
                copyResolvedHints(track, resolved);
            } catch (Exception ignored) {
                // musicdl only lists Qianqian results with a valid stream.
            }
        }
        return filterResolved(tracks);
    }

    private static String joinJsonNames(JSONArray array, String field, String delimiter) {
        if (array == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String name = clean(item.optString(field));
            if (name.isEmpty()) continue;
            if (builder.length() > 0) builder.append(delimiter);
            builder.append(name);
        }
        return builder.toString();
    }

    private static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long parsePositiveLong(String value) {
        try {
            String cleaned = clean(value).replace("MB", "").replace("Mb", "")
                    .replace("mb", "").trim();
            double parsed = Double.parseDouble(cleaned);
            return parsed > 0 ? (long) parsed : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static JSONArray searchQQ(String keyword) throws Exception {
        try {
            JSONArray official = searchQQOfficial(keyword);
            if (official.length() > 0) return official;
        } catch (Exception ignored) {
            // Fall through to the legacy bridge.
        }
        return searchQQLegacy(keyword);
    }

    /**
     * QQ Music's mobile search is a POST to musicu.fcg with a device-shaped
     * comm block. The response's item_song list carries both the song mid and
     * the exact size of every encoded version (128K, 320K, FLAC, master), which
     * is what musicdl prints in its Filesize column.
     */
    private static JSONArray searchQQOfficial(String keyword) throws Exception {
        long t = System.currentTimeMillis();
        long searchId = (long) (1 + (t % 20)) * 18014398509481984L
                + (long) (t % 4194304) * 4294967296L
                + (t % (24L * 60L * 60L * 1000L));
        JSONObject comm = new JSONObject();
        comm.put("cv", 13020508);
        comm.put("v", 13020508);
        comm.put("QIMEI36", "6c9d3cd110abca9b16311cee10001e717614");
        comm.put("ct", "19");
        comm.put("tmeAppID", "qqmusic");
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("uid", "3931641530");

        JSONObject param = new JSONObject();
        param.put("searchid", String.valueOf(searchId));
        param.put("query", keyword);
        param.put("search_type", 0);
        param.put("num_per_page", MAX_PER_SOURCE);
        param.put("page_num", 1);
        param.put("highlight", 1);
        param.put("grp", 1);

        JSONObject data = new JSONObject();
        data.put("module", "music.search.SearchCgiService");
        data.put("method", "DoSearchForQQMusicMobile");
        data.put("param", param);

        JSONObject payload = new JSONObject();
        payload.put("comm", comm);
        payload.put("music.search.SearchCgiService.DoSearchForQQMusicMobile", data);

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://y.qq.com/");
        headers.put("Origin", "https://y.qq.com/");
        HttpResult result = httpPostJson("https://u.y.qq.com/cgi-bin/musicu.fcg",
                payload.toString(), headers, MAX_JSON_BYTES);
        if (!result.isSuccess()) {
            throw new ApiException("qq-search", "QQ 音乐搜索接口返回 HTTP " + result.status);
        }
        JSONObject root = parseObject(result.text());
        JSONObject node = root == null ? null
                : root.optJSONObject("music.search.SearchCgiService.DoSearchForQQMusicMobile");
        JSONObject dataNode = node == null ? null : node.optJSONObject("data");
        JSONObject body = dataNode == null ? null : dataNode.optJSONObject("body");
        JSONArray raw = body == null ? null : body.optJSONArray("item_song");
        if (raw == null) {
            throw new ApiException("qq-search", "QQ 音乐返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = stripHtml(firstNonEmpty(item.optString("name"),
                    item.optString("title")));
            String mid = clean(item.optString("mid"));
            if (title.isEmpty() || mid.isEmpty()) continue;
            String artist = joinJsonNames(item.optJSONArray("singer"), "name", "/");
            JSONObject albumNode = item.optJSONObject("album");
            String album = albumNode == null ? "" : clean(albumNode.optString("name"));
            String albumMid = albumNode == null ? "" : clean(albumNode.optString("mid"));
            String cover = albumMid.isEmpty() ? ""
                    : "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albumMid + ".jpg";
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack("qq", mid, i + 1, keyword, title, artist,
                    album, cover);
            int interval = item.optInt("interval", 0);
            if (interval > 0) track.put("duration", interval);

            JSONObject file = item.optJSONObject("file");
            if (file != null) {
                long standard = file.optLong("size_128mp3", 0L);
                long high = Math.max(file.optLong("size_320mp3", 0L),
                        Math.max(file.optLong("size_192ogg", 0L),
                                file.optLong("size_192aac", 0L)));
                long lossless = Math.max(file.optLong("size_flac", 0L),
                        Math.max(file.optLong("size_hires", 0L),
                                Math.max(file.optLong("size_dolby", 0L),
                                        file.optLong("size_ape", 0L))));
                JSONArray sizeNew = file.optJSONArray("size_new");
                if (sizeNew != null) {
                    // Index 0/1/2 are the master, spatial and premium FLAC
                    // encodings that vkeys exposes as quality 14/13/16.
                    for (int j = 0; j < Math.min(3, sizeNew.length()); j++) {
                        lossless = Math.max(lossless, sizeNew.optLong(j, 0L));
                    }
                }
                JSONObject qualitySizes = new JSONObject();
                if (standard > 0L) qualitySizes.put("standard", standard);
                if (high > 0L) qualitySizes.put("high", high);
                if (lossless > 0L) qualitySizes.put("lossless", lossless);
                if (qualitySizes.length() > 0) {
                    track.put("qualitySizes", qualitySizes);
                    long shown = lossless > 0L ? lossless : (high > 0L ? high : standard);
                    if (shown > 0L) {
                        track.put("size", shown);
                        track.put("sizeExact", true);
                    }
                }
                String mediaMid = clean(file.optString("media_mid"));
                if (!mediaMid.isEmpty()) track.put("mediaMid", mediaMid);
                if (lossless > 0L) {
                    track.put("bestQuality", "lossless");
                    track.put("bestExt", "flac");
                } else if (high > 0L) {
                    track.put("bestQuality", "high");
                    track.put("bestExt", "mp3");
                } else {
                    track.put("bestQuality", "standard");
                    track.put("bestExt", "mp3");
                }
            }
            JSONObject pay = item.optJSONObject("pay");
            if (pay != null && (pay.optInt("pay_play", 0) != 0 || pay.optInt("pay_down", 0) != 0)) {
                track.put("payLabel", "VIP");
            }
            // vkeys can often resolve paid tracks publicly; do not hide the row
            // behind the old "paid QQ" rule.
            track.put("paid", false);
            tracks.put(track);
        }
        return tracks;
    }

    private static JSONArray searchQQLegacy(String keyword) throws Exception {
        String url = "https://tang.api.s01s.cn/music_open_api.php?msg=" + enc(keyword) + "&type=json";
        HttpResult result = httpGet(url, null, MAX_JSON_BYTES, null);
        if (!result.isSuccess()) {
            throw new ApiException("qq-search", "QQ 音乐搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        JSONArray raw;
        if (parsed instanceof JSONArray) {
            raw = (JSONArray) parsed;
        } else if (parsed instanceof JSONObject) {
            raw = ((JSONObject) parsed).optJSONArray("data");
        } else {
            raw = null;
        }
        if (raw == null) {
            throw new ApiException("qq-search", "QQ 音乐返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = clean(item.optString("song_title"));
            String artist = clean(item.optString("singer_name"));
            String id = clean(item.optString("song_mid"));
            String pay = clean(item.optString("pay"));
            if (title.isEmpty() || id.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack("qq", id, i + 1, keyword, title, artist, "", "");
            track.put("paid", "付费".equals(pay) || pay.toLowerCase(Locale.ROOT).contains("vip"));
            track.put("payLabel", pay);
            tracks.put(track);
        }
        return tracks;
    }

    private static JSONArray searchKuwo(String keyword) throws Exception {
        try {
            JSONArray official = searchKuwoOfficial(keyword);
            if (official.length() > 0) return official;
        } catch (Exception ignored) {
            // The public bridge below remains a fallback.
        }
        return searchKuwoLegacy(keyword);
    }

    private static JSONArray searchKuwoOfficial(String keyword) throws Exception {
        String url = "http://www.kuwo.cn/search/searchMusicBykeyWord?vipver=1&client=kt"
                + "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json"
                + "&mobi=1&issubtitle=1&show_copyright_off=1&pn=0&rn=" + MAX_PER_SOURCE
                + "&all=" + enc(keyword);
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36");
        HttpResult result = httpGet(url, headers, MAX_JSON_BYTES, "https://www.kuwo.cn/");
        if (!result.isSuccess()) {
            throw new ApiException("kuwo-search", "酷我搜索接口返回 HTTP " + result.status);
        }
        JSONObject root = parseObject(result.text());
        JSONArray raw = root == null ? null : root.optJSONArray("abslist");
        if (raw == null) {
            throw new ApiException("kuwo-search", "酷我返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = firstNonEmpty(item.optString("SONGNAME"), item.optString("NAME"));
            String id = firstNonEmpty(item.optString("MUSICRID"),
                    item.optString("DC_TARGETID"));
            if (id.startsWith("MUSIC_")) id = id.substring("MUSIC_".length());
            String artist = firstNonEmpty(item.optString("ARTIST"), item.optString("AARTIST"));
            String album = item.optString("ALBUM");
            if (title.isEmpty() || id.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            String cover = normalizeCover(firstNonEmpty(item.optString("hts_MVPIC")));
            if (cover.isEmpty()) {
                String shortPic = item.optString("MVPIC");
                if (!shortPic.isEmpty()) {
                    cover = normalizeCover("https://img3.kuwo.cn/wmvpic/" + shortPic);
                }
            }
            JSONObject track = baseTrack("kuwo", id, i + 1, keyword, title, artist,
                    album, cover);
            track.put("duration", parsePositiveInt(item.optString("DURATION")));
            JSONObject qualitySizes = kuwoQualitySizesFromMinfo(
                    item.optString("MINFO"), item.optString("N_MINFO"));
            if (qualitySizes.length() > 0) {
                track.put("qualitySizes", qualitySizes);
                long lossless = qualitySizes.optLong("lossless", 0L);
                long high = qualitySizes.optLong("high", 0L);
                long standard = qualitySizes.optLong("standard", 0L);
                long shown = lossless > 0 ? lossless : (high > 0 ? high : standard);
                if (shown > 0) {
                    track.put("size", shown);
                    track.put("sizeExact", true);
                }
                if (lossless > 0) {
                    track.put("bestQuality", "lossless");
                    track.put("bestExt", "flac");
                } else if (high > 0) {
                    track.put("bestQuality", "high");
                    track.put("bestExt", "mp3");
                } else {
                    track.put("bestQuality", "standard");
                    track.put("bestExt", "mp3");
                }
            }
            tracks.put(track);
        }
        return tracks;
    }

    private static JSONObject kuwoQualitySizesFromMinfo(String... minfoValues) throws Exception {
        long losslessSize = 0L;
        long highSize = 0L;
        long standardSize = 0L;
        int losslessBitrate = 0;
        int highBitrate = 0;
        int standardBitrate = 0;
        Set<String> seen = new HashSet<>();
        for (String minfo : minfoValues) {
            if (minfo == null || minfo.trim().isEmpty()) continue;
            for (String chunk : minfo.split(";")) {
                Map<String, String> fields = new HashMap<>();
                for (String token : chunk.split(",")) {
                    int colon = token.indexOf(':');
                    if (colon <= 0) continue;
                    fields.put(token.substring(0, colon).trim(),
                            token.substring(colon + 1).trim());
                }
                String format = clean(fields.get("format")).toLowerCase(Locale.ROOT);
                if (!("flac".equals(format) || "mp3".equals(format)
                        || "ogg".equals(format) || "aac".equals(format))) {
                    continue;
                }
                int bitrate = parsePositiveInt(fields.get("bitrate"));
                long bytes = parseKuwoSize(fields.get("size"));
                if (bytes <= 0L) continue;
                String key = format + "|" + bitrate;
                if (!seen.add(key)) continue;
                if ("flac".equals(format)) {
                    if (bitrate > losslessBitrate
                            || (bitrate == losslessBitrate && bytes > losslessSize)) {
                        losslessBitrate = bitrate;
                        losslessSize = bytes;
                    }
                } else if ("mp3".equals(format)) {
                    if (bitrate >= 250) {
                        if (bitrate > highBitrate
                                || (bitrate == highBitrate && bytes > highSize)) {
                            highBitrate = bitrate;
                            highSize = bytes;
                        }
                    } else if (bitrate > standardBitrate
                            || (bitrate == standardBitrate && bytes > standardSize)) {
                        standardBitrate = bitrate;
                        standardSize = bytes;
                    }
                } else {
                    if (bitrate >= 250 && highSize == 0L) {
                        highBitrate = bitrate;
                        highSize = bytes;
                    } else if (bitrate < 250 && standardSize == 0L) {
                        standardBitrate = bitrate;
                        standardSize = bytes;
                    }
                }
            }
        }
        JSONObject sizes = new JSONObject();
        if (standardSize > 0L) sizes.put("standard", standardSize);
        if (highSize > 0L) sizes.put("high", highSize);
        if (losslessSize > 0L) sizes.put("lossless", losslessSize);
        return sizes;
    }

    private static long parseKuwoSize(String value) {
        String cleaned = clean(value).toLowerCase(Locale.ROOT);
        try {
            if (cleaned.endsWith("mb")) {
                return (long) (Double.parseDouble(cleaned.substring(0, cleaned.length() - 2))
                        * 1024.0 * 1024.0);
            }
            return parsePositiveLong(cleaned);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static JSONArray searchKuwoLegacy(String keyword) throws Exception {
        String url = "https://oiapi.net/api/Kuwo?msg=" + enc(keyword)
                + "&page=1&limit=" + MAX_PER_SOURCE;
        HttpResult result = httpGet(url, null, MAX_JSON_BYTES, null);
        if (!result.isSuccess()) {
            throw new ApiException("kuwo-search", "酷我搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        JSONArray raw = parsed instanceof JSONObject
                ? ((JSONObject) parsed).optJSONArray("data") : null;
        if (raw == null) {
            throw new ApiException("kuwo-search", "酷我返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = clean(item.optString("song"));
            String artist = clean(item.optString("singer"));
            String id = clean(item.optString("rid"));
            String album = clean(item.optString("album"));
            if (title.isEmpty() || id.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack("kuwo", id, i + 1, keyword, title, artist,
                    album, normalizeCover(item.optString("picture")));
            track.put("duration", parsePositiveInt(item.optString("time")));
            long displayedSize = kuwoStandardSize(item);
            if (displayedSize > 0L) {
                track.put("size", displayedSize);
                track.put("sizeExact", true);
            }
            JSONObject qualitySizes = kuwoQualitySizes(item);
            if (qualitySizes.length() > 0) track.put("qualitySizes", qualitySizes);
            tracks.put(track);
        }
        return tracks;
    }

    private static JSONArray searchJoox(String keyword) throws Exception {
        String url = "https://apicx.asia/api/joox_music?msg=" + enc(keyword)
                + "&token=" + enc(JOOX_TOKEN) + "&br=4";
        HttpResult result = httpGet(url, null, MAX_JSON_BYTES, null);
        if (!result.isSuccess()) {
            throw new ApiException("joox-search", "JOOX 搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONObject)) {
            throw new ApiException("joox-search", "JOOX 返回了意外的数据");
        }
        JSONObject root = (JSONObject) parsed;
        JSONObject data = root.optJSONObject("data");
        JSONArray raw = data == null ? null : data.optJSONArray("songs");
        if (raw == null) {
            throw new ApiException("joox-search", "JOOX 返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = clean(item.optString("歌曲名称"));
            String artist = clean(item.optString("歌手"));
            String mid = clean(item.optString("songmid"));
            String songId = clean(item.optString("歌曲ID"));
            String id = mid.isEmpty() ? songId : mid;
            if (title.isEmpty() || id.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            int index = item.optInt("序号", i + 1);
            JSONObject track = baseTrack("joox", id, index, keyword, title, artist,
                    clean(item.optString("专辑")), "");
            track.put("duration", parseDuration(item.optString("时长")));
            tracks.put(track);
        }
        return tracks;
    }

    /**
     * Kugou's own web search gives richer metadata than the Meting bridge. The
     * audio URL itself still comes from the public Meting bridge, because the
     * official play endpoint requires a signed cookie handshake.
     */
    private static JSONArray searchKugou(String keyword) throws Exception {
        Exception firstError = null;
        boolean directOk = false;
        boolean fallbackOk = false;

        try {
            String url = "https://songsearch.kugou.com/song_search_v2?format=json&keyword="
                    + enc(keyword) + "&platform=WebFilter&page=1&pagesize=" + MAX_PER_SOURCE;
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.kugou.com/");
            HttpResult result = httpGet(url, headers, MAX_JSON_BYTES, "https://www.kugou.com/");
            if (!result.isSuccess()) {
                throw new ApiException("kugou-search", "酷狗搜索接口返回 HTTP " + result.status);
            }
            Object parsed = parseJson(result.text());
            if (!(parsed instanceof JSONObject)) {
                throw new ApiException("kugou-search", "酷狗返回了意外的数据");
            }
            JSONObject data = ((JSONObject) parsed).optJSONObject("data");
            JSONArray raw = data == null ? null : data.optJSONArray("lists");
            if (raw == null) throw new ApiException("kugou-search", "酷狗返回了意外的数据");
            directOk = true;

            JSONArray tracks = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
                JSONObject item = raw.optJSONObject(i);
                if (item == null) continue;
                String title = stripHtml(item.optString("SongName"));
                String artist = stripHtml(item.optString("SingerName"));
                String standardHash = clean(item.optString("FileHash"));
                String hqHash = clean(item.optString("HQFileHash"));
                String sqHash = clean(item.optString("SQFileHash"));
                String id = firstNonEmpty(standardHash, hqHash, sqHash);
                if (title.isEmpty() || id.isEmpty()) continue;
                if (!seen.add(normalizeKey(title, artist))) continue;

                String album = stripHtml(item.optString("AlbumName"));
                String cover = normalizeCover(item.optString("Image").replace("{size}", "300"));
                JSONObject track = baseTrack("kugou", id, i + 1, keyword,
                        title, artist, album, cover);
                track.put("audioHint", kugouUrl(id));
                track.put("lrcUrl", kugouLyricUrl(id));
                track.put("standardHash", standardHash);
                track.put("hqHash", hqHash);
                track.put("sqHash", sqHash);
                int duration = item.optInt("Duration", 0);
                if (duration > 0) track.put("duration", duration);
                long fileSize = item.optLong("FileSize", 0L);
                long hqSize = item.optLong("HQFileSize", 0L);
                long sqSize = item.optLong("SQFileSize", 0L);
                long shownSize = sqSize > 0L ? sqSize : (hqSize > 0L ? hqSize : fileSize);
                if (shownSize > 0L) {
                    track.put("size", shownSize);
                    track.put("sizeExact", true);
                }
                JSONObject qualitySizes = new JSONObject();
                if (fileSize > 0L) qualitySizes.put("standard", fileSize);
                if (hqSize > 0L) qualitySizes.put("high", hqSize);
                if (sqSize > 0L) qualitySizes.put("lossless", sqSize);
                if (qualitySizes.length() > 0) track.put("qualitySizes", qualitySizes);
                if (sqSize > 0L) {
                    track.put("bestQuality", "lossless");
                    track.put("bestExt", "flac");
                } else if (hqSize > 0L) {
                    track.put("bestQuality", "high");
                    track.put("bestExt", "mp3");
                } else {
                    track.put("bestQuality", "standard");
                    track.put("bestExt", "mp3");
                }
                tracks.put(track);
            }
            tracks = filterPlayableKugou(tracks);
            if (tracks.length() > 0) return tracks;
        } catch (Exception e) {
            firstError = e;
        }

        try {
            JSONArray fallback = filterPlayableKugou(
                    searchMetingArray(keyword, "kugou", "kugou"));
            fallbackOk = true;
            if (fallback.length() > 0) return fallback;
        } catch (Exception e) {
            if (firstError == null) firstError = e;
        }

        if (directOk || fallbackOk) return new JSONArray();
        if (firstError instanceof ApiException) throw firstError;
        throw new ApiException("kugou-search", "酷狗搜索暂时不可用");
    }

    /**
     * Many Kugou search results are licensed for VIP playback only and the
     * public bridge returns an empty body. Filter those out at search time so
     * a Kugou row in the UI always has a real chance of playing immediately.
     */
    private static JSONArray filterPlayableKugou(JSONArray tracks) {
        if (tracks == null || tracks.length() == 0) return new JSONArray();
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.kugou.com/");
        ExecutorService probePool = Executors.newFixedThreadPool(
                Math.min(5, tracks.length()), r -> {
                    Thread t = new Thread(r, "musicdl-kugou-probe");
                    t.setDaemon(true);
                    return t;
                });
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (int i = 0; i < tracks.length(); i++) {
            final JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            futures.add(probePool.submit(() -> {
                String url = clean(track.optString("audioHint"));
                if (url.isEmpty()) return null;
                AudioProbe probe = probeAudio(url, headers);
                return probe.ok ? track : null;
            }));
        }
        JSONArray playable = new JSONArray();
        long deadline = System.currentTimeMillis() + 4500L;
        for (Future<JSONObject> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                future.cancel(true);
                continue;
            }
            try {
                JSONObject track = future.get(remaining, TimeUnit.MILLISECONDS);
                if (track != null) playable.put(track);
            } catch (Exception ignored) {
                future.cancel(true);
            }
        }
        probePool.shutdownNow();
        return playable;
    }

    /** 5sing is Kugou's original/cover-song community. */
    private static JSONArray searchFiveSing(String keyword) throws Exception {
        // The 5sing API certificate does not match its search host; Android's
        // verified TLS rejects it. The search payload is public, so use HTTP and
        // keep HTTPS for the actual audio files.
        String url = "http://search.5sing.kugou.com/home/json?keyword=" + enc(keyword)
                + "&sort=1&page=1&filter=0&type=0";
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://5sing.kugou.com/");
        HttpResult result = httpGet(url, headers, MAX_JSON_BYTES, "https://5sing.kugou.com/");
        if (!result.isSuccess()) {
            throw new ApiException("fivesing-search", "5sing 搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONObject)) {
            throw new ApiException("fivesing-search", "5sing 返回了意外的数据");
        }
        JSONArray raw = ((JSONObject) parsed).optJSONArray("list");
        if (raw == null) {
            throw new ApiException("fivesing-search", "5sing 返回了意外的数据");
        }

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = stripHtml(item.optString("songName"));
            String artist = stripHtml(item.optString("singer"));
            String id = clean(item.optString("songId"));
            String songType = clean(item.optString("typeEname"));
            if (title.isEmpty() || id.isEmpty() || songType.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack("fivesing", id, i + 1, keyword,
                    title, artist, "", "");
            track.put("songType", songType);
            track.put("audioHint", "http://mobileapi.5sing.kugou.com/song/getSongUrl"
                    + "?songid=" + enc(id) + "&songtype=" + enc(songType));
            long songSize = item.optLong("songSize", 0L);
            if (songSize > 0L) {
                track.put("size", songSize);
                track.put("sizeExact", true);
            }
            tracks.put(track);
        }
        return tracks;
    }

    /** Jamendo is a Creative Commons music catalogue with direct public streams. */
    private static JSONArray searchJamendo(String keyword) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json");
        headers.put("Referer", "https://www.jamendo.com/search?q=" + enc(keyword));
        headers.put("Origin", "https://www.jamendo.com");
        headers.put("x-jam-version", JAMENDO_VERSION);
        headers.put("x-jam-call", jamendoCall("/api/search"));
        headers.put("x-requested-with", "XMLHttpRequest");

        String url = "https://www.jamendo.com/api/search?query=" + enc(keyword)
                + "&type=track&limit=" + MAX_PER_SOURCE + "&identities=www&offset=0";
        HttpResult result = httpGet(url, headers, MAX_JSON_BYTES, "https://www.jamendo.com/");
        if (!result.isSuccess()) {
            throw new ApiException("jamendo-search", "Jamendo 搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONArray)) {
            throw new ApiException("jamendo-search", "Jamendo 返回了意外的数据");
        }

        JSONArray raw = (JSONArray) parsed;
        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String id = clean(item.optString("id"));
            String title = stripHtml(item.optString("name"));
            JSONObject artistObject = item.optJSONObject("artist");
            JSONObject albumObject = item.optJSONObject("album");
            String artist = artistObject == null ? "" : stripHtml(artistObject.optString("name"));
            String album = albumObject == null ? "" : stripHtml(albumObject.optString("name"));
            String stream = jamendoStream(item);
            if (id.isEmpty() || title.isEmpty() || stream.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            long albumId = albumObject == null ? 0L : albumObject.optLong("id", 0L);
            String cover = albumId > 0
                    ? "https://usercontent.jamendo.com?type=album&id=" + albumId
                    + "&width=300&trackid=" + enc(id)
                    : "";
            JSONObject track = baseTrack("jamendo", id, i + 1, keyword,
                    title, artist, album, cover);
            track.put("audioHint", stream);
            String streamLower = stream.toLowerCase(Locale.ROOT);
            track.put("extHint",
                    streamLower.contains("format=ogg") || streamLower.contains(".ogg")
                            ? "ogg" : "mp3");
            int duration = item.optInt("duration", 0);
            if (duration > 0) track.put("duration", duration);
            tracks.put(track);
        }
        return tracks;
    }

    /** Search wrapper for the public Meting bridge (used as a Kugou fallback). */
    private static JSONArray searchMetingArray(String keyword, String server, String source)
            throws Exception {
        String url = "https://api.qijieya.cn/meting/?server=" + enc(server)
                + "&type=search&id=" + enc(keyword) + "&limit=" + MAX_PER_SOURCE;
        HttpResult result = httpGet(url, null, MAX_JSON_BYTES, null);
        if (!result.isSuccess()) {
            throw new ApiException(source + "-search", "备用搜索接口返回 HTTP " + result.status);
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONArray)) {
            throw new ApiException(source + "-search", "备用接口返回了意外的数据");
        }
        JSONArray raw = (JSONArray) parsed;
        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length() && tracks.length() < MAX_PER_SOURCE; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String title = stripHtml(item.optString("name"));
            String artist = stripHtml(item.optString("artist"));
            String audioUrl = item.optString("url");
            String id = queryParam(audioUrl, "id");
            if (title.isEmpty() || id.isEmpty()) continue;
            if (!seen.add(normalizeKey(title, artist))) continue;

            JSONObject track = baseTrack(source, id, i + 1, keyword, title, artist, "",
                    normalizeCover(item.optString("pic")));
            track.put("audioHint", audioUrl);
            track.put("lrcUrl", item.optString("lrc"));
            tracks.put(track);
        }
        return tracks;
    }

    // ---------------------------------------------------------------------
    // Resolve
    // ---------------------------------------------------------------------

    private static JSONObject resolveNetease(JSONObject track, String quality) throws Exception {
        String id = clean(track.optString("id"));
        if (id.isEmpty()) throw new ApiException("invalid", "无法识别这首歌曲。");

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        AudioProbe probe = new AudioProbe();
        String selectedTier = "";

        if ("auto".equals(quality)) {
            String url = clean(track.optString("audioHint"));
            if (!url.startsWith("http")) url = neteaseUrl(id, 0);
            probe = probeAudio(url, headers);
            if (!probe.ok) {
                String outer = "https://music.163.com/song/media/outer/url?id=" + enc(id) + ".mp3";
                probe = probeAudio(outer, headers);
                selectedTier = "standard";
            }
        } else {
            int[] bitrates = "lossless".equals(quality)
                    ? new int[]{999000, 320, 128}
                    : "high".equals(quality)
                    ? new int[]{320, 128}
                    : new int[]{128};
            String[] tiers = "lossless".equals(quality)
                    ? new String[]{"lossless", "high", "standard"}
                    : "high".equals(quality)
                    ? new String[]{"high", "standard"}
                    : new String[]{"standard"};
            for (int i = 0; i < bitrates.length; i++) {
                AudioProbe candidate = probeAudio(neteaseUrl(id, bitrates[i]), headers);
                if (candidate.ok) {
                    probe = candidate;
                    selectedTier = tiers[i];
                    break;
                }
            }
            if (!probe.ok) {
                String outer = "https://music.163.com/song/media/outer/url?id=" + enc(id) + ".mp3";
                probe = probeAudio(outer, headers);
                selectedTier = "standard";
            }
        }
        if (!probe.ok) {
            throw new ApiException("unavailable", "这首网易云歌曲暂时没有可播放地址，换一个来源试试。");
        }

        JSONObject out = resolvedBase(track);
        String ext = extension(probe.contentType, probe.finalUrl, "mp3");
        if ("flac".equals(ext)) {
            out.put("mime", "audio/flac");
        } else {
            out.put("mime", mimeFor(probe.contentType, ext));
        }
        int duration = track.optInt("duration", 0);
        String actual = detectQuality(probe, duration, ext, selectedTier);
        out.put("url", probe.finalUrl);
        out.put("ext", ext);
        out.put("quality", actual);
        out.put("qualityLabel", qualityLabelFor(actual, ext));
        putProbeSize(out, probe);

        String lrcUrl = clean(track.optString("lrcUrl"));
        if (!lrcUrl.isEmpty()) {
            try {
                HttpResult lyric = httpGet(lrcUrl, null, 300_000, "https://music.163.com/");
                if (lyric.isSuccess()) out.put("lrc", extractLyric(lyric.text()));
            } catch (Exception ignored) {
                // Lyrics are a bonus; playback must still work without them.
            }
        }
        return out;
    }

    private static String neteaseUrl(String id, int bitrate) {
        String url = "https://api.qijieya.cn/meting/?server=netease&type=url&id=" + enc(id);
        return bitrate > 0 ? url + "&br=" + bitrate : url;
    }

    private static JSONObject resolveMigu(JSONObject track, String quality) throws Exception {
        return resolveMiguInternal(track, quality, true);
    }

    private static JSONObject resolveMiguInternal(JSONObject track, String quality,
                                                  boolean fetchLyric) throws Exception {
        String contentId = clean(track.optString("id"));
        String copyrightId = clean(track.optString("copyrightId"));
        JSONArray formats = track.optJSONArray("miguFormats");
        if (contentId.isEmpty() || copyrightId.isEmpty()
                || formats == null || formats.length() == 0) {
            throw new ApiException("migu-resolve", "咪咕这首歌曲缺少可用的音质信息。");
        }

        List<JSONObject> ordered = orderMiguFormats(formats, quality);
        AudioProbe probe = null;
        String chosenUrl = "";
        String chosenTone = "";
        String chosenExt = "mp3";
        int duration = 0;
        JSONObject chosenData = null;
        for (JSONObject candidate : ordered) {
            String tone = clean(candidate.optString("formatType"));
            String resource = clean(candidate.optString("resourceType"));
            if (tone.isEmpty() || resource.isEmpty()) continue;
            JSONObject data;
            try {
                data = miguListen(contentId, copyrightId, tone, resource);
            } catch (Exception ignored) {
                continue;
            }
            if (data == null) continue;
            String url = clean(data.optString("url"));
            if (!url.startsWith("http")) continue;
            // Migu's VIP SQ request often resolves to the 128K preview path.
            // The same object can be served at 320K, exactly like musicdl.
            if (!"standard".equals(quality)) {
                url = url.replace("/MP3_128_16_Stero/", "/MP3_320_16_Stero/");
            }
            AudioProbe candidateProbe = probeAudio(url, null);
            if (!candidateProbe.ok) continue;
            String ext = extension(candidateProbe.contentType, candidateProbe.finalUrl, "mp3");
            if (!isPlayableAudioExt(ext)) continue;
            probe = candidateProbe;
            chosenUrl = url;
            chosenTone = tone;
            chosenExt = ext;
            chosenData = data;
            JSONObject song = data.optJSONObject("song");
            if (song != null) duration = song.optInt("duration", 0);
            break;
        }
        if (probe == null) {
            throw new ApiException("unavailable", "这首咪咕歌曲暂时没有可播放地址。");
        }

        JSONObject out = resolvedBase(track);
        out.put("url", chosenUrl);
        out.put("ext", chosenExt);
        out.put("mime", "flac".equals(chosenExt) ? "audio/flac"
                : mimeFor(probe.contentType, chosenExt));
        String hint = "lossless";
        if ("LQ".equalsIgnoreCase(chosenTone) || "PQ".equalsIgnoreCase(chosenTone)) {
            hint = "standard";
        } else if ("HQ".equalsIgnoreCase(chosenTone)) {
            hint = "high";
        }
        String actual = detectQuality(probe, duration, chosenExt, hint);
        out.put("quality", actual);
        out.put("qualityLabel", qualityLabelFor(actual, chosenExt));
        putProbeSize(out, probe);
        if (duration > 0) out.put("duration", duration);

        if (fetchLyric) {
            String lrcUrl = chosenData == null ? "" : clean(chosenData.optString("lrcUrl"));
            if (lrcUrl.isEmpty()) lrcUrl = clean(track.optString("lyricUrl"));
            if (lrcUrl.startsWith("http")) {
                try {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Referer", "https://y.migu.cn/");
                    HttpResult lyric = httpGet(lrcUrl, headers, 300_000,
                            "https://y.migu.cn/");
                    if (lyric.isSuccess()) out.put("lrc", extractLyric(lyric.text()));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private static JSONObject miguListen(String contentId, String copyrightId,
                                         String tone, String resource) throws Exception {
        String url = "https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4?contentId="
                + enc(contentId) + "&copyrightId=" + enc(copyrightId)
                + "&resourceType=" + enc(resource) + "&netType=01&toneFlag="
                + enc(tone) + "&scene=&lowerQualityContentId=" + enc(contentId);
        Map<String, String> headers = miguHeaders();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("birth", "h5page");
        headers.put("signature", "1");
        HttpResult result = httpGet(url, headers, MAX_JSON_BYTES,
                "https://h5.nf.migu.cn/");
        if (!result.isSuccess()) return null;
        JSONObject root = parseObject(miguDecode(result.body));
        return root == null ? null : root.optJSONObject("data");
    }

    private static List<JSONObject> orderMiguFormats(JSONArray formats, String quality) {
        List<JSONObject> lossless = new ArrayList<>();
        List<JSONObject> high = new ArrayList<>();
        List<JSONObject> standard = new ArrayList<>();
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;
            String tone = clean(format.optString("formatType")).toUpperCase(Locale.ROOT);
            String ext = clean(format.optString("fileType")).toLowerCase(Locale.ROOT);
            if ("Z3D".equals(tone)) continue;
            if ("flac".equals(ext) || "SQ".equals(tone) || "ZQ".equals(tone)
                    || "ZQ24".equals(tone) || "ZQ32".equals(tone)) {
                lossless.add(format);
            } else if ("HQ".equals(tone)) {
                high.add(format);
            } else {
                standard.add(format);
            }
        }
        Comparator<JSONObject> bySizeDesc = (left, right) -> Long.compare(
                right.optLong("size", 0L), left.optLong("size", 0L));
        lossless.sort(bySizeDesc);
        high.sort(bySizeDesc);
        standard.sort(bySizeDesc);

        List<JSONObject> ordered = new ArrayList<>();
        if ("lossless".equals(quality) || "auto".equals(quality)) {
            ordered.addAll(lossless);
            ordered.addAll(high);
            ordered.addAll(standard);
        } else if ("high".equals(quality)) {
            ordered.addAll(high);
            ordered.addAll(lossless);
            ordered.addAll(standard);
        } else {
            ordered.addAll(standard);
            ordered.addAll(high);
            ordered.addAll(lossless);
        }
        return ordered;
    }

    private static JSONObject resolveQianqian(JSONObject track, String quality) throws Exception {
        return resolveQianqianInternal(track, quality, true);
    }

    private static JSONObject resolveQianqianInternal(JSONObject track, String quality,
                                                      boolean fetchLyric) throws Exception {
        String tsid = clean(track.optString("id"));
        if (tsid.isEmpty()) throw new ApiException("invalid", "无法识别这首千千歌曲。");
        String[] rates;
        String[] hints;
        if ("lossless".equals(quality) || "auto".equals(quality)) {
            rates = new String[]{"3000", "320", "128", "64"};
            hints = new String[]{"lossless", "high", "standard", "standard"};
        } else if ("high".equals(quality)) {
            rates = new String[]{"320", "128", "64"};
            hints = new String[]{"high", "standard", "standard"};
        } else {
            rates = new String[]{"128", "64"};
            hints = new String[]{"standard", "standard"};
        }

        AudioProbe probe = null;
        JSONObject chosenData = null;
        String chosenUrl = "";
        String chosenHint = "standard";
        String chosenExt = "mp3";
        int duration = 0;
        for (int i = 0; i < rates.length; i++) {
            JSONObject data;
            try {
                data = qianqianTrackLink(tsid, rates[i]);
            } catch (Exception ignored) {
                continue;
            }
            if (data == null) continue;
            String url = clean(data.optString("path"));
            if (url.isEmpty()) {
                JSONObject trail = data.optJSONObject("trail_audio_info");
                if (trail != null) url = clean(trail.optString("path"));
            }
            if (!url.startsWith("http")) continue;
            AudioProbe candidateProbe = probeAudio(url, null);
            if (!candidateProbe.ok) continue;
            String ext = extension(candidateProbe.contentType, candidateProbe.finalUrl, "mp3");
            if (!isPlayableAudioExt(ext)) continue;
            probe = candidateProbe;
            chosenData = data;
            chosenUrl = url;
            chosenHint = hints[i];
            chosenExt = ext;
            duration = data.optInt("duration", 0);
            break;
        }
        if (probe == null) {
            throw new ApiException("unavailable", "这首千千歌曲暂时没有可播放地址。");
        }

        JSONObject out = resolvedBase(track);
        out.put("url", chosenUrl);
        out.put("ext", chosenExt);
        out.put("mime", "flac".equals(chosenExt) ? "audio/flac"
                : mimeFor(probe.contentType, chosenExt));
        String actual = detectQuality(probe, duration, chosenExt, chosenHint);
        out.put("quality", actual);
        out.put("qualityLabel", qualityLabelFor(actual, chosenExt));
        if (probe.totalBytes <= 0L && chosenData != null) {
            long reported = chosenData.optLong("size", 0L);
            if (reported > 0L) {
                out.put("size", reported);
                out.put("sizeExact", true);
            }
        } else {
            putProbeSize(out, probe);
        }
        if (duration > 0) out.put("duration", duration);

        if (fetchLyric) {
            String lrcUrl = clean(track.optString("lrcUrl"));
            if (lrcUrl.startsWith("http")) {
                try {
                    HttpResult lyric = httpGet(lrcUrl, qianqianHeaders(), 300_000,
                            "https://music.91q.com/");
                    if (lyric.isSuccess()) out.put("lrc", extractLyric(lyric.text()));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private static JSONObject qianqianTrackLink(String tsid, String rate) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("TSID", tsid);
        params.put("appid", QIANQIAN_APPID);
        params.put("rate", rate);
        String url = "https://music.91q.com/v1/song/tracklink?"
                + qianqianSignedQuery(params);
        HttpResult result = httpGet(url, qianqianHeaders(), MAX_JSON_BYTES,
                "https://music.91q.com/");
        if (!result.isSuccess()) return null;
        JSONObject root = parseObject(result.text());
        return root == null ? null : root.optJSONObject("data");
    }

    private static boolean isPlayableAudioExt(String ext) {
        if (ext == null) return false;
        switch (ext.toLowerCase(Locale.ROOT)) {
            case "mp3":
            case "flac":
            case "m4a":
            case "aac":
            case "ogg":
            case "opus":
            case "wav":
                return true;
            default:
                return false;
        }
    }

    private static JSONObject resolveQQ(JSONObject track, String quality) throws Exception {
        try {
            return resolveQQOfficial(track, quality);
        } catch (Exception ignored) {
            return resolveQQLegacy(track, quality);
        }
    }

    private static JSONObject resolveQQOfficial(JSONObject track, String quality)
            throws Exception {
        String mid = clean(track.optString("id"));
        if (mid.isEmpty()) throw new ApiException("invalid", "无法识别这首 QQ 音乐。");
        int duration = track.optInt("duration", 0);

        int[] levels;
        String[] hints;
        if ("lossless".equals(quality) || "auto".equals(quality)) {
            levels = new int[]{14, 13, 12, 11, 10, 9, 8, 7, 6};
            hints = new String[]{"lossless", "lossless", "lossless", "lossless",
                    "lossless", "high", "high", "high", "standard"};
        } else if ("high".equals(quality)) {
            levels = new int[]{8, 9, 7, 6};
            hints = new String[]{"high", "high", "high", "standard"};
        } else {
            levels = new int[]{6, 3, 2};
            hints = new String[]{"standard", "standard", "standard"};
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://y.qq.com/");
        for (int i = 0; i < levels.length; i++) {
            int level = levels[i];
            HttpResult result;
            try {
                result = httpGet("https://api.vkeys.cn/music/tencent/song/link?mid="
                        + enc(mid) + "&quality=" + level, headers, MAX_JSON_BYTES,
                        "https://y.qq.com/");
            } catch (Exception ignored) {
                continue;
            }
            if (!result.isSuccess()) continue;
            JSONObject root = parseObject(result.text());
            JSONObject data = root == null ? null : root.optJSONObject("data");
            String playUrl = data == null ? "" : clean(data.optString("url"));
            if (!playUrl.startsWith("http")) continue;
            AudioProbe probe = probeAudio(playUrl, null);
            if (!probe.ok) continue;
            String ext = extension(probe.contentType, probe.finalUrl, "mp3");
            if (!isPlayableAudioExt(ext)) continue;

            String hint = hints[i];
            String actual = detectQuality(probe, duration, ext, hint);
            String detail = qqLevelLabel(level);
            JSONObject out = resolvedBase(track);
            out.put("url", playUrl);
            out.put("ext", ext);
            out.put("mime", "flac".equals(ext) ? "audio/flac"
                    : mimeFor(probe.contentType, ext));
            out.put("quality", actual);
            out.put("qualityLabel", tierLabel(actual)
                    + (detail.isEmpty() ? "" : " · " + detail)
                    + " · " + ext.toUpperCase(Locale.ROOT));
            putProbeSize(out, probe);
            if (duration > 0) out.put("duration", duration);

            try {
                HttpResult lyric = httpGet("https://api.vkeys.cn/v2/music/tencent/lyric?mid="
                        + enc(mid), null, 500_000, "https://y.qq.com/");
                if (lyric.isSuccess()) {
                    JSONObject lyricRoot = parseObject(lyric.text());
                    JSONObject lyricData = lyricRoot == null ? null
                            : lyricRoot.optJSONObject("data");
                    String lrc = lyricData == null ? "" : lyricData.optString("lrc", "");
                    if (!lrc.isEmpty()) out.put("lrc", extractLyric(lrc));
                }
            } catch (Exception ignored) {
            }
            return out;
        }
        throw new ApiException("unavailable",
                "这首 QQ 音乐暂时没有可公开播放的地址。");
    }

    private static String qqLevelLabel(int level) {
        switch (level) {
            case 14:
                return "母带";
            case 13:
                return "臻品空间音频";
            case 12:
                return "杜比全景声";
            case 11:
                return "Hi-Res";
            case 10:
                return "SQ 无损";
            case 9:
                return "HQ OGG";
            case 8:
                return "320K";
            case 7:
                return "OGG";
            case 6:
                return "128K";
            case 3:
                return "96K";
            case 2:
                return "48K";
            default:
                return "";
        }
    }

    private static JSONObject resolveQQLegacy(JSONObject track, String quality)
            throws Exception {
        String msg = clean(track.optString("keyword"));
        if (msg.isEmpty()) {
            msg = (clean(track.optString("title")) + " " + clean(track.optString("artist"))).trim();
        }
        String mid = clean(track.optString("id"));
        if (mid.isEmpty()) throw new ApiException("invalid", "无法识别这首歌曲。");

        String detailUrl = "https://tang.api.s01s.cn/music_open_api.php?msg=" + enc(msg)
                + "&type=json&mid=" + enc(mid);
        HttpResult result = httpGet(detailUrl, null, MAX_JSON_BYTES, null);
        if (!result.isSuccess()) {
            throw new ApiException("network", "QQ 音乐暂时无法连接，请稍后再试。");
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONObject)) {
            throw new ApiException("unavailable", "QQ 音乐没有返回可用的歌曲信息。");
        }
        JSONObject detail = (JSONObject) parsed;

        String[] fields;
        if ("lossless".equals(quality)) {
            fields = new String[]{
                    "song_play_url_sq", "song_play_url_hq", "song_play_url_standard",
                    "song_play_url", "song_play_url_fq", "song_play_url_accom"
            };
        } else if ("high".equals(quality)) {
            fields = new String[]{
                    "song_play_url_hq", "song_play_url_standard", "song_play_url",
                    "song_play_url_fq", "song_play_url_accom"
            };
        } else {
            fields = new String[]{
                    "song_play_url_standard", "song_play_url", "song_play_url_fq",
                    "song_play_url_hq", "song_play_url_accom"
            };
        }
        String playUrl = "";
        String chosenField = "";
        for (String field : fields) {
            String candidate = clean(detail.optString(field));
            if (!candidate.isEmpty()) {
                playUrl = candidate;
                chosenField = field;
                break;
            }
        }
        if (playUrl.isEmpty()) {
            if (track.optBoolean("paid") || "付费".equals(clean(detail.optString("vip")))) {
                throw new ApiException("paid", "这首 QQ 音乐为付费歌曲，暂不支持播放。");
            }
            throw new ApiException("unavailable", "这首 QQ 音乐暂时没有可用的播放地址。");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://y.qq.com/");
        AudioProbe probe = probeAudio(playUrl, headers);
        if (!probe.ok) {
            throw new ApiException("unavailable", "这首 QQ 音乐暂时无法播放，换一首试试。");
        }

        JSONObject out = resolvedBase(track);
        out.put("title", choose(detail.optString("song_title"), track.optString("title")));
        out.put("artist", choose(detail.optString("singer_name"), track.optString("artist")));
        out.put("album", choose(detail.optString("album_name"), track.optString("album")));
        String cover = normalizeCover(choose(detail.optString("album_pic"),
                track.optString("cover")));
        if (!cover.isEmpty()) out.put("cover", cover);
        String ext = extension(probe.contentType, probe.finalUrl, "m4a");
        int duration = detail.optInt("song_play_time", 0);
        String requestedTier = chosenField.contains("_sq") ? "lossless"
                : chosenField.contains("_hq") ? "high" : "standard";
        String actual = detectQuality(probe, duration, ext, requestedTier);
        out.put("url", probe.finalUrl);
        out.put("ext", ext);
        out.put("mime", "flac".equals(ext) ? "audio/flac" : mimeFor(probe.contentType, ext));
        out.put("quality", actual);
        out.put("qualityLabel", qualityLabelFor(actual, ext));
        putProbeSize(out, probe);
        out.put("lrc", clean(detail.optString("song_lyric")));
        if (duration > 0) out.put("duration", duration);
        return out;
    }

    private static JSONObject resolveKuwo(JSONObject track, String quality) throws Exception {
        try {
            return resolveKuwoOfficial(track, quality);
        } catch (Exception ignored) {
            return resolveKuwoLegacy(track, quality);
        }
    }

    private static JSONObject resolveKuwoOfficial(JSONObject track, String quality)
            throws Exception {
        String rid = clean(track.optString("id"));
        if (rid.startsWith("MUSIC_")) rid = rid.substring("MUSIC_".length());
        if (rid.isEmpty()) throw new ApiException("invalid", "无法识别这首酷我歌曲。");
        int duration = track.optInt("duration", 0);

        String[] formats;
        String[] hints;
        if ("lossless".equals(quality)) {
            formats = new String[]{"flac", "mp3"};
            hints = new String[]{"lossless", "high"};
        } else if ("high".equals(quality)) {
            formats = new String[]{"mp3", "flac"};
            hints = new String[]{"high", "lossless"};
        } else if ("auto".equals(quality)) {
            formats = new String[]{"flac", "mp3"};
            hints = new String[]{"lossless", "high"};
        } else {
            formats = new String[]{"mp3", "flac"};
            hints = new String[]{"standard", "lossless"};
        }

        for (int i = 0; i < formats.length; i++) {
            String query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk"
                    + "&p2p=1&type=convert_url2&sig=0&format=" + formats[i]
                    + "&rid=" + rid;
            String encrypted;
            try {
                encrypted = KuwoCrypto.encryptQuery(query);
            } catch (Exception e) {
                continue;
            }
            String url = "http://mobi.kuwo.cn/mobi.s?f=kuwo&q=" + enc(encrypted);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "okhttp/3.10.0");
            HttpResult result = httpGet(url, headers, 128_000, "http://www.kuwo.cn/");
            if (!result.isSuccess()) continue;
            Matcher matcher = Pattern.compile("http[^\\s$\"]+").matcher(result.text());
            if (!matcher.find()) continue;
            String playUrl = matcher.group(0);
            if (!playUrl.startsWith("http")) continue;
            AudioProbe probe = probeAudio(playUrl, null);
            if (!probe.ok) continue;
            String ext = extension(probe.contentType, probe.finalUrl, formats[i]);
            if (!isPlayableAudioExt(ext)) continue;

            JSONObject out = resolvedBase(track);
            String actual = detectQuality(probe, duration, ext, hints[i]);
            out.put("url", playUrl);
            out.put("ext", ext);
            out.put("mime", "flac".equals(ext) ? "audio/flac"
                    : mimeFor(probe.contentType, ext));
            out.put("quality", actual);
            out.put("qualityLabel", qualityLabelFor(actual, ext));
            putProbeSize(out, probe);
            if (out.optLong("size", 0L) <= 0L) {
                JSONObject sizes = track.optJSONObject("qualitySizes");
                if (sizes != null) {
                    long size = sizes.optLong(actual, 0L);
                    if (size > 0L) {
                        out.put("size", size);
                        out.put("sizeExact", true);
                    }
                }
            }
            if (duration > 0) out.put("duration", duration);
            return out;
        }
        throw new ApiException("unavailable", "这首酷我歌曲暂时没有可用的播放地址。");
    }

    private static JSONObject resolveKuwoLegacy(JSONObject track, String quality)
            throws Exception {
        String msg = clean(track.optString("keyword"));
        if (msg.isEmpty()) msg = clean(track.optString("title"));
        int index = track.optInt("index", 1);
        if (msg.isEmpty()) throw new ApiException("invalid", "无法识别这首歌曲。");

        // Kuwo's public bridge exposes FLAC (1), 300K OGG (3), 192K OGG (2)
        // and 100K OGG (0). Try the requested tier first, then fall back.
        int[] bitrates;
        String[] tiers;
        if ("lossless".equals(quality)) {
            bitrates = new int[]{1, 3, 2, 0};
            tiers = new String[]{"lossless", "high", "high", "standard"};
        } else if ("high".equals(quality)) {
            bitrates = new int[]{3, 2, 0};
            tiers = new String[]{"high", "high", "standard"};
        } else if ("auto".equals(quality)) {
            bitrates = new int[]{2, 0};
            tiers = new String[]{"high", "standard"};
        } else {
            bitrates = new int[]{0, 2};
            tiers = new String[]{"standard", "high"};
        }
        for (int i = 0; i < bitrates.length; i++) {
            int br = bitrates[i];
            String detailUrl = "https://oiapi.net/api/Kuwo?msg=" + enc(msg)
                    + "&n=" + index + "&br=" + br;
            try {
                HttpResult result = httpGet(detailUrl, null, MAX_JSON_BYTES, null);
                if (!result.isSuccess()) continue;
                Object parsed = parseJson(result.text());
                if (!(parsed instanceof JSONObject)) continue;
                JSONObject data = ((JSONObject) parsed).optJSONObject("data");
                if (data == null) continue;
                String url = clean(data.optString("url"));
                if (url.isEmpty()) continue;

                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", "https://www.kuwo.cn/");
                AudioProbe probe = probeAudio(url, headers);
                if (!probe.ok) continue;

                JSONObject out = resolvedBase(track);
                out.put("title", choose(data.optString("song"), track.optString("title")));
                out.put("artist", choose(data.optString("singer"), track.optString("artist")));
                out.put("album", choose(data.optString("album"), track.optString("album")));
                String cover = normalizeCover(choose(data.optString("picture"),
                        track.optString("cover")));
                if (!cover.isEmpty()) out.put("cover", cover);
                String ext = extension(probe.contentType, probe.finalUrl, "ogg");
                int duration = parsePositiveInt(data.optString("time"));
                String actual = detectQuality(probe, duration, ext, tiers[i]);
                String format = clean(data.optString("format")).toUpperCase(Locale.ROOT);
                String bitrate = clean(data.optString("bitrate"));
                String detail = format.isEmpty() ? ext.toUpperCase(Locale.ROOT) : format;
                if (!bitrate.isEmpty()) detail += " · " + bitrate + "K";
                out.put("url", probe.finalUrl);
                out.put("ext", ext);
                out.put("mime", "flac".equals(ext) ? "audio/flac" : mimeFor(probe.contentType, ext));
                out.put("quality", actual);
                out.put("qualityLabel", tierLabel(actual) + " · " + detail);
                putProbeSize(out, probe);
                if (duration > 0) out.put("duration", duration);
                return out;
            } catch (Exception ignored) {
                // Try the next bitrate.
            }
        }
        throw new ApiException("unavailable", "这首酷我歌曲暂时没有可用的播放地址。");
    }

    private static JSONObject resolveJoox(JSONObject track, String quality) throws Exception {
        String msg = clean(track.optString("keyword"));
        if (msg.isEmpty()) msg = clean(track.optString("title"));
        int index = track.optInt("index", 1);
        if (msg.isEmpty()) throw new ApiException("invalid", "无法识别这首歌曲。");

        String detailUrl = "https://apicx.asia/api/joox_music?msg=" + enc(msg)
                + "&n=" + index + "&token=" + enc(JOOX_TOKEN) + "&br=4";
        HttpResult result = httpGet(detailUrl, null, MAX_JSON_BYTES, null);
        if (!result.isSuccess()) {
            throw new ApiException("network", "JOOX 暂时无法连接，请稍后再试。");
        }
        Object parsed = parseJson(result.text());
        if (!(parsed instanceof JSONObject)) {
            throw new ApiException("unavailable", "JOOX 没有返回可用的歌曲信息。");
        }
        JSONObject data = ((JSONObject) parsed).optJSONObject("data");
        if (data == null) throw new ApiException("unavailable", "JOOX 没有返回可用的歌曲信息。");
        JSONObject links = data.optJSONObject("播放链接");
        if (links == null) throw new ApiException("unavailable", "这首 JOOX 歌曲暂时没有播放地址。");

        String[] preferred;
        if ("lossless".equals(quality)) {
            preferred = new String[]{
                    "无损FLAC", "Hi-Res无损", "母带无损", "Atmos全景声", "MP3 320",
                    "OGG 320", "AAC 192", "OGG 192", "MP3 128", "AAC 96", "AAC 48"
            };
        } else if ("high".equals(quality)) {
            preferred = new String[]{
                    "MP3 320", "OGG 320", "AAC 192", "OGG 192", "MP3 128",
                    "AAC 96", "AAC 48"
            };
        } else if ("auto".equals(quality)) {
            preferred = new String[]{
                    "MP3 320", "AAC 192", "OGG 192", "MP3 128", "OGG 320", "AAC 96",
                    "AAC 48", "无损FLAC", "Hi-Res无损", "母带无损", "Atmos全景声"
            };
        } else {
            preferred = new String[]{
                    "MP3 128", "AAC 96", "AAC 48", "OGG 192"
            };
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.joox.com/");
        String chosenKey = null;
        AudioProbe chosen = null;
        for (String key : preferred) {
            String candidate = clean(links.optString(key));
            if (candidate.isEmpty()) continue;
            AudioProbe probe = probeAudio(candidate, headers);
            if (probe.ok) {
                chosenKey = key;
                chosen = probe;
                break;
            }
        }
        if (chosen == null) {
            throw new ApiException("unavailable", "这首 JOOX 歌曲暂时无法播放，换一首试试。");
        }

        JSONObject out = resolvedBase(track);
        out.put("title", choose(data.optString("歌曲名称"), track.optString("title")));
        out.put("artist", choose(data.optString("歌手"), track.optString("artist")));
        out.put("album", choose(data.optString("专辑"), track.optString("album")));
        String keyExt = extensionFromJooxKey(chosenKey);
        String ext = extension(chosen.contentType, chosen.finalUrl, keyExt);
        if ("flac".equals(keyExt)) ext = "flac";
        int duration = parseDuration(data.optString("时长"));
        String requestedTier = chosenKey != null && chosenKey.contains("无损") ? "lossless"
                : chosenKey != null && (chosenKey.contains("320") || chosenKey.contains("192"))
                ? "high" : "standard";
        String actual = detectQuality(chosen, duration, ext, requestedTier);
        out.put("url", chosen.finalUrl);
        out.put("ext", ext);
        out.put("mime", "flac".equals(ext) ? "audio/flac" : mimeFor(chosen.contentType, ext));
        out.put("quality", actual);
        out.put("qualityLabel", tierLabel(actual) + " · " + (chosenKey == null
                ? ext.toUpperCase(Locale.ROOT) : chosenKey));
        putProbeSize(out, chosen);
        out.put("lrc", clean(data.optString("歌词内容")));
        if (duration > 0) out.put("duration", duration);
        return out;
    }

    private static JSONObject resolveKugou(JSONObject track, String quality) throws Exception {
        String id = clean(track.optString("id"));
        if (id.isEmpty()) throw new ApiException("invalid", "无法识别这首歌曲。");
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.kugou.com/");

        String hint = clean(track.optString("audioHint"));
        String standardHash = clean(track.optString("standardHash"));
        String hqHash = clean(track.optString("hqHash"));
        String sqHash = clean(track.optString("sqHash"));
        LinkedHashMap<String, String> candidates = new LinkedHashMap<>();
        if ("auto".equals(quality)) {
            addKugouCandidate(candidates, sqHash, "lossless");
            addKugouCandidate(candidates, hqHash, "high");
            addKugouCandidate(candidates, firstNonEmpty(standardHash, id), "standard");
            if (hint.startsWith("http")) candidates.put(hint, "");
        } else if ("lossless".equals(quality)) {
            addKugouCandidate(candidates, sqHash, "lossless");
            addKugouCandidate(candidates, hqHash, "high");
            addKugouCandidate(candidates, firstNonEmpty(standardHash, id), "standard");
        } else if ("high".equals(quality)) {
            addKugouCandidate(candidates, hqHash, "high");
            addKugouCandidate(candidates, firstNonEmpty(standardHash, id), "standard");
        } else {
            addKugouCandidate(candidates, firstNonEmpty(standardHash, id), "standard");
            addKugouCandidate(candidates, hqHash, "high");
        }
        if (candidates.isEmpty()) {
            if (hint.startsWith("http")) candidates.put(hint, "");
            addKugouCandidate(candidates, id, "");
        }

        AudioProbe probe = new AudioProbe();
        String tierHint = "";
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            AudioProbe result = probeAudio(candidate.getKey(), headers);
            if (result.ok) {
                probe = result;
                tierHint = candidate.getValue();
                break;
            }
        }
        if (!probe.ok) {
            throw new ApiException("unavailable",
                    "这首酷狗歌曲暂时没有可用的公开播放地址，换一首或换一个来源试试。");
        }

        JSONObject out = resolvedBase(track);
        String ext = extension(probe.contentType, probe.finalUrl, "mp3");
        int duration = track.optInt("duration", 0);
        String actual = detectQuality(probe, duration, ext, tierHint);
        out.put("url", probe.finalUrl);
        out.put("ext", ext);
        out.put("mime", "flac".equals(ext) ? "audio/flac" : mimeFor(probe.contentType, ext));
        out.put("quality", actual);
        out.put("qualityLabel", qualityLabelFor(actual, ext));
        putProbeSize(out, probe);

        String lrcUrl = clean(track.optString("lrcUrl"));
        if (lrcUrl.isEmpty()) lrcUrl = kugouLyricUrl(id);
        if (!lrcUrl.isEmpty()) {
            try {
                HttpResult lyric = httpGet(lrcUrl, null, 300_000,
                        "https://www.kugou.com/");
                if (lyric.isSuccess()) out.put("lrc", extractLyric(lyric.text()));
            } catch (Exception ignored) {
                // Lyrics are optional.
            }
        }
        return out;
    }

    private static void addKugouCandidate(Map<String, String> candidates,
                                          String hash, String tier) {
        String value = clean(hash);
        if (value.isEmpty()) return;
        String url = kugouUrl(value);
        if (!candidates.containsKey(url)) candidates.put(url, tier);
    }

    private static JSONObject resolveFiveSing(JSONObject track, String quality) throws Exception {
        String id = clean(track.optString("id"));
        String songType = clean(track.optString("songType"));
        if (id.isEmpty() || songType.isEmpty()) {
            throw new ApiException("invalid", "无法识别这首 5sing 歌曲。");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://5sing.kugou.com/");
        String metadataUrl = "http://mobileapi.5sing.kugou.com/song/getSongUrl"
                + "?songid=" + enc(id) + "&songtype=" + enc(songType);
        HttpResult result = httpGet(metadataUrl, headers, MAX_JSON_BYTES,
                "https://5sing.kugou.com/");
        if (!result.isSuccess()) {
            throw new ApiException("network", "5sing 暂时无法连接，请稍后再试。");
        }
        JSONObject root = parseObject(result.text());
        if (root == null) {
            throw new ApiException("unavailable", "5sing 没有返回可用的歌曲信息。");
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new ApiException("unavailable", "这首 5sing 歌曲暂时没有公开播放地址。");
        }

        String[] fields;
        if ("lossless".equals(quality)) {
            fields = new String[]{
                    "squrl", "hqurl", "lqurl", "squrl_backup", "hqurl_backup", "lqurl_backup"
            };
        } else if ("high".equals(quality)) {
            fields = new String[]{
                    "hqurl", "squrl", "lqurl", "hqurl_backup", "squrl_backup", "lqurl_backup"
            };
        } else if ("standard".equals(quality)) {
            fields = new String[]{
                    "lqurl", "hqurl", "squrl", "lqurl_backup", "hqurl_backup", "squrl_backup"
            };
        } else {
            fields = new String[]{
                    "squrl", "hqurl", "lqurl", "squrl_backup", "hqurl_backup", "lqurl_backup"
            };
        }
        AudioProbe chosen = null;
        String chosenFormat = "";
        String chosenField = "";
        for (String field : fields) {
            String candidate = clean(data.optString(field));
            if (candidate.isEmpty()) continue;
            AudioProbe probe = probeAudio(candidate, headers);
            if (probe.ok) {
                chosen = probe;
                chosenField = field;
                chosenFormat = field.startsWith("sq") ? clean(data.optString("sqext"))
                        : field.startsWith("hq") ? clean(data.optString("hqext"))
                        : clean(data.optString("lqext"));
                break;
            }
        }
        if (chosen == null) {
            throw new ApiException("unavailable",
                    "这首 5sing 歌曲暂时没有可播放的公开地址。");
        }

        JSONObject out = resolvedBase(track);
        String ext = extension(chosen.contentType, chosen.finalUrl,
                chosenFormat.isEmpty() ? "mp3" : chosenFormat);
        if ("flac".equalsIgnoreCase(chosenFormat)) ext = "flac";
        String requestedTier = chosenField.startsWith("sq") ? "lossless"
                : chosenField.startsWith("hq") ? "high" : "standard";
        String actual = detectQuality(chosen, track.optInt("duration", 0), ext, requestedTier);
        out.put("url", chosen.finalUrl);
        out.put("ext", ext);
        out.put("mime", "flac".equals(ext) ? "audio/flac" : mimeFor(chosen.contentType, ext));
        out.put("quality", actual);
        out.put("qualityLabel", qualityLabelFor(actual, ext));
        putProbeSize(out, chosen);

        try {
            String infoUrl = "http://mobileapi.5sing.kugou.com/song/newget"
                    + "?songid=" + enc(id) + "&songtype=" + enc(songType)
                    + "&songfields=&userfields=";
            HttpResult infoResult = httpGet(infoUrl, headers, 500_000,
                    "https://5sing.kugou.com/");
            if (infoResult.isSuccess()) {
                JSONObject infoRoot = parseObject(infoResult.text());
                JSONObject info = infoRoot == null ? null : infoRoot.optJSONObject("data");
                if (info != null) {
                    String title = stripHtml(info.optString("SN"));
                    if (!title.isEmpty()) out.put("title", title);
                    JSONObject user = info.optJSONObject("user");
                    if (user != null) {
                        String artist = stripHtml(user.optString("NN"));
                        if (!artist.isEmpty()) out.put("artist", artist);
                        String cover = normalizeCover(user.optString("I"));
                        if (!cover.isEmpty()) out.put("cover", cover);
                    }
                    String album = stripHtml(info.optString("albumName"));
                    if (!album.isEmpty()) out.put("album", album);
                    String lyric = info.optString("dynamicWords");
                    if (!lyric.isEmpty()) out.put("lrc", lyric);
                }
            }
        } catch (Exception ignored) {
            // Search metadata and playback still work without the detail call.
        }
        return out;
    }

    private static JSONObject resolveJamendo(JSONObject track, String quality) throws Exception {
        String url = clean(track.optString("audioHint"));
        if (!url.startsWith("http")) {
            throw new ApiException("invalid", "无法识别这首 Jamendo 歌曲。");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.jamendo.com/");
        AudioProbe probe = probeAudio(url, headers);
        if (!probe.ok) {
            throw new ApiException("unavailable",
                    "这首 Jamendo 歌曲暂时无法播放，换一首试试。");
        }
        String fallbackExt = clean(track.optString("extHint"));
        if (fallbackExt.isEmpty()) fallbackExt = "mp3";

        JSONObject out = resolvedBase(track);
        String ext = extension(probe.contentType, probe.finalUrl, fallbackExt);
        out.put("url", probe.finalUrl);
        out.put("ext", ext);
        out.put("mime", mimeFor(probe.contentType, ext));
        out.put("quality", "standard");
        out.put("qualityLabel", "标准 · " + ext.toUpperCase(Locale.ROOT) + " · 免费");
        putProbeSize(out, probe);
        return out;
    }

    // ---------------------------------------------------------------------
    // HTTP / parsing helpers
    // ---------------------------------------------------------------------

    private static JSONObject baseTrack(String source, String id, int index, String keyword,
                                        String title, String artist, String album, String cover)
            throws Exception {
        JSONObject track = new JSONObject();
        String uiId = source + ":" + id + ":" + index;
        track.put("uiId", uiId);
        track.put("source", source);
        track.put("sourceName", SOURCE_NAMES.get(source));
        track.put("id", id);
        track.put("index", index);
        track.put("keyword", keyword);
        track.put("title", title);
        track.put("artist", artist);
        track.put("album", album);
        track.put("cover", cover);
        track.put("duration", 0);
        track.put("paid", false);
        track.put("payLabel", "");
        return track;
    }

    private static JSONObject resolvedBase(JSONObject track) throws Exception {
        JSONObject out = new JSONObject();
        String[] keys = new String[]{
                "uiId", "source", "sourceName", "id", "index", "keyword", "title",
                "artist", "album", "cover", "paid", "payLabel", "audioHint", "lrcUrl",
                "songType", "extHint"
        };
        for (String key : keys) {
            if (track.has(key)) out.put(key, track.opt(key));
        }
        return out;
    }

    private static HttpResult httpGet(String url, Map<String, String> extraHeaders,
                                      int maxBytes, String referer) throws IOException {
        String currentUrl = url;
        for (int redirects = 0; redirects < 8; redirects++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(currentUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                // Follow redirects manually, including HTTPS -> HTTP CDN hops.
                // HttpURLConnection refuses those cross-protocol redirects on
                // some Android and desktop runtimes, which made Kugou's public
                // bridge appear broken.
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", UA);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (referer != null) connection.setRequestProperty("Referer", referer);
                if (extraHeaders != null) {
                    for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location != null && !location.trim().isEmpty()) {
                        URL base = new URL(currentUrl);
                        URL next = new URL(base, location.trim());
                        String protocol = next.getProtocol().toLowerCase(Locale.ROOT);
                        if ("http".equals(protocol) || "https".equals(protocol)) {
                            currentUrl = next.toString();
                            continue;
                        }
                    }
                }

                InputStream input = status >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                byte[] body = readLimited(input, maxBytes);
                String contentEncoding = connection.getHeaderField("Content-Encoding");
                if (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
                    body = gunzip(body, maxBytes);
                } else if (contentEncoding != null
                        && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                    body = gunzip(body, maxBytes);
                }
                HttpResult result = new HttpResult();
                result.status = status;
                result.body = body;
                result.contentType = connection.getContentType();
                result.finalUrl = connection.getURL().toString();
                result.contentLength = connection.getContentLengthLong();
                String contentRange = connection.getHeaderField("Content-Range");
                if (contentRange != null) result.contentRange = contentRange;
                String disposition = connection.getHeaderField("Content-Disposition");
                if (disposition != null) result.contentDisposition = disposition;
                return result;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IOException("Too many redirects for " + url);
    }

    private static HttpResult httpPostJson(String url, String jsonBody,
                                           Map<String, String> extraHeaders,
                                           int maxBytes) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Content-Type", "application/json");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            byte[] body = readLimited(input, maxBytes);
            String contentEncoding = connection.getHeaderField("Content-Encoding");
            if (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
                body = gunzip(body, maxBytes);
            } else if (contentEncoding != null
                    && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                body = gunzip(body, maxBytes);
            }
            HttpResult result = new HttpResult();
            result.status = status;
            result.body = body;
            result.contentType = connection.getContentType();
            result.finalUrl = connection.getURL().toString();
            result.contentLength = connection.getContentLengthLong();
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        if (input == null) return new byte[0];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            int read;
            while (total < maxBytes && (read = input.read(buffer, 0, Math.min(buffer.length,
                    maxBytes - total))) != -1) {
                output.write(buffer, 0, read);
                total += read;
            }
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed, int maxBytes) {
        if (compressed == null || compressed.length == 0) return new byte[0];
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while (total < maxBytes
                    && (read = gzip.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) != -1) {
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (IOException e) {
            return compressed;
        }
    }

    private static AudioProbe probeAudio(String url, Map<String, String> headers) {
        Map<String, String> probeHeaders = new HashMap<>();
        if (headers != null) probeHeaders.putAll(headers);
        probeHeaders.put("Range", "bytes=0-2047");
        try {
            HttpResult result = httpGet(url, probeHeaders, 8192, null);
            boolean statusOk = result.status >= 200 && result.status < 400;
            boolean isAudio = isAudioMime(result.contentType) || looksLikeAudio(result.body);
            boolean ranged = result.status == 206 && result.body.length > 0;
            AudioProbe probe = new AudioProbe();
            probe.ok = statusOk && result.body.length > 0 && (isAudio || ranged);
            probe.finalUrl = result.finalUrl;
            probe.contentType = result.contentType;
            probe.totalBytes = totalBytesFrom(result);
            return probe;
        } catch (Exception e) {
            return new AudioProbe();
        }
    }

    /** Extracts the full object size from a ranged probe without trusting the 2 KB body length. */
    private static long totalBytesFrom(HttpResult result) {
        if (result == null) return 0L;
        String range = result.contentRange;
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                String total = range.substring(slash + 1).trim();
                try {
                    long parsed = Long.parseLong(total);
                    if (parsed > 0L) return parsed;
                } catch (NumberFormatException ignored) {
                    // Content-Range may be "*" when the server cannot report it.
                }
            }
        }
        // A 206 response without a usable Content-Range only reports the probe
        // range length in Content-Length, not the full file.
        if (result.status == 206) return 0L;
        return result.contentLength > 0L ? result.contentLength : 0L;
    }

    private static void putProbeSize(JSONObject out, AudioProbe probe) throws Exception {
        if (probe != null && probe.totalBytes > 0L) {
            out.put("size", probe.totalBytes);
            out.put("sizeExact", true);
        }
    }

    private static boolean isAudioMime(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("audio/") || lower.startsWith("video/mp4")
                || lower.contains("application/ogg") || lower.contains("application/octet-stream");
    }

    private static boolean looksLikeAudio(byte[] data) {
        if (data == null || data.length < 4) return false;
        String head = new String(data, 0, Math.min(data.length, 12), StandardCharsets.ISO_8859_1);
        if (head.startsWith("ID3") || head.startsWith("fLaC") || head.startsWith("OggS")
                || head.startsWith("RIFF")) return true;
        if (data.length > 8 && head.substring(4).startsWith("ftyp")) return true;
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        return b0 == 0xFF && (b1 & 0xE0) == 0xE0;
    }

    private static Object parseJson(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("\uFEFF")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) return null;
        try {
            return new JSONTokener(cleaned).nextValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject parseObject(String text) {
        Object parsed = parseJson(text);
        return parsed instanceof JSONObject ? (JSONObject) parsed : null;
    }

    private static String kugouUrl(String id) {
        return "https://api.qijieya.cn/meting/?server=kugou&type=url&id="
                + enc(id.toLowerCase(Locale.ROOT));
    }

    private static String kugouLyricUrl(String id) {
        return "https://api.qijieya.cn/meting/?server=kugou&type=lrc&id="
                + enc(id.toLowerCase(Locale.ROOT));
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String stripHtml(String value) {
        String text = clean(value);
        if (text.isEmpty()) return "";
        text = text.replaceAll("(?is)<[^>]*>", "");
        text = text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
        return text.trim();
    }

    private static String jamendoCall(String path) {
        String random = String.valueOf(Math.random());
        return "$" + sha1Hex(path + random) + "*" + random + "~";
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                output.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return output.toString();
        } catch (Exception e) {
            return Long.toHexString(System.nanoTime());
        }
    }

    private static String jamendoStream(JSONObject item) {
        JSONObject stream = item.optJSONObject("stream");
        if (stream != null) {
            String[] keys = new String[]{"mp32", "mp3", "ogg"};
            for (String key : keys) {
                String value = clean(stream.optString(key));
                if (value.startsWith("http")) return value;
            }
        }
        JSONObject download = item.optJSONObject("download");
        if (download != null) {
            String[] keys = new String[]{"mp3", "ogg"};
            for (String key : keys) {
                String value = clean(download.optString(key));
                if (value.startsWith("http")) return value;
            }
        }
        return "";
    }

    private static String extractLyric(String body) {
        if (body == null) return "";
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimmed);
                String lyric = json.optString("lrc", "");
                if (lyric.isEmpty()) lyric = json.optString("lyric", "");
                if (lyric.isEmpty() && json.optJSONObject("data") != null) {
                    JSONObject data = json.optJSONObject("data");
                    lyric = data.optString("lrc", "");
                    if (lyric.isEmpty()) lyric = data.optString("lyric", "");
                }
                return lyric;
            } catch (Exception ignored) {
                return trimmed;
            }
        }
        return body;
    }

    private static List<String> parseSources(String csv) {
        String raw = csv == null ? "" : csv.trim();
        if (raw.isEmpty()) raw = "netease,qq,kugou,kuwo,joox,fivesing,jamendo";
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String source = part.trim().toLowerCase(Locale.ROOT);
            if (VALID_SOURCES.contains(source) && !result.contains(source)) {
                result.add(source);
            }
        }
        return result;
    }

    private static String friendlySearchError(String source) {
        String name = SOURCE_NAMES.containsKey(source) ? SOURCE_NAMES.get(source) : source;
        return name + "暂时无法连接，其他来源的结果已为你保留。";
    }

    private static String searchError(String code, String message) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", false);
            object.put("results", new JSONArray());
            object.put("errors", new JSONArray());
            object.put("code", code);
            object.put("error", message);
            return object.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"results\":[],\"errors\":[],\"error\":\""
                    + shorten(message, 120).replace("\"", "'") + "\"}";
        }
    }

    private static String normalizeCover(String cover) {
        String value = clean(cover);
        if (value.startsWith("http://")) {
            return "https://" + value.substring("http://".length());
        }
        return value;
    }

    private static String normalizeKey(String title, String artist) {
        String combined = (title + "|" + artist).toLowerCase(Locale.ROOT);
        return combined.replaceAll("[\\s\\p{Punct}]+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String choose(String first, String fallback) {
        String value = clean(first);
        return value.isEmpty() ? clean(fallback) : value;
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String queryParam(String url, String key) {
        if (url == null) return "";
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(key) + "=([^&]+)")
                .matcher(url);
        if (matcher.find()) {
            try {
                return URLDecoder.decode(matcher.group(1), "UTF-8");
            } catch (Exception ignored) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(clean(value));
            return Math.max(parsed, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Kuwo search advertises every available quality. The app resolves the
     * reliable standard OGG stream first, so show that size in the result row
     * instead of a made-up estimate.
     */
    private static long kuwoStandardSize(JSONObject item) {
        JSONArray types = item.optJSONArray("types");
        if (types == null) return 0L;
        long fallback = 0L;
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.optJSONObject(i);
            if (type == null) continue;
            String format = clean(type.optString("format")).toLowerCase(Locale.ROOT);
            String bitrate = clean(type.optString("bitrate"));
            long bytes = parseSizeLabel(type.optString("size"));
            if (bytes <= 0L) continue;
            if (fallback == 0L) fallback = bytes;
            if ("ogg".equals(format) && "100".equals(bitrate)) return bytes;
            if ("mp3".equals(format) && "128".equals(bitrate)) return bytes;
        }
        return fallback;
    }

    private static JSONObject kuwoQualitySizes(JSONObject item) {
        JSONObject result = new JSONObject();
        JSONArray types = item.optJSONArray("types");
        if (types == null) return result;
        try {
            for (int i = 0; i < types.length(); i++) {
                JSONObject type = types.optJSONObject(i);
                if (type == null) continue;
                String format = clean(type.optString("format")).toLowerCase(Locale.ROOT);
                String bitrate = clean(type.optString("bitrate"));
                long bytes = parseSizeLabel(type.optString("size"));
                if (bytes <= 0L) continue;
                if ("flac".equals(format)) {
                    if (!result.has("lossless")) result.put("lossless", bytes);
                } else if (("ogg".equals(format) && "300".equals(bitrate))
                        || ("mp3".equals(format) && "320".equals(bitrate))) {
                    if (!result.has("high")) result.put("high", bytes);
                } else if (("ogg".equals(format) && "100".equals(bitrate))
                        || ("mp3".equals(format) && "128".equals(bitrate))) {
                    if (!result.has("standard")) result.put("standard", bytes);
                }
            }
        } catch (Exception ignored) {
            // Size hints are optional; the resolver still probes the real file.
        }
        return result;
    }

    private static long parseSizeLabel(String raw) {
        String value = clean(raw).toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.isEmpty()) return 0L;
        int index = 0;
        while (index < value.length()
                && (Character.isDigit(value.charAt(index)) || value.charAt(index) == '.')) {
            index++;
        }
        if (index == 0) return 0L;
        double number;
        try {
            number = Double.parseDouble(value.substring(0, index));
        } catch (NumberFormatException e) {
            return 0L;
        }
        String unit = value.substring(index);
        double multiplier;
        if (unit.isEmpty()) {
            multiplier = 1d;
        } else if (unit.startsWith("g")) {
            multiplier = 1024d * 1024d * 1024d;
        } else if (unit.startsWith("k")) {
            multiplier = 1024d;
        } else if (unit.startsWith("b")) {
            multiplier = 1d;
        } else {
            multiplier = 1024d * 1024d;
        }
        return Math.max(Math.round(number * multiplier), 0L);
    }

    private static int parseDuration(String value) {
        String input = clean(value);
        if (input.isEmpty()) return 0;
        try {
            if (input.contains(":")) {
                String[] parts = input.split(":");
                if (parts.length == 2) {
                    return Integer.parseInt(parts[0].trim()) * 60
                            + Integer.parseInt(parts[1].trim());
                }
                if (parts.length == 3) {
                    return Integer.parseInt(parts[0].trim()) * 3600
                            + Integer.parseInt(parts[1].trim()) * 60
                            + Integer.parseInt(parts[2].trim());
                }
            }
            return Math.max(Integer.parseInt(input), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String normalizeQuality(String value) {
        String quality = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("high".equals(quality) || "hq".equals(quality) || "320".equals(quality)) {
            return "high";
        }
        if ("lossless".equals(quality) || "flac".equals(quality) || "sq".equals(quality)
                || "无损".equals(quality)) {
            return "lossless";
        }
        return "auto".equals(quality) ? "auto" : "standard";
    }

    private static String tierLabel(String tier) {
        if ("lossless".equals(tier)) return "无损";
        if ("high".equals(tier)) return "较高";
        if ("auto".equals(tier)) return "自动";
        return "标准";
    }

    private static String qualityLabelFor(String tier, String ext) {
        String format = ext == null ? "" : ext.trim().toUpperCase(Locale.ROOT);
        return format.isEmpty() ? tierLabel(tier) : tierLabel(tier) + " · " + format;
    }

    /**
     * Prefers URL/format evidence for lossless, then derives a tier from the
     * actual probe size and duration. The hint is only used when the stream is
     * too short or the server does not report its length.
     */
    private static String detectQuality(AudioProbe probe, int durationSeconds,
                                        String ext, String hint) {
        String normalizedHint = normalizeQuality(hint);
        if (probe == null) return "auto".equals(normalizedHint) ? "standard" : normalizedHint;
        String url = probe.finalUrl == null ? "" : probe.finalUrl.toLowerCase(Locale.ROOT);
        boolean losslessExt = "flac".equalsIgnoreCase(ext) || "wav".equalsIgnoreCase(ext)
                || "ape".equalsIgnoreCase(ext) || "aiff".equalsIgnoreCase(ext)
                || "aif".equalsIgnoreCase(ext) || "dsf".equalsIgnoreCase(ext)
                || "dff".equalsIgnoreCase(ext) || url.contains(".flac");
        if (losslessExt) return "lossless";
        if (durationSeconds > 0 && probe.totalBytes > 0L) {
            long kbps = probe.totalBytes * 8L / Math.max(durationSeconds, 1) / 1000L;
            if (kbps >= 700L) return "lossless";
            if (kbps >= 180L) return "high";
            return "standard";
        }
        if ("lossless".equals(normalizedHint)) {
            // A "SQ" endpoint can occasionally return a 320K MP3. Do not call
            // that lossless just because the requested tier was lossless.
            return "high";
        }
        if ("auto".equals(normalizedHint)) return "standard";
        return normalizedHint;
    }

    private static String extension(String contentType, String url, String fallback) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains(".flac")) return "flac";
        if (lower.contains("mpeg") && !lower.contains("mp4")) return "mp3";
        if (lower.contains("mp4") || lower.contains("aac")) return "m4a";
        if (lower.contains("flac")) return "flac";
        if (lower.contains("ogg")) return "ogg";
        if (lower.contains("wav")) return "wav";
        String[] known = new String[]{"flac", "m4a", "mp3", "ogg", "aac", "wav"};
        for (String ext : known) {
            if (lowerUrl.contains("." + ext)) return ext;
        }
        return fallback == null || fallback.isEmpty() ? "mp3" : fallback;
    }

    private static String mimeFor(String contentType, String ext) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (isAudioMime(contentType) && !lower.contains("octet-stream")) {
            return contentType.split(";")[0].trim();
        }
        switch (ext == null ? "" : ext) {
            case "m4a":
                return "audio/mp4";
            case "flac":
                return "audio/flac";
            case "ogg":
                return "audio/ogg";
            case "wav":
                return "audio/wav";
            case "aac":
                return "audio/aac";
            default:
                return "audio/mpeg";
        }
    }

    private static String extensionFromJooxKey(String key) {
        if (key == null) return "mp3";
        if (key.contains("FLAC") || key.contains("无损") || key.contains("母带")) return "flac";
        if (key.startsWith("AAC")) return "m4a";
        if (key.startsWith("OGG")) return "ogg";
        return "mp3";
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /**
     * Kuwo's mobile convert-url endpoint obfuscates its query with the same
     * DES-like block cipher used by musicdl's KuwoMusicClientUtils. Only the
     * encrypt direction is needed here; the tables and transformations are a
     * direct Java port of the upstream Python implementation.
     */
    private static final class KuwoCrypto {
        private static final byte[] KEY = "ylzsxkwm".getBytes(StandardCharsets.UTF_8);
        private static final int[] ARRAYLS = {
                1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1
        };
        private static final long[] ARRAYLSMASK = {0L, 0x100001L, 0x300003L};
        private static final int[] ARRAYE = {
                31, 0, 1, 2, 3, 4, -1, -1, 3, 4, 5, 6, 7, 8, -1, -1,
                7, 8, 9, 10, 11, 12, -1, -1, 11, 12, 13, 14, 15, 16, -1, -1,
                15, 16, 17, 18, 19, 20, -1, -1, 19, 20, 21, 22, 23, 24, -1, -1,
                23, 24, 25, 26, 27, 28, -1, -1, 27, 28, 29, 30, 31, 30, -1, -1
        };
        private static final int[] ARRAYIP1 = {
                39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
                37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
                35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
                33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24
        };
        private static final int[] ARRAYIP2 = {
                57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
                61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
                56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
                60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6
        };
        private static final int[] ARRAYP = {
                15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
                1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24
        };
        private static final int[] ARRAYPC1 = {
                56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
                58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 62, 54, 46, 38,
                30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36,
                28, 20, 12, 4, 27, 19, 11, 3
        };
        private static final int[] ARRAYPC2 = {
                13, 16, 10, 23, 0, 4, -1, -1, 2, 27, 14, 5, 20, 9, -1, -1,
                22, 18, 11, 3, 25, 7, -1, -1, 15, 6, 26, 19, 12, 1, -1, -1,
                40, 51, 30, 36, 46, 54, -1, -1, 29, 39, 50, 44, 32, 47, -1, -1,
                43, 48, 38, 55, 33, 52, -1, -1, 45, 41, 49, 35, 28, 31, -1, -1
        };
        private static final int[][] MATRIXNSBOX = {
                {14, 4, 3, 15, 2, 13, 5, 3, 13, 14, 6, 9, 11, 2, 0, 5, 4, 1, 10, 12, 15, 6, 9, 10, 1, 8, 12, 7, 8, 11, 7, 0, 0, 15, 10, 5, 14, 4, 9, 10, 7, 8, 12, 3, 13, 1, 3, 6, 15, 12, 6, 11, 2, 9, 5, 0, 4, 2, 11, 14, 1, 7, 8, 13},
                {15, 0, 9, 5, 6, 10, 12, 9, 8, 7, 2, 12, 3, 13, 5, 2, 1, 14, 7, 8, 11, 4, 0, 3, 14, 11, 13, 6, 4, 1, 10, 15, 3, 13, 12, 11, 15, 3, 6, 0, 4, 10, 1, 7, 8, 4, 11, 14, 13, 8, 0, 6, 2, 15, 9, 5, 7, 1, 10, 12, 14, 2, 5, 9},
                {10, 13, 1, 11, 6, 8, 11, 5, 9, 4, 12, 2, 15, 3, 2, 14, 0, 6, 13, 1, 3, 15, 4, 10, 14, 9, 7, 12, 5, 0, 8, 7, 13, 1, 2, 4, 3, 6, 12, 11, 0, 13, 5, 14, 6, 8, 15, 2, 7, 10, 8, 15, 4, 9, 11, 5, 9, 0, 14, 3, 10, 7, 1, 12},
                {7, 10, 1, 15, 0, 12, 11, 5, 14, 9, 8, 3, 9, 7, 4, 8, 13, 6, 2, 1, 6, 11, 12, 2, 3, 0, 5, 14, 10, 13, 15, 4, 13, 3, 4, 9, 6, 10, 1, 12, 11, 0, 2, 5, 0, 13, 14, 2, 8, 15, 7, 4, 15, 1, 10, 7, 5, 6, 12, 11, 3, 8, 9, 14},
                {2, 4, 8, 15, 7, 10, 13, 6, 4, 1, 3, 12, 11, 7, 14, 0, 12, 2, 5, 9, 10, 13, 0, 3, 1, 11, 15, 5, 6, 8, 9, 14, 14, 11, 5, 6, 4, 1, 3, 10, 2, 12, 15, 0, 13, 2, 8, 5, 11, 8, 0, 15, 7, 14, 9, 4, 12, 7, 10, 9, 1, 13, 6, 3},
                {12, 9, 0, 7, 9, 2, 14, 1, 10, 15, 3, 4, 6, 12, 5, 11, 1, 14, 13, 0, 2, 8, 7, 13, 15, 5, 4, 10, 8, 3, 11, 6, 10, 4, 6, 11, 7, 9, 0, 6, 4, 2, 13, 1, 9, 15, 3, 8, 15, 3, 1, 14, 12, 5, 11, 0, 2, 12, 14, 7, 5, 10, 8, 13},
                {4, 1, 3, 10, 15, 12, 5, 0, 2, 11, 9, 6, 8, 7, 6, 9, 11, 4, 12, 15, 0, 3, 10, 5, 14, 13, 7, 8, 13, 14, 1, 2, 13, 6, 14, 9, 4, 1, 2, 14, 11, 13, 5, 0, 1, 10, 8, 3, 0, 11, 3, 5, 9, 4, 15, 2, 7, 8, 12, 15, 10, 7, 6, 12},
                {13, 7, 10, 0, 6, 9, 5, 15, 8, 4, 3, 10, 11, 14, 12, 5, 2, 11, 9, 6, 15, 12, 0, 3, 4, 1, 14, 13, 1, 2, 7, 8, 1, 2, 12, 15, 10, 4, 0, 3, 13, 14, 6, 9, 7, 8, 9, 6, 15, 1, 5, 12, 3, 10, 14, 5, 8, 7, 11, 0, 4, 13, 2, 11}
        };

        private static final long[] ARRAYMASK = buildMasks();

        private static long[] buildMasks() {
            long[] masks = new long[64];
            for (int i = 0; i < 64; i++) masks[i] = 1L << i;
            return masks;
        }

        static String encryptQuery(String query) {
            byte[] encrypted = crypt(query.getBytes(StandardCharsets.UTF_8), true);
            return Base64.getEncoder().encodeToString(encrypted);
        }

        private static byte[] crypt(byte[] message, boolean encrypt) {
            long keyLong = 0L;
            for (int i = 0; i < 8 && i < KEY.length; i++) {
                keyLong |= ((long) (KEY[i] & 0xFF)) << (i * 8);
            }
            long[] subKeys = new long[16];
            subKeys(keyLong, subKeys, encrypt ? 0 : 1);

            int blocks = message.length / 8;
            long[] output = new long[blocks + 1];
            long[] input = new long[blocks];
            for (int block = 0; block < blocks; block++) {
                long value = 0L;
                for (int i = 0; i < 8; i++) {
                    value |= ((long) (message[block * 8 + i] & 0xFF)) << (i * 8);
                }
                input[block] = value;
            }
            for (int block = 0; block < blocks; block++) {
                output[block] = des64(subKeys, input[block]);
            }
            int remaining = message.length - blocks * 8;
            long tail = 0L;
            for (int i = 0; i < remaining; i++) {
                tail |= ((long) (message[blocks * 8 + i] & 0xFF)) << (i * 8);
            }
            if (remaining != 0 || encrypt) output[blocks] = des64(subKeys, tail);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(output.length * 8);
            for (long value : output) {
                for (int i = 0; i < 8; i++) {
                    bytes.write((int) ((value >>> (i * 8)) & 0xFF));
                }
            }
            return bytes.toByteArray();
        }

        private static void subKeys(long key, long[] subKeys, int mode) {
            long state = bitTransform(ARRAYPC1, 56, key);
            for (int i = 0; i < 16; i++) {
                int rotation = ARRAYLS[i];
                long mask = ARRAYLSMASK[rotation];
                state = ((state & mask) << (28 - rotation))
                        | ((state & ~mask) >>> rotation);
                subKeys[i] = bitTransform(ARRAYPC2, 64, state);
            }
            if (mode == 1) {
                for (int i = 0; i < 8; i++) {
                    long tmp = subKeys[i];
                    subKeys[i] = subKeys[15 - i];
                    subKeys[15 - i] = tmp;
                }
            }
        }

        private static long des64(long[] keys, long value) {
            long out = bitTransform(ARRAYIP2, 64, value);
            long left = out & 0xFFFFFFFFL;
            long right = (out >>> 32) & 0xFFFFFFFFL;
            for (int round = 0; round < 16; round++) {
                long mixed = bitTransform(ARRAYE, 64, right) ^ keys[round];
                int[] nibbles = new int[8];
                for (int i = 0; i < 8; i++) {
                    nibbles[i] = (int) ((mixed >>> (i * 8)) & 0xFF);
                }
                long swapped = 0L;
                for (int i = 7; i >= 0; i--) {
                    swapped = (swapped << 4) | (MATRIXNSBOX[i][nibbles[i]] & 0xF);
                }
                long nextLeft = right;
                long nextRight = (left ^ bitTransform(ARRAYP, 32, swapped)) & 0xFFFFFFFFL;
                left = nextLeft;
                right = nextRight;
            }
            long swapped = ((left << 32) & 0xFFFFFFFF00000000L) | (right & 0xFFFFFFFFL);
            return bitTransform(ARRAYIP1, 64, swapped);
        }

        private static long bitTransform(int[] source, int count, long value) {
            long result = 0L;
            for (int i = 0; i < count; i++) {
                int index = source[i];
                if (index >= 0 && (value & ARRAYMASK[index]) != 0L) {
                    result |= ARRAYMASK[i];
                }
            }
            return result;
        }
    }

    private static final class HttpResult {
        int status;
        byte[] body = new byte[0];
        String contentType;
        String finalUrl = "";
        String contentDisposition = "";
        String contentRange = "";
        long contentLength = -1L;

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        String text() {
            String value = new String(body, StandardCharsets.UTF_8);
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        }
    }

    private static final class AudioProbe {
        boolean ok;
        String finalUrl = "";
        String contentType;
        long totalBytes;
    }

    private static final class ApiException extends Exception {
        final String code;

        ApiException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
