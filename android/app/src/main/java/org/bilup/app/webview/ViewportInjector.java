package org.bilup.app.webview;

import android.webkit.WebView;

public class ViewportInjector {
    private final boolean isPhone;
    
    public ViewportInjector(boolean isPhone) {
        this.isPhone = isPhone;
    }
    
    public void inject(WebView webView) {
        String jsCode = isPhone ? createPhoneViewportJs() : createTabletViewportJs();
        webView.evaluateJavascript(jsCode, null);
    }
    
    private String createPhoneViewportJs() {
        return "(function() {" +
            "var designWidth = 1280;" +
            "var designHeight = 720;" +
            "var scaleX = window.innerWidth / designWidth;" +
            "var scaleY = window.innerHeight / designHeight;" +
            "var initialScale = Math.min(scaleX, scaleY);" +
            "initialScale = Math.min(initialScale, 0.85);" +
            "initialScale = Math.max(initialScale, 0.35);" +
            "var viewport = document.querySelector('meta[name=viewport]');" +
            "if (viewport) {" +
                "viewport.content = 'width=' + designWidth + ', initial-scale=' + initialScale + ', maximum-scale=1.0, user-scalable=no, viewport-fit=cover';" +
            "} else {" +
                "var meta = document.createElement('meta');" +
                "meta.name = 'viewport';" +
                "meta.content = 'width=' + designWidth + ', initial-scale=' + initialScale + ', maximum-scale=1.0, user-scalable=no, viewport-fit=cover';" +
                "document.head.appendChild(meta);" +
            "}" +
        "})();";
    }
    
    private String createTabletViewportJs() {
        return "(function() {" +
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
    }
}
