/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 *
 * "Sign in with Google" account chooser, launched in place of Play services'
 * GOOGLE_SIGN_IN activity (see GmsSignInInterceptor). Result contract follows
 * microG AuthSignInActivity; UI follows Zerax's GmsSignInBridgeActivity:
 *
 *   portrait:  [G Sign in with Google] / app icon / "Continue to <app>" /
 *              "Choose an account to continue" / account rows / "+ Add a Google account"
 *              / [Cancel]
 *   landscape: compact heading, account rows, [Cancel] [Add account]
 *   loading:   wavy progress + "Signing in with <email>…"
 */

package com.mundo.proxy.gms;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mundo.core.gms.ForeignParcelBundle;
import com.mundo.core.gms.GmsAuthClient;
import com.mundo.core.gms.GmsConstants;
import com.mundo.core.gms.GoogleSignInAccount;
import com.mundo.core.gms.SafeParcelHelper;
import com.mundo.core.gms.SignInOptions;

import java.util.Collections;
import java.util.List;

public class GoogleLoginActivity extends Activity {

    public static final String EXTRA_USER_ID = "mundo.gms.userId";
    public static final String EXTRA_PACKAGE = "mundo.gms.package";
    public static final String EXTRA_OPTIONS = "mundo.gms.options";
    /** Set in the result when launched without a package (plain "add account"). */
    public static final String RESULT_EMAIL = "email";

    private static final int RC_ADD_ACCOUNT = 0x4D01;

    // Result extras read by play-services-auth (SignInHubActivity / direct callers)
    private static final String EXTRA_SIGN_IN_ACCOUNT = "signInAccount";
    private static final String EXTRA_GOOGLE_SIGN_IN_ACCOUNT = "googleSignInAccount";
    private static final String EXTRA_GOOGLE_SIGN_IN_STATUS = "googleSignInStatus";
    private static final String EXTRA_ERROR_CODE = "errorCode";
    private static final String CLASS_SIGN_IN_ACCOUNT = "com.google.android.gms.auth.api.signin.SignInAccount";
    private static final String CLASS_GOOGLE_SIGN_IN_ACCOUNT = "com.google.android.gms.auth.api.signin.GoogleSignInAccount";
    private static final String CLASS_STATUS = "com.google.android.gms.common.api.Status";

    private final Handler main = new Handler(Looper.getMainLooper());

    private int userId;
    private String callerPkg;
    private SignInOptions options;

    private GoogleAuthUi ui;
    private ScrollView accountsScroll;
    private LinearLayout accountList;
    private LinearLayout progressLayout;
    private TextView statusText;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        Intent in = getIntent();
        userId = in.getIntExtra(EXTRA_USER_ID, 0);
        callerPkg = in.getStringExtra(EXTRA_PACKAGE);
        options = SignInOptions.fromBundle(in.getBundleExtra(EXTRA_OPTIONS));
        setResult(RESULT_CANCELED, errorIntent(GmsConstants.STATUS_SIGN_IN_CANCELLED));

        if (callerPkg == null) {
            // Host UI "add account": just the WebView login.
            launchAddAccount();
            return;
        }
        setupView();
        showLoading("Loading accounts…");
        loadAccounts();
    }

    // ---------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------

    private void setupView() {
        ui = new GoogleAuthUi(this, userId);
        LinearLayout card = ui.dialog();
        card.addView(ui.brand("Sign in with Google"));
        ui.appHeading(card, callerPkg, "Choose an account to continue");

        accountsScroll = new ScrollView(this);
        accountsScroll.setFillViewport(false);
        accountsScroll.setVerticalScrollBarEnabled(false);
        accountList = ui.column();
        accountsScroll.addView(accountList);
        card.addView(accountsScroll, new LinearLayout.LayoutParams(-1, -2));

        progressLayout = ui.column();
        progressLayout.setGravity(Gravity.CENTER);
        progressLayout.setPadding(0, ui.dp(22), 0, ui.dp(22));
        progressLayout.setVisibility(View.GONE);
        progressLayout.addView(ui.loading(), new LinearLayout.LayoutParams(-1, ui.dp(20)));
        ui.space(progressLayout, 16);
        statusText = ui.caption("Signing in…");
        statusText.setGravity(Gravity.CENTER);
        progressLayout.addView(statusText);
        card.addView(progressLayout);
        ui.space(card, ui.compact() ? 0 : 12);
        setCancelFooter();
    }

    private void setCancelFooter() {
        ui.footer().removeAllViews();
        ui.footer().addView(ui.button("Cancel", false, this::cancel),
                new LinearLayout.LayoutParams(-1, ui.dp(48)));
    }

    private void loadAccounts() {
        new Thread(() -> {
            List<String> accounts;
            try {
                accounts = GmsAuthClient.getAccounts(userId);
            } catch (Exception e) {
                accounts = Collections.emptyList();
            }
            final List<String> result = accounts;
            main.post(() -> populateAccounts(result));
        }, "GmsAccounts").start();
    }

    private void populateAccounts(List<String> accounts) {
        if (isFinishing() || isDestroyed()) return;
        accountList.removeAllViews();
        accountList.setVisibility(View.VISIBLE);
        accountsScroll.setVisibility(View.VISIBLE);
        progressLayout.setVisibility(View.GONE);

        int n = accounts.size();
        for (int i = 0; i < n; i++) {
            final String email = accounts.get(i);
            String title = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            if (title.isEmpty()) title = email;
            View row = ui.accountRow(title, email, false, () -> signInWith(email));
            accountList.addView(row, new LinearLayout.LayoutParams(-1, -2));
            ui.reveal(row, i);
            ui.space(accountList, 8);
        }

        if (ui.compact()) {
            ui.footer().removeAllViews();
            LinearLayout buttons = new LinearLayout(this);
            buttons.addView(ui.button("Cancel", false, this::cancel),
                    new LinearLayout.LayoutParams(0, ui.dp(48), 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(48), 1f);
            lp.setMarginStart(ui.dp(10));
            buttons.addView(ui.button("Add account", n == 0, this::launchAddAccount), lp);
            ui.footer().addView(buttons);
            if (n == 0) accountList.addView(ui.caption("Add a Google account to continue"));
        } else {
            setCancelFooter();
            View add = ui.accountRow(n == 0 ? "Add a Google account" : "Use another account",
                    n == 0 ? "Sign in securely with Google" : "Add a Google account",
                    true, this::launchAddAccount);
            accountList.addView(add, new LinearLayout.LayoutParams(-1, -2));
            ui.reveal(add, n);
        }
        accountsScroll.setNestedScrollingEnabled(false);
    }

    private void showLoading(String msg) {
        accountsScroll.setVisibility(View.GONE);
        progressLayout.setVisibility(View.VISIBLE);
        statusText.setText(msg);
        setCancelFooter();
    }

    // ---------------------------------------------------------------------
    // Flow
    // ---------------------------------------------------------------------

    private void launchAddAccount() {
        Intent i = new Intent(this, GoogleWebLoginActivity.class);
        i.putExtra(GoogleWebLoginActivity.EXTRA_USER_ID, userId);
        startActivityForResult(i, RC_ADD_ACCOUNT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_ADD_ACCOUNT) return;
        String email = resultCode == RESULT_OK && data != null
                ? data.getStringExtra(GoogleWebLoginActivity.RESULT_ACCOUNT) : null;
        if (callerPkg == null) {
            if (email != null) {
                Intent r = new Intent();
                r.putExtra(RESULT_EMAIL, email);
                finished = true;
                setResult(RESULT_OK, r);
            }
            finish();
            return;
        }
        if (email != null) {
            signInWith(email);
        } else {
            loadAccounts();
        }
    }

    private void signInWith(String email) {
        showLoading("Signing in with " + email + "…");
        new Thread(() -> {
            GmsAuthClient.SignInResult r = GmsAuthClient.signIn(userId, callerPkg, options, email, true);
            main.post(() -> {
                if (r.isSuccess()) finishWithAccount(r.account);
                else fail(r.statusCode, failureMessage(r.statusCode));
            });
        }, "GmsSignIn").start();
    }

    private static String failureMessage(int code) {
        switch (code) {
            case GmsConstants.STATUS_SIGN_IN_REQUIRED:
                return "Google account is not available";
            case GmsConstants.STATUS_NETWORK_ERROR:
                return "Network error while contacting Google";
            case GmsConstants.STATUS_DEVELOPER_ERROR:
                return "Google rejected this app's sign-in request";
            default:
                return "Google authorization failed";
        }
    }

    /** Same three extras Zerax returns, so SignInHubActivity and direct callers both work. */
    private void finishWithAccount(GoogleSignInAccount account) {
        if (finished) return;
        finished = true;
        Intent data = new Intent();
        data.replaceExtras(ForeignParcelBundle.create(
                new ForeignParcelBundle.Entry(EXTRA_SIGN_IN_ACCOUNT, CLASS_SIGN_IN_ACCOUNT,
                        account::writeSignInAccount),
                new ForeignParcelBundle.Entry(EXTRA_GOOGLE_SIGN_IN_ACCOUNT, CLASS_GOOGLE_SIGN_IN_ACCOUNT,
                        account::writeSafeParcel),
                new ForeignParcelBundle.Entry(EXTRA_GOOGLE_SIGN_IN_STATUS, CLASS_STATUS,
                        dest -> SafeParcelHelper.writeStatus(dest, GmsConstants.STATUS_SUCCESS, null))));
        setResult(RESULT_OK, data);
        finish();
    }

    private void fail(int code, String message) {
        if (finished || isFinishing()) return;
        finished = true;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED, errorIntent(code));
        finish();
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        setResult(RESULT_CANCELED, errorIntent(GmsConstants.STATUS_SIGN_IN_CANCELLED));
        finish();
    }

    private static Intent errorIntent(int code) {
        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR_CODE, code);
        return data;
    }

    @Override
    public void onBackPressed() {
        cancel();
    }

    @Override
    protected void onDestroy() {
        if (ui != null) ui.close();
        super.onDestroy();
    }
}
