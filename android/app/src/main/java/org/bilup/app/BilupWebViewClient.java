package org.bilup.app;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.util.regex.Pattern;

/**
 * 自定义 WebViewClient，修复 Capacitor 下 SPA 路由的静态资源映射。
 * <p>
 * scratch-gui 构建采用 ROUTING_STYLE=wildcard，页面路由均为无扩展名路径
 * （/editor、/addons、/fullscreen、/embed、/&lt;id&gt; 等），前端
 * {@code WildcardRouter} 依赖 URL pathname 的原文（如 "editor"、"fullscreen"）
 * 来判断页面类型。Capacitor 默认的 html5mode 会把所有无扩展名路径回退到
 * index.html，导致 /editor 打开的是社区首页、iframe 加载 /addons 时打不开
 * 插件设置。
 * <p>
 * 这里仅在资源请求层（{@link #shouldInterceptRequest}）把 wildcard 路由在
 * 内部改写为对应的 .html 文件后再交给 Capacitor 本地服务器处理，同时保持
 * WebView 显示的 URL 不变（不调用 loadUrl 改写地址栏）。这样：
 * <ul>
 *   <li>Capacitor 能正确返回 editor.html / addons.html / player.html 的内容；</li>
 *   <li>前端 WildcardRouter 仍按原 pathname 解析页面类型，进入正确的模式。</li>
 * </ul>
 * 映射规则与 scratch-gui dev server 的 historyApiFallback.rewrites 一致：
 * <ul>
 *   <li>/editor、/&lt;id&gt;/editor  → editor.html（编辑器）</li>
 *   <li>/addons                     → addons.html（插件设置）</li>
 *   <li>/fullscreen、/&lt;id&gt;/fullscreen → fullscreen.html</li>
 *   <li>/embed、/&lt;id&gt;/embed   → embed.html</li>
 *   <li>/&lt;id&gt;                 → player.html（播放器）</li>
 * </ul>
 * 其余路径（如 /settings、/explore、/project/* 等 community 前端路由）保持
 * 原样，由 Capacitor 的 html5mode 回退到 index.html 处理。
 */
public class BilupWebViewClient extends BridgeWebViewClient {

    private final Bridge bridge;

    private static final Pattern P_EDITOR = Pattern.compile("^/editor/?$");
    private static final Pattern P_ADDONS = Pattern.compile("^/addons/?$");
    private static final Pattern P_FULLSCREEN = Pattern.compile("^/fullscreen/?$");
    private static final Pattern P_EMBED = Pattern.compile("^/embed/?$");
    private static final Pattern P_PLAYER = Pattern.compile("^/\\d+/?$");
    private static final Pattern P_NUM_FULLSCREEN = Pattern.compile("^/\\d+/fullscreen/?$");
    private static final Pattern P_NUM_EDITOR = Pattern.compile("^/\\d+/editor/?$");
    private static final Pattern P_NUM_EMBED = Pattern.compile("^/\\d+/embed/?$");

    public BilupWebViewClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    /**
     * 将 wildcard 页面路由映射到构建产物中的 .html 文件；无映射返回 null。
     */
    private static String mapPath(String path) {
        if (path == null) return null;
        if (P_EDITOR.matcher(path).matches() || P_NUM_EDITOR.matcher(path).matches()) {
            return "/editor.html";
        }
        if (P_ADDONS.matcher(path).matches()) {
            return "/addons.html";
        }
        if (P_FULLSCREEN.matcher(path).matches() || P_NUM_FULLSCREEN.matcher(path).matches()) {
            return "/fullscreen.html";
        }
        if (P_EMBED.matcher(path).matches() || P_NUM_EMBED.matcher(path).matches()) {
            return "/embed.html";
        }
        if (P_PLAYER.matcher(path).matches()) {
            return "/player.html";
        }
        return null;
    }

    private boolean isLocalServerUrl(Uri url) {
        String host = url.getHost();
        return host != null && host.equalsIgnoreCase(bridge.getHost());
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        // 不限制 isForMainFrame()：插件设置窗口通过 iframe 加载 /addons，
        // iframe 文档请求的 isForMainFrame() 为 false，也必须走路由映射。
        if (isLocalServerUrl(request.getUrl())) {
            String target = mapPath(request.getUrl().getPath());
            if (target != null) {
                // 仅改写内部资源路径，WebView 地址栏 URL 保持不变，
                // 前端 WildcardRouter 仍能按原 pathname 解析页面类型。
                WebResourceRequest rewritten = new RewrittenRequest(request, target);
                return super.shouldInterceptRequest(view, rewritten);
            }
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
