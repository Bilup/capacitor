package org.bilup.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
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
 */
public class BlobReceiver {

    private final Context context;

    public BlobReceiver(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void saveBlob(String base64Data, String fileName, String mimeType) {
        try {
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
            saveBytesToFile(data, fileName, mimeType);
            showToast("文件已保存至 下载/Bilup/" + fileName);
        } catch (Exception e) {
            showToast("文件保存失败");
        }
    }

    private String saveBytesToFile(byte[] data, String fileName, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — MediaStore
            return saveViaMediaStore(data, fileName, mimeType);
        } else {
            // Android 9 及以下 — 直接写入文件
            return saveViaFileSystem(data, fileName);
        }
    }

    private String saveViaMediaStore(byte[] data, String fileName, String mimeType) throws Exception {
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
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法打开输出流，URI: " + uri);
                }
                out.write(data);
            }
            ContentValues updateValues = new ContentValues();
            updateValues.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, updateValues, null, null);
            return "下载/Bilup";
        }
        return saveViaFileSystem(data, fileName);
    }

    private String saveViaFileSystem(byte[] data, String fileName) throws Exception {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            throw new IOException("无法获取下载目录");
        }
        File dir = new File(downloadDir, "Bilup");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
        return "下载/Bilup";
    }

    private void showToast(final String message) {
        // 确保在主线程显示 Toast
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
