package org.bilup.app;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

import java.io.OutputStream;

public class MainActivity extends BridgeActivity {
    private static final String DEVICE_TYPE_PHONE = "phone";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                configureEssentialSettings(webView);
            }

            @Override
            public void onPageLoaded(WebView webView) {
                configureDisplaySettings(webView);
                injectViewportMeta(webView);
                injectMobileEnhancements(webView);
                setupDownloadListener(webView);
            }
        });
    }

    private boolean isPhoneDevice() {
        return DEVICE_TYPE_PHONE.equals(BuildConfig.DEVICE_TYPE);
    }

    /**
     * 核心存储/功能设置 — 在 onPageStarted 中调用（页面内容加载前生效）
     */
    private void configureEssentialSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
    }

    /**
     * 显示/布局设置 — 在 onPageLoaded 中调用
     */
    private void configureDisplaySettings(WebView webView) {
        WebSettings settings = webView.getSettings();

        if (isPhoneDevice()) {
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
        } else {
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(true);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
        }

        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setGeolocationEnabled(true);

        webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
    }

    // ======================================================================
    //  文件保存功能
    // ======================================================================

    /**
     * 设置文件下载监听 — 支持 scratch-gui "保存到计算机" 功能
     *
     * Android 10+ 使用 MediaStore（适配分区存储），
     * Android 9 及以下使用 DownloadManager。
     */
    private void setupDownloadListener(WebView webView) {
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimetype, long contentLength) {
                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    if (fileName == null || fileName.isEmpty()) {
                        fileName = "Bilup_project_" + System.currentTimeMillis() + ".sb3";
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveFileWithMediaStore(fileName, mimetype, url);
                    } else {
                        saveFileWithDownloadManager(fileName, mimetype, url);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "文件保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Android 10+ 使用 MediaStore API 保存文件（适配分区存储）
     */
    private void saveFileWithMediaStore(String fileName, String mimeType, String fileUrl) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bilup");

        ContentResolver resolver = getContentResolver();
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                // 若 MediaStore.Downloads 不可用，降级到 MediaStore.Files
                uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
            }
        } catch (Exception ignored) {
            // 部分设备不支持 MediaStore.Downloads，降级到 DownloadManager
            saveFileWithDownloadManager(fileName, mimeType, fileUrl);
            return;
        }

        if (uri != null) {
            // 通过 URL 连接下载文件内容写入
            java.net.URL url = new java.net.URL(fileUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            try (java.io.InputStream input = conn.getInputStream();
                 OutputStream output = resolver.openOutputStream(uri)) {
                if (output != null) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = input.read(buffer)) != -1) {
                        output.write(buffer, 0, len);
                    }
                }
            } finally {
                conn.disconnect();
            }

            Toast.makeText(MainActivity.this,
                    "已保存: " + fileName, Toast.LENGTH_SHORT).show();
        } else {
            saveFileWithDownloadManager(fileName, mimeType, fileUrl);
        }
    }

    /**
     * Android 9 及以下使用 DownloadManager
     */
    private void saveFileWithDownloadManager(String fileName, String mimeType, String fileUrl) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(fileUrl));
            request.setMimeType(mimeType);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Bilup/" + fileName);
            request.setTitle(fileName);
            request.setDescription("正在下载作品文件...");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(MainActivity.this,
                        "正在保存: " + fileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this,
                    "文件保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ======================================================================
    //  移动端 WebView 增强（菜单栏、触摸事件、UI 适配）
    // ======================================================================

    /**
     * 注入 viewport meta 标签 — 在 onPageLoaded 中调用（此时窗口尺寸已准确）
     */
    private void injectViewportMeta(WebView webView) {
        String jsCode = "(function() {" +
            "if (document.querySelector('meta[name=viewport][data-set]')) return;" +
            "var designWidth = 1280;" +
            "var designHeight = 720;" +
            "var scaleX = window.innerWidth / designWidth;" +
            "var scaleY = window.innerHeight / designHeight;" +
            "var scale = Math.min(scaleX, scaleY);" +
            "scale = Math.max(scale, 0.5);" +
            "scale = Math.min(scale, 1.0);" +
            "var viewport = document.querySelector('meta[name=viewport]');" +
            "var content = 'width=device-width, initial-scale=' + scale + ', maximum-scale=1.0, viewport-fit=cover';" +
            "if (viewport) {" +
                "viewport.content = content;" +
                "viewport.setAttribute('data-set', 'true');" +
            "} else {" +
                "var meta = document.createElement('meta');" +
                "meta.name = 'viewport';" +
                "meta.content = content;" +
                "meta.setAttribute('data-set', 'true');" +
                "document.head.appendChild(meta);" +
            "}" +
        "})();";

        webView.evaluateJavascript(jsCode, null);
    }

    /**
     * 注入移动端增强脚本：
     * 1. CSS 适配（菜单栏触摸滚动、滚动条美化）
     * 2. 隐藏受限 UI 元素（通过精确匹配，避免误伤菜单栏）
     * 3. 菜单栏触摸事件增强 — 将 mouseenter/mouseleave 转为 touch 事件
     */
    private void injectMobileEnhancements(WebView webView) {
        String phoneFlag = "'" + BuildConfig.DEVICE_TYPE + "' === 'phone'";
        String jsCode = "(function() {" +
            "if (!window.Capacitor || document.getElementById('bilup-enhance')) return;" +
            "var enh = document.createElement('div'); enh.id = 'bilup-enhance'; enh.style.display = 'none'; document.body.appendChild(enh);" +
            /* ========== CSS 注入 ========== */
            "var style = document.createElement('style');" +
            "style.textContent = '" +
                "body { overflow-x: hidden; -webkit-tap-highlight-color: transparent; } " +
                "input, textarea { font-size: 16px; } " +
                /* 菜单栏水平触摸滚动 */
                "[class*=\"menuBar\"], [class*=\"menu-bar\"] { " +
                    "overflow-x: auto; overflow-y: hidden; " +
                    "-webkit-overflow-scrolling: touch; " +
                    "scroll-behavior: smooth; " +
                    "-ms-overflow-style: none; scrollbar-width: none; " +
                "} " +
                "[class*=\"menuBar\"]::-webkit-scrollbar, [class*=\"menu-bar\"]::-webkit-scrollbar { display: none; } " +
                "::-webkit-scrollbar { width: 4px; height: 4px; } " +
                "::-webkit-scrollbar-track { background: transparent; } " +
                "::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.2); border-radius: 2px; }" +
            "';" +
            "document.head.appendChild(style);" +
            /* ========== 隐藏受限 UI 元素（精确匹配，非子串匹配） ========== */
            "var restrictedExact = {" +
                "\"隐私政策\":1,\"Privacy Policy\":1,\"鸣谢\":1,\"Credits\":1," +
                "\"关于\":1,\"About\":1,\"关于我们\":1,\"About Us\":1," +
                "\"捐赠\":1,\"Donate\":1,\"切换到作品页面\":1" +
            "};" +
            "document.querySelectorAll('button, a').forEach(function(el) {" +
                "var text = (el.textContent || '').trim();" +
                "if (restrictedExact[text]) {" +
                    "el.style.display = 'none'; el.disabled = true;" +
                "}" +
            "});" +
            /* ========== 菜单栏触摸事件增强 ========== */
            "function enhanceMenuBar() {" +
                "var menuBars = document.querySelectorAll('[class*=\"menuBar\"], [class*=\"menu-bar\"]');" +
                "menuBars.forEach(function(bar) {" +
                    "if (bar.dataset.bilupEnhanced) return;" +
                    "bar.dataset.bilupEnhanced = 'true';" +
                    /* 子菜单项 — 触摸展开下拉菜单（模拟 mouseenter） */
                    "bar.querySelectorAll('[class*=\"menu-item\"], [class*=\"menuItem\"], li, [role=\"menuitem\"]').forEach(function(item) {" +
                        "item.addEventListener('touchstart', function(e) {" +
                            "var touch = e.changedTouches[0];" +
                            "var clickEvent = new MouseEvent('click', {" +
                                "bubbles: true, cancelable: true, " +
                                "clientX: touch.clientX, clientY: touch.clientY" +
                            "});" +
                            "this.dispatchEvent(clickEvent);" +
                        "}, { passive: true });" +
                    "});" +
                "});" +
            "}" +
            "enhanceMenuBar();" +
            /* 动态内容变化时重新增强 */
            "var observer = new MutationObserver(function() { enhanceMenuBar(); });" +
            "observer.observe(document.body, { childList: true, subtree: true });" +
        "})();";

        webView.evaluateJavascript(jsCode, null);
    }
}
