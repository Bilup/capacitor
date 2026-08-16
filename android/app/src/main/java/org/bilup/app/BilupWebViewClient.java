package org.bilup.app;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.util.Map;
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

    /** 需要拦截的账号域名（含其所有子域名） */
    private static final String BLOCKED_ACCOUNTS_HOST = "accounts.bilup.org";

    /**
     * 本地静态资源的缓存头。
     * Capacitor 本地服务器对所有响应强制 Cache-Control: no-cache（见 WebViewLocalServer.PathHandler），
     * 导致 JS/CSS/图片等静态资源无法被 WebView 磁盘缓存，每次进入编辑器都要重新从 APK 读取并解压，
     * 这是大作品素材加载慢的重要原因。这里对带 contenthash 的构建产物覆盖为长缓存头。
     */
    private static final String STATIC_ASSET_CACHE_CONTROL = "public, max-age=604800, immutable";

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

    /**
     * 判断请求是否为 HTML 文档类请求（通过 Accept 请求头判断）。
     * 用于区分 iframe 页面加载与图片/JS/CSS 等静态资源请求。
     */
    private boolean isHtmlDocumentRequest(WebResourceRequest request) {
        Map<String, String> headers = request.getRequestHeaders();
        if (headers == null) return false;
        String accept = headers.get("Accept");
        return accept != null && accept.toLowerCase().contains("text/html");
    }

    /**
     * 判断 URL 是否指向被拦截的账号域名（accounts.bilup.org 及其子域名）。
     */
    private boolean isBlockedAccountsHost(Uri url) {
        String host = url.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals(BLOCKED_ACCOUNTS_HOST) || host.endsWith("." + BLOCKED_ACCOUNTS_HOST);
    }

    /**
     * 拦截所有跳转到 accounts.bilup.org 的导航操作：
     * 用户点击链接、window.location、window.open 等都会被此方法拦截。
     * 返回 true 表示导航被阻止。
     */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (isBlockedAccountsHost(request.getUrl())) {
            notifyAccountsBlocked();
            return true;
        }
        return super.shouldOverrideUrlLoading(view, request);
    }

    /**
     * 提示用户跳转已被拦截（切回主线程弹 Toast）。
     * 文案从资源文件读取，随系统语言自动切换（默认英文，中文见 values-zh-rCN）。
     * 注意不能用 getMainExecutor()：那是 API 28+ 的 API，minSdk=24 的老机器会崩溃。
     */
    private void notifyAccountsBlocked() {
        final Context context = bridge.getContext();
        if (context == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                String message = context.getString(R.string.accounts_blocked_toast);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        WebResourceResponse response;
        // 拦截 iframe/子框架加载 accounts.bilup.org 的 HTML 文档请求。
        // shouldOverrideUrlLoading 只覆盖主框架导航，iframe 内的跳转在这里拦截。
        // 仅拦截 HTML 文档类请求（Accept 头含 text/html），避免误伤图片/JS/CSS 等静态资源，
        // 也避免静态资源请求频繁触发 Toast。
        if (isBlockedAccountsHost(request.getUrl()) && isHtmlDocumentRequest(request)) {
            notifyAccountsBlocked();
            return new WebResourceResponse("text/html", "UTF-8", null);
        }
        // 不限制 isForMainFrame()：插件设置窗口通过 iframe 加载 /addons，
        // iframe 文档请求的 isForMainFrame() 为 false，也必须走路由映射。
        if (isLocalServerUrl(request.getUrl())) {
            String target = mapPath(request.getUrl().getPath());
            if (target != null) {
                // 仅改写内部资源路径，WebView 地址栏 URL 保持不变，
                // 前端 WildcardRouter 仍能按原 pathname 解析页面类型。
                WebResourceRequest rewritten = new RewrittenRequest(request, target);
                response = super.shouldInterceptRequest(view, rewritten);
            } else {
                response = super.shouldInterceptRequest(view, request);
            }
        } else {
            response = super.shouldInterceptRequest(view, request);
        }
        // 本地静态资源注入长缓存头，让 WebView 磁盘缓存生效，加速素材/资源二次加载
        return enableStaticAssetCache(request, response);
    }

    /**
     * 为本地服务器的静态资源启用 WebView 磁盘缓存。
     * <p>
     * Capacitor 本地服务器（WebViewLocalServer.PathHandler）对所有响应强制设置
     * {@code Cache-Control: no-cache}，WebView 因此从不缓存任何资源，导致每次打开
     * 编辑器都要重新从 APK 读取（asset 多为压缩存储，读取即解压），大作品场景尤其明显。
     * <p>
     * 这里仅对带扩展名且非 HTML 的静态资源覆盖为长缓存头。scratch-gui 构建产物均带
     * contenthash，文件名变化时 URL 同步变化，长缓存不会导致旧资源被误用；HTML 文档
     * 保持 no-cache，确保应用更新后能立即加载新页面。
     */
    private WebResourceResponse enableStaticAssetCache(WebResourceRequest request,
                                                       WebResourceResponse response) {
        if (response == null) return null;
        // 仅处理本地虚拟服务器上的资源，网络请求保持原样（由服务器返回的缓存头控制）
        if (!isLocalServerUrl(request.getUrl())) return response;

        String path = request.getUrl().getPath();
        if (path == null || path.isEmpty()) return response;
        // HTML 文档 / 无扩展名的 SPA 路由不做缓存，保证页面更新立即生效
        if (path.endsWith(".html")) return response;
        int dot = path.lastIndexOf('.');
        if (dot <= 0 || dot == path.length() - 1) return response;

        Map<String, String> headers = response.getResponseHeaders();
        if (headers == null) return response;
        headers.put("Cache-Control", STATIC_ASSET_CACHE_CONTROL);
        return response;
    }

    /**
     * 处理 WebView 渲染进程崩溃（通常由打开大作品时的内存不足引起）。
     * <p>
     * 默认行为是直接终止应用（表现为闪退）。这里接管崩溃事件，尝试重新加载
     * 当前页面以恢复编辑器，把"闪退"降级为"重新加载"。仅在渲染进程确实崩溃
     * 时处理，系统主动回收（didCrash() == false）时交给默认逻辑。
     */
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && detail != null && detail.didCrash()) {
            final String url = view != null ? view.getUrl() : null;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (view != null) {
                            view.loadUrl(url != null ? url : bridge.getServerUrl());
                        }
                    } catch (Exception ignored) {
                        // 重新加载失败时交给默认行为处理
                    }
                }
            });
            return true;
        }
        return super.onRenderProcessGone(view, detail);
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
