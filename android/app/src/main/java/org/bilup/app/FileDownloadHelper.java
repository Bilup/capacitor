package org.bilup.app;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 文件下载辅助类。
 * - 普通 URL（http/https）→ 下载后写入应用私有目录
 * - blob: URL → 通过 evaluateJavascript 让 JS fetch blob，走 BilupFileBridge 回传
 *
 * 所有文件统一保存到应用私有目录：
 * Android/data/org.bilup.app.phone/files/Download/Bilup/
 * 无需任何存储权限，兼容所有 Android 版本和品牌 ROM。
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
                    boolean success = handleRegularDownload(url, fileName);
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

    /**
     * 处理普通 URL 下载，直接保存到应用私有目录。
     * @return true 表示保存成功
     */
    private boolean handleRegularDownload(String fileUrl, String fileName) {
        try {
            saveFileToAppPrivateDir(fileUrl, fileName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Download failed: " + fileName, e);
            return false;
        }
    }

    /**
     * 通过 HttpURLConnection 下载并保存到应用私有目录。
     * 无需任何存储权限，兼容所有 Android 版本和品牌 ROM。
     */
    private void saveFileToAppPrivateDir(String fileUrl, String fileName) throws Exception {
        File appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appDir == null) {
            appDir = context.getFilesDir();
        }
        File dir = new File(appDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建目录: " + dir.getAbsolutePath());
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

        Log.i(TAG, "Saved to: " + file.getAbsolutePath());
        // 触发 MediaScanner 扫描，确保文件在各品牌文件管理器中立即可见
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            Log.w(TAG, "MediaScanner failed: " + e.getMessage());
        }
    }
}
