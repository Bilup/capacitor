package org.bilup.app;

import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import org.bilup.app.webview.ContentRestrictionInjector;
import org.bilup.app.webview.ViewportInjector;
import org.bilup.app.webview.WebViewConfigurator;

public class MainActivity extends BridgeActivity {
    private static final String DEVICE_TYPE_PHONE = "phone";
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        boolean isPhone = isPhoneDevice();
        ViewportInjector viewportInjector = new ViewportInjector(isPhone);
        WebViewConfigurator webViewConfigurator = new WebViewConfigurator(isPhone);
        ContentRestrictionInjector restrictionInjector = new ContentRestrictionInjector(isPhone);
        
        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                viewportInjector.inject(webView);
            }
            
            @Override
            public void onPageLoaded(WebView webView) {
                webViewConfigurator.configure(webView);
                restrictionInjector.inject(webView);
            }
        });
    }
    
    private boolean isPhoneDevice() {
        return DEVICE_TYPE_PHONE.equals(BuildConfig.DEVICE_TYPE);
    }
}
