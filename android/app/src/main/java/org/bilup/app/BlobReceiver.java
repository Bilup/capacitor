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
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 接收 JavaScript 通过 BilupFileBridge.saveBlob() 传递的 base64 数据，
 * 解码后写入公共 Download/Bilup/ 目录，用户可在系统文件管理器中直接找到。
 *
 * 保存策略（兼容所有 Android 版本）：
 * - Android 10+ (API 29+)：使用 MediaStore.Downloads（无需权限）
 * - Android 7-9 (API 24-28)：使用 Environment.getExternalStoragePublicDirectory（需 WRITE_EXTERNAL_STORAGE）
 */
public class BlobReceiver {
    private static final String TAG = "BilupBlobReceiver";
    private static final String BILUP_DIR = "Bilup";

    private final Context context;
    private final WebView webView;

    public BlobReceiver(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
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
            // 保存到公共 Downloads 目录，返回可公开访问的 URI
            Uri fileUri = saveBytesToPublicDownload(data, safeName, safeMime);

            // 保存成功后：打开文件位置预览 + 通知 JS + Toast
            notifySaveSuccess(fileUri, safeName, safeMime);

            Log.i(TAG, "Save SUCCESS: " + safeName);
        } catch (Exception e) {
            Log.e(TAG, "Save FAILED: " + safeName, e);
            notifySaveFailed(safeName);
        }
    }

    /**
     * 将字节数据保存到公共 Download/Bilup/ 目录。
     * - API 29+：通过 MediaStore.Downloads，无需存储权限
     * - API 24-28：通过外部公共存储目录，需 WRITE_EXTERNAL_STORAGE 权限
     *
     * @return 文件的 content:// URI（可在 Intent 中直接使用）
     */
    private Uri saveBytesToPublicDownload(byte[] data, String fileName, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(data, fileName, mimeType);
        } else {
            File file = saveViaPublicStorage(data, fileName);
            // 低版本通过 FileProvider 生成可访问的 URI
            String authority = context.getPackageName() + ".fileprovider";
            return FileProvider.getUriForFile(context, authority, file);
        }
    }

    /**
     * API 29+：使用 MediaStore 保存到公共 Download/Bilup/ 目录。
     * 无需任何存储权限，兼容 Android 10~15+，文件立即在所有文件管理器中可见。
     */
    private Uri saveViaMediaStore(byte[] data, String fileName, String mimeType) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        // RELATIVE_PATH 指定 Download/Bilup/ 目录
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + BILUP_DIR);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("无法打开 MediaStore OutputStream");
            }
            out.write(data);
            out.flush();
        }

        // 写入完成，标记 IS_PENDING = 0 让文件立即可见
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);

        Log.i(TAG, "Saved via MediaStore: " + uri.toString());
        return uri;
    }

    /**
     * API 24-28：通过公共外部存储目录保存。
     * 文件写入后通过 MediaScannerConnection 扫描使其立即可见。
     */
    private File saveViaPublicStorage(byte[] data, String fileName) throws Exception {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloadDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
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


    // ==================== 保存完成后的处理 ====================

    /**
     * 保存成功后：打开文件预览 + JS 回调 + Toast
     */
    private void notifySaveSuccess(final Uri fileUri, final String fileName, final String mimeType) {
        if (!(context instanceof android.app.Activity)) {
            Log.w(TAG, "Context is not Activity, cannot show file location");
            return;
        }
        android.app.Activity activity = (android.app.Activity) context;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 1. 打开文件位置预览（使用可公开访问的 URI）
                openFileLocation(fileUri, mimeType);

                // 2. JS 回调：通知前端保存完成
                String jsCallback = "javascript:(function(){"
                    + "var e = new CustomEvent('bilupSaveComplete', {"
                    + "  detail: { fileName: '" + fileName.replace("'", "\\'") + "' }"
                    + "});"
                    + "document.dispatchEvent(e);"
                    + "})();";
                if (webView != null) {
                    webView.evaluateJavascript(jsCallback, null);
                }

                // 3. Toast 提示
                try {
                    Toast.makeText(context, "已保存到 下载/Bilup/" + fileName,
                            Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Toast failed", e);
                }
            }
        });
    }

    /**
     * 保存失败时：JS 回调 + Toast
     */
    private void notifySaveFailed(final String fileName) {
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        ((android.app.Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 1. JS 回调：通知前端保存失败
                String jsCallback = "javascript:(function(){"
                    + "var e = new CustomEvent('bilupSaveFailed', {"
                    + "  detail: { fileName: '" + fileName.replace("'", "\\'") + "' }"
                    + "});"
                    + "document.dispatchEvent(e);"
                    + "})();";
                if (webView != null) {
                    webView.evaluateJavascript(jsCallback, null);
                }

                // 2. Toast
                try {
                    Toast.makeText(context, "文件保存失败", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Toast failed", e);
                }
            }
        });
    }

    /**
     * 使用系统文件管理器打开已保存文件的位置（"查看文件在哪里"效果）。
     * URI 可能是 MediaStore content URI 或 FileProvider URI，均可被系统文件管理器识别。
     */
    private void openFileLocation(Uri fileUri, String mimeType) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, mimeType != null ? mimeType : "application/octet-stream");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // 从 Activity 启动时不需要 FLAG_ACTIVITY_NEW_TASK，避免部分品牌 ROM 行为异常
            // context 已确认是 Activity，此处直接使用 Activity 的 startActivity

            context.startActivity(intent);
            Log.i(TAG, "Opened file: " + fileUri);
        } catch (Exception e) {
            Log.w(TAG, "Failed to open file: " + e.getMessage());
            // 打开文件失败不影响主流程，用户仍可通过 Toast 路径手动查找
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
