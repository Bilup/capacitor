package org.bilup.app;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 文件下载辅助类。
 * - 普通 URL（http/https）→ DownloadManager（Android 9-）或 MediaStore（Android 10+）
 * - blob: URL → 通过 evaluateJavascript 让 JS fetch blob，走 BilupFileBridge 回传
 *
 * 多层降级策略：
 *   1. MediaStore.Downloads  (Android 10+)
 *   2. MediaStore.Files      (Android 10+ 降级)
 *   3. DownloadManager       (Android 9- 或 MediaStore 降级)
 *   4. 公共 Downloads 目录直接写入
 *   5. 应用私有目录           (最终降级，无需权限)
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

                Log.d(TAG, "Download started: url=" + url + ", fileName=" + fileName
                        + ", mimeType=" + mimetype + ", contentLength=" + contentLength);

                if (url != null && url.startsWith("blob:")) {
                    handleBlobDownload(url, fileName, mimetype);
                } else {
                    final String fName = fileName;
                    boolean success = handleRegularDownload(url, fileName, mimetype);
                    final boolean fSuccess = success;
                    // DownloadListener 回调在 WebView 内部线程，Toast 需切到主线程
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (fSuccess) {
                                    Toast.makeText(context,
                                            "文件已保存至 \u4E0B\u8F7D/Bilup/" + fName,
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    Toast.makeText(context,
                                            "文件保存失败", Toast.LENGTH_LONG).show();
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

        Log.d(TAG, "Injecting blob download JS for: " + safeName);
        webView.evaluateJavascript(js, null);
    }

    // ==================== 普通 URL 下载 ====================

    /**
     * 处理普通 URL 下载，使用完整的多层降级策略（所有 Android 版本通用）
     * @return true 表示保存成功，false 表示失败
     */
    private boolean handleRegularDownload(String url, String fileName, String mimeType) {
        try {
            return saveFileWithFullFallback(url, fileName, mimeType);
        } catch (Exception e) {
            Log.e(TAG, "handleRegularDownload failed: " + fileName, e);
            return false;
        }
    }

    // ==================== MediaStore 路径 (Android 10+) ====================

    /**
     * 完整多层降级保存链，所有 Android 版本通用：
     *   1. MediaStore.Downloads  (Android 10+ 首选)
     *   2. MediaStore.Files      (Android 10+ 降级)
     *   3. DownloadManager       (Android 9- 或 MediaStore 降级)
     *   4. 公共 Downloads 目录直接写入
     *   5. 应用私有目录           (最终降级，无需权限)
     *
     * @return true 表示保存成功
     */
    private boolean saveFileWithFullFallback(String fileUrl, String fileName, String mimeType)
            throws Exception {
        // 1. 尝试 MediaStore.Downloads
        if (trySaveViaMediaStore(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                fileUrl, fileName, mimeType)) {
            return true;
        }
        Log.w(TAG, "MediaStore.Downloads failed for " + fileName
                + ", trying MediaStore.Files...");

        // 2. 降级：MediaStore.Files
        if (trySaveViaMediaStore(MediaStore.Files.getContentUri("external"),
                fileUrl, fileName, mimeType)) {
            return true;
        }
        Log.w(TAG, "MediaStore.Files failed for " + fileName
                + ", trying DownloadManager...");

        // 3. 降级：DownloadManager
        if (saveFileWithDownloadManager(fileUrl, fileName, mimeType)) {
            return true;
        }
        Log.w(TAG, "DownloadManager failed for " + fileName
                + ", trying direct file system...");

        // 4. 降级：直接文件写入
        try {
            saveFileDirectly(fileUrl, fileName);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Direct file system failed: " + e.getMessage());
        }

        // 5. 最终降级：应用私有目录
        Log.w(TAG, "All paths failed, trying app-private directory...");
        try {
            saveToAppPrivateDir(fileUrl, fileName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "All save methods failed for: " + fileName, e);
            return false;
        }
    }

    /**
     * 尝试通过 MediaStore 下载并保存
     */
    private boolean trySaveViaMediaStore(Uri contentUri, String fileUrl,
                                         String fileName, String mimeType) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE,
                mimeType != null ? mimeType : "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + BILUP_DIR);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(contentUri, values);
        if (uri == null) {
            Log.w(TAG, "MediaStore insert returned null for URI: " + contentUri);
            return false;
        }

        // 从 URL 下载并写入
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        try (InputStream input = conn.getInputStream();
             OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) {
                Log.w(TAG, "openOutputStream returned null for URI: " + uri);
                return false;
            }
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
            output.flush();
        } finally {
            conn.disconnect();
        }

        // 清除 IS_PENDING
        ContentValues updateValues = new ContentValues();
        updateValues.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, updateValues, null, null);

        Log.d(TAG, "MediaStore save success: " + fileName
                + " via URI: " + contentUri);

        // 强制 MediaStore 扫描，确保文件在各品牌设备上都立即可见
        final String scanPath = Environment.DIRECTORY_DOWNLOADS + "/" + BILUP_DIR + "/" + fileName;
        triggerMediaScan(scanPath);

        return true;
    }

    // ==================== DownloadManager (Android 9- + 降级) ====================

    /**
     * @return true 表示保存成功
     */
    private boolean saveFileWithDownloadManager(String fileUrl, String fileName, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(fileUrl));
            request.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, BILUP_DIR + "/" + fileName);
            request.setTitle(fileName);
            request.setDescription("正在下载作品文件...");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Log.d(TAG, "DownloadManager enqueued: " + fileName);
                return true;
            } else {
                Log.w(TAG, "DownloadManager service not available");
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "saveFileWithDownloadManager failed: " + fileName, e);
            return false;
        }
    }

    // ==================== 直接文件系统写入 ====================

    private void saveFileDirectly(String fileUrl, String fileName) throws Exception {
        File downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            throw new IOException("无法获取公共下载目录");
        }
        File dir = new File(downloadDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);

        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        try (InputStream input = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.flush();
        } finally {
            conn.disconnect();
        }

        Log.d(TAG, "Saved directly to: " + file.getAbsolutePath());
        triggerMediaScan(file.getAbsolutePath());
    }

    // ==================== 应用私有目录 (最终降级，无需权限) ====================

    private void saveToAppPrivateDir(String fileUrl, String fileName) throws Exception {
        File appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appDir == null) {
            appDir = context.getFilesDir();
        }
        File dir = new File(appDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建应用私有目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);

        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        try (InputStream input = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.flush();
        } finally {
            conn.disconnect();
        }

        Log.i(TAG, "Saved to app-private directory: " + file.getAbsolutePath());
        triggerMediaScan(file.getAbsolutePath());
        // onDownloadStart 会统一显示成功 Toast，此处不再重复显示
    }

    // ==================== MediaScanner（跨品牌兼容：确保文件在各文件管理器中可见） ====================

    /**
     * 触发 MediaScanner 扫描指定路径，使文件立即在文件管理器中可见。
     * 不同品牌的 ROM（小米 MIUI、OPPO ColorOS、vivo FuntouchOS 等）
     * 对 MediaStore 索引更新的行为不一致，强制扫描可解决文件"保存成功但看不到"的问题。
     */
    private void triggerMediaScan(final String filePath) {
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{filePath}, null, null);
            Log.d(TAG, "MediaScanner triggered for: " + filePath);
        } catch (Exception e) {
            Log.w(TAG, "MediaScanner failed: " + e.getMessage());
        }
    }

    // ==================== Toast 辅助 ====================

    private void showToast(final String message) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Toast failed", e);
                    }
                }
            });
        } else {
            Log.w(TAG, "Context is not Activity, cannot show toast: " + message);
        }
    }
}
