package org.bilup.app.webview;

import android.webkit.WebView;

public class ContentRestrictionInjector {
    private final boolean isPhone;
    
    public ContentRestrictionInjector(boolean isPhone) {
        this.isPhone = isPhone;
    }
    
    public void inject(WebView webView) {
        String jsCode = createJsCode();
        webView.evaluateJavascript(jsCode, null);
    }
    
    private String createJsCode() {
        return "(function() {" +
            "if (window.Capacitor) {" +
                createStyleInjection() +
                createRestrictedPatterns() +
                createHideFunction() +
                createObserverSetup() +
                createClickInterceptor() +
            "}" +
        "})();";
    }
    
    private String createStyleInjection() {
        String mobileStyles = "body { min-width: 1280px; overflow-x: auto; -webkit-tap-highlight-color: transparent; } " +
            "button, a { -webkit-tap-highlight-color: transparent; touch-action: manipulation; } " +
            "input, textarea { font-size: 16px; } " +
            "[class*=\"menuBar\"], [class*=\"menu-bar\"] { overflow-x: auto; -webkit-overflow-scrolling: touch; } " +
            "::-webkit-scrollbar { width: 4px; height: 4px; } " +
            "::-webkit-scrollbar-track { background: transparent; } " +
            "::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.2); border-radius: 2px; }";
        
        String tabletStyles = "body { min-width: 1280px; overflow: auto; }";
        
        return "var style = document.createElement('style');" +
            "style.textContent = '" + (isPhone ? mobileStyles : tabletStyles) + "';" +
            "document.head.appendChild(style);";
    }
    
    private String createRestrictedPatterns() {
        return "var debounceTimer = null;" +
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
            "];";
    }
    
    private String createHideFunction() {
        return "var hideRestrictedElements = function() {" +
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
        "hideRestrictedElements();";
    }
    
    private String createObserverSetup() {
        return "if (document.body) {" +
            "var observer = new MutationObserver(function(mutations) {" +
                "if (debounceTimer) {" +
                    "clearTimeout(debounceTimer);" +
                "}" +
                "debounceTimer = setTimeout(hideRestrictedElements, 100);" +
            "});" +
            "observer.observe(document.body, { childList: true, subtree: true });" +
        "}";
    }
    
    private String createClickInterceptor() {
        return "document.addEventListener('click', function(e) {" +
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
        "}, true);";
    }
}
