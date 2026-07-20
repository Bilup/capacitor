package org.bilup.app;

import android.content.res.Configuration;
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
                injectViewportMeta(webView);
            }
            
            @Override
            public void onPageLoaded(WebView webView) {
                configureWebViewSettings(webView);
                injectMobileRestrictions(webView);
                injectOrientationStyles(webView);
            }
        });
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            injectViewportMeta(webView);
            injectOrientationStyles(webView);
        }
    }
    
    private boolean isPhoneDevice() {
        return DEVICE_TYPE_PHONE.equals(BuildConfig.DEVICE_TYPE);
    }
    
    private void configureWebViewSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        
        if (isPhoneDevice()) {
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
        } else {
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(true);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
        }
        
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        
        webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
    }
    
    private void injectViewportMeta(WebView webView) {
        String jsCode;
        
        if (isPhoneDevice()) {
            jsCode = "(function() {" +
                "var isLandscape = window.innerWidth > window.innerHeight;" +
                "var designWidth = isLandscape ? 1280 : 720;" +
                "var designHeight = isLandscape ? 720 : 1280;" +
                "var scaleX = window.innerWidth / designWidth;" +
                "var scaleY = window.innerHeight / designHeight;" +
                "var initialScale = Math.min(scaleX, scaleY);" +
                "initialScale = Math.min(initialScale, isLandscape ? 0.95 : 0.85);" +
                "initialScale = Math.max(initialScale, isLandscape ? 0.5 : 0.35);" +
                "var viewport = document.querySelector('meta[name=viewport]');" +
                "if (viewport) {" +
                    "viewport.content = 'width=' + designWidth + ', initial-scale=' + initialScale + ', maximum-scale=3.0, minimum-scale=0.3, user-scalable=yes, viewport-fit=cover';" +
                "} else {" +
                    "var meta = document.createElement('meta');" +
                    "meta.name = 'viewport';" +
                    "meta.content = 'width=' + designWidth + ', initial-scale=' + initialScale + ', maximum-scale=3.0, minimum-scale=0.3, user-scalable=yes, viewport-fit=cover';" +
                    "document.head.appendChild(meta);" +
                "}" +
            "})();";
        } else {
            jsCode = "(function() {" +
                "var isLandscape = window.innerWidth > window.innerHeight;" +
                "var designWidth = isLandscape ? 1280 : 720;" +
                "var scaleX = window.innerWidth / designWidth;" +
                "var scaleY = window.innerHeight / 720;" +
                "var initialScale = Math.min(scaleX, scaleY);" +
                "initialScale = Math.min(initialScale, isLandscape ? 0.9 : 0.7);" +
                "initialScale = Math.max(initialScale, isLandscape ? 0.4 : 0.3);" +
                "var viewport = document.querySelector('meta[name=viewport]');" +
                "if (viewport) {" +
                    "viewport.content = 'width=' + designWidth + ', initial-scale=' + initialScale + ', maximum-scale=2.0, user-scalable=yes';" +
                "} else {" +
                    "var meta = document.createElement('meta');" +
                    "meta.name = 'viewport';" +
                    "meta.content = 'width=' + designWidth + ', initial-scale=' + initialScale + ', maximum-scale=2.0, user-scalable=yes';" +
                    "document.head.appendChild(meta);" +
                "}" +
            "})();";
        }
        
        webView.evaluateJavascript(jsCode, null);
    }
    
    private void injectOrientationStyles(WebView webView) {
        String jsCode = "(function() {" +
            "if (window.Capacitor) {" +
                "var isLandscape = window.innerWidth > window.innerHeight;" +
                "var existingStyle = document.getElementById('bilup-orientation-style');" +
                "if (existingStyle) {" +
                    "existingStyle.remove();" +
                "}" +
                "var style = document.createElement('style');" +
                "style.id = 'bilup-orientation-style';" +
                "var landscapeStyles = '" +
                    "[class*=\"stage-header-wrapper\"], [class*=\"stage-header\"] { flex-direction: row; flex-wrap: nowrap; } " +
                    "[class*=\"stage-selector\"], [class*=\"stage-select\"] { flex-shrink: 0; } " +
                    "[class*=\"gui-wrapper\"], [class*=\"editor-wrapper\"] { flex-direction: row; } " +
                    "[class*=\"blocks-wrapper\"], [class*=\"blocks-container\"] { width: 35%; min-width: 280px; max-width: 400px; } " +
                    "[class*=\"stage-wrapper\"], [class*=\"stage-container\"] { width: 65%; } " +
                    "[class*=\"menu-bar\"] { flex-wrap: nowrap; } " +
                    "[class*=\"monitor-list\"], [class*=\"monitors\"] { max-height: 100%; overflow-y: auto; } " +
                    "[class*=\"blockly-scrollbar\"] { width: 6px; }" +
                "';" +
                "var portraitStyles = '" +
                    "[class*=\"stage-header-wrapper\"], [class*=\"stage-header\"] { flex-direction: column; } " +
                    "[class*=\"gui-wrapper\"], [class*=\"editor-wrapper\"] { flex-direction: column; } " +
                    "[class*=\"blocks-wrapper\"], [class*=\"blocks-container\"] { width: 100%; max-height: 55vh; overflow-y: auto; } " +
                    "[class*=\"stage-wrapper\"], [class*=\"stage-container\"] { width: 100%; min-height: 35vh; } " +
                    "[class*=\"menu-bar\"] { flex-wrap: wrap; } " +
                    "[class*=\"monitor-list\"], [class*=\"monitors\"] { max-height: 200px; } " +
                    "[class*=\"blockly-scrollbar\"] { width: 4px; }" +
                "';" +
                "style.textContent = isLandscape ? landscapeStyles : portraitStyles;" +
                "document.head.appendChild(style);" +
            "}" +
        "})();";
        
        webView.evaluateJavascript(jsCode, null);
    }
    
    private void injectMobileRestrictions(WebView webView) {
        String jsCode = "(function() {" +
            "if (window.Capacitor) {" +
                "var style = document.createElement('style');" +
                "var mobileStyles = '" +
                    "body { min-width: 1280px; overflow-x: auto; -webkit-tap-highlight-color: transparent; touch-action: pan-x pan-y pinch-zoom; } " +
                    "button, a { -webkit-tap-highlight-color: transparent; touch-action: manipulation; } " +
                    "input, textarea { font-size: 16px; } " +
                    "[class*=\"menuBar\"], [class*=\"menu-bar\"] { overflow-x: auto; -webkit-overflow-scrolling: touch; } " +
                    "::-webkit-scrollbar { width: 4px; height: 4px; } " +
                    "::-webkit-scrollbar-track { background: transparent; } " +
                    "::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.2); border-radius: 2px; }" +
                "';" +
                "var tabletStyles = 'body { min-width: 1280px; overflow: auto; }';" +
                "style.textContent = '" + BuildConfig.DEVICE_TYPE + "' === 'phone' ? mobileStyles : tabletStyles;" +
                "document.head.appendChild(style);" +
                "var debounceTimer = null;" +
                "var restrictedPatterns = [" +
                    "/^\\s*隐私政策\\s*$/, " +
                    "/^\\s*Privacy Policy\\s*$/, " +
                    "/^\\s*隐私\\s*$/, " +
                    "/^\\s*Privacy\\s*$/, " +
                    "/^\\s*鸣谢\\s*$/, " +
                    "/^\\s*Credits\\s*$/, " +
                    "/^\\s*关于\\s*$/, " +
                    "/^\\s*About\\s*$/, " +
                    "/^\\s*关于我们\\s*$/, " +
                    "/^\\s*About Us\\s*$/, " +
                    "/^\\s*捐赠\\s*$/, " +
                    "/^\\s*Donate\\s*$/" +
                "];" +
                "var hideRestrictedElements = function() {" +
                    "var buttons = document.querySelectorAll('button, a');" +
                    "buttons.forEach(function(btn) {" +
                        "var text = btn.textContent || btn.innerText || '';" +
                        "var trimmedText = text.trim();" +
                        "if (trimmedText.indexOf('切换到作品页面') !== -1) {" +
                            "btn.style.display = 'none';" +
                            "btn.disabled = true;" +
                            "return;" +
                        "}" +
                        "var isRestricted = restrictedPatterns.some(function(pattern) {" +
                            "return pattern.test(trimmedText);" +
                        "});" +
                        "if (isRestricted) {" +
                            "btn.style.display = 'none';" +
                            "btn.disabled = true;" +
                        "}" +
                    "});" +
                    "var accountInfoGroup = document.querySelector('[class*=\"account-info-group\"]');" +
                    "if (accountInfoGroup) {" +
                        "var infoButtons = accountInfoGroup.querySelectorAll('button');" +
                        "if (infoButtons.length > 0) {" +
                            "var lastBtn = infoButtons[infoButtons.length - 1];" +
                            "lastBtn.style.display = 'none';" +
                        "}" +
                    "}" +
                    "var menuItems = document.querySelectorAll('[class*=\"menuBarItem\"], [class*=\"menu-bar-item\"]');" +
                    "menuItems.forEach(function(item) {" +
                        "var iconChild = item.querySelector('svg, [data-lucide=\"info\"]');" +
                        "if (iconChild && accountInfoGroup && accountInfoGroup.contains(item)) {" +
                            "item.style.display = 'none';" +
                        "}" +
                    "});" +
                "};" +
                "hideRestrictedElements();" +
                "if (document.body) {" +
                    "var observer = new MutationObserver(function(mutations) {" +
                        "if (debounceTimer) {" +
                            "clearTimeout(debounceTimer);" +
                        "}" +
                        "debounceTimer = setTimeout(hideRestrictedElements, 100);" +
                    "});" +
                    "observer.observe(document.body, { childList: true, subtree: true });" +
                "}" +
                "document.addEventListener('click', function(e) {" +
                    "var target = e.target;" +
                    "while (target) {" +
                        "var text = target.textContent || target.innerText || '';" +
                        "var trimmedText = text.trim();" +
                        "var href = target.getAttribute('href') || '';" +
                        "var isRestricted = restrictedPatterns.some(function(pattern) {" +
                            "return pattern.test(trimmedText);" +
                        "});" +
                        "var isRestrictedLink = href.indexOf('privacy') !== -1 || " +
                            "href.indexOf('credits') !== -1 || " +
                            "href.indexOf('about') !== -1 || " +
                            "href.indexOf('donate') !== -1;" +
                        "if (isRestricted || isRestrictedLink) {" +
                            "e.preventDefault();" +
                            "e.stopPropagation();" +
                            "return false;" +
                        "}" +
                        "target = target.parentElement;" +
                    "}" +
                "}, true);" +
            "}" +
        "})();";
        
        webView.evaluateJavascript(jsCode, null);
    }
}
