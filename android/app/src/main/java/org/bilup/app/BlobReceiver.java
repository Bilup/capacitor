package org.bilup.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 接收 JavaScript 通过 BilupFileBridge.saveBlob() 传递的 base64 数据，
 * 解码后写入 Downloads/Bilup/ 目录。
 *
 * 解决 blob: 协议 URL（如 blob:http://localhost/xxx）无法被
 * DownloadManager / HttpURLConnection 直接处理的问题。
 *
 * 保存优先级（多层降级）：
 *   1. MediaStore.Downloads (Android 10+)
 *   2. MediaStore.Files (Android 10+, 降级备选)
 *   3. 公共 Downloads 目录直接写入 (Android 9-)
 *   4. 应用私有外部存储 (Android 全版本, 无需额外权限)
 */
public class BlobReceiver {
    private static final String TAG = "BilupBlobReceiver";
    private static final String BILUP_DIR = "Bilup";

    private final Context context;

    public BlobReceiver(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void saveBlob(String base64Data, String fileName, String mimeType) {
        String safeName = (fileName != null && !fileName.isEmpty()) ? fileName
                : "Bilup_project_" + System.currentTimeMillis() + ".sb3";
        String safeMime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "application/octet-stream";

        Log.d(TAG, "saveBlob called: fileName=" + safeName + ", mimeType=" + safeMime + ", dataLen="
                + (base64Data != null ? base64Data.length() : 0));

        try {
            if (base64Data == null || base64Data.isEmpty()) {
                throw new IllegalArgumentException("base64Data is null or empty");
            }
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
            if (data.length == 0) {
                throw new IOException("解码后数据为空");
            }
            saveBytesToFile(data, safeName, safeMime);
            showToast("文件已保存至 \u4E0B\u8F7D/Bilup/" + safeName);
            Log.i(TAG, "Save SUCCESS: " + safeName);
        } catch (Exception e) {
            Log.e(TAG, "Save FAILED: " + safeName, e);
            showToast("文件保存失败");
        }
    }

    /**
     * 多层降级保存链：
     *   1. MediaStore.Downloads (Android 10+)
     *   2. MediaStore.Files (Android 10+ 降级)
     *   3. saveViaFileSystem (公共 Downloads 目录)
     *   4. saveToAppPrivateDir (应用私有目录，无需权限)
     */
    private void saveBytesToFile(byte[] data, String fileName, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (saveViaMediaStoreDownload(data, fileName, mimeType)) {
                return;
            }
            Log.w(TAG, "MediaStore.Downloads failed, trying MediaStore.Files...");
            if (saveViaMediaStoreFiles(data, fileName, mimeType)) {
                return;
            }
            Log.w(TAG, "MediaStore.Files failed, trying direct file system...");
        }
        // Android 9- 或 MediaStore 全部失败 → 直接文件系统
        try {
            saveViaFileSystem(data, fileName);
            return;
        } catch (Exception e) {
            Log.w(TAG, "Direct file system failed: " + e.getMessage());
        }
        // 最终降级：应用私有目录
        Log.w(TAG, "All public paths failed, trying app-private directory...");
        saveToAppPrivateDir(data, fileName);
    }

    // ==================== MediaStore.Downloads (Android 10+) ====================

    private boolean saveViaMediaStoreDownload(byte[] data, String fileName, String mimeType)
            throws Exception {
        ContentValues values = buildContentValues(fileName, mimeType);
        ContentResolver resolver = context.getContentResolver();

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Log.w(TAG, "MediaStore.Downloads insert returned null");
            return false;
        }

        return writeToMediaStoreUri(resolver, uri, data, fileName);
    }

    // ==================== MediaStore.Files (Android 10+ 降级) ====================

    private boolean saveViaMediaStoreFiles(byte[] data, String fileName, String mimeType)
            throws Exception {
        ContentValues values = buildContentValues(fileName, mimeType);
        ContentResolver resolver = context.getContentResolver();

        // 尝试用 Files API
        Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) {
            Log.w(TAG, "MediaStore.Files insert returned null");
            return false;
        }

        return writeToMediaStoreUri(resolver, uri, data, fileName);
    }

    // ==================== 公共方法：写入 MediaStore URI ====================

    private boolean writeToMediaStoreUri(ContentResolver resolver, Uri uri,
                                         byte[] data, String fileName) throws Exception {
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                Log.w(TAG, "openOutputStream returned null for URI: " + uri);
                return false;
            }
            out.write(data);
            out.flush();
        }

        // 清除 IS_PENDING 标志
        ContentValues updateValues = new ContentValues();
        updateValues.put(MediaStore.Downloads.IS_PENDING, 0);
        int rows = resolver.update(uri, updateValues, null, null);
        Log.d(TAG, "MediaStore IS_PENDING cleared, rows=" + rows + ", fileName=" + fileName);

        // 强制 MediaStore 扫描，确保文件在各品牌设备上都立即可见（尤其小米 MIUI、OPPO ColorOS 等）
        final String displayPath = Environment.DIRECTORY_DOWNLOADS + "/" + BILUP_DIR + "/" + fileName;
        triggerMediaScan(displayPath);

        return true;
    }

    // ==================== 构建 ContentValues ====================

    private ContentValues buildContentValues(String fileName, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE,
                mimeType != null ? mimeType : "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + BILUP_DIR);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        return values;
    }

    // ==================== 直接文件系统写入 (Android 9-) ====================

    private void saveViaFileSystem(byte[] data, String fileName) throws Exception {
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
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
        Log.d(TAG, "Saved via file system: " + file.getAbsolutePath());
        triggerMediaScan(file.getAbsolutePath());
    }

    // ==================== 应用私有目录 (最终降级，无需权限) ====================

    private void saveToAppPrivateDir(byte[] data, String fileName) throws Exception {
        File appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appDir == null) {
            appDir = context.getFilesDir();
        }
        File dir = new File(appDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建应用私有目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
        Log.i(TAG, "Saved to app-private directory: " + file.getAbsolutePath());
        triggerMediaScan(file.getAbsolutePath());
        // saveBlob 会统一显示成功 Toast，此处不再重复显示
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
            // MediaScanner 不可用时不影响文件保存，仅记录日志
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
