# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ============ Bilup 项目自定义 Keep 规则 ============

# 保留自定义包内所有类（含 WebView 相关客户端、增强工具、文件处理辅助类），
# 它们会被 Capacitor/Bridge 通过反射实例化或作为 WebChromeClient/WebViewClient 使用。
-keep class org.bilup.app.** { *; }

# WebView 的 @JavascriptInterface 方法必须保留，否则 JS 侧 BilupFileBridge.saveBlob()
# 会因方法被混淆删除而调用失败。
-keepclassmembers class org.bilup.app.BlobReceiver {
    public *;
}

# 保留 line number，便于线上崩溃堆栈定位（仅 release 可用，几乎无体积影响）
-keepattributes SourceFile,LineNumberTable
