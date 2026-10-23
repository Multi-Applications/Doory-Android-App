package de.multiapplications.doory;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public final class UpdateManager {

    public static final String BUNDLED_RELEASE_TAG = "v0.1.0";

    private static final String GITHUB_REPO = "shaongitbd/doory";
    private static final String REGISTRY = "https://ghcr.io";
    private static final String IMAGE_REPOSITORY = "shaongitbd/doory-frontend";
    private static final String HTML_PREFIX = "usr/share/nginx/html/";

    private static final String PREFS = "doory_updates";
    private static final String KEY_ACTIVE_TAG = "active_tag";
    private static final String KEY_PREVIOUS_TAG = "previous_tag";
    private static final String KEY_LAST_CHECK = "last_check";

    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long ERROR_RETRY_MS = 60L * 60L * 1000L;
    private static final String ACCEPT_MANIFEST = "application/vnd.oci.image.index.v1+json, "
            + "application/vnd.docker.distribution.manifest.list.v2+json, "
            + "application/vnd.oci.image.manifest.v1+json, "
            + "application/vnd.docker.distribution.manifest.v2+json";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public enum Status {
        NO_UPDATE,
        UPDATE_READY,
        OFFLINE,
        ERROR,
        BUSY
    }

    public static final class Result {
        public final Status status;
        public final String installedTag;
        public final String latestTag;
        public final String message;

        Result(Status status, String installedTag, String latestTag, String message) {
            this.status = status;
            this.installedTag = installedTag;
            this.latestTag = latestTag;
            this.message = message;
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    public static final class WebInfo {
        public final String tag;
        public final String fetchedAt;
        public final long files;
        public final long bytes;
        public final boolean installedUpdate;

        WebInfo(String tag, String fetchedAt, long files, long bytes,
                boolean installedUpdate) {
            this.tag = tag;
            this.fetchedAt = fetchedAt;
            this.files = files;
            this.bytes = bytes;
            this.installedUpdate = installedUpdate;
        }
    }

    private static final class Release {
        final String tag;
        final String commit;
        final String publishedAt;

        Release(String tag, String commit, String publishedAt) {
            this.tag = tag;
            this.commit = commit;
            this.publishedAt = publishedAt;
        }
    }

    private UpdateManager() {
    }

    public static String getBundledTag() {
        return BUNDLED_RELEASE_TAG;
    }

    public static String getActiveTag(Context context) {
        SharedPreferences preferences = preferences(context);
        String tag = preferences.getString(KEY_ACTIVE_TAG, BUNDLED_RELEASE_TAG);
        if (isBlank(tag)) {
            return BUNDLED_RELEASE_TAG;
        }
        File root = webRoot(context, tag);
        return root == null ? BUNDLED_RELEASE_TAG : tag;
    }

    public static File getActiveWebRoot(Context context) {
        String tag = preferences(context).getString(KEY_ACTIVE_TAG,
                BUNDLED_RELEASE_TAG);
        return webRoot(context, tag);
    }

    public static File getPreviousWebRoot(Context context) {
        String tag = preferences(context).getString(KEY_PREVIOUS_TAG, "");
        return webRoot(context, tag);
    }

    public static WebInfo getWebInfo(Context context) {
        File root = getActiveWebRoot(context);
        if (root != null) {
            try {
                JSONObject metadata = new JSONObject(readUtf8(
                        new File(root, "update.json")));
                return new WebInfo(
                        metadata.optString("tag", getActiveTag(context)),
                        metadata.optString("fetchedAt", ""),
                        metadata.optLong("files", 0),
                        metadata.optLong("bytes", 0),
                        true);
            } catch (Exception ignored) {
                return new WebInfo(getActiveTag(context), "", 0, 0, true);
            }
        }

        String fetchedAt = "";
        long files = 0;
        long bytes = 0;
        try (InputStream stream = context.getAssets()
                .open("www/offline-snapshot.json")) {
            JSONObject metadata = new JSONObject(new String(readAll(stream, 1024 * 1024),
                    StandardCharsets.UTF_8));
            fetchedAt = metadata.optString("fetchedAt", "");
            files = metadata.optLong("files", 0);
            bytes = metadata.optLong("bytes", 0);
        } catch (Exception ignored) {
        }
        return new WebInfo(BUNDLED_RELEASE_TAG, fetchedAt, files, bytes, false);
    }

    public static void checkForUpdates(Context context, boolean manual,
                                       Callback callback) {
        Context app = context.getApplicationContext();

        if (!isOnline(app)) {
            if (manual) {
                deliver(callback, new Result(Status.OFFLINE, getActiveTag(app),
                        null, null));
            }
            return;
        }

        SharedPreferences preferences = preferences(app);
        long lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L);
        if (!manual && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }

        if (!RUNNING.compareAndSet(false, true)) {
            if (manual) {
                deliver(callback, new Result(Status.BUSY, getActiveTag(app),
                        null, null));
            }
            return;
        }

        Thread worker = new Thread(() -> {
            Result result;
            cleanupInterruptedUpdates(app);
            try {
                Release release = fetchLatestRelease();
                String installedTag = getActiveTag(app);
                if (compareVersions(release.tag, installedTag) <= 0) {
                    result = new Result(Status.NO_UPDATE, installedTag,
                            release.tag, null);
                } else {
                    File root = downloadAndExtract(app, release);
                    String newTag = activate(app, release.tag, root);
                    result = new Result(Status.UPDATE_READY, newTag,
                            release.tag, null);
                }
                preferences(app).edit()
                        .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                        .apply();
            } catch (Exception exception) {
                preferences(app).edit()
                        .putLong(KEY_LAST_CHECK, System.currentTimeMillis()
                                - CHECK_INTERVAL_MS + ERROR_RETRY_MS)
                        .apply();
                result = new Result(Status.ERROR, getActiveTag(app), null,
                        friendlyError(exception));
            } finally {
                RUNNING.set(false);
            }
            deliver(callback, result);
        }, "doory-update-check");
        worker.setDaemon(true);
        worker.start();
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void deliver(Callback callback, Result result) {
        if (callback == null) {
            return;
        }
        MAIN_HANDLER.post(() -> callback.onResult(result));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File webBase(Context context) {
        return new File(context.getFilesDir(), "web");
    }

    private static File webRoot(Context context, String tag) {
        if (isBlank(tag)) {
            return null;
        }
        File directory = new File(webBase(context), safeTag(tag));
        return new File(directory, "index.html").isFile() ? directory : null;
    }

    private static Release fetchLatestRelease() throws IOException {
        String url = "https://api.github.com/repos/" + GITHUB_REPO
                + "/releases/latest";
        HttpURLConnection connection = openConnection(url, null,
                "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            String body = readBody(connection, code);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API HTTP " + code);
            }
            JSONObject json = new JSONObject(body);
            String tag = json.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                throw new IOException("GitHub release has no tag");
            }
            return new Release(tag,
                    json.optString("target_commitish", ""),
                    json.optString("published_at", ""));
        } catch (org.json.JSONException exception) {
            throw new IOException("GitHub response could not be read", exception);
        } finally {
            connection.disconnect();
        }
    }

    private static File downloadAndExtract(Context context, Release release)
            throws Exception {
        if (context.getCacheDir().getUsableSpace() < 40L * 1024L * 1024L) {
            throw new IOException("Not enough free storage");
        }

        String token = fetchRegistryToken();
        JSONObject manifest = fetchImageManifest(release.tag, token);
        JSONObject config = fetchConfig(manifest, token);

        JSONArray layers = manifest.getJSONArray("layers");
        int layerIndex = findWebLayer(config, layers);
        if (layerIndex < 0 || layerIndex >= layers.length()) {
            throw new IOException("No web build found in the frontend image");
        }

        JSONObject layer = layers.getJSONObject(layerIndex);
        String digest = layer.getString("digest");
        File archive = downloadBlob(context, digest, token);
        try {
            return extractArchive(context, archive, release, digest);
        } finally {
            if (archive.exists() && !archive.delete()) {
                archive.deleteOnExit();
            }
        }
    }

    private static String fetchRegistryToken() throws IOException {
        String scope = "repository:" + IMAGE_REPOSITORY + ":pull";
        String url = REGISTRY + "/token?service=ghcr.io&scope="
                + URLEncoder.encode(scope, StandardCharsets.UTF_8.name());
        HttpURLConnection connection = openConnection(url, null,
                "application/json");
        try {
            int code = connection.getResponseCode();
            String body = readBody(connection, code);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GHCR token HTTP " + code);
            }
            String token = new JSONObject(body).optString("token", "");
            if (token.isEmpty()) {
                throw new IOException("GHCR token missing");
            }
            return token;
        } catch (org.json.JSONException exception) {
            throw new IOException("GHCR token response could not be read",
                    exception);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject fetchImageManifest(String releaseTag, String token)
            throws Exception {
        List<String> candidates = new ArrayList<>();
        if (releaseTag.startsWith("v") || releaseTag.startsWith("V")) {
            candidates.add(releaseTag.substring(1));
        }
        candidates.add(releaseTag);

        IOException lastError = null;
        for (String candidate : candidates) {
            try {
                JSONObject document = fetchManifestDocument(candidate, token);
                if (document.has("manifests")) {
                    String digest = chooseManifestDigest(
                            document.getJSONArray("manifests"));
                    if (digest == null) {
                        throw new IOException("No matching image found");
                    }
                    return fetchManifestDocument(digest, token);
                }
                if (document.has("layers")) {
                    return document;
                }
                throw new IOException("Invalid image manifest");
            } catch (Exception exception) {
                lastError = exception instanceof IOException
                        ? (IOException) exception
                        : new IOException("Could not read image manifest",
                                exception);
            }
        }
        throw lastError == null
                ? new IOException("Frontend image not found") : lastError;
    }

    private static JSONObject fetchManifestDocument(String reference, String token)
            throws Exception {
        String url = REGISTRY + "/v2/" + IMAGE_REPOSITORY + "/manifests/"
                + reference;
        HttpURLConnection connection = openConnection(url, token,
                ACCEPT_MANIFEST);
        try {
            int code = connection.getResponseCode();
            String body = readBody(connection, code);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GHCR manifest HTTP " + code);
            }
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private static String chooseManifestDigest(JSONArray manifests)
            throws org.json.JSONException {
        String fallback = null;
        for (int index = 0; index < manifests.length(); index++) {
            JSONObject manifest = manifests.getJSONObject(index);
            String digest = manifest.optString("digest", "");
            if (digest.isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = digest;
            }
            JSONObject platform = manifest.optJSONObject("platform");
            if (platform == null) {
                continue;
            }
            String architecture = platform.optString("architecture", "");
            String os = platform.optString("os", "");
            if ("amd64".equals(architecture) && "linux".equals(os)) {
                return digest;
            }
        }
        return fallback;
    }

    private static JSONObject fetchConfig(JSONObject manifest, String token)
            throws Exception {
        JSONObject descriptor = manifest.getJSONObject("config");
        String digest = descriptor.getString("digest");
        return new JSONObject(fetchBlobText(digest, token, 1024 * 1024));
    }

    private static String fetchBlobText(String digest, String token, int limit)
            throws Exception {
        String url = REGISTRY + "/v2/" + IMAGE_REPOSITORY + "/blobs/" + digest;
        HttpURLConnection connection = openConnection(url, token,
                "application/octet-stream");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GHCR blob HTTP " + code);
            }
            try (InputStream input = connection.getInputStream()) {
                return new String(readAll(input, limit), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static int findWebLayer(JSONObject config, JSONArray layers) {
        JSONObject rootfs = config.optJSONObject("rootfs");
        JSONArray diffIds = rootfs == null ? null : rootfs.optJSONArray("diff_ids");
        JSONArray history = config.optJSONArray("history");
        if (diffIds == null || history == null
                || diffIds.length() != layers.length()) {
            return layers.length() - 1;
        }

        int layerIndex = 0;
        int webLayer = -1;
        for (int index = 0; index < history.length(); index++) {
            JSONObject step = history.optJSONObject(index);
            if (step == null || step.optBoolean("empty_layer", false)) {
                continue;
            }
            if (layerIndex >= layers.length()) {
                break;
            }
            String createdBy = step.optString("created_by", "")
                    .toLowerCase(Locale.ROOT);
            if (createdBy.contains(HTML_PREFIX)
                    || (createdBy.contains("copy") && createdBy.contains("dist"))) {
                webLayer = layerIndex;
            }
            layerIndex++;
        }
        return webLayer >= 0 ? webLayer : layers.length() - 1;
    }

    private static File downloadBlob(Context context, String digest, String token)
            throws Exception {
        if (!digest.startsWith("sha256:")) {
            throw new IOException("Unsupported layer digest");
        }
        String expected = digest.substring("sha256:".length());

        File target = File.createTempFile("doory-web-", ".tar.gz",
                context.getCacheDir());
        String url = REGISTRY + "/v2/" + IMAGE_REPOSITORY + "/blobs/" + digest;
        HttpURLConnection connection = openConnection(url, token,
                "application/octet-stream");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GHCR layer HTTP " + code);
            }
            try (InputStream input = new BufferedInputStream(
                    connection.getInputStream(), 64 * 1024);
                 OutputStream output = new BufferedOutputStream(
                         new FileOutputStream(target), 64 * 1024)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                }
            }

            String actual = toHex(sha256.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("Layer checksum does not match");
            }
            return target;
        } catch (Exception exception) {
            if (target.exists() && !target.delete()) {
                target.deleteOnExit();
            }
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    private static File extractArchive(Context context, File archive,
                                       Release release, String layerDigest)
            throws Exception {
        File base = webBase(context);
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("Could not create update directory");
        }

        File temporary = new File(base, ".tmp-" + safeTag(release.tag) + "-"
                + System.nanoTime());
        if (!temporary.mkdirs()) {
            throw new IOException("Could not create temporary update directory");
        }

        int files = 0;
        long bytes = 0;
        try {
            try (InputStream fileInput = new FileInputStream(archive);
                 GZIPInputStream gzip = new GZIPInputStream(
                         new BufferedInputStream(fileInput, 64 * 1024));
                 TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    String name = entry.getName();
                    if (name.startsWith("./")) {
                        name = name.substring(2);
                    }
                    if (!name.startsWith(HTML_PREFIX)) {
                        continue;
                    }
                    String relative = name.substring(HTML_PREFIX.length());
                    if (relative.isEmpty()) {
                        continue;
                    }

                    File outputFile = new File(temporary, relative);
                    String rootPath = temporary.getCanonicalPath();
                    if (!outputFile.getCanonicalPath()
                            .startsWith(rootPath + File.separator)) {
                        throw new IOException("Unsafe path in update");
                    }

                    if (entry.isDirectory()) {
                        if (!outputFile.isDirectory() && !outputFile.mkdirs()) {
                            throw new IOException("Could not create directory");
                        }
                        continue;
                    }
                    if (!entry.isFile()) {
                        continue;
                    }

                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Could not create directory");
                    }
                    try (OutputStream output = new BufferedOutputStream(
                            new FileOutputStream(outputFile), 64 * 1024)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = tar.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                    files++;
                    bytes += Math.max(0L, entry.getSize());
                }
            }

            if (!new File(temporary, "index.html").isFile()
                    || !new File(temporary, "assets").isDirectory()) {
                throw new IOException("Web build is incomplete");
            }

            JSONObject metadata = new JSONObject();
            metadata.put("tag", release.tag);
            metadata.put("commit", release.commit);
            metadata.put("publishedAt", release.publishedAt);
            metadata.put("fetchedAt", isoNow());
            metadata.put("files", files);
            metadata.put("bytes", bytes);
            metadata.put("layerDigest", layerDigest);
            metadata.put("source", "ghcr.io/" + IMAGE_REPOSITORY);
            try (OutputStream output = new FileOutputStream(
                    new File(temporary, "update.json"))) {
                output.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
            }

            File target = new File(base, safeTag(release.tag));
            if (target.exists()) {
                deleteRecursively(target);
            }
            if (!temporary.renameTo(target)) {
                copyDirectory(temporary, target);
                deleteRecursively(temporary);
            }
            return target;
        } catch (Exception exception) {
            deleteRecursively(temporary);
            throw exception;
        }
    }

    private static String activate(Context context, String newTag, File root) {
        if (root == null || !new File(root, "index.html").isFile()) {
            throw new IllegalStateException("Update is incomplete");
        }

        SharedPreferences preferences = preferences(context);
        String previousTag = preferences.getString(KEY_ACTIVE_TAG,
                BUNDLED_RELEASE_TAG);
        if (previousTag.equals(newTag)) {
            previousTag = preferences.getString(KEY_PREVIOUS_TAG, "");
        }
        preferences.edit()
                .putString(KEY_ACTIVE_TAG, newTag)
                .putString(KEY_PREVIOUS_TAG, previousTag)
                .apply();

        cleanupOldBuilds(context, newTag, previousTag);
        return newTag;
    }

    private static void cleanupInterruptedUpdates(Context context) {
        File[] builds = webBase(context).listFiles();
        if (builds != null) {
            for (File child : builds) {
                if (child.isDirectory() && child.getName().startsWith(".tmp-")) {
                    deleteRecursively(child);
                }
            }
        }
        File[] cached = context.getCacheDir().listFiles();
        if (cached != null) {
            for (File child : cached) {
                String name = child.getName();
                if (child.isFile() && name.startsWith("doory-web-")
                        && name.endsWith(".tar.gz")) {
                    if (!child.delete()) {
                        child.deleteOnExit();
                    }
                }
            }
        }
    }

    private static void cleanupOldBuilds(Context context, String activeTag,
                                         String previousTag) {
        File base = webBase(context);
        File[] children = base.listFiles();
        if (children == null) {
            return;
        }
        String keepActive = safeTag(activeTag);
        String keepPrevious = isBlank(previousTag)
                ? "" : safeTag(previousTag);
        for (File child : children) {
            String name = child.getName();
            if (!child.isDirectory() || name.startsWith(".tmp-")) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                }
                continue;
            }
            if (!name.equals(keepActive) && !name.equals(keepPrevious)) {
                deleteRecursively(child);
            }
        }
    }

    private static HttpURLConnection openConnection(String initialUrl,
                                                     String initialToken,
                                                     String accept)
            throws IOException {
        String url = initialUrl;
        String token = initialToken;
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "DooryAndroid/1.0");
            if (!isBlank(token)) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (!isBlank(accept)) {
                connection.setRequestProperty("Accept", accept);
            }

            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (isBlank(location)) {
                    throw new IOException("HTTP redirect without a target");
                }
                URL base = new URL(url);
                URL next = new URL(base, location);
                if (!next.getHost().equalsIgnoreCase(base.getHost())) {
                    token = null;
                }
                url = next.toString();
                continue;
            }
            return connection;
        }
        throw new IOException("Too many HTTP redirects");
    }

    private static String readBody(HttpURLConnection connection, int code)
            throws IOException {
        InputStream input = code >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) {
            return "";
        }
        try (InputStream stream = input) {
            return new String(readAll(stream, 2 * 1024 * 1024),
                    StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String readUtf8(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return new String(readAll(input, 2 * 1024 * 1024),
                    StandardCharsets.UTF_8);
        }
    }

    private static void copyDirectory(File source, File target) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Could not create target directory");
        }
        File[] children = source.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File destination = new File(target, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, destination);
            } else {
                try (InputStream input = new FileInputStream(child);
                     OutputStream output = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    private static String safeTag(String tag) {
        String value = isBlank(tag) ? "unknown" : tag.trim();
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.length() > 80) {
            value = value.substring(0, 80);
        }
        if (".".equals(value) || "..".equals(value) || value.isEmpty()) {
            value = "unknown";
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String isoNow() {
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private static String friendlyError(Exception exception) {
        String message = exception.getMessage();
        if (isBlank(message)) {
            return "Network or server error";
        }
        return message;
    }

    static int compareVersions(String left, String right) {
        List<Integer> leftParts = versionParts(left);
        List<Integer> rightParts = versionParts(right);
        int length = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.size() ? leftParts.get(index) : 0;
            int rightValue = index < rightParts.size() ? rightParts.get(index) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static List<Integer> versionParts(String tag) {
        List<Integer> parts = new ArrayList<>();
        if (tag == null) {
            return parts;
        }
        String cleaned = tag.trim().replaceFirst("^[vV]", "");
        String[] segments = cleaned.split("[^0-9]+");
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            try {
                parts.add(Integer.parseInt(segment));
            } catch (NumberFormatException ignored) {
            }
        }
        return parts;
    }
}
