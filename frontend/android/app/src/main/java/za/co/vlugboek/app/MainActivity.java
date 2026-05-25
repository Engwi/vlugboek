package za.co.vlugboek.app;

import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(VlugboekDownloadsPlugin.class);
        super.onCreate(savedInstanceState);

        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        WebView webView = getBridge().getWebView();
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
    }
}
