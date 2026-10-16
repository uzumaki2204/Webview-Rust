package com.example.webviewbrowser;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Factory for {@link DownloadListener} instances that:
 * <ol>
 *   <li>Notify the Rust core about the pending download via
 *       {@link RustBridge#nativeEnqueueDownload(String, String)}.</li>
 *   <li>Enqueue the actual download with Android's {@link DownloadManager}.</li>
 *   <li>Show a brief {@link Toast} confirming the download has started.</li>
 * </ol>
 */
public class DownloadHandler {

    // Prevent instantiation — this is a pure factory.
    private DownloadHandler() {}

    /**
     * Create a {@link DownloadListener} ready to be attached to a {@link android.webkit.WebView}.
     *
     * @param context Android context (used to obtain system services and show Toasts).
     * @param tabId   The tab that initiated the download (passed to the Rust core for tracking).
     * @return A fully configured {@link DownloadListener}.
     */
    public static DownloadListener createFor(Context context, int tabId) {
        return (url, userAgent, contentDisposition, mimetype, contentLength) -> {

            // 1. Notify the Rust core.
            int downloadId = RustBridge.nativeEnqueueDownload(url, mimetype != null ? mimetype : "");

            // 2. Derive a sensible filename.
            String filename = extractFilename(url, contentDisposition, mimetype);

            // 3. Enqueue via Android DownloadManager.
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, filename);

                if (userAgent != null && !userAgent.isEmpty()) {
                    request.addRequestHeader("User-Agent", userAgent);
                }

                if (mimetype != null && !mimetype.isEmpty()) {
                    request.setMimeType(mimetype);
                }

                // Allow download over mobile data and Wi-Fi.
                request.setAllowedNetworkTypes(
                        DownloadManager.Request.NETWORK_WIFI
                                | DownloadManager.Request.NETWORK_MOBILE);

                DownloadManager dm =
                        (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                }
            } catch (Exception e) {
                // Surface error to user rather than crashing silently.
                Toast.makeText(context,
                        "Download failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }

            // 4. Notify user.
            Toast.makeText(context, "Downloading: " + filename, Toast.LENGTH_SHORT).show();
        };
    }

    // -------------------------------------------------------------------------
    // Filename extraction helpers
    // -------------------------------------------------------------------------

    /**
     * Derives a filename for the download using (in order of preference):
     * <ol>
     *   <li>The {@code filename=} parameter in the {@code Content-Disposition} header.</li>
     *   <li>The last path segment of the URL.</li>
     *   <li>A generic fallback like {@code "download.bin"}.</li>
     * </ol>
     *
     * @param url                The download URL.
     * @param contentDisposition The raw {@code Content-Disposition} header value (may be null).
     * @param mimeType           The MIME type (used to derive an extension as a last resort).
     * @return A non-null, non-empty filename string.
     */
    static String extractFilename(String url, String contentDisposition, String mimeType) {
        // 1. Try Content-Disposition header.
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            String fromHeader = filenameFromContentDisposition(contentDisposition);
            if (fromHeader != null && !fromHeader.isEmpty()) {
                return sanitize(fromHeader);
            }
        }

        // 2. Try the URL path.
        if (url != null && !url.isEmpty()) {
            try {
                Uri uri = Uri.parse(url);
                String path = uri.getLastPathSegment();
                if (path != null && !path.isEmpty() && path.contains(".")) {
                    // URL-decode percent-encoded characters.
                    String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
                    return sanitize(decoded);
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback: generic name + extension from MIME type.
        String ext = extensionFromMimeType(mimeType);
        return "download" + ext;
    }

    /**
     * Parses the {@code filename=} or {@code filename*=UTF-8''} parameter from a
     * {@code Content-Disposition} header value.
     */
    private static String filenameFromContentDisposition(String header) {
        // RFC 5987 extended notation (filename*=UTF-8''encoded-name)
        String lower = header.toLowerCase();
        int idx = lower.indexOf("filename*=utf-8''");
        if (idx >= 0) {
            String encoded = header.substring(idx + "filename*=utf-8''".length()).trim();
            // Strip trailing semicolons / params.
            int semi = encoded.indexOf(';');
            if (semi >= 0) encoded = encoded.substring(0, semi);
            try {
                return URLDecoder.decode(encoded.trim(), StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {}
        }

        // Standard filename= parameter
        idx = lower.indexOf("filename=");
        if (idx >= 0) {
            String name = header.substring(idx + "filename=".length()).trim();
            // Strip surrounding quotes if present.
            if (name.startsWith("\"")) {
                int end = name.indexOf('"', 1);
                name = (end >= 0) ? name.substring(1, end) : name.substring(1);
            } else {
                // Strip at first semicolon.
                int semi = name.indexOf(';');
                if (semi >= 0) name = name.substring(0, semi);
            }
            return name.trim();
        }

        return null;
    }

    /** Returns a file extension (e.g. {@code ".apk"}) for a given MIME type, or {@code ""} if unknown. */
    private static String extensionFromMimeType(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return ".bin";
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        return (ext != null && !ext.isEmpty()) ? "." + ext : ".bin";
    }

    /**
     * Removes characters that are unsafe in file names on common filesystems.
     * Collapses consecutive unsafe characters into a single underscore.
     */
    private static String sanitize(String name) {
        // Replace path separators and other illegal chars.
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        // Collapse runs of underscores/spaces.
        sanitized = sanitized.replaceAll("[_\\s]{2,}", "_");
        // Trim leading/trailing underscores/spaces.
        sanitized = sanitized.trim().replaceAll("^[_\\s]+|[_\\s]+$", "");
        return sanitized.isEmpty() ? "download" : sanitized;
    }
}
