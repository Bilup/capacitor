package org.bilup.app;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {
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
            }
        });
    }
    
    private void configureWebViewSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        
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
        String jsCode = "(function() {" +
            "var designWidth = 1280;" +
            "var initialScale = Math.min(window.innerWidth / designWidth, 0.7);" +
            "initialScale = Math.max(initialScale, 0.3);" +
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
        
        webView.evaluateJavascript(jsCode, null);
    }
    
    private void injectMobileRestrictions(WebView webView) {
        String jsCode = "(function() {" +
            "if (window.Capacitor) {" +
                "var style = document.createElement('style');" +
                "style.textContent = 'body { min-width: 1280px; overflow: auto; }';" +
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
                    "/^\\s*About Us\\s*$/" +
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
                            "href.indexOf('about') !== -1;" +
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
