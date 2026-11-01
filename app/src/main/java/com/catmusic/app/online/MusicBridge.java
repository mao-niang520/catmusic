package com.catmusic.app.online;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Narrow JavaScript bridge. It exposes only search, resolve, download and a
 * couple of safe helpers to the bundled local page.
 */
public final class MusicBridge {

    private static final String TAG = "CatMusicOnline";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String DOWNLOAD_DIR = "CatMusic";
    private static final long MAX_AUDIO_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_COVER_BYTES = 12L * 1024L * 1024L;

    public interface OnlinePlaybackListener {
        void onOnlinePlaybackStarted();
    }

    private final Context appContext;
    private final WebView webView;
    private final ExecutorService executor;
    private final OnlinePlaybackListener playbackListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MusicBridge(Context context, WebView webView) {
        this(context, webView, null);
    }

    public MusicBridge(Context context, WebView webView,
                       OnlinePlaybackListener playbackListener) {
        this.appContext = context.getApplicationContext();
        this.webView = webView;
        this.playbackListener = playbackListener;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "musicdl-bridge");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The local Media3 queue keeps playing while the user browses the Online
     * tab. It pauses only at this point, when an online preview really starts,
     * so switching tabs is uninterrupted and the two players never overlap.
     */
    @JavascriptInterface
    public void pauseLocalPlayback() {
        OnlinePlaybackListener listener = playbackListener;
        if (listener != null) {
            mainHandler.post(listener::onOnlinePlaybackStarted);
        }
    }

    @JavascriptInterface
    public void search(final String requestId, final String keyword, final String sources) {
        executor.execute(() -> {
            String response;
            try {
                response = MusicApi.search(keyword, sources);
            } catch (Throwable t) {
                Log.e(TAG, "Search failed", t);
                response = errorJson("internal", "搜索失败，请稍后再试。");
            }
            callJs("onSearchResult", requestId, response);
        });
    }

    @JavascriptInterface
    public void resolve(final String requestId, final String trackJson) {
        executor.execute(() -> {
            String response;
            try {
                response = MusicApi.resolve(trackJson);
            } catch (Throwable t) {
                Log.e(TAG, "Resolve failed", t);
                response = errorJson("resolve", "获取播放地址失败，请稍后重试。");
            }
            callJs("onResolveResult", requestId, response);
        });
    }

    @JavascriptInterface
    public void resolveQuality(final String requestId, final String trackJson,
                               final String quality) {
        executor.execute(() -> {
            String response;
            try {
                response = MusicApi.resolve(trackJson, quality);
            } catch (Throwable t) {
                Log.e(TAG, "Quality resolve failed", t);
                response = errorJson("resolve", "获取该音质失败，请稍后重试。");
            }
            callJs("onResolveResult", requestId, response);
        });
    }

    @JavascriptInterface
    public void download(final String trackJson) {
        executor.execute(() -> {
            try {
                JSONObject track = new JSONObject(trackJson);
                String url = track.optString("url");
                if (!isHttpUrl(url)) {
                    showToast("下载地址无效，请重新播放后再试。");
                    return;
                }
                String title = safe(track.optString("title"), "未命名歌曲");
                String artist = safe(track.optString("artist"), "未知歌手");
                String source = track.optString("source");
                String ext = normalizeExtension(track.optString("ext"));
                String fileName = sanitizeFileName(artist + " - " + title) + "." + ext;
                if (fileName.length() > 120) {
                    fileName = fileName.substring(0, 116) + "." + ext;
                }

                String mime = track.optString("mime");
                if (mime.isEmpty() || !mime.startsWith("audio/")) {
                    mime = mimeForExtension(ext);
                }

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(title);
                request.setDescription("CatMusic · 在线音乐 · " + artist);
                request.setMimeType(mime);
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(false);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                );
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_MUSIC, DOWNLOAD_DIR + "/" + fileName
                    );
                } else {
                    // API 26-28: app-scoped external storage avoids the legacy
                    // WRITE_EXTERNAL_STORAGE runtime permission.
                    request.setDestinationInExternalFilesDir(
                            appContext, Environment.DIRECTORY_MUSIC,
                            DOWNLOAD_DIR + "/" + fileName
                    );
                }
                request.addRequestHeader("User-Agent", UA);
                String referer = refererFor(source);
                if (referer != null) {
                    request.addRequestHeader("Referer", referer);
                }

                DownloadManager manager =
                        (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) {
                    showToast("系统下载服务不可用。");
                    return;
                }
                manager.enqueue(request);
                callJsRaw("onDownloadQueued", track.optString("uiId"));
            } catch (Throwable t) {
                Log.e(TAG, "Download failed", t);
                showToast("下载失败，请稍后再试。");
            }
        });
    }

    /**
     * Downloads a resolved online track into CatMusic's private library.
     * Audio is written to the bridge's executor thread, then handed to Kotlin
     * for cover/LRC import and MusicRepository insertion. Progress is sent back
     * to the page so the save sheet can show a live percentage.
     */
    @JavascriptInterface
    public void downloadToLibrary(final String requestId, final String trackJson,
                                  final boolean includeLyrics) {
        final String id = requestId == null ? "" : requestId;
        executor.execute(() -> {
            File audio = null;
            File cover = null;
            try {
                JSONObject track = new JSONObject(trackJson);
                String url = track.optString("url");
                if (!isHttpUrl(url)) {
                    callJs("onLibraryError", id, "下载地址无效，请重新播放后再试。");
                    return;
                }
                String source = track.optString("source");
                String referer = refererFor(source);
                sendLibraryProgress(id, "audio", 0L, -1L);
                audio = downloadFileToCache(
                        url, "online-audio-", ".part", referer,
                        MAX_AUDIO_BYTES, "audio", id
                );

                String coverUrl = track.optString("cover");
                if (isHttpUrl(coverUrl)) {
                    try {
                        sendLibraryProgress(id, "cover", 0L, -1L);
                        cover = downloadFileToCache(
                                coverUrl, "online-cover-", ".img", referer,
                                MAX_COVER_BYTES, "cover", id
                        );
                    } catch (Throwable coverError) {
                        // Artwork is optional; the track still imports without it.
                        Log.w(TAG, "Online cover download failed", coverError);
                    }
                }

                sendLibraryProgress(id, "import", 0L, -1L);
                String result = OnlineLibraryImporter.importDownloaded(
                        appContext, trackJson, audio, cover, includeLyrics
                );
                callJs("onLibraryResult", id, result);
            } catch (Throwable t) {
                Log.e(TAG, "Could not save the online track into the library", t);
                callJs("onLibraryError", id, friendlyDownloadError(t));
            } finally {
                if (audio != null) audio.delete();
                if (cover != null) cover.delete();
            }
        });
    }

    @JavascriptInterface
    public void openExternal(final String url) {
        if (!isHttpUrl(url)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Exception ignored) {
            showToast("无法打开外部链接。");
        }
    }

    @JavascriptInterface
    public void toast(final String message) {
        showToast(message);
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private File downloadFileToCache(String url, String prefix, String suffix,
                                     String referer, long maxBytes,
                                     String phase, String requestId) throws IOException {
        File temp = File.createTempFile(prefix, suffix, appContext.getCacheDir());
        boolean keep = false;
        try {
            String currentUrl = url;
            for (int redirects = 0; redirects < 8; redirects++) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(currentUrl).openConnection();
                    connection.setConnectTimeout(20_000);
                    connection.setReadTimeout(45_000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", UA);
                    connection.setRequestProperty("Accept", "*/*");
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    if (referer != null) {
                        connection.setRequestProperty("Referer", referer);
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
                        throw new IOException("Unsupported redirect");
                    }
                    if (status < 200 || status >= 300) {
                        throw new IOException("HTTP " + status);
                    }

                    long total = connection.getContentLengthLong();
                    if (total > maxBytes) {
                        throw new IOException("File is too large");
                    }
                    InputStream input = connection.getInputStream();
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[64 * 1024];
                        long received = 0L;
                        long lastUpdate = 0L;
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            received += read;
                            if (received > maxBytes) {
                                throw new IOException("File is too large");
                            }
                            output.write(buffer, 0, read);
                            long now = System.currentTimeMillis();
                            if (now - lastUpdate >= 250L) {
                                lastUpdate = now;
                                sendLibraryProgress(requestId, phase, received, total);
                            }
                        }
                        output.flush();
                        sendLibraryProgress(
                                requestId, phase, received, total > 0L ? total : received
                        );
                    } finally {
                        try {
                            input.close();
                        } catch (IOException ignored) {
                        }
                    }
                    keep = true;
                    return temp;
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            throw new IOException("Too many redirects");
        } finally {
            if (!keep) temp.delete();
        }
    }

    private void sendLibraryProgress(String requestId, String phase, long received, long total) {
        String js = "window.AppNative&&window.AppNative.onLibraryProgress("
                + JSONObject.quote(requestId) + "," + JSONObject.quote(phase)
                + "," + received + "," + total + ");";
        runOnWebView(() -> webView.evaluateJavascript(js, null));
    }

    private static String friendlyDownloadError(Throwable error) {
        if (error instanceof IOException) {
            String message = error.getMessage();
            if (message != null && message.contains("too large")) {
                return "音频文件过大，已停止保存。";
            }
            return "下载失败，请检查网络后重试。";
        }
        return "保存失败，请稍后再试。";
    }

    private void callJs(String callback, String requestId, String json) {
        String js = "window.AppNative&&window.AppNative." + callback + "("
                + JSONObject.quote(requestId) + "," + JSONObject.quote(json) + ");";
        runOnWebView(() -> webView.evaluateJavascript(js, null));
    }

    private void callJsRaw(String callback, String value) {
        String js = "window.AppNative&&window.AppNative." + callback + "("
                + JSONObject.quote(value) + ");";
        runOnWebView(() -> webView.evaluateJavascript(js, null));
    }

    private void showToast(String message) {
        runOnWebView(() -> callJsRaw("onToast", message));
    }

    private void runOnWebView(Runnable action) {
        webView.post(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.w(TAG, "JS callback failed", t);
            }
        });
    }

    private static String errorJson(String code, String message) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", false);
            object.put("code", code);
            object.put("error", message);
            return object.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"操作失败\"}";
        }
    }

    private static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static String safe(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static String sanitizeFileName(String value) {
        String cleaned = value == null ? "" : value;
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1).trim();
        return cleaned.isEmpty() ? "MusicDL 音频" : cleaned;
    }

    private static String normalizeExtension(String ext) {
        String value = ext == null ? "" : ext.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "mp3":
            case "m4a":
            case "flac":
            case "ogg":
            case "aac":
            case "wav":
                return value;
            default:
                return "mp3";
        }
    }

    private static String mimeForExtension(String ext) {
        switch (ext) {
            case "m4a":
                return "audio/mp4";
            case "flac":
                return "audio/flac";
            case "ogg":
                return "audio/ogg";
            case "aac":
                return "audio/aac";
            case "wav":
                return "audio/wav";
            default:
                return "audio/mpeg";
        }
    }

    private static String refererFor(String source) {
        if ("netease".equals(source)) return "https://music.163.com/";
        if ("qq".equals(source)) return "https://y.qq.com/";
        if ("kugou".equals(source)) return "https://www.kugou.com/";
        if ("kuwo".equals(source)) return "https://www.kuwo.cn/";
        if ("joox".equals(source)) return "https://www.joox.com/";
        if ("fivesing".equals(source)) return "https://5sing.kugou.com/";
        if ("jamendo".equals(source)) return "https://www.jamendo.com/";
        return null;
    }
}
