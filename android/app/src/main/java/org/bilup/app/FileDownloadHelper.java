package org.bilup.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 文件下载辅助类。
 * - 普通 URL（http/https）→ 下载后写入公共 Download/Bilup/ 目录
 * - blob: URL → 通过 evaluateJavascript 让 JS fetch blob，走 BilupFileBridge 回传
 *
 * 保存策略：
 * - Android 10+ (API 29+)：使用 MediaStore.Downloads（无需权限）
 * - Android 7-9 (API 24-28)：使用公共外部存储目录（需 WRITE_EXTERNAL_STORAGE）
 * 文件保存在公共下载目录，用户可通过系统文件管理器直接找到。
 */
public class FileDownloadHelper {
    private static final String TAG = "BilupFileDownload";
    private static final String BILUP_DIR = "Bilup";

    private final Context context;
    private final WebView webView;

    public FileDownloadHelper(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
    }

    /**
     * 清洗文件名，移除文件系统不允许的非法字符。
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "Bilup_project_" + System.currentTimeMillis() + ".sb3";
        }
        String cleaned = fileName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "Bilup_project_" + System.currentTimeMillis() + ".sb3";
        }
        if (cleaned.length() > 150) {
            cleaned = cleaned.substring(0, 150);
        }
        return cleaned;
    }

    /**
     * 为 WebView 设置下载监听器
     */
    public void setupDownloadListener() {
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimetype, long contentLength) {

                String fileName = sanitizeFileName(
                        URLUtil.guessFileName(url, contentDisposition, mimetype));

                Log.d(TAG, "Download started: url=" + url + ", fileName=" + fileName
                        + ", mimeType=" + mimetype + ", contentLength=" + contentLength);

                if (url != null && url.startsWith("blob:")) {
                    handleBlobDownload(url, fileName, mimetype);
                } else {
                    final String fName = fileName;
                    final String fMime = mimetype;
                    final Uri fileUri = handleRegularDownload(url, fileName, mimetype);
                    // DownloadListener 回调在 WebView 内部线程，需切到主线程
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (fileUri != null) {
                                        // 打开文件位置预览
                                        openFileLocation(fileUri, fMime);
                                        // JS 回调通知前端保存完成
                                        notifyJSSaveComplete(fName);
                                        Toast.makeText(context,
                                                "已保存到 下载/Bilup/" + fName,
                                                Toast.LENGTH_LONG).show();
                                    } else {
                                        Toast.makeText(context,
                                                "文件保存失败", Toast.LENGTH_LONG).show();
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Save UI update failed", e);
                                }
                            }
                        });
                    }
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

        // 与 Java 侧 MAX_BASE64_LENGTH（140MB base64 ≈ 100MB 原始数据）保持一致。
        // 在 readAsDataURL 之前预检大小：若超限直接放弃，避免 base64 字符串
        // （膨胀约 33%）在 JS 堆中就 OOM，导致渲染进程崩溃。
        String js = "(function() {" +
            "var url = '" + safeUrl + "';" +
            "var cached = (window._bilupBlobMap && window._bilupBlobMap[url]);" +
            "var MAX_BYTES = 100 * 1024 * 1024;" +
            "function saveBlob(b) {" +
                "if (b.size > MAX_BYTES) {" +
                    "console.error('File too large for mobile save (>100MB):', b.size);" +
                    "try { BilupFileBridge.saveBlob('', '" + safeName + "', '" + safeMime + "'); }" +
                    "catch(e) {}" +
                    "return;" +
                "}" +
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

        Log.d(TAG, "Injecting blob download JS for: " + safeName);
        try {
            if (!webView.isDestroyed()) {
                webView.evaluateJavascript(js, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Blob download JS injection failed", e);
        }
    }

    /**
     * 单个文件允许下载的最大字节数（约 200MB）。
     * 超过该大小的文件放弃下载，避免 ByteArrayOutputStream 全量读入内存时
     * 抛 OutOfMemoryError（不是 Exception，若漏掉会直接崩溃应用）。
     */
    private static final long MAX_DOWNLOAD_BYTES = 200 * 1024 * 1024L;

    /**
     * 处理普通 URL 下载，保存到公共 Download/Bilup/ 目录。
     * @return 文件的 content:// URI，失败返回 null
     */
    @Nullable
    private Uri handleRegularDownload(String fileUrl, String fileName, String mimeType) {
        try {
            return downloadToPublicStorage(fileUrl, fileName, mimeType);
        } catch (Throwable t) {
            // 捕获 Throwable：大文件下载时可能抛 OutOfMemoryError，必须兜住
            Log.e(TAG, "Download failed: " + fileName, t);
            return null;
        }
    }

    /**
     * 通过 HttpURLConnection 下载并保存到公共 Download/Bilup/ 目录。
     * 按 API 级别选择 MediaStore 或公共存储路径。
     *
     * @return 文件的 content:// URI
     */
    private Uri downloadToPublicStorage(String fileUrl, String fileName, String mimeType) throws Exception {
        byte[] data = downloadBytes(fileUrl);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(data, fileName, mimeType);
        } else {
            File file = saveViaPublicStorage(data, fileName);
            String authority = context.getPackageName() + ".fileprovider";
            return FileProvider.getUriForFile(context, authority, file);
        }
    }

    /**
     * 从 URL 下载完整的字节数据。
     * 下载过程中持续校验大小，超过 MAX_DOWNLOAD_BYTES 立即中止，
     * 避免超大文件把内存耗尽（OOM）崩溃应用。
     */
    private byte[] downloadBytes(String fileUrl) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        // 服务器声明了 Content-Length 时提前拦截超大文件
        long contentLength = conn.getContentLengthLong();
        if (contentLength > MAX_DOWNLOAD_BYTES) {
            conn.disconnect();
            throw new IOException("文件过大，无法下载（超过 200MB）");
        }

        try (InputStream input = conn.getInputStream()) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            long total = 0;
            while ((len = input.read(buffer)) != -1) {
                total += len;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("文件过大，无法下载（超过 200MB）");
                }
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * API 29+：使用 MediaStore 保存到公共 Download/Bilup/ 目录。
     */
    private Uri saveViaMediaStore(byte[] data, String fileName, String mimeType) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + BILUP_DIR);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new Exception("MediaStore insert returned null");
        }

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new Exception("无法打开 MediaStore OutputStream");
            }
            out.write(data);
            out.flush();
        }

        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);

        Log.i(TAG, "Saved via MediaStore: " + uri.toString());
        return uri;
    }

    /**
     * API 24-28：通过公共外部存储目录保存。
     */
    private File saveViaPublicStorage(byte[] data, String fileName) throws Exception {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloadDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
        Log.i(TAG, "Saved to public storage: " + file.getAbsolutePath());

        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            Log.w(TAG, "MediaScanner failed: " + e.getMessage());
        }
        return file;
    }

    /**
     * 使用系统文件管理器打开已保存文件的位置。
     */
    private void openFileLocation(Uri fileUri, String mimeType) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, mimeType != null ? mimeType : "application/octet-stream");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(intent);
            Log.i(TAG, "Opened file: " + fileUri);
        } catch (Exception e) {
            Log.w(TAG, "Failed to open file: " + e.getMessage());
        }
    }

    /**
     * 通知前端 JS 保存完成（触发 bilupSaveComplete 事件）
     */
    private void notifyJSSaveComplete(String fileName) {
        String js = "javascript:(function(){"
            + "var e = new CustomEvent('bilupSaveComplete', {"
            + "  detail: { fileName: '" + fileName.replace("'", "\\'") + "', path: '" +
            ("下载/Bilup/" + fileName).replace("'", "\\'") + "' }"
            + "});"
            + "document.dispatchEvent(e);"
            + "})();";
        try {
            if (!webView.isDestroyed()) {
                webView.evaluateJavascript(js, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "JS save complete callback failed", e);
        }
    }
}
