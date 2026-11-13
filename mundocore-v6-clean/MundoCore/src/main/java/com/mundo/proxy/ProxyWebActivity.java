package com.mundo.proxy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.HashMap;
import java.util.Map;
import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.R;

public class ProxyWebActivity extends Activity {

    public static final String TAG = "ProxyWebActivity";

    private WebView mWebView;

    private static final Map<String, String> TOKEN_MAP = new HashMap<>();

    public static final String FACEBOOK = "facebook";
    public static final String GOOGLE   = "google";
    public static final String WECHAT   = "wechat";
    public static final String QQ       = "qq";

    private static void setToken(String key, String value) {
        if (key != null && value != null) {
            TOKEN_MAP.put(key, value);
        }
    }

    public static String getToken(String key) {
        return TOKEN_MAP.get(key);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        mWebView = findViewById(R.id.webView);
        initWebView(mWebView);

        String url = getIntent().getStringExtra("url");
        if (url != null) {
            mWebView.loadUrl(url);
        }
    }

    private void initWebView(WebView webView) {

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // Facebook deprecated the WebView login (Oct 2021) and blocks the default
        // WebView user-agent. Spoof a real Chrome UA so FB still serves the page
        // when a guest app forces the WebView path.
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Mobile Safari/537.36");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "Redirect: " + url);

                // FB OAuth completed -> custom-scheme redirect (fbconnect:// or
                // fb{APP_ID}://). Route it back into the guest virtual space so
                // the guest's CustomTabActivity receives the token.
                if (url != null && !url.startsWith("http")) {
                    if (url.startsWith("fbconnect") || url.startsWith("fb")) {
                        try {
                            Intent redirectIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            redirectIntent.addCategory(Intent.CATEGORY_BROWSABLE);
                            String[] target = FacebookLoginHelper.readLoginTarget();
                            int userId = 0;
                            if (target != null) {
                                redirectIntent.setPackage(target[0]);
                                try { userId = Integer.parseInt(target[1]); } catch (Exception ignored) {}
                            } else {
                                redirectIntent.setPackage(BActivityThread.getAppPackageName());
                                userId = BActivityThread.getUserId();
                            }
                            MundoCore.getBActivityManager().startActivity(redirectIntent, userId);
                            Log.i(TAG, "FB redirect routed into virtual space: " + url);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to route fb redirect: " + e.getMessage());
                        }
                        finish();
                        return true;
                    }
                }

                if (url.contains("facebook.com")) {
                    setToken(FACEBOOK, url);
                    Log.i(TAG, "Facebook login detected");
                }
                else if (url.contains("google.com")) {
                    setToken(GOOGLE, url);
                    Log.i(TAG, "Google login detected");
                }
                else if (url.contains("wechat.com")) {
                    setToken(WECHAT, url);
                    Log.i(TAG, "WeChat login detected");
                }
                else if (url.contains("qq.com")) {
                    setToken(QQ, url);
                    Log.i(TAG, "QQ login detected");
                }

                return false;
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mWebView.destroy();
        }
        super.onDestroy();
    }
}