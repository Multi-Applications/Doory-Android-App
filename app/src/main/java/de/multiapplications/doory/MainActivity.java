package de.multiapplications.doory;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    public static final String ACTION_NEW_DESIGN = "de.multiapplications.doory.NEW_DESIGN";
    public static final String ACTION_DASHBOARD = "de.multiapplications.doory.DASHBOARD";
    public static final String ACTION_TEMPLATES = "de.multiapplications.doory.TEMPLATES";

    private static final String HOST = "doory.art";
    private static final String BASE_URL = "https://" + HOST + "/";
    private static final String ASSET_ROOT = "www/";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    private static final String DOWNLOAD_HOOK_JS = """
            (function () {
              if (window.__dooryAndroidDownloadHook) return;
              window.__dooryAndroidDownloadHook = true;
              var originalClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                try {
                  var href = this.href || '';
                  var isBlob = href.indexOf('blob:') === 0 || href.indexOf('data:') === 0;
                  if (this.hasAttribute('download') && isBlob) {
                    var name = this.getAttribute('download') || 'doory-export';
                    fetch(href).then(function (response) {
                      return response.blob();
                    }).then(function (blob) {
                      var reader = new FileReader();
                      reader.onload = function () {
                        var dataUrl = String(reader.result || '');
                        var comma = dataUrl.indexOf(',');
                        if (comma < 0) {
                          window.DooryAndroid.downloadFailed('Empty export file.');
                          return;
                        }
                        window.DooryAndroid.saveBase64(name, blob.type || '', dataUrl.slice(comma + 1));
                      };
                      reader.onerror = function () {
                        window.DooryAndroid.downloadFailed('Could not read export file.');
                      };
                      reader.readAsDataURL(blob);
                    }).catch(function (error) {
                      window.DooryAndroid.downloadFailed(String(error));
                    });
                    return;
                  }
                } catch (ignored) {
                }
                return originalClick.apply(this, arguments);
              };
            })();
            """;

    private FrameLayout root;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private volatile File activeWebRoot;
    private ProgressDialog updateProgress;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    private byte[] pendingDownloadBytes;
    private String pendingDownloadName;
    private String pendingDownloadMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();

        activeWebRoot = UpdateManager.getActiveWebRoot(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.doory_background));

        webView = createWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyWindowInsets();
        applySystemBarAppearance();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBack);
        }

        handleIntent(getIntent(), true);
        webView.postDelayed(() -> checkForUpdates(false), 2500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, false);
    }

    private void handleIntent(Intent intent, boolean initial) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_NEW_DESIGN.equals(action)) {
            webView.loadUrl(BASE_URL + "create");
        } else if (ACTION_DASHBOARD.equals(action)) {
            webView.loadUrl(BASE_URL + "dashboard");
        } else if (ACTION_TEMPLATES.equals(action)) {
            webView.loadUrl(BASE_URL + "templates");
        } else if (initial) {
            webView.loadUrl(BASE_URL);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
    }

    private void applySystemBarAppearance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.navigationBars());
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (root != null) {
            root.requestApplyInsets();
        }
    }

    private void applyWindowInsets() {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left = 0;
            int top = 0;
            int right = 0;
            int bottom = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    android.view.DisplayCutout cutout = windowInsets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
            }

            insetLeft = left;
            insetTop = top;
            insetRight = right;
            insetBottom = bottom;
            applyContentPadding();
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void applyContentPadding() {
        if (root == null) {
            return;
        }
        if (customView != null) {
            root.setPadding(0, 0, 0, 0);
        } else {
            root.setPadding(insetLeft, insetTop, insetRight, insetBottom);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(getColor(R.color.doory_background));
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " DooryAndroid/1.0");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);

        view.addJavascriptInterface(new DownloadBridge(), "DooryAndroid");

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView webView,
                                                              WebResourceRequest request) {
                return interceptAsset(request);
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                super.onPageFinished(webView, url);
                webView.evaluateJavascript(DOWNLOAD_HOOK_JS, null);
            }

            @Override
            public void onReceivedError(WebView webView, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(webView, request, error);
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, R.string.load_error, Toast.LENGTH_LONG).show();
                }
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                List<String> mimeTypes = acceptedMimeTypes(params.getAcceptTypes());
                if (mimeTypes.size() == 1) {
                    intent.setType(mimeTypes.get(0));
                } else {
                    intent.setType("*/*");
                    if (!mimeTypes.isEmpty()) {
                        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                                mimeTypes.toArray(new String[0]));
                    }
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                try {
                    startActivityForResult(Intent.createChooser(intent,
                            getString(R.string.choose_file)), REQ_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException exception) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                applyContentPadding();
                hideSystemBarsForMedia();
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String filename = filenameFromDisposition(contentDisposition);
            if (isBlank(filename)) {
                filename = filenameFromUrl(url);
            }
            filename = ensureExtension(sanitizeFilename(filename), mimeType);

            if (url.startsWith("blob:") || url.startsWith("data:") || url.startsWith(BASE_URL)) {
                fetchAndSaveInPage(url, filename, mimeType);
            } else {
                enqueueSystemDownload(url, filename, mimeType, userAgent);
            }
        });

        return view;
    }

    private void hideSystemBarsForMedia() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void exitCustomView() {
        if (customView == null) {
            return;
        }
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        applyContentPadding();
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars());
            }
        }
        applySystemBarAppearance();
        root.requestApplyInsets();
    }

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (HOST.equals(uri.getHost())) {
            return false;
        }
        if ("http".equals(scheme) || "https".equals(scheme)
                || "mailto".equals(scheme) || "tel".equals(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, R.string.external_open_failed, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return true;
    }

    private WebResourceResponse interceptAsset(WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!HOST.equals(uri.getHost())) {
            return null;
        }

        String path = uri.getPath() == null ? "/" : uri.getPath();

        if (path.startsWith("/api/")) {
            return null;
        }

        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty() || relative.endsWith("/")) {
            relative += "index.html";
        }

        if (relative.contains("..") || relative.contains("\\")) {
            return notFound();
        }

        try {
            InputStream stream = openLocalFile(relative);
            return new WebResourceResponse(mimeFor(relative), encodingFor(relative),
                    200, "OK", noCacheHeaders(), stream);
        } catch (IOException missing) {
            String accept = request.getRequestHeaders().get("Accept");
            boolean navigation = accept != null && accept.contains("text/html");
            if (navigation && !looksLikeAsset(relative)) {
                return serveIndex();
            }
            return null;
        }
    }

    private InputStream openLocalFile(String relative) throws IOException {
        File updateRoot = activeWebRoot;
        if (updateRoot != null) {
            File candidate = new File(updateRoot, relative);
            String rootPath = updateRoot.getCanonicalPath();
            if (candidate.getCanonicalPath()
                    .startsWith(rootPath + File.separator) && candidate.isFile()) {
                return new BufferedInputStream(new FileInputStream(candidate));
            }
        }
        return getAssets().open(ASSET_ROOT + relative);
    }

    private WebResourceResponse serveIndex() {
        try {
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK",
                    noCacheHeaders(), openLocalFile("index.html"));
        } catch (IOException exception) {
            return textResponse("text/plain", "Doory assets are missing.", 500,
                    "Internal Server Error");
        }
    }

    private static Map<String, String> noCacheHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        return headers;
    }

    private WebResourceResponse notFound() {
        return textResponse("application/json",
                "{\"error\":{\"code\":\"not_found\"}}", 404, "Not Found");
    }

    private WebResourceResponse textResponse(String mime, String body, int status, String reason) {
        return new WebResourceResponse(mime, "UTF-8", status, reason,
                noCacheHeaders(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean looksLikeAsset(String relative) {
        String lastSegment = relative.substring(relative.lastIndexOf('/') + 1);
        return lastSegment.contains(".")
                || relative.startsWith("assets/")
                || relative.startsWith("icons/")
                || relative.startsWith("media/");
    }

    private String mimeFor(String path) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(path);
        if (extension != null && !extension.isEmpty()) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
            if (mime != null) {
                return mime;
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".webmanifest")) return "application/manifest+json";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private String encodingFor(String path) {
        String mime = mimeFor(path);
        return mime.startsWith("text/") || mime.contains("javascript")
                || mime.contains("json") || mime.contains("svg")
                ? "UTF-8" : null;
    }

    private List<String> acceptedMimeTypes(String[] acceptTypes) {
        List<String> result = new ArrayList<>();
        if (acceptTypes == null) {
            return result;
        }
        for (String raw : acceptTypes) {
            if (isBlank(raw)) {
                continue;
            }
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (value.isEmpty() || "*/*".equals(value)) {
                    continue;
                }
                if (value.contains("/")) {
                    if (!result.contains(value)) {
                        result.add(value);
                    }
                } else {
                    String extension = value.startsWith(".") ? value.substring(1) : value;
                    String mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
                    if (mime != null && !result.contains(mime)) {
                        result.add(mime);
                    }
                }
            }
        }
        return result;
    }

    private void fetchAndSaveInPage(String url, String filename, String mime) {
        String script = "(function(){"
                + "fetch(" + JSONObject.quote(url) + ").then(function(r){return r.blob();})"
                + ".then(function(b){"
                + "var f=new FileReader();"
                + "f.onload=function(){"
                + "var d=String(f.result||'');var i=d.indexOf(',');"
                + "if(i<0){window.DooryAndroid.downloadFailed('Empty export file.');return;}"
                + "window.DooryAndroid.saveBase64(" + JSONObject.quote(filename) + ","
                + "b.type||" + JSONObject.quote(mime == null ? "" : mime) + ",d.slice(i+1));"
                + "};"
                + "f.onerror=function(){window.DooryAndroid.downloadFailed('Could not read export file.');};"
                + "f.readAsDataURL(b);"
                + "}).catch(function(e){window.DooryAndroid.downloadFailed(String(e));});"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    private void enqueueSystemDownload(String url, String filename, String mime, String userAgent) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(filename);
            request.setDescription("Doory");
            request.setMimeType(mimeForName(filename, mime));
            if (!isBlank(userAgent)) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            if (!isBlank(cookies)) {
                request.addRequestHeader("Cookie", cookies);
            }
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Doory/" + filename);
            request.allowScanningByMediaScanner();

            DownloadManager manager =
                    (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("DownloadManager unavailable");
            }
            manager.enqueue(request);
            toast(getString(R.string.saved_to_downloads, filename));
        } catch (Exception exception) {
            showDownloadFailure(exception.getMessage());
        }
    }

    private void saveDownload(final String rawName, final String rawMime, final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            showDownloadFailure("Empty file");
            return;
        }

        String mime = mimeForName(rawName, rawMime);
        String filename = ensureExtension(sanitizeFilename(rawName), mime);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(filename, mime, bytes);
            return;
        }

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadBytes = bytes;
            pendingDownloadName = filename;
            pendingDownloadMime = mime;
            runOnUiThread(() -> requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE));
            return;
        }
        saveToLegacyDownloads(filename, mime, bytes);
    }

    private void saveToMediaStore(String filename, String mime, byte[] bytes) {
        Uri item = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Doory");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) {
                throw new IOException("MediaStore rejected the download");
            }
            try (OutputStream output = getContentResolver().openOutputStream(item)) {
                if (output == null) {
                    throw new IOException("Could not open download target");
                }
                output.write(bytes);
                output.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(item, done, null, null);

            toast(getString(R.string.saved_to_downloads, filename));
        } catch (Exception exception) {
            if (item != null) {
                try {
                    getContentResolver().delete(item, null, null);
                } catch (Exception ignored) {
                }
            }
            showDownloadFailure(exception.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void saveToLegacyDownloads(String filename, String mime, byte[] bytes) {
        try {
            File directory = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "Doory");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create Downloads/Doory");
            }
            File target = uniqueFile(directory, filename);
            try (FileOutputStream output = new FileOutputStream(target)) {
                output.write(bytes);
            }
            android.media.MediaScannerConnection.scanFile(this,
                    new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
            toast(getString(R.string.saved_to_downloads, target.getName()));
        } catch (Exception exception) {
            showDownloadFailure(exception.getMessage());
        }
    }

    private File uniqueFile(File directory, String filename) {
        File candidate = new File(directory, filename);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int index = 1; index < 10000; index++) {
            candidate = new File(directory, base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, System.currentTimeMillis() + "-" + filename);
    }

    private void showDownloadFailure(String detail) {
        String reason = isBlank(detail) ? getString(R.string.unknown_error) : detail;
        toast(getString(R.string.download_failed, reason));
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String mimeForName(String filename, String provided) {
        if (!isBlank(provided) && !"application/octet-stream".equals(provided)) {
            return provided;
        }
        String extension = MimeTypeMap.getFileExtensionFromUrl(filename);
        if (extension != null && !extension.isEmpty()) {
            String guessed = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
            if (guessed != null) {
                return guessed;
            }
        }
        return isBlank(provided)
                ? "application/octet-stream" : provided;
    }

    private String ensureExtension(String filename, String mime) {
        String name = sanitizeFilename(filename);
        if (name.contains(".")) {
            return name;
        }
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return extension == null ? name : name + "." + extension;
    }

    private String sanitizeFilename(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            name = "doory-export";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        name = name.replaceAll("^[. ]+|[. ]+$", "");
        if (name.isEmpty()) {
            name = "doory-export";
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private String filenameFromDisposition(String contentDisposition) {
        if (isBlank(contentDisposition)) {
            return null;
        }
        Matcher encoded = Pattern.compile("filename\\*=UTF-8''([^;]+)",
                Pattern.CASE_INSENSITIVE).matcher(contentDisposition);
        if (encoded.find()) {
            return Uri.decode(encoded.group(1));
        }
        Matcher plain = Pattern.compile("filename=\"?([^\";]+)\"?",
                Pattern.CASE_INSENSITIVE).matcher(contentDisposition);
        if (plain.find()) {
            return plain.group(1);
        }
        return null;
    }

    private String filenameFromUrl(String url) {
        try {
            String path = Uri.parse(url).getLastPathSegment();
            if (!isBlank(path) && !"null".equals(path)) {
                return path;
            }
        } catch (Exception ignored) {
        }
        return "doory-export";
    }

    @SuppressWarnings("deprecation")
    private void checkForUpdates(boolean manual) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (updateProgress != null && updateProgress.isShowing()) {
            if (manual) {
                Toast.makeText(this, R.string.update_busy, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (manual) {
            updateProgress = new ProgressDialog(this);
            updateProgress.setMessage(getString(R.string.update_checking));
            updateProgress.setCancelable(false);
            updateProgress.show();
        }

        UpdateManager.checkForUpdates(this, manual, result -> {
            if (updateProgress != null) {
                updateProgress.dismiss();
                updateProgress = null;
            }
            if (!isFinishing() && !isDestroyed()) {
                handleUpdateResult(result, manual);
            }
        });
    }

    private void handleUpdateResult(UpdateManager.Result result, boolean manual) {
        switch (result.status) {
            case UPDATE_READY:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.update_ready_title)
                        .setMessage(getString(R.string.update_ready_message,
                                result.installedTag))
                        .setPositiveButton(R.string.update_reload,
                                (dialog, which) -> reloadWithActiveUpdate())
                        .setNegativeButton(R.string.update_later,
                                (dialog, which) -> Toast.makeText(this,
                                        getString(R.string.update_deferred,
                                                result.installedTag),
                                        Toast.LENGTH_LONG).show())
                        .show();
                break;
            case NO_UPDATE:
                if (manual) {
                    Toast.makeText(this, getString(R.string.update_none,
                            result.installedTag), Toast.LENGTH_LONG).show();
                }
                break;
            case OFFLINE:
                if (manual) {
                    Toast.makeText(this, R.string.update_offline,
                            Toast.LENGTH_LONG).show();
                }
                break;
            case BUSY:
                if (manual) {
                    Toast.makeText(this, R.string.update_busy,
                            Toast.LENGTH_SHORT).show();
                }
                break;
            case ERROR:
                if (manual) {
                    String detail = isBlank(result.message)
                            ? getString(R.string.unknown_error) : result.message;
                    Toast.makeText(this,
                            getString(R.string.update_failed, detail),
                            Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
        }
    }

    private void reloadWithActiveUpdate() {
        activeWebRoot = UpdateManager.getActiveWebRoot(this);
        String current = webView.getUrl();
        if (current == null || !current.startsWith(BASE_URL)) {
            current = BASE_URL;
        }
        webView.loadUrl(current);
    }

    private void handleBack() {
        if (customView != null) {
            exitCustomView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.onBackPressed();
        } else {
            handleBack();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER) {
            return;
        }
        if (filePathCallback == null) {
            return;
        }

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null && clipData.getItemCount() > 0) {
                results = new Uri[clipData.getItemCount()];
                for (int index = 0; index < clipData.getItemCount(); index++) {
                    results[index] = clipData.getItemAt(index).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingDownloadBytes != null) {
            saveToLegacyDownloads(pendingDownloadName, pendingDownloadMime,
                    pendingDownloadBytes);
        } else {
            Toast.makeText(this, R.string.storage_permission_denied,
                    Toast.LENGTH_LONG).show();
        }
        pendingDownloadBytes = null;
        pendingDownloadName = null;
        pendingDownloadMime = null;
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        if (customView != null) {
            hideSystemBarsForMedia();
        } else {
            applySystemBarAppearance();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            return;
        }
        if (customView != null) {
            hideSystemBarsForMedia();
        } else {
            applySystemBarAppearance();
        }
    }

    @Override
    protected void onDestroy() {
        if (updateProgress != null) {
            updateProgress.dismiss();
            updateProgress = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class DownloadBridge {
        @JavascriptInterface
        public void saveBase64(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                saveDownload(name, mime, bytes);
            } catch (Throwable throwable) {
                showDownloadFailure(throwable.getMessage());
            }
        }

        @JavascriptInterface
        public void downloadFailed(String message) {
            showDownloadFailure(message);
        }
    }
}
