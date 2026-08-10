package org.bilup.app;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

/**
 * 自定义 WebViewClient，修复 Capacitor 下 SPA 路由的静态资源映射。
 * <p>
 * scratch-gui 构建产物中 /editor 对应独立的 editor.html（编辑器页面），
 * 而 Capacitor 默认的 html5mode 会把所有无扩展名路径回退到 index.html，
 * 导致访问 /editor 时打开的是社区主页而非编辑器。
 * <p>
 * 这里通过两层保障把 /editor（含子路径）映射到 editor.html：
 * <ol>
 *   <li>导航层 {@link #shouldOverrideUrlLoading}：整页跳转到 /editor 时直接改写为 /editor.html；</li>
 *   <li>资源层 {@link #shouldInterceptRequest}：兜底处理，将 /editor 的请求 URL 替换为
 *       /editor.html 后再交给 Capacitor 本地服务器处理（保留 Capacitor 的 JS 注入逻辑）。</li>
 * </ol>
 * 其余路径（如 /settings 等 community 前端路由）保持原样，由 Capacitor 的 html5mode 回退到
 * index.html 处理。
 */
public class BilupWebViewClient extends BridgeWebViewClient {

    private final Bridge bridge;

    public BilupWebViewClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    private boolean isEditorPath(Uri url) {
        String host = url.getHost();
        String path = url.getPath();
        return host != null && host.equalsIgnoreCase(bridge.getHost())
                && path != null && (path.equals("/editor") || path.startsWith("/editor/"));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request.isForMainFrame() && isEditorPath(request.getUrl())) {
            // 将 /editor 路由重写为 /editor.html，保留 query 与 fragment
            String rewritten = request.getUrl().buildUpon().path("/editor.html").build().toString();
            view.loadUrl(rewritten);
            return true;
        }
        return super.shouldOverrideUrlLoading(view, request);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (request.isForMainFrame() && isEditorPath(request.getUrl())) {
            // 兜底：把 /editor 请求改写为 /editor.html 后交给 Capacitor 本地服务器处理
            WebResourceRequest editorRequest = new RewrittenRequest(request, "/editor.html");
            return super.shouldInterceptRequest(view, editorRequest);
        }
        return super.shouldInterceptRequest(view, request);
    }

    /**
     * 包装 WebResourceRequest，仅替换 URL 的 path，其余信息原样透传。
     */
    private static final class RewrittenRequest implements WebResourceRequest {

        private final WebResourceRequest original;
        private final Uri rewrittenUrl;

        RewrittenRequest(WebResourceRequest original, String newPath) {
            this.original = original;
            this.rewrittenUrl = original.getUrl().buildUpon().path(newPath).build();
        }

        @Override
        public Uri getUrl() {
            return rewrittenUrl;
        }

        @Override
        public boolean isForMainFrame() {
            return original.isForMainFrame();
        }

        @Override
        public boolean isRedirect() {
            return original.isRedirect();
        }

        @Override
        public boolean hasGesture() {
            return original.hasGesture();
        }

        @Override
        public String getMethod() {
            return original.getMethod();
        }

        @Override
        public java.util.Map<String, String> getRequestHeaders() {
            return original.getRequestHeaders();
        }
    }
}
