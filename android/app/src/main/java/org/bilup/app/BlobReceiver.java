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
 * 接收 JavaScript 通过 BilupFileBridge 传递的作品数据，写入公共 Download/Bilup/ 目录。
 *
 * <p>采用「分块流式」协议替代原先的「整文件 base64 一次性桥接」：
 * <pre>
 *   beginBlobSave(name, mime, totalBytes) → 打开输出流，返回是否成功
 *   appendBlobChunk(base64Chunk) × N      → 解码并写入一片数据（每片约 4MB）
 *   endBlobSave() / abortBlobSave()       → 收尾提交或丢弃半成品
 * </pre>
 *
 * <p>原实现需要把整个文件转成 base64 字符串，经 JS 堆 → 桥接层 → Java 堆 → 解码后
 * 字节数组共 4 份拷贝（base64 本身还膨胀 33%），峰值内存约为文件的 5~6 倍，
 * 100MB 作品必然 OOM。分块后任一时刻只持有一片（约数百 KB），
 * <b>峰值内存与文件大小无关</b>，因此不再需要原先 100MB 的硬性上限。
 *
 * <p>保存位置（与旧实现一致，用户可在系统文件管理器中直接找到）：
 * <ul>
 *   <li>Android 10+ (API 29+)：MediaStore.Downloads，无需权限</li>
 *   <li>Android 7-9 (API 24-28)：公共 Download/Bilup/ 目录，需 WRITE_EXTERNAL_STORAGE</li>
 * </ul>
 */
public class BlobReceiver {
    private static final String TAG = "BilupBlobReceiver";
    private static final String BILUP_DIR = "Bilup";

    /**
     * 单个文件大小上限（512MB）。
     * 流式写入后内存占用与文件大小无关，此上限仅用于拦截误触的超大文件，
     * 避免长时间占用 IO 与磁盘空间，而非内存保护。
     */
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    private final Context context;
    private final WebView webView;

    /** 当前进行中的保存会话（同一时刻只允许一个），null 表示无会话。 */
    private PendingSave pending;

    public BlobReceiver(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
    }

    /**
     * 清洗文件名，移除文件系统不允许的非法字符，防止保存失败。
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "Bilup_project_" + System.currentTimeMillis() + ".sb3";
        }
        // 移除路径分隔符及 Windows/Android 文件系统非法字符
        String cleaned = fileName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "Bilup_project_" + System.currentTimeMillis() + ".sb3";
        }
        // 限制文件名长度，避免文件系统限制（255 字节）
        if (cleaned.length() > 150) {
            cleaned = cleaned.substring(0, 150);
        }
        return cleaned;
    }

    // ==================== 分块流式保存协议 ====================

    /**
     * 开始一次保存：创建目标并打开输出流。
     * 若已有未完成的会话，先丢弃它（避免输出流泄漏）。
     *
     * @param totalBytes JS 侧提供的文件总大小，仅用于提前拒绝超大文件
     * @return true 表示可以开始追加数据；false 表示已拒绝（并已回调失败事件）
     */
    @JavascriptInterface
    public boolean beginBlobSave(String fileName, String mimeType, long totalBytes) {
        synchronized (this) {
            // 上一次会话未正常结束（如页面被重载）时先清理，防止句柄与半成品文件泄漏
            abortLocked();

            String safeName = sanitizeFileName(fileName);
            String safeMime = (mimeType != null && !mimeType.isEmpty())
                    ? mimeType : "application/octet-stream";

            if (totalBytes > MAX_FILE_BYTES) {
                Log.w(TAG, "beginBlobSave rejected, too large: " + fileName + ", bytes=" + totalBytes);
                notifySaveFailed(safeName);
                return false;
            }

            try {
                PendingSave session = openOutput(safeName, safeMime);
                session.fileName = safeName;
                session.mimeType = safeMime;
                session.totalBytes = totalBytes;
                pending = session;
                Log.d(TAG, "beginBlobSave: fileName=" + safeName + ", mimeType=" + safeMime
                        + ", totalBytes=" + totalBytes);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "beginBlobSave failed: " + safeName, t);
                notifySaveFailed(safeName);
                return false;
            }
        }
    }

    /**
     * 追加一片数据（base64 字符串，约 4MB 原始数据）。
     * 解码后立即写入输出流并释放，不在内存中累积。
     *
     * @return false 表示会话已失效或写入失败（JS 侧应停止继续发送）
     */
    @JavascriptInterface
    public boolean appendBlobChunk(String base64Chunk) {
        synchronized (this) {
            if (pending == null) {
                Log.w(TAG, "appendBlobChunk without active session");
                return false;
            }
            try {
                if (base64Chunk != null && !base64Chunk.isEmpty()) {
                    // 用 NO_WRAP：data URL 中的 base64 不含换行，
                    // 跳过 DEFAULT 的换行扫描/处理可减少解码耗时。
                    byte[] data = Base64.decode(base64Chunk, Base64.NO_WRAP);
                    pending.out.write(data);
                    pending.bytesWritten += data.length;
                    if (pending.bytesWritten > MAX_FILE_BYTES) {
                        throw new IOException("文件过大，无法在移动端保存");
                    }
                }
                return true;
            } catch (Throwable t) {
                // 必须捕获 Throwable：解码/写入仍可能抛 OutOfMemoryError，漏掉会直接崩溃
                Log.e(TAG, "appendBlobChunk failed", t);
                String name = pending.fileName;
                abortLocked();
                notifySaveFailed(name);
                return false;
            }
        }
    }

    /**
     * 结束保存：关闭输出流并把文件提交到公共目录（MediaStore 置为可见 / 触发媒体扫描）。
     *
     * @return true 表示保存成功
     */
    @JavascriptInterface
    public boolean endBlobSave() {
        PendingSave session;
        synchronized (this) {
            session = pending;
            pending = null;
        }
        if (session == null) {
            Log.w(TAG, "endBlobSave without active session");
            return false;
        }
        try {
            session.out.flush();
            session.out.close();
            if (session.bytesWritten <= 0) {
                throw new IOException("解码后数据为空");
            }
            Uri fileUri = finalizeOutput(session);
            notifySaveSuccess(fileUri, session.fileName, session.mimeType);
            Log.i(TAG, "Save SUCCESS: " + session.fileName + ", bytes=" + session.bytesWritten);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "endBlobSave failed: " + session.fileName, t);
            cleanupOutput(session);
            notifySaveFailed(session.fileName);
            return false;
        }
    }

    /**
     * 丢弃当前未完成的保存会话，删除半成品文件。
     * JS 侧在分片失败时调用；宿主在销毁 / 渲染进程消失时也应调用以释放输出流。
     */
    @JavascriptInterface
    public void abortBlobSave() {
        synchronized (this) {
            abortLocked();
        }
    }

    /** 调用方需已持有 this 的监视器。 */
    private void abortLocked() {
        if (pending == null) return;
        PendingSave session = pending;
        pending = null;
        cleanupOutput(session);
    }

    // ==================== 输出流生命周期 ====================

    /**
     * 按 API 级别创建目标并打开输出流。
     * - API 29+：MediaStore 先插入 IS_PENDING=1 的记录，拿到可写 URI
     * - API 24-28：直接创建公共存储目录下的文件
     */
    private PendingSave openOutput(String fileName, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + BILUP_DIR);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert returned null");
            }
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                    // 清理失败不影响错误上报
                }
                throw new IOException("无法打开 MediaStore OutputStream");
            }
            return new PendingSave(out, uri, null);
        }

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloadDir, BILUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        return new PendingSave(new FileOutputStream(file), null, file);
    }

    /**
     * 提交输出：MediaStore 置为可见（IS_PENDING=0）或触发媒体扫描。
     *
     * @return 文件的 content:// URI（可在 Intent 中直接使用）
     */
    private Uri finalizeOutput(PendingSave session) throws Exception {
        if (session.mediaUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(session.mediaUri, values, null, null);
            Log.i(TAG, "Saved via MediaStore: " + session.mediaUri);
            return session.mediaUri;
        }
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{session.file.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            Log.w(TAG, "MediaScanner failed: " + e.getMessage());
        }
        Log.i(TAG, "Saved to public storage: " + session.file.getAbsolutePath());
        String authority = context.getPackageName() + ".fileprovider";
        return FileProvider.getUriForFile(context, authority, session.file);
    }

    /** 关闭输出流并删除半成品（失败或中止时调用）。 */
    private void cleanupOutput(PendingSave session) {
        try {
            session.out.close();
        } catch (Exception ignored) {
            // 已关闭或关闭失败均无需处理
        }
        if (session.mediaUri != null) {
            try {
                context.getContentResolver().delete(session.mediaUri, null, null);
            } catch (Exception ignored) {
                // 记录可能已被删除
            }
        } else if (session.file != null) {
            try {
                if (session.file.exists()) {
                    session.file.delete();
                }
            } catch (Exception ignored) {
                // 删除失败不影响主流程
            }
        }
    }

    /** 一次保存会话的可变状态。 */
    private static final class PendingSave {
        /** 目标输出流，分片依次写入 */
        final OutputStream out;
        /** API 29+ 的 MediaStore 记录 URI，否则为 null */
        final Uri mediaUri;
        /** API 24-28 的目标文件，否则为 null */
        final File file;

        String fileName;
        String mimeType;
        long totalBytes;
        long bytesWritten;

        PendingSave(OutputStream out, Uri mediaUri, File file) {
            this.out = out;
            this.mediaUri = mediaUri;
            this.file = file;
        }
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

                // 2. JS 回调：通知前端保存完成（携带可展示的路径）
                // WebView 可能已被销毁（如用户保存后立即关闭页面），
                // evaluateJavascript 会抛 IllegalStateException，必须保护
                try {
                    String jsCallback = "javascript:(function(){"
                        + "var e = new CustomEvent('bilupSaveComplete', {"
                        + "  detail: { fileName: '" + fileName.replace("'", "\\'") + "', path: '" +
                        ("下载/Bilup/" + fileName).replace("'", "\\'") + "' }"
                        + "});"
                        + "document.dispatchEvent(e);"
                        + "})();";
                    if (WebViewEnhancer.isAlive(webView)) {
                        webView.evaluateJavascript(jsCallback, null);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "JS callback failed", e);
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
                // WebView 可能已被销毁，必须保护（见 notifySaveSuccess 注释）
                try {
                    String jsCallback = "javascript:(function(){"
                        + "var e = new CustomEvent('bilupSaveFailed', {"
                        + "  detail: { fileName: '" + fileName.replace("'", "\\'") + "' }"
                        + "});"
                        + "document.dispatchEvent(e);"
                        + "})();";
                    if (WebViewEnhancer.isAlive(webView)) {
                        webView.evaluateJavascript(jsCallback, null);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "JS callback failed", e);
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
}