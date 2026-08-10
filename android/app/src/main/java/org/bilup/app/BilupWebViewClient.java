package org.bilup.app;

import android.net.Uri;
import android.webkit.WebResourceRequest;
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
 * 这里将 Capacitor 本地服务器上的 /editor（含子路径）导航重写为
 * /editor.html，其余路径（如 /settings 等 community 前端路由）保持原样，
 * 由 Capacitor 的 html5mode 回退到 index.html 处理。
 */
public class BilupWebViewClient extends BridgeWebViewClient {

    private final Bridge bridge;

    public BilupWebViewClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request.isForMainFrame()) {
            Uri url = request.getUrl();
            String host = url.getHost();
            String path = url.getPath();
            if (host != null && host.equalsIgnoreCase(bridge.getHost())
                    && path != null && (path.equals("/editor") || path.startsWith("/editor/"))) {
                // 将 /editor 路由重写为 /editor.html，保留 query 与 fragment
                String rewritten = url.buildUpon().path("/editor.html").build().toString();
                view.loadUrl(rewritten);
                return true;
            }
        }
        return super.shouldOverrideUrlLoading(view, request);
    }
}
