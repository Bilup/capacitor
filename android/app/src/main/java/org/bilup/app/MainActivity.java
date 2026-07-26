package org.bilup.app;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {
    private static final String DEVICE_TYPE_PHONE = "phone";
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                // 页面加载前配置关键存储设置，确保 localStorage/IndexedDB 可用
                configureEssentialSettings(webView);
                injectViewportMeta(webView);
            }
            
            @Override
            public void onPageLoaded(WebView webView) {
                // 页面加载后配置显示相关设置和功能增强
                configureDisplaySettings(webView);
                injectMobileRestrictions(webView);
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
    
    /**
     * 设置文件下载监听 — 支持 scratch-gui "保存到计算机" 功能
     */
    private void setupDownloadListener(WebView webView) {
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimetype, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    
                    // 从 URL 或 Content-Disposition 中提取文件名
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    if (fileName == null || fileName.isEmpty()) {
                        fileName = "Bilup_project_" + System.currentTimeMillis() + ".sb3";
                    }
                    
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
        });
    }
    
    private void injectViewportMeta(WebView webView) {
        String jsCode = "(function() {" +
            "var designWidth = 1280;" +
            "var designHeight = 720;" +
            "var scaleX = window.innerWidth / designWidth;" +
            "var scaleY = window.innerHeight / designHeight;" +
            /* 取宽高比中的较小值，确保内容完全可见 */
            "var scale = Math.min(scaleX, scaleY);" +
            /* 最小 0.5 保证可读性，最大 1.0 防止放大 */
            "scale = Math.max(scale, 0.5);" +
            "scale = Math.min(scale, 1.0);" +
            "var viewport = document.querySelector('meta[name=viewport]');" +
            "var content = 'width=device-width, initial-scale=' + scale + ', maximum-scale=1.0, viewport-fit=cover';" +
            "if (viewport) {" +
                "viewport.content = content;" +
            "} else {" +
                "var meta = document.createElement('meta');" +
                "meta.name = 'viewport';" +
                "meta.content = content;" +
                "document.head.appendChild(meta);" +
            "}" +
        "})();";

        webView.evaluateJavascript(jsCode, null);
    }
    
    private void injectMobileRestrictions(WebView webView) {
        String jsCode = "(function() {" +
            "if (!window.Capacitor) return;" +
            "var isPhone = '" + BuildConfig.DEVICE_TYPE + "' === 'phone';" +
            /* 使用 CSS 注入样式 — 不再强制 min-width: 1280px，避免右侧按钮被裁剪 */
            "var style = document.createElement('style');" +
            "style.textContent = '" +
                "body { overflow-x: hidden; -webkit-tap-highlight-color: transparent; } " +
                "button, a { -webkit-tap-highlight-color: transparent; touch-action: manipulation; } " +
                "input, textarea { font-size: 16px; } " +
                "[class*=\"menuBar\"], [class*=\"menu-bar\"] { overflow-x: auto; -webkit-overflow-scrolling: touch; } " +
                "::-webkit-scrollbar { width: 4px; height: 4px; } " +
                "::-webkit-scrollbar-track { background: transparent; } " +
                "::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.2); border-radius: 2px; }" +
            "';" +
            "document.head.appendChild(style);" +
            /* 隐藏受限UI元素 — 仅做一次清理，不持续监听 */
            "var restrictedTexts = [" +
                "'隐私政策', 'Privacy Policy', '隐私', 'Privacy', " +
                "'鸣谢', 'Credits', '关于', 'About', '关于我们', 'About Us', " +
                "'捐赠', 'Donate', '切换到作品页面'" +
            "];" +
            "document.querySelectorAll('button, a').forEach(function(el) {" +
                "var text = (el.textContent || '').trim();" +
                "var matched = restrictedTexts.some(function(t) { return text.indexOf(t) !== -1; });" +
                "if (matched) { el.style.display = 'none'; el.disabled = true; }" +
            "});" +
            "var group = document.querySelector('[class*=\"account-info-group\"]');" +
            "if (group) { var btns = group.querySelectorAll('button'); if (btns.length > 0) btns[btns.length - 1].style.display = 'none'; }" +
            /* 点击拦截 */
            "document.addEventListener('click', function(e) {" +
                "var el = e.target;" +
                "var text = (el.textContent || '').trim();" +
                "var href = el.getAttribute('href') || '';" +
                "if (restrictedTexts.some(function(t) { return text.indexOf(t) !== -1; }) || " +
                    "/privacy|credits|about|donate/i.test(href)) {" +
                    "e.preventDefault(); e.stopPropagation();" +
                "}" +
            "}, true);" +
        "})();";

        webView.evaluateJavascript(jsCode, null);
    }
}
