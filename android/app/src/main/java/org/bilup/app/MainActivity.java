package org.bilup.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {
    private static final String DEVICE_TYPE_PHONE = "phone";
    private static final int REQUEST_CODE_STORAGE_PERMISSION = 100;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAndRequestStoragePermission();

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                configureEssentialSettings(webView);
            }

            @Override
            public void onPageLoaded(WebView webView) {
                configureDisplaySettings(webView);
                // 注入 JS 接口必须在 evaluateJavascript 之前
                webView.addJavascriptInterface(new BlobReceiver(MainActivity.this), "BilupFileBridge");
                WebViewEnhancer.injectViewportMeta(webView);
                WebViewEnhancer.injectMobileEnhancements(webView);
                new FileDownloadHelper(MainActivity.this, webView).setupDownloadListener();
            }
        });
    }

    /**
     * 检测并请求修改手机存储的权限（运行时权限）。
     * - Android 6-9 (API 23-28): 需要 WRITE_EXTERNAL_STORAGE
     * - Android 10-12 (API 29-32): 需要 READ_EXTERNAL_STORAGE
     * - Android 13+ (API 33+): 使用 MediaStore 无需额外权限
     */
    private void checkAndRequestStoragePermission() {
        String permission = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+, MediaStore 无需存储权限
            return;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12: READ_EXTERNAL_STORAGE
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        } else {
            // Android 6-9: WRITE_EXTERNAL_STORAGE
            permission = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{permission}, REQUEST_CODE_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 权限请求结果在此处理，用户拒绝后不影响已有功能（MediaStore 备用方案可用）
    }

    private boolean isPhoneDevice() {
        return DEVICE_TYPE_PHONE.equals(BuildConfig.DEVICE_TYPE);
    }

    private void configureEssentialSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
    }

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
}
