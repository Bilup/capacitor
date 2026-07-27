package org.bilup.app;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 接收 JavaScript 通过 BilupFileBridge.saveBlob() 传递的 base64 数据，
 * 解码后写入应用私有目录：Android/data/org.bilup.app.phone/files/Download/Bilup/
 *
 * 使用应用私有目录保存，无需额外存储权限，兼容所有 Android 版本和品牌 ROM。
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
            saveBytesToFile(data, safeName);
            showToast("文件已保存至 \u4E0B\u8F7D/Bilup/" + safeName);
            Log.i(TAG, "Save SUCCESS: " + safeName);
        } catch (Exception e) {
            Log.e(TAG, "Save FAILED: " + safeName, e);
            showToast("文件保存失败");
        }
    }

    /**
     * 直接保存到应用私有目录：Android/data/org.bilup.app.phone/files/Download/Bilup/
     * 无需任何存储权限，兼容所有 Android 版本和品牌 ROM。
     */
    private void saveBytesToFile(byte[] data, String fileName) throws Exception {
        File appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appDir == null) {
            appDir = context.getFilesDir();
        }
        File dir = new File(appDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
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
