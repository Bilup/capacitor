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
import java.io.IOException;
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

        // 复用 WebViewEnhancer 中的分块流式保存实现（幂等定义）：
        // 文件按 4MB 分片交给 Java 写入并立即释放，不再需要整份 base64 驻留内存，
        // 因此原先"超过 100MB 直接放弃"的预检上限已移除，改由 Java 侧统一限制。
        String js = "(function() {" +
            WebViewEnhancer.STREAM_SAVE_JS +
            "if (typeof window._bilupStreamSave !== 'function') {" +
                "console.error('bilup: stream save unavailable');" +
                "return;" +
            "}" +
            "var url = '" + safeUrl + "';" +
            "var cached = (window._bilupBlobMap && window._bilupBlobMap[url]);" +
            "if (cached) { window._bilupStreamSave(cached, '" + safeName + "', '" + safeMime + "'); return; }" +
            "fetch(url).then(function(r) { return r.blob(); })" +
            ".then(function(b) { window._bilupStreamSave(b, '" + safeName + "', '" + safeMime + "'); })" +
            ".catch(function(e) { console.error('Fetch blob failed:', e); });" +
        "})();";

        Log.d(TAG, "Injecting blob download JS for: " + safeName);
        try {
            if (WebViewEnhancer.isAlive(webView)) {
                webView.evaluateJavascript(js, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Blob download JS injection failed", e);
        }
    }

    /**
     * 单个文件允许下载的最大字节数（200MB）。
     * 改为边下边写的流式保存后，内存占用恒定为 8KB 缓冲区、与文件大小无关；
     * 此上限仅用于拦截超大文件，避免长时间占用网络与磁盘。
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
            // 捕获 Throwable：网络/IO 层仍可能抛 Error，必须兜住避免崩溃
            Log.e(TAG, "Download failed: " + fileName, t);
            return null;
        }
    }

    /**
     * 边下载边写入公共 Download/Bilup/ 目录，全程不在内存中缓存整个文件。
     * 按 API 级别选择 MediaStore 或公共存储路径。
     *
     * @return 文件的 content:// URI
     */
    private Uri downloadToPublicStorage(String fileUrl, String fileName, String mimeType) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        try {
            // 服务器声明了 Content-Length 时提前拦截超大文件
            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw new IOException("文件过大，无法下载（超过 200MB）");
            }
            try (InputStream input = conn.getInputStream()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return streamToMediaStore(input, fileName, mimeType);
                }
                File file = streamToPublicStorage(input, fileName);
                String authority = context.getPackageName() + ".fileprovider";
                return FileProvider.getUriForFile(context, authority, file);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 流式写入 MediaStore（API 29+）。任何一步失败都会删除半成品记录，避免留下不可用文件。
     */
    private Uri streamToMediaStore(InputStream input, String fileName, String mimeType) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + BILUP_DIR);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }

        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法打开 MediaStore OutputStream");
                }
                copyStream(input, out);
            }
            // 写入完成，标记 IS_PENDING = 0 让文件立即可见
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);

            Log.i(TAG, "Saved via MediaStore: " + uri);
            return uri;
        } catch (Exception e) {
            try {
                resolver.delete(uri, null, null);
            } catch (Exception ignored) {
                // 清理失败不影响错误上报
            }
            throw e;
        }
    }

    /**
     * 流式写入公共存储目录（API 24-28），失败时删除半成品文件。
     */
    private File streamToPublicStorage(InputStream input, String fileName) throws Exception {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloadDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        try {
            try (OutputStream out = new FileOutputStream(file)) {
                copyStream(input, out);
            }
        } catch (Exception e) {
            try {
                if (file.exists()) {
                    file.delete();
                }
            } catch (Exception ignored) {
                // 删除失败不影响错误上报
            }
            throw e;
        }
        Log.i(TAG, "Saved to public storage: " + file.getAbsolutePath());

        // 触发 MediaScanner 扫描，文件立即在各文件管理器中可见
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            Log.w(TAG, "MediaScanner failed: " + e.getMessage());
        }
        return file;
    }

    /**
     * 边读边写，全程只持有一个 8KB 缓冲区；
     * 同时按累计字节数校验上限，超限立即中止，避免磁盘被超大文件占满。
     */
    private void copyStream(InputStream input, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        long total = 0;
        while ((len = input.read(buffer)) != -1) {
            total += len;
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new IOException("文件过大，无法下载（超过 200MB）");
            }
            out.write(buffer, 0, len);
        }
        out.flush();
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
            if (WebViewEnhancer.isAlive(webView)) {
                webView.evaluateJavascript(js, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "JS save complete callback failed", e);
        }
    }
}
