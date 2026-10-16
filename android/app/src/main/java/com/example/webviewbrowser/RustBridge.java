package com.example.webviewbrowser;

/**
 * JNI bridge to the Rust core library (librust_core.so).
 * All methods delegate directly to native implementations in the Rust layer.
 */
public class RustBridge {

    static {
        System.loadLibrary("rust_core");
    }

    /**
     * Initialise the Rust runtime / state machine. Must be called once on app start
     * before any other native method.
     */
    public static native void nativeInit();

    /**
     * Ask Rust to create a new tab entry and return its unique tab ID.
     *
     * @param url The initial URL to associate with the new tab.
     * @return The newly allocated tab ID (>= 0).
     */
    public static native int nativeCreateTab(String url);

    /**
     * Ask Rust to remove tab state for the given tab ID.
     *
     * @param tabId The tab to close.
     * @return {@code true} if the tab existed and was closed.
     */
    public static native boolean nativeCloseTab(int tabId);

    /**
     * Inform Rust that the user has switched to a different tab.
     *
     * @param tabId The tab to make active.
     * @return {@code true} on success.
     */
    public static native boolean nativeSwitchTab(int tabId);

    /**
     * @return The tab ID that Rust considers currently active, or -1 if none.
     */
    public static native int nativeGetActiveTabId();

    /**
     * @return A JSON array string describing all open tabs, e.g.
     *         {@code [{"id":1,"title":"Google","url":"https://google.com","loading":false}, ...]}
     */
    public static native String nativeGetTabListJson();

    /**
     * Notify Rust that the URL of a tab has changed (e.g. after navigation).
     *
     * @param tabId The tab whose URL changed.
     * @param url   The new URL string.
     */
    public static native void nativeUpdateTabUrl(int tabId, String url);

    /**
     * Notify Rust that the document title of a tab has changed.
     *
     * @param tabId The tab whose title changed.
     * @param title The new page title.
     */
    public static native void nativeUpdateTabTitle(int tabId, String title);

    /**
     * Notify Rust about the loading state of a tab.
     *
     * @param tabId     The tab whose loading state changed.
     * @param isLoading {@code true} while the page is loading, {@code false} when finished.
     */
    public static native void nativeUpdateTabLoading(int tabId, boolean isLoading);

    /**
     * Pass a JSON message from the in-page JavaScript bridge to Rust for processing.
     *
     * @param tabId       The tab that posted the message.
     * @param messageJson Arbitrary JSON payload from the web page.
     * @return A JSON string response from Rust (may be {@code "null"} or an error object).
     */
    public static native String nativeHandleJsMessage(int tabId, String messageJson);

    /**
     * Ask Rust to enqueue a download and return a download-tracking ID.
     *
     * @param url      The URL to download.
     * @param mimeType The MIME type reported by the server.
     * @return A download ID assigned by the Rust layer (>= 0).
     */
    public static native int nativeEnqueueDownload(String url, String mimeType);

    /**
     * @return The total number of open tabs tracked by the Rust layer.
     */
    public static native int nativeGetTabCount();

    /**
     * Clear all tabs tracked by the Rust layer.
     */
    public static native void nativeClearTabs();
}
