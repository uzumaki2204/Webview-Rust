package com.example.webviewbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.webkit.ValueCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Wraps a single {@link WebView} instance to represent one browser tab.
 *
 * <p>Each {@code WebTabView} manages its own {@link WebView}, JS bridge, and forwards
 * page-lifecycle events to a {@link TabEventListener} so the hosting Activity can
 * react (update URL bar, progress indicator, tab count badge, etc.).</p>
 */
public class WebTabView {

    public static final int CAMERA_PERMISSION_REQUEST_CODE = 2001;
    public static final int FILE_CHOOSER_REQUEST_CODE = 2002;

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final int tabId;
    private final String initialUrl;
    private final WebView webView;
    private final Context context;
    private final TabEventListener listener;

    private PermissionRequest pendingPermissionRequest;
    private ValueCallback<Uri[]> fileChooserCallback;

    /** Maximum thumbnail width in pixels (height scales proportionally). */
    private static final int THUMBNAIL_MAX_WIDTH = 400;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates and fully configures a new tab wrapping a {@link WebView}.
     *
     * @param context    Android context (should be the hosting Activity).
     * @param tabId      Unique tab ID assigned by the Rust core.
     * @param initialUrl URL to load immediately after setup.
     * @param listener   Callback sink for page-lifecycle events.
     */
    public WebTabView(Context context, int tabId, String initialUrl, TabEventListener listener) {
        this.context = context;
        this.tabId = tabId;
        this.initialUrl = initialUrl != null ? initialUrl : "";
        this.listener = listener;

        webView = new WebView(context);
        configureWebSettings();
        webView.addJavascriptInterface(new JsBridgeInterface(tabId), "AndroidBridge");
        webView.setWebViewClient(buildWebViewClient());
        webView.setWebChromeClient(buildWebChromeClient());

        if (initialUrl != null && !initialUrl.isEmpty()) {
            webView.loadUrl(initialUrl);
        }
    }

    // -------------------------------------------------------------------------
    // WebView configuration
    // -------------------------------------------------------------------------

    private void configureWebSettings() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Keep the device's default user-agent; do not override.
    }

    // -------------------------------------------------------------------------
    // WebViewClient
    // -------------------------------------------------------------------------

    private WebViewClient buildWebViewClient() {
        return new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                RustBridge.nativeUpdateTabLoading(tabId, true);
                if (listener != null) {
                    listener.onPageStarted(tabId, url);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                RustBridge.nativeUpdateTabLoading(tabId, false);
                RustBridge.nativeUpdateTabUrl(tabId, url);
                if (listener != null) {
                    listener.onPageFinished(tabId, url);
                }
            }

            /**
             * Return {@code false} so that all navigations are handled inside the WebView
             * (i.e. do not hand off links to an external browser).
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            /** Legacy override kept for API < 24 compatibility. */
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        };
    }

    // -------------------------------------------------------------------------
    // WebChromeClient
    // -------------------------------------------------------------------------

    private WebChromeClient buildWebChromeClient() {
        return new WebChromeClient() {

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (title == null) title = "";
                RustBridge.nativeUpdateTabTitle(tabId, title);
                if (listener != null) {
                    listener.onTitleChanged(tabId, title);
                }
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (listener != null) {
                    listener.onProgressChanged(tabId, newProgress);
                }
            }

            /**
             * Called when a web page requests access to resources like camera or microphone
             * (e.g. WebRTC, getUserMedia). We check Android runtime permissions and grant
             * the web request if the user has allowed it.
             */
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Run on UI thread
                new Handler(Looper.getMainLooper()).post(() -> {
                    String[] resources = request.getResources();
                    List<String> permissionsNeeded = new ArrayList<>();
                    for (String resource : resources) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                    != PackageManager.PERMISSION_GRANTED) {
                                permissionsNeeded.add(Manifest.permission.CAMERA);
                            }
                        } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED) {
                                permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
                            }
                        }
                    }

                    if (!permissionsNeeded.isEmpty() && context instanceof Activity) {
                        // Store the pending request and ask the user for permission
                        pendingPermissionRequest = request;
                        ActivityCompat.requestPermissions((Activity) context,
                                permissionsNeeded.toArray(new String[0]),
                                CAMERA_PERMISSION_REQUEST_CODE);
                    } else {
                        // Permissions already granted
                        request.grant(resources);
                    }
                });
            }

            /**
             * Support HTML <input type="file" accept="image/*" capture> and regular file uploads.
             */
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();
                    if (context instanceof Activity) {
                        ((Activity) context).startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                        return true;
                    }
                } catch (Exception e) {
                    try {
                        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("*/*");
                        if (context instanceof Activity) {
                            ((Activity) context).startActivityForResult(fallback, FILE_CHOOSER_REQUEST_CODE);
                            return true;
                        }
                    } catch (Exception ex) {
                        fileChooserCallback = null;
                        return false;
                    }
                }
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (listener != null) {
                    listener.onShowCustomView(view, callback);
                }
            }

            @Override
            public void onHideCustomView() {
                if (listener != null) {
                    listener.onHideCustomView();
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // JS Bridge (inner class)
    // -------------------------------------------------------------------------

    /**
     * Exposed to JavaScript as {@code window.AndroidBridge}.
     *
     * <p>Web pages call {@code window.AndroidBridge.postMessage(jsonString)} to send
     * arbitrary messages to the Rust core via the JNI layer.</p>
     */
    private static class JsBridgeInterface {

        private final int tabId;

        JsBridgeInterface(int tabId) {
            this.tabId = tabId;
        }

        /**
         * Called from JavaScript. Passes the message to the Rust core and returns
         * whatever the Rust core responds with (a JSON string).
         *
         * @param json Arbitrary JSON payload from the web page.
         * @return JSON response from the Rust core.
         */
        @JavascriptInterface
        public String postMessage(String json) {
            return RustBridge.nativeHandleJsMessage(tabId, json);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** @return The underlying {@link WebView}. */
    public WebView getWebView() {
        return webView;
    }

    /** @return The unique tab ID assigned by the Rust core. */
    public int getTabId() {
        return tabId;
    }

    /** @return The initial URL with which this tab was opened. */
    public String getInitialUrl() {
        return initialUrl;
    }

    /**
     * Navigate the tab to the given URL.
     *
     * @param url The URL to load.
     */
    public void loadUrl(String url) {
        webView.loadUrl(url);
    }

    /** Navigate back in the WebView's history stack if possible. */
    public void goBack() {
        if (webView.canGoBack()) {
            webView.goBack();
        }
    }

    /** Navigate forward in the WebView's history stack if possible. */
    public void goForward() {
        if (webView.canGoForward()) {
            webView.goForward();
        }
    }

    /** @return {@code true} if the WebView has a back history entry. */
    public boolean canGoBack() {
        return webView.canGoBack();
    }

    /** @return {@code true} if the WebView has a forward history entry. */
    public boolean canGoForward() {
        return webView.canGoForward();
    }

    /** Reload the current page. */
    public void reload() {
        webView.reload();
    }

    /**
     * Capture a thumbnail of the current page and deliver it asynchronously on the
     * UI thread.
     *
     * <p>The resulting bitmap is at most {@value #THUMBNAIL_MAX_WIDTH}px wide; height
     * is scaled proportionally from the WebView dimensions.</p>
     *
     * @param callback Receives the captured {@link Bitmap}, or {@code null} if the
     *                 WebView has zero dimensions.
     */
    public void captureSnapshot(Consumer<Bitmap> callback) {
        new Handler(Looper.getMainLooper()).post(() -> {
            int viewWidth = webView.getWidth();
            int viewHeight = webView.getHeight();

            if (viewWidth <= 0 || viewHeight <= 0) {
                if (callback != null) {
                    callback.accept(null);
                }
                return;
            }

            // Scale so that the thumbnail width is at most THUMBNAIL_MAX_WIDTH.
            float scale = Math.min(1f, (float) THUMBNAIL_MAX_WIDTH / viewWidth);
            int bmpWidth = Math.max(1, Math.round(viewWidth * scale));
            int bmpHeight = Math.max(1, Math.round(viewHeight * scale));

            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            // Scale the canvas so the WebView content fits the thumbnail dimensions.
            canvas.scale(scale, scale);
            webView.draw(canvas);

            if (callback != null) {
                callback.accept(bitmap);
            }
        });
    }

    /**
     * Destroy the underlying WebView and free its resources. Must be called when the
     * tab is permanently closed.
     */
    public void destroy() {
        webView.stopLoading();
        webView.clearHistory();
        webView.removeAllViews();
        webView.destroy();
    }

    /**
     * Handles Android runtime permission results forwarded by {@link MainActivity}.
     */
    public void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && pendingPermissionRequest != null) {
            boolean allGranted = grantResults.length > 0;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }
    }

    /**
     * Handles file chooser results forwarded by {@link MainActivity}.
     */
    public void onFileChooserResult(int resultCode, Intent data) {
        if (fileChooserCallback == null) return;
        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            } else if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
        }
        fileChooserCallback.onReceiveValue(results);
        fileChooserCallback = null;
    }

    // -------------------------------------------------------------------------
    // TabEventListener interface
    // -------------------------------------------------------------------------

    /**
     * Receives page-lifecycle callbacks from a {@link WebTabView}.
     */
    public interface TabEventListener {

        /**
         * Called when the page has started loading.
         *
         * @param tabId The tab that started loading.
         * @param url   The URL being loaded.
         */
        void onPageStarted(int tabId, String url);

        /**
         * Called when the page has finished loading.
         *
         * @param tabId The tab that finished loading.
         * @param url   The final URL after any redirects.
         */
        void onPageFinished(int tabId, String url);

        /**
         * Called when the page title changes.
         *
         * @param tabId The tab whose title changed.
         * @param title The new page title.
         */
        void onTitleChanged(int tabId, String title);

        /**
         * Called repeatedly while the page is loading to update a progress indicator.
         *
         * @param tabId    The tab whose progress changed.
         * @param progress Loading progress in the range [0, 100].
         */
        void onProgressChanged(int tabId, int progress);

        /**
         * Called when a web element (e.g. HTML5 video) requests fullscreen display.
         *
         * @param view     The custom view to display in fullscreen.
         * @param callback Callback to notify when the custom view is dismissed.
         */
        void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback);

        /**
         * Called when fullscreen custom view display should be dismissed.
         */
        void onHideCustomView();
    }
}
