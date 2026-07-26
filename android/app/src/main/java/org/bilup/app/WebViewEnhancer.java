package org.bilup.app;

import android.webkit.WebView;

/**
 * WebView 移动端增强工具类。
 * <p>
 * 注入 viewport meta 和增强脚本，解决菜单栏触摸、blob 下载等移动端适配问题。
 */
public final class WebViewEnhancer {

    private WebViewEnhancer() {
        // 工具类，禁止实例化
    }

    /**
     * 注入 viewport meta 标签 — 在 onPageLoaded 中调用（此时窗口尺寸已准确）
     */
    public static void injectViewportMeta(WebView webView) {
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
     * 2. 隐藏受限 UI 元素（精确匹配）
     * 3. 菜单栏触摸事件增强 — 模拟 mouseenter / click
     * 4. Blob 下载拦截 — 拦截 &lt;a download&gt; 链接点击，通过 BilupFileBridge 保存
     */
    public static void injectMobileEnhancements(WebView webView) {
        String jsCode = "(function() {" +
            "if (document.getElementById('bilup-enhance')) return;" +
            "var enh = document.createElement('div'); enh.id = 'bilup-enhance'; enh.style.display = 'none'; document.body.appendChild(enh);" +

            /* ========== CSS 注入 ========== */
            "var style = document.createElement('style');" +
            "style.textContent = '" +
                "body { overflow-x: hidden; -webkit-tap-highlight-color: transparent; } " +
                "input, textarea { font-size: 16px; } " +
                "[class*=\"menuBar\"], [class*=\"menu-bar\"] { " +
                    "overflow: visible; " +
                    "-ms-overflow-style: none; scrollbar-width: none; " +
                "} " +
                "::-webkit-scrollbar { width: 4px; height: 4px; } " +
                "::-webkit-scrollbar-track { background: transparent; } " +
                "::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.2); border-radius: 2px; }" +
            "';" +
            "document.head.appendChild(style);" +

            /* ========== 隐藏受限 UI 元素（精确匹配） ========== */
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
            "function hasSubmenu(item) {" +
                "for (var c = item.firstElementChild; c; c = c.nextElementSibling) {" +
                    "var cn = c.className;" +
                    "if (typeof cn === 'string' && (cn.indexOf('dropdown') !== -1 || cn.indexOf('Dropdown') !== -1 || cn.indexOf('submenu') !== -1 || cn.indexOf('Submenu') !== -1)) {" +
                        "return true;" +
                    "}" +
                "}" +
                "return false;" +
            "}" +
            "function enhanceMenuBar() {" +
                "var menuBars = document.querySelectorAll('[class*=\"menuBar\"], [class*=\"menu-bar\"]');" +
                "menuBars.forEach(function(bar) {" +
                    "if (bar.dataset.bilupEnhanced) return;" +
                    "bar.dataset.bilupEnhanced = 'true';" +
                    "var items = bar.querySelectorAll('[class*=\"menu-item\"], [class*=\"menuItem\"], li, [role=\"menuitem\"]');" +
                    "items.forEach(function(item) {" +
                        "item.addEventListener('touchstart', function(e) {" +
                            "if (hasSubmenu(this)) {" +
                                "e.preventDefault();" +
                            "}" +
                        "}, { passive: false });" +
                        "item.addEventListener('touchend', function(e) {" +
                            "if (hasSubmenu(this)) {" +
                                "var touch = e.changedTouches[0];" +
                                "var enterEvent = new MouseEvent('mouseenter', {" +
                                    "bubbles: true, cancelable: true, " +
                                    "clientX: touch.clientX, clientY: touch.clientY" +
                                "});" +
                                "this.dispatchEvent(enterEvent);" +
                                "e.preventDefault();" +
                            "}" +
                            /* 叶子菜单项：不做任何干预，让浏览器原生 touch→click 以 isTrusted=true 触发 */
                        "}, { passive: false });" +
                    "});" +
                "});" +
            "}" +
            "enhanceMenuBar();" +
            "var observer = new MutationObserver(function() { enhanceMenuBar(); });" +
            "observer.observe(document.body, { childList: true, subtree: true });" +

            /* ========== Blob 下载拦截 ========== */
            /* 拦截 URL.createObjectURL 以缓存 Blob 引用，防止 revokeObjectURL 后 fetch 失败 */
            "if (!window._bilupBlobMap) {" +
                "window._bilupBlobMap = {};" +
                "var _origCreate = URL.createObjectURL;" +
                "URL.createObjectURL = function(blob) {" +
                    "var url = _origCreate.call(URL, blob);" +
                    "window._bilupBlobMap[url] = blob;" +
                    "return url;" +
                "};" +
                "var _origRevoke = URL.revokeObjectURL;" +
                "URL.revokeObjectURL = function(url) {" +
                    "delete window._bilupBlobMap[url];" +
                    "_origRevoke.call(URL, url);" +
                "};" +
            "}" +
            "document.addEventListener('click', function(e) {" +
                "var link = e.target.closest('a');" +
                "if (link && link.href && link.href.indexOf('blob:') === 0) {" +
                    "e.preventDefault(); e.stopPropagation();" +
                    "var fileName = link.download || 'Bilup_project_' + Date.now() + '.sb3';" +
                    "var cachedBlob = window._bilupBlobMap[link.href];" +
                    "if (cachedBlob) {" +
                        "var reader = new FileReader();" +
                        "reader.onloadend = function() {" +
                            "var base64 = reader.result.split(',')[1];" +
                            "try { BilupFileBridge.saveBlob(base64, fileName, cachedBlob.type || 'application/octet-stream'); }" +
                            "catch(err) { console.error('BilupFileBridge err:', err); }" +
                        "};" +
                        "reader.onerror = function() { console.error('FileReader failed'); };" +
                        "reader.readAsDataURL(cachedBlob);" +
                    "} else {" +
                        /* 降级：通过 fetch 获取 blob */
                        "var mimeType = '';" +
                        "fetch(link.href).then(function(r) {" +
                            "mimeType = r.headers.get('Content-Type') || r.type || 'application/octet-stream';" +
                            "return r.blob();" +
                        "}).then(function(b) {" +
                            "var reader = new FileReader();" +
                            "reader.onloadend = function() {" +
                                "var base64 = reader.result.split(',')[1];" +
                                "try { BilupFileBridge.saveBlob(base64, fileName, mimeType); }" +
                                "catch(err) { console.error('BilupFileBridge err:', err); }" +
                            "};" +
                            "reader.onerror = function() { console.error('FileReader failed'); };" +
                            "reader.readAsDataURL(b);" +
                        "}).catch(function(err) { console.error('Blob fetch err:', err); });" +
                    "}" +
                "}" +
            "}, true);" +
        "})();";

        webView.evaluateJavascript(jsCode, null);
    }
}
