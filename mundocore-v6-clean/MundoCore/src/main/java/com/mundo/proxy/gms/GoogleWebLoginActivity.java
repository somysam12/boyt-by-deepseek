/*
 * SPDX-FileCopyrightText: 2013-2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 *
 * WebView Google login (microG LoginActivity: EmbeddedSetup + "mm" JsBridge +
 * oauth_token cookie), styled like Zerax's EmbeddedGoogleLoginActivity:
 * glass card, "Sign in with Google" header with a close button, status line,
 * wavy progress and the WebView below.
 *
 * Result: RESULT_OK with "authAccount" = email once GmsAuthService stored the account.
 */

package com.mundo.proxy.gms;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mundo.core.gms.GmsAuthClient;
import com.mundo.core.gms.GmsConstants;

import org.json.JSONArray;

import java.net.URI;
import java.util.Locale;

public class GoogleWebLoginActivity extends Activity {

    private static final String TAG = "GoogleWebLoginActivity";

    public static final String EXTRA_USER_ID = "mundo.gms.userId";
    public static final String EXTRA_EMAIL = "email";
    public static final String RESULT_ACCOUNT = "authAccount";

    private static final String EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup";
    private static final String PROGRAMMATIC_AUTH_URL = "https://accounts.google.com/o/oauth2/programmatic_auth";
    private static final String MAGIC_USER_AGENT = " MinuteMaid";
    private static final String COOKIE_OAUTH_TOKEN = "oauth_token";

    private GoogleAuthUi ui;
    private WebView web;
    private TextView status;
    private WavyProgressIndicator progress;
    private int userId;
    private volatile String androidIdHex;
    private boolean completing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        userId = getIntent().getIntExtra(EXTRA_USER_ID, 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ui = new GoogleAuthUi(this, userId);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.2f);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(ui.dark ? 0xFF182030 : 0xFFEDF2F9);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        LinearLayout card = ui.column();
        ui.glassSurface(card, 28);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -1);
        cardLp.setMargins(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        root.addView(card, cardLp);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(20), ui.dp(14), ui.dp(12), ui.dp(8));
        header.addView(ui.brand("Sign in with Google"), new LinearLayout.LayoutParams(0, -2, 1f));
        Button close = ui.button("×", false, this::cancel);
        close.setTextSize(24);
        close.setPadding(0, 0, 0, 0);
        close.setMinimumHeight(0);
        close.setContentDescription("Close Google sign-in");
        header.addView(close, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        card.addView(header);

        status = ui.caption("Connecting to Google…");
        status.setGravity(Gravity.CENTER);
        status.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(14));
        card.addView(status);

        progress = ui.loading();
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, ui.dp(20));
        pLp.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(6));
        card.addView(progress, pLp);

        FrameLayout webFrame = new FrameLayout(this);
        webFrame.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        web.setAlpha(0f);
        webFrame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        card.addView(webFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        ui.enter(card);
        configureWebView();
        setResult(RESULT_CANCELED);

        CookieManager.getInstance().removeAllCookies(v -> new Thread(() -> {
            // microG: check in first so the page and the token exchange carry an androidId
            long aid = GmsAuthClient.getAndroidId(userId);
            androidIdHex = aid != 0 ? Long.toHexString(aid) : null;
            runOnUiThread(this::loadLogin);
        }, "GmsCheckin").start());
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(s.getUserAgentString() + MAGIC_USER_AGENT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new JsBridge(), "mm");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (!isGoogleUrl(url)) return;
                status.setText("accounts.google.com");
                web.animate().alpha(1f).setDuration(240).start();
                Uri u = Uri.parse(url);
                if ("close".equals(u.getFragment())
                        || "/o/oauth2/programmatic_auth".equals(u.getPath())
                        || "/signin/continue".equals(u.getPath())) {
                    completeLogin();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showError("Google's secure connection could not be verified");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && blockExternal(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockExternal(url);
            }
        });
    }

    private void loadLogin() {
        if (isFinishing()) return;
        Locale l = Locale.getDefault();
        Uri.Builder b = Uri.parse(EMBEDDED_SETUP_URL).buildUpon()
                .appendQueryParameter("source", "android")
                .appendQueryParameter("xoauth_display_name", "Google sign-in")
                .appendQueryParameter("lang", l.getLanguage())
                .appendQueryParameter("cc", l.getCountry().toLowerCase(Locale.US))
                .appendQueryParameter("langCountry", l.toString().toLowerCase(Locale.US))
                .appendQueryParameter("hl", l.toString().replace('_', '-'))
                .appendQueryParameter("tmpl", "new_account");
        String email = getIntent().getStringExtra(EXTRA_EMAIL);
        if (email != null) b.appendQueryParameter("Email", email);
        web.loadUrl(b.build().toString());
    }

    private boolean blockExternal(String url) {
        if (isGoogleUrl(url)) return false;
        status.setText("This Google sign-in step requires an unsupported external page");
        return true;
    }

    static boolean isGoogleUrl(String url) {
        if (url == null) return false;
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme())
                    && "accounts.google.com".equalsIgnoreCase(u.getHost())
                    && u.getRawUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443);
        } catch (Exception e) {
            return false;
        }
    }

    private void completeLogin() {
        runOnUiThread(() -> {
            if (isFinishing() || completing || web == null || !isGoogleUrl(web.getUrl())) return;
            String path = Uri.parse(web.getUrl()).getPath();
            String cookies = CookieManager.getInstance().getCookie(
                    "/o/oauth2/programmatic_auth".equals(path) ? PROGRAMMATIC_AUTH_URL : EMBEDDED_SETUP_URL);
            String token = null;
            if (cookies != null) {
                for (String part : cookies.split(";")) {
                    part = part.trim();
                    if (part.startsWith(COOKIE_OAUTH_TOKEN + "=")) token = part.substring(COOKIE_OAUTH_TOKEN.length() + 1);
                }
            }
            if (token == null || token.isEmpty()) {
                showError("Google did not return a completed account session");
                return;
            }
            completing = true;
            progress.setVisibility(View.VISIBLE);
            status.setText("Completing Google sign-in…");
            final String oauthToken = token;
            new Thread(() -> {
                String email = GmsAuthClient.login(userId, oauthToken);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (email == null) {
                        completing = false;
                        showError("Google sign-in could not be completed");
                        return;
                    }
                    Intent data = new Intent();
                    data.putExtra(RESULT_ACCOUNT, email);
                    setResult(RESULT_OK, data);
                    CookieManager.getInstance().removeAllCookies(v -> finish());
                });
            }, "GmsLogin").start();
        });
    }

    private void showError(String msg) {
        progress.setVisibility(View.GONE);
        status.setText(msg);
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else cancel();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("mm");
            web.stopLoading();
            web.destroy();
            web = null;
        }
        if (isFinishing()) CookieManager.getInstance().removeAllCookies(null);
        if (ui != null) ui.close();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // JS bridge "mm" (microG LoginActivity.JsBridge)
    // ---------------------------------------------------------------------

    private class JsBridge {
        @JavascriptInterface public final void addAccount(String json) { }
        @JavascriptInterface public final void attemptLogin(String accountName, String password) { }
        @JavascriptInterface public void backupSyncOptIn(String accountName) { }
        @JavascriptInterface public final void cancelFido2SignRequest() { }
        @JavascriptInterface public void clearOldLoginAttempts() { }
        @JavascriptInterface public final void closeView() { completeLogin(); }
        @JavascriptInterface public void fetchIIDToken(String entity) { }
        @JavascriptInterface public final String fetchVerifiedPhoneNumber() { return null; }

        @JavascriptInterface
        public final String getAccounts() {
            JSONArray json = new JSONArray();
            try {
                for (String a : GmsAuthClient.getAccounts(userId)) json.put(a);
            } catch (Exception ignored) {
            }
            return json.toString();
        }

        @JavascriptInterface public final String getAllowedDomains() { return new JSONArray().toString(); }
        @JavascriptInterface public final String getAndroidId() { return androidIdHex; }
        @JavascriptInterface public final int getAuthModuleVersionCode() { return GmsConstants.GMS_VERSION_CODE; }
        @JavascriptInterface public final int getBuildVersionSdk() { return Build.VERSION.SDK_INT; }
        @JavascriptInterface public int getDeviceContactsCount() { return -1; }
        @JavascriptInterface public final int getDeviceDataVersionInfo() { return 1; }

        @JavascriptInterface
        public final void getDroidGuardResult(String s) {
            // No DroidGuard in MundoCore; microG also ignores DroidGuard failures here.
            Log.w(TAG, "getDroidGuardResult: not supported");
        }

        @JavascriptInterface public final String getFactoryResetChallenges() { return new JSONArray().toString(); }
        @JavascriptInterface public final String getPhoneNumber() { return null; }
        @JavascriptInterface public final int getPlayServicesVersionCode() { return GmsConstants.GMS_VERSION_CODE; }
        @JavascriptInterface public final String getSimSerial() { return null; }
        @JavascriptInterface public final int getSimState() { return 0; }
        @JavascriptInterface public final void goBack() { }
        @JavascriptInterface public final boolean hasPhoneNumber() { return false; }
        @JavascriptInterface public final boolean hasTelephony() { return false; }

        @JavascriptInterface
        public final void hideKeyboard() {
            runOnUiThread(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null && web != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            });
        }

        @JavascriptInterface public final boolean isUserOwner() { return true; }
        @JavascriptInterface public final void launchEmergencyDialer() { }
        @JavascriptInterface public final void log(String s) { }
        @JavascriptInterface public final void notifyOnTermsOfServiceAccepted() { }
        @JavascriptInterface public final void sendFido2SkUiEvent(String event) { }
        @JavascriptInterface public final void setAccountIdentifier(String accountName) { }
        @JavascriptInterface public void setAllActionsEnabled(boolean z) { }
        @JavascriptInterface public final void setBackButtonEnabled(boolean backButtonEnabled) { }
        @JavascriptInterface public final void setNewAccountCreated() { }
        @JavascriptInterface public void setPrimaryActionEnabled(boolean z) { }
        @JavascriptInterface public void setPrimaryActionLabel(String str, int i) { }
        @JavascriptInterface public void setSecondaryActionEnabled(boolean z) { }
        @JavascriptInterface public void setSecondaryActionLabel(String str, int i) { }

        @JavascriptInterface
        public final void showKeyboard() {
            runOnUiThread(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null && web != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        @JavascriptInterface
        public final void showView() {
            runOnUiThread(() -> {
                if (web != null) web.animate().alpha(1f).setDuration(240).start();
            });
        }

        @JavascriptInterface public final void skipLogin() { runOnUiThread(GoogleWebLoginActivity.this::cancel); }
        @JavascriptInterface public final void startAfw() { }
        @JavascriptInterface public final void startFido2SignRequest(String request) { }
    }
}
