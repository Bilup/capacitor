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
     * 分块流式保存 Blob 的 JS 实现（幂等定义，重复注入只定义一次）。
     * <p>
     * 通过 BilupFileBridge 的 beginBlobSave / appendBlobChunk / endBlobSave / abortBlobSave
     * 协议，把 Blob 切成 4MB 分片依次交给 Java 写入并立即释放，避免原先"整份 base64 字符串"
     * 同时占据 JS 堆、桥接层与 Java 堆（约文件体积 5~6 倍）导致的 OOM。
     * <p>
     * 供本类的点击拦截与 {@link FileDownloadHelper} 的 blob 下载共用；
     * 保存成功/失败由 Java 侧派发 bilupSaveComplete / bilupSaveFailed 事件。
     */
    static final String STREAM_SAVE_JS =
        "if (!window._bilupStreamSave) {" +
            "window._bilupStreamSave = function(blob, fileName, mimeType) {" +
                "if (!blob || typeof BilupFileBridge === 'undefined') { console.error('bilup: no blob/bridge'); return; }" +
                /* 4MB 分片：往返次数比 1MB 减少 75%，固定开销（跨桥调用 + 任务调度）显著下降，
                   峰值内存仍只有几十 MB 量级，与文件大小无关 */
                "var CHUNK = 4 * 1024 * 1024;" +
                "var name = fileName || ('Bilup_project_' + Date.now() + '.sb3');" +
                "var mime = mimeType || blob.type || 'application/octet-stream';" +
                "var total = blob.size || 0;" +
                "var started = false;" +
                "try { started = BilupFileBridge.beginBlobSave(name, mime, total); } catch (e) { started = false; }" +
                "if (!started) { console.error('bilup: beginBlobSave rejected'); return; }" +
                "var aborted = false;" +
                "function stop() {" +
                    "if (aborted) return;" +
                    "aborted = true;" +
                    "try { BilupFileBridge.abortBlobSave(); } catch (e) {}" +
                "}" +
                "function finish() {" +
                    "try { BilupFileBridge.endBlobSave(); } catch (e) { stop(); }" +
                "}" +
                /* 读取一片并返回 base64。readAsDataURL 由浏览器在 IO 线程完成编码，
                   不占 JS 主线程；这里只负责切分与回调。 */
                "function readSlice(offset, cb) {" +
                    "var end = Math.min(offset + CHUNK, total);" +
                    "var reader = new FileReader();" +
                    "reader.onload = function() {" +
                        "var result = reader.result;" +
                        "var comma = result.indexOf(',');" +
                        "cb(comma >= 0 ? result.substring(comma + 1) : '', end);" +
                    "};" +
                    "reader.onerror = function() { cb(null, end); };" +
                    "reader.readAsDataURL(blob.slice(offset, end));" +
                "}" +
                /* 单槽流水线：任意时刻只有一次读取在途，并且总是"提前多读一片"。
                   发送第 N 片（同步跨桥 + Java 解码 + 写盘）时，第 N+1 片已在并行读取，
                   从而把 Blob 读取与 base64 编码的耗时隐藏掉。
                   原实现是严格串行：读一片 → 发一片 → 再读下一片，读取期间主线程空等。 */
                "var sendOffset = 0;" +
                "var ready = {};" +
                "var readingOffset = -1;" +
                "var sending = false;" +
                "function ensureRead(offset) {" +
                    "if (aborted || offset >= total) return;" +
                    "if (ready[offset] !== undefined) return;" +
                    "if (readingOffset !== -1) return;" +
                    "readingOffset = offset;" +
                    "readSlice(offset, function(base64, end) {" +
                        "readingOffset = -1;" +
                        "if (base64 === null) { stop(); return; }" +
                        "ready[offset] = { end: end, base64: base64 };" +
                        /* 先启动下一片读取，再发送当前片：两者并行 */
                        "ensureRead(end);" +
                        "pump();" +
                    "});" +
                "}" +
                "function pump() {" +
                    "if (aborted || sending) return;" +
                    "if (sendOffset >= total) { finish(); return; }" +
                    "var item = ready[sendOffset];" +
                    "if (!item) { ensureRead(sendOffset); return; }" +
                    "delete ready[sendOffset];" +
                    "sending = true;" +
                    "var ok = false;" +
                    "try { ok = BilupFileBridge.appendBlobChunk(item.base64); } catch (e) { ok = false; }" +
                    "sending = false;" +
                    "if (!ok) { stop(); return; }" +
                    "sendOffset = item.end;" +
                    "ensureRead(sendOffset);" +
                    "pump();" +
                "}" +
                "if (total === 0) { finish(); return; }" +
                "ensureRead(0);" +
            "};" +
        "}";

    /**
     * 判断 WebView 是否仍可安全使用。
     * <p>
     * {@link WebView#isDestroyed()} 是 API 26+ 才公开的方法，而本应用
     * minSdk=24，直接调用会编译失败。这里通过反射调用：方法存在（API 26+）
     * 则返回真实状态，不存在（API 24/25）则视为可用，由外层 try-catch 兜底。
     *
     * @param webView 目标 WebView，可为 null
     * @return true 表示可安全使用；false 表示已销毁或不可用
     */
    public static boolean isAlive(WebView webView) {
        if (webView == null) return false;
        try {
            java.lang.reflect.Method m = WebView.class.getMethod("isDestroyed");
            return !((Boolean) m.invoke(webView));
        } catch (Exception e) {
            // API 24/25 无该方法，或调用失败，视为可用（外层 try-catch 兜底）
            return true;
        }
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

        try {
            if (isAlive(webView)) {
                webView.evaluateJavascript(jsCode, null);
            }
        } catch (Exception ignored) {
            // WebView 已销毁或注入失败时静默忽略，不影响主流程
        }
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

            /* ========== 隐藏受限 UI 元素（精确匹配，延到浏览器空闲时执行） ========== */
            /* 全量 querySelectorAll('button, a') 是 O(节点数) 的一次性扫描，
               在 onPageLoaded 同步执行会推迟首屏可交互，改到空闲时段再做。 */
            "var runIdle = window.requestIdleCallback || function(cb) { setTimeout(cb, 1); };" +
            "runIdle(function() {" +
                "if (window._bilupRestrictedHidden) return;" +
                "window._bilupRestrictedHidden = true;" +
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
            "});" +

            /* ========== 菜单栏触摸事件增强（事件委托，无 MutationObserver） ========== */
            /* 原实现用 MutationObserver 监听整个 document.body 子树，并在每次 DOM 变更后
               重扫菜单栏；scratch-gui 渲染期 DOM 变更极频繁，这是老机器上持续的运行时负担。
               改为在 document 上做一次性委托监听：只在用户真正触摸菜单项时才进入逻辑，
               菜单栏动态重建也无需重新绑定。 */
            "function hasSubmenu(item) {" +
                "for (var c = item.firstElementChild; c; c = c.nextElementSibling) {" +
                    "var cn = c.className;" +
                    "if (typeof cn === 'string' && (cn.indexOf('dropdown') !== -1 || cn.indexOf('Dropdown') !== -1 || cn.indexOf('submenu') !== -1 || cn.indexOf('Submenu') !== -1)) {" +
                        "return true;" +
                    "}" +
                "}" +
                "return false;" +
            "}" +
            "function menuItemFor(target) {" +
                "if (!target || !target.closest) return null;" +
                "var item = target.closest('[class*=\"menu-item\"], [class*=\"menuItem\"], li, [role=\"menuitem\"]');" +
                "if (!item) return null;" +
                "return item.closest('[class*=\"menuBar\"], [class*=\"menu-bar\"]') ? item : null;" +
            "}" +
            "document.addEventListener('touchstart', function(e) {" +
                "var item = menuItemFor(e.target);" +
                "if (item && hasSubmenu(item)) e.preventDefault();" +
            "}, { capture: true, passive: false });" +
            "document.addEventListener('touchend', function(e) {" +
                "var item = menuItemFor(e.target);" +
                "if (!item || !hasSubmenu(item)) return;" +
                /* 叶子菜单项：不做任何干预，让浏览器原生 touch→click 以 isTrusted=true 触发 */
                "var touch = e.changedTouches && e.changedTouches[0];" +
                "if (touch) {" +
                    "item.dispatchEvent(new MouseEvent('mouseenter', {" +
                        "bubbles: true, cancelable: true, " +
                        "clientX: touch.clientX, clientY: touch.clientY" +
                    "}));" +
                "}" +
                "e.preventDefault();" +
            "}, { capture: true, passive: false });" +

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

            /* ========== 分块流式保存实现 + Blob 下载拦截 ========== */
            /* 整份 base64 会同时占据 JS 堆/桥接层/Java 堆，大作品必然 OOM；
               改为按 4MB 分片流水线流式写入（见 STREAM_SAVE_JS），峰值内存与文件大小无关。 */
            STREAM_SAVE_JS +
            "document.addEventListener('click', function(e) {" +
                "var link = e.target.closest('a');" +
                "if (!link || !link.href || link.href.indexOf('blob:') !== 0) return;" +
                "e.preventDefault(); e.stopPropagation();" +
                "var fileName = link.download || ('Bilup_project_' + Date.now() + '.sb3');" +
                "var cachedBlob = window._bilupBlobMap[link.href];" +
                "if (cachedBlob) {" +
                    "window._bilupStreamSave(cachedBlob, fileName, cachedBlob.type);" +
                    "return;" +
                "}" +
                /* 降级：通过 fetch 获取 blob */
                "fetch(link.href).then(function(r) { return r.blob(); })" +
                ".then(function(b) { window._bilupStreamSave(b, fileName, b.type); })" +
                ".catch(function(err) { console.error('Blob fetch err:', err); });" +
            "}, true);" +
        "})();";

        try {
            if (isAlive(webView)) {
                webView.evaluateJavascript(jsCode, null);
            }
        } catch (Exception ignored) {
            // WebView 已销毁或注入失败时静默忽略，不影响主流程
        }
    }
}
