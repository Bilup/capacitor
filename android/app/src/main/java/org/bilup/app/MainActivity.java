package org.bilup.app;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

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
