package org.bilup.app;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {
    private static final String DEVICE_TYPE_PHONE = "phone";
    private static final int REQUEST_CODE_STORAGE_PERMISSION = 100;
    private static final int REQUEST_CODE_FILE_CHOOSER = 200;
    // 保存 WebView 文件选择回调，等待 onActivityResult 返回用户选中的文件
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void load() {
        super.load();
        // 用自定义 WebViewClient 修复 wildcard 页面路由（/editor、/addons、/player 等），
        // 避免被 Capacitor 的 html5mode 错误回退到 index.html
        if (getBridge() != null) {
            getBridge().setWebViewClient(new BilupWebViewClient(getBridge()));
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAndRequestStoragePermission();

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                configureEssentialSettings(webView);
            }

            @Override
            public void onPageLoaded(WebView webView) {
                configureDisplaySettings(webView);
                // 设置 WebChromeClient（包装原有 client，支持 JS 弹窗）
                setupWebChromeClient(webView);
                // 注入 JS 接口必须在 evaluateJavascript 之前
                webView.addJavascriptInterface(new BlobReceiver(MainActivity.this, webView), "BilupFileBridge");
                WebViewEnhancer.injectViewportMeta(webView);
                WebViewEnhancer.injectMobileEnhancements(webView);
                new FileDownloadHelper(MainActivity.this, webView).setupDownloadListener();
            }
        });
    }

    /**
     * 检测并请求外部存储写入权限。
     *
     * 权限需求说明：
     * - Android 10+ (API 29+)：使用 MediaStore.Downloads 保存，无需任何存储权限
     * - Android 7-9 (API 24-28)：使用 Environment.getExternalStoragePublicDirectory，
     *   需要 WRITE_EXTERNAL_STORAGE 权限
     * - Android 6 (API 23)：当前 minSdkVersion=24，不会到达此分支
     */
    private void checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用 MediaStore，无需存储权限
            return;
        }
        // Android 7-9：需要 WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CODE_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 权限请求结果在此处理，用户拒绝后不影响已有功能（MediaStore 备用方案可用）
    }

    /**
     * 设置 WebChromeClient。
     * 包装 WebView 原有的 WebChromeClient（Capacitor 内部已设置），在其基础上添加
     * JS 弹窗（alert/confirm/prompt）和 window.open() 支持，避免前端弹窗被静默忽略。
     */
    private void setupWebChromeClient(WebView webView) {
        final WebChromeClient originalClient = webView.getWebChromeClient();

        webView.setWebChromeClient(new WebChromeClient() {
            // ==================== JS 弹窗 ====================

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("提示")
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("确认")
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.cancel();
                            }
                        })
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                       String defaultValue, final JsPromptResult result) {
                // 使用 AlertDialog 带输入框
                android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setText(defaultValue != null ? defaultValue : "");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("输入")
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.cancel();
                            }
                        })
                        .setCancelable(false)
                        .show();
                return true;
            }

            // ==================== window.open() 支持 ====================

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                // 将 window.open() 的目标 URL 在当前 WebView 中加载（不打开新窗口）
                WebView.HitTestResult hr = view.getHitTestResult();
                String url = hr != null ? hr.getExtra() : null;
                if (url == null) {
                    url = view.getUrl();
                }
                if (url != null) {
                    webView.loadUrl(url);
                }
                android.webkit.WebView.WebViewTransport transport =
                        (android.webkit.WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(webView);
                resultMsg.sendToTarget();
                return true;
            }

            // ==================== 文件选择器（本地打开作品） ====================

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                // 若上一次选择尚未回调，先通知取消，避免 WebView 收到重复回调
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = callback;

                Intent intent = fileChooserParams.createIntent();
                if (intent == null) {
                    // 兜底：使用系统通用文件选择器
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, REQUEST_CODE_FILE_CHOOSER);
                } catch (Exception e) {
                    // 无法打开文件选择器时通知前端失败
                    if (MainActivity.this.filePathCallback != null) {
                        MainActivity.this.filePathCallback.onReceiveValue(null);
                        MainActivity.this.filePathCallback = null;
                    }
                    return false;
                }
                return true;
            }

            // ==================== 委托其余方法给 Capacitor 原有 client ====================

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (originalClient != null) {
                    originalClient.onProgressChanged(view, newProgress);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (originalClient != null) {
                    originalClient.onReceivedTitle(view, title);
                }
            }

            @Override
            public void onReceivedIcon(WebView view, android.graphics.Bitmap icon) {
                if (originalClient != null) {
                    originalClient.onReceivedIcon(view, icon);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            if (filePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    // 单个文件
                    results = new Uri[]{data.getData()};
                } else if (data.getClipData() != null) {
                    // 多个文件
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private boolean isPhoneDevice() {
        return DEVICE_TYPE_PHONE.equals(BuildConfig.DEVICE_TYPE);
    }

    private void configureEssentialSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // 允许 JavaScript 通过 window.open() 打开新窗口
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // ============ 性能优化（针对老机器） ============
        // 提高渲染进程优先级：老机器多任务时避免 WebView 渲染被系统回收/降频。
        // RENDERER_PRIORITY_IMPORTANT 是最高优先级，保证渲染进程不被系统优先杀死。
        // 第二个参数 waivedWhenNotVisible=true：WebView 不可见时自动降级，节省资源。
        // setRendererPriorityPolicy 仅在 API 26+ 可用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
    }

    private void configureDisplaySettings(WebView webView) {
        WebSettings settings = webView.getSettings();

        if (isPhoneDevice()) {
            // 手机：内容适配屏幕宽度，初始缩小至全屏可见
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
        } else {
            // 平板：同样适配屏幕宽度，但允许用户手动缩放
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
        }

        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setGeolocationEnabled(true);

        webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
    }
}
