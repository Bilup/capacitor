package org.bilup.app;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 文件下载辅助类。
 * - 普通 URL（http/https）→ DownloadManager（Android 9-）或 MediaStore（Android 10+）
 * - blob: URL → 通过 evaluateJavascript 让 JS fetch blob，走 BilupFileBridge 回传
 */
public class FileDownloadHelper {

    private final Context context;
    private final WebView webView;

    public FileDownloadHelper(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
    }

    /**
     * 为 WebView 设置下载监听器
     */
    public void setupDownloadListener() {
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimetype, long contentLength) {

                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "Bilup_project_" + System.currentTimeMillis() + ".sb3";
                }

                if (url != null && url.startsWith("blob:")) {
                    handleBlobDownload(url, fileName, mimetype);
                } else {
                    handleRegularDownload(url, fileName, mimetype);
                }
            }
        });
    }

    /**
     * 处理 blob: 协议的 URL：优先使用 JS 侧缓存的 Blob 引用，降级到 fetch
     */
    private void handleBlobDownload(String blobUrl, String fileName, String mimeType) {
        String safeUrl = blobUrl.replace("'", "\\'");
        String safeName = (fileName != null ? fileName : "project.sb3").replace("'", "\\'");
        String safeMime = (mimeType != null ? mimeType : "application/octet-stream").replace("'", "\\'");

        String js = "(function() {" +
            "var url = '" + safeUrl + "';" +
            "var cached = (window._bilupBlobMap && window._bilupBlobMap[url]);" +
            "function saveBlob(b) {" +
                "var reader = new FileReader();" +
                "reader.onloadend = function() {" +
                    "var base64 = reader.result.split(',')[1];" +
                    "try { BilupFileBridge.saveBlob(base64, '" + safeName + "', '" + safeMime + "'); }" +
                    "catch(e) { console.error('BilupFileBridge error:', e); }" +
                "};" +
                "reader.readAsDataURL(b);" +
            "}" +
            "if (cached) { saveBlob(cached); }" +
            "else {" +
                "fetch(url).then(function(r) { return r.blob(); })" +
                ".then(function(b) { saveBlob(b); })" +
                ".catch(function(e) { console.error('Fetch blob failed:', e); });" +
            "}" +
        "})();";

        webView.evaluateJavascript(js, null);
    }

    /**
     * 处理普通 http/https URL
     */
    private void handleRegularDownload(String url, String fileName, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveFileWithMediaStore(url, fileName, mimeType);
            } else {
                saveFileWithDownloadManager(url, fileName, mimeType);
            }
        } catch (Exception e) {
            Toast.makeText(context,
                    "文件保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveFileWithMediaStore(String fileUrl, String fileName, String mimeType) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bilup");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
        }

            if (uri != null) {
                URL url = new URL(fileUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                try (InputStream input = conn.getInputStream();
                     OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null) {
                        throw new IOException("无法打开输出流，URI: " + uri);
                    }
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = input.read(buffer)) != -1) {
                        output.write(buffer, 0, len);
                    }
                } finally {
                    conn.disconnect();
                }

                ContentValues updateValues = new ContentValues();
                updateValues.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, updateValues, null, null);

                Toast.makeText(context, "文件已保存至 下载/Bilup/" + fileName, Toast.LENGTH_LONG).show();
            } else {
                saveFileWithDownloadManager(fileUrl, fileName, mimeType);
            }
    }

    private void saveFileWithDownloadManager(String fileUrl, String fileName, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(fileUrl));
            request.setMimeType(mimeType);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Bilup/" + fileName);
            request.setTitle(fileName);
            request.setDescription("正在下载作品文件...");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(context, "文件已保存至 下载/Bilup/" + fileName, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(context,
                    "文件保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
