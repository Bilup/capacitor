package org.bilup.app.webview;

import android.webkit.WebSettings;
import android.webkit.WebView;

public class WebViewConfigurator {
    private final boolean isPhone;
    
    public WebViewConfigurator(boolean isPhone) {
        this.isPhone = isPhone;
    }
    
    public void configure(WebView webView) {
        WebSettings settings = webView.getSettings();
        
        configureZoomSettings(settings);
        configureCoreSettings(settings);
        
        webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
    }
    
    private void configureZoomSettings(WebSettings settings) {
        if (isPhone) {
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
    }
    
    private void configureCoreSettings(WebSettings settings) {
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
    }
}
