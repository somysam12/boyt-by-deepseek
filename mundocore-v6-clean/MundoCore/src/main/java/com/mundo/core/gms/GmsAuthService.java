/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 *
 * Server-side Google account service for MundoCore.
 *
 * Runs ONLY in MundoCore's server process (registered in ServiceManager as
 * "gms_auth"). It is the single owner of GmsTokenStore and GmsCheckin data, so
 * GoogleLoginActivity (main process) and FakeSignInService (guest processes) all
 * see the same accounts and tokens.
 *
 * Ported from microG:
 *   - login():   LoginActivity.retrieveRtToken / saveAccount / retrieveGmsToken
 *   - signIn():  auth/signin/extensions.kt performSignIn + getOAuthManager /
 *                getIdTokenManager / getServerAuthTokenManager
 *   - consent:   extensions.kt performConsentView (ConsentSignInActivity)
 *   - silent / signOut / revoke: AuthSignInService.silentSignIn / signOut / revokeAccess
 *
 * Wire protocol (no AIDL needed): transaction CALL, data = (String method, Bundle args),
 * reply = Bundle. See GmsAuthClient.
 */

package com.mundo.core.gms;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;

import com.mundo.MundoCore;
import com.mundo.proxy.gms.ConsentSignInActivityKt;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class GmsAuthService extends Binder {

    private static final String TAG = "GmsAuthService";
    static final String DESCRIPTOR = "com.mundo.core.gms.IGmsAuthService";
    static final int TRANSACTION_CALL = FIRST_CALL_TRANSACTION;

    // methods
    static final String M_LOGIN = "login";
    static final String M_ACCOUNTS = "accounts";
    static final String M_SIGN_IN = "signIn";
    static final String M_SIGN_OUT = "signOut";
    static final String M_REVOKE = "revoke";
    static final String M_REMOVE_ACCOUNT = "removeAccount";
    static final String M_ANDROID_ID = "androidId";
    static final String M_PROFILE = "profile";

    // keys
    static final String K_USER = "userId";
    static final String K_PKG = "pkg";
    static final String K_TOKEN = "token";
    static final String K_EMAIL = "email";
    static final String K_OPTIONS = "options";
    static final String K_INTERACTIVE = "interactive";
    static final String K_STATUS = "status";
    static final String K_ERROR = "error";
    static final String K_ACCOUNT = "account";
    static final String K_ACCOUNTS = "accounts";
    static final String K_ANDROID_ID = "androidId";

    private static final String GMS = GmsConstants.GMS_PACKAGE;
    private static final String SERVICE_PROFILE =
            "oauth2:https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile";

    private static volatile GmsAuthService sInstance;

    private final Context context;
    private final GmsTokenStore store;

    public static GmsAuthService get() {
        if (sInstance == null) {
            synchronized (GmsAuthService.class) {
                if (sInstance == null) sInstance = new GmsAuthService(MundoCore.getContext());
            }
        }
        return sInstance;
    }

    private GmsAuthService(Context ctx) {
        context = ctx.getApplicationContext();
        store = new GmsTokenStore(context);
        GmsTokenStore.deleteLegacyPlaintextStore(context);
        attachInterface(null, DESCRIPTOR);
    }

    @Override
    public String getInterfaceDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != TRANSACTION_CALL) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        String method = data.readString();
        Bundle args = data.readBundle(getClass().getClassLoader());
        Bundle result;
        // Only MundoCore's own processes (same uid) may call in.
        if (getCallingUid() != android.os.Process.myUid()) {
            result = error("caller not allowed");
        } else {
            try {
                result = dispatch(method, args != null ? args : new Bundle());
            } catch (Throwable t) {
                Log.w(TAG, method + " failed: " + t);
                result = error(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        if (reply != null) {
            reply.writeNoException();
            reply.writeBundle(result);
        }
        return true;
    }

    private Bundle dispatch(String method, Bundle a) throws Exception {
        int userId = a.getInt(K_USER, 0);
        switch (method) {
            case M_LOGIN:
                return login(userId, a.getString(K_TOKEN));
            case M_ACCOUNTS: {
                Bundle b = new Bundle();
                b.putStringArrayList(K_ACCOUNTS, new ArrayList<>(store.getAccounts(userId)));
                return b;
            }
            case M_SIGN_IN:
                return signIn(userId, a.getString(K_PKG), SignInOptions.fromBundle(a.getBundle(K_OPTIONS)),
                        a.getString(K_EMAIL), a.getBoolean(K_INTERACTIVE));
            case M_SIGN_OUT:
                return signOut(userId, a.getString(K_PKG), SignInOptions.fromBundle(a.getBundle(K_OPTIONS)));
            case M_REVOKE:
                return revoke(userId, a.getString(K_PKG), SignInOptions.fromBundle(a.getBundle(K_OPTIONS)));
            case M_REMOVE_ACCOUNT:
                store.removeAccount(userId, a.getString(K_EMAIL));
                return new Bundle();
            case M_PROFILE:
                return profile(userId, a.getString(K_EMAIL));
            case M_ANDROID_ID: {
                Bundle b = new Bundle();
                GmsCheckin.checkin(context, false);
                b.putLong(K_ANDROID_ID, GmsCheckin.getAndroidId(context));
                return b;
            }
            default:
                return error("unknown method " + method);
        }
    }

    private static Bundle error(String msg) {
        Bundle b = new Bundle();
        b.putString(K_ERROR, msg);
        return b;
    }

    private static Bundle status(int code) {
        Bundle b = new Bundle();
        b.putInt(K_STATUS, code);
        return b;
    }

    private AuthManager manager(int userId, String email, String pkg, String service) {
        return new AuthManager(context, store, userId, email, pkg, service);
    }

    // =====================================================================
    // Login: oauth_token cookie -> master token (microG LoginActivity)
    // =====================================================================

    private Bundle login(int userId, String oauthToken) throws IOException {
        if (oauthToken == null) return error("no oauth_token");
        if (!GmsCheckin.checkin(context, false)) {
            Log.w(TAG, "check-in failed; continuing without androidId");
        }

        // retrieveRtToken()
        AuthResponse response = new AuthRequest().fromContext(context)
                .appIsGms()
                .callerIsGms()
                .service("ac2dm")
                .token(oauthToken).isAccessToken()
                .addAccount()
                .getAccountId()
                .droidguardResults("null")
                .getResponse();
        if (response.token == null || response.email == null) {
            return error("ac2dm exchange returned no token (" + response.issueAdvice + ")");
        }
        String email = response.email;

        // saveAccount()
        store.addAccount(userId, email, response.token);
        AuthManager gms = manager(userId, email, GMS, "ac2dm");
        if (response.Sid != null) gms.setAuthToken("SID", response.Sid);
        if (response.LSid != null) gms.setAuthToken("LSID", response.LSid);
        gms.setUserData("services", response.services);
        gms.setUserData("firstName", response.firstName);
        gms.setUserData("lastName", response.lastName);
        if (response.accountId != null && !response.accountId.isEmpty()) {
            gms.setUserData("GoogleUserId", response.accountId);
        }

        // retrieveGmsToken()
        try {
            gms.setPermitted(true);
            AuthResponse r2 = new AuthRequest().fromContext(context)
                    .appIsGms()
                    .callerIsGms()
                    .service("ac2dm")
                    .email(email)
                    .token(response.token)
                    .systemPartition(true)
                    .hasPermission(true)
                    .addAccount()
                    .getAccountId()
                    .droidguardResults("null")
                    .getResponse();
            gms.storeResponse(r2);
        } catch (IOException e) {
            Log.w(TAG, "retrieveGmsToken: " + e.getMessage());
        }

        loadProfile(userId, email);
        GmsCheckin.checkin(context, true);

        Bundle b = new Bundle();
        b.putString(K_EMAIL, email);
        return b;
    }

    /** Stand-in for microG PeopleManager: fills display name / photo / user id. */
    private void loadProfile(int userId, String email) {
        try {
            AuthManager m = manager(userId, email, GMS, SERVICE_PROFILE);
            m.setPermitted(true);
            String token = m.requestAuth(false).auth;
            if (token == null) return;
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://www.googleapis.com/oauth2/v3/userinfo").openConnection();
            c.setRequestProperty("Authorization", "Bearer " + token);
            try {
                if (c.getResponseCode() != 200) return;
                JSONObject j = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
                AuthManager gms = manager(userId, email, GMS, "ac2dm");
                if (gms.getUserData("GoogleUserId") == null && j.has("sub")) {
                    gms.setUserData("GoogleUserId", j.optString("sub"));
                }
                gms.setUserData("displayName", j.optString("name", null));
                gms.setUserData("givenName", j.optString("given_name", null));
                gms.setUserData("familyName", j.optString("family_name", null));
                gms.setUserData("photoUrl", j.optString("picture", null));
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "loadProfile: " + e.getMessage());
        }
    }

    /** Name + small avatar for the account chooser (cached after the first download). */
    private Bundle profile(int userId, String email) {
        Bundle b = new Bundle();
        if (email == null || !store.hasAccount(userId, email)) return b;
        AuthManager gms = manager(userId, email, GMS, "ac2dm");
        if (gms.getUserData("displayName") == null) loadProfile(userId, email);
        b.putString(GmsAuthClient.PROFILE_DISPLAY_NAME, gms.getUserData("displayName"));
        b.putString(GmsAuthClient.PROFILE_GIVEN_NAME, gms.getUserData("givenName"));
        b.putString(GmsAuthClient.PROFILE_FAMILY_NAME, gms.getUserData("familyName"));
        String cached = gms.getUserData("avatar");
        byte[] avatar = null;
        if (cached != null) {
            avatar = Base64.decode(cached, Base64.NO_WRAP);
        } else {
            String url = gms.getUserData("photoUrl");
            if (url != null && url.startsWith("https://")) {
                try {
                    // googleusercontent photos accept a size suffix; 96px keeps it small
                    String sized = url.contains("googleusercontent.com") && !url.contains("=")
                            ? url + "=s96-c" : url;
                    HttpURLConnection c = (HttpURLConnection) new URL(sized).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    try {
                        if (c.getResponseCode() == 200) {
                            byte[] d = readAll(c.getInputStream());
                            if (d.length <= 65536) {
                                avatar = d;
                                gms.setUserData("avatar", Base64.encodeToString(d, Base64.NO_WRAP));
                            }
                        }
                    } finally {
                        c.disconnect();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "avatar: " + e.getMessage());
                }
            }
        }
        if (avatar != null) b.putByteArray(GmsAuthClient.PROFILE_AVATAR, avatar);
        return b;
    }

    // =====================================================================
    // Sign-in (microG extensions.kt / AuthSignInService.kt)
    // =====================================================================

    private Bundle signIn(int userId, String pkg, SignInOptions options, String email, boolean interactive)
            throws IOException {
        if (pkg == null) return status(GmsConstants.STATUS_DEVELOPER_ERROR);

        String account = email != null ? email : options.accountName;
        if (account == null) account = store.getPackageAccount(userId, pkg);
        if (account == null && interactive) account = store.getDefaultAccount(userId);
        if (account == null || !store.hasAccount(userId, account)) {
            return status(GmsConstants.STATUS_SIGN_IN_REQUIRED);
        }
        if (!interactive && options.forceCodeForRefreshToken) {
            // microG: silentSignIn never mints a forced refresh-token code
            return status(GmsConstants.STATUS_SIGN_IN_REQUIRED);
        }

        GoogleSignInAccount result = performSignIn(userId, pkg, options, account, interactive);
        if (result == null) {
            return status(interactive ? GmsConstants.STATUS_SIGN_IN_FAILED : GmsConstants.STATUS_DEVELOPER_ERROR);
        }
        store.setPackageAccount(userId, pkg, account);
        store.setDefaultAccount(userId, account);
        Bundle b = status(GmsConstants.STATUS_SUCCESS);
        b.putBundle(K_ACCOUNT, result.toBundle());
        return b;
    }

    private List<String> oauthScopes(SignInOptions o) {
        List<String> scopes = o.sortedScopes();
        if (o.includeGame()) scopes.add(SignInOptions.SCOPE_GAMES_LITE);
        if (scopes.isEmpty()) scopes.add(SignInOptions.SCOPE_EMAIL); // avoid an empty "oauth2:" service
        return scopes;
    }

    private AuthManager getOAuthManager(int userId, String pkg, SignInOptions o, String email) {
        AuthManager m = manager(userId, email, pkg, "oauth2:" + join(oauthScopes(o)));
        m.includeGrantedScopes = "1";
        return m;
    }

    private AuthManager getIdTokenManager(int userId, String pkg, SignInOptions o, String email) {
        if (!o.idTokenRequested || o.serverClientId == null) return null;
        AuthManager m = manager(userId, email, pkg, "audience:server:client_id:" + o.serverClientId);
        m.includeEmail = o.includeEmail() ? "1" : "0";
        m.includeProfile = o.includeProfile() ? "1" : "0";
        m.includeGrantedScopes = "0";
        return m;
    }

    private AuthManager getServerAuthTokenManager(int userId, String pkg, SignInOptions o, String email) {
        if (!o.serverAuthCodeRequested || o.serverClientId == null) return null;
        AuthManager m = manager(userId, email, pkg,
                "oauth2:server:client_id:" + o.serverClientId + ":api_scope:" + join(o.sortedScopes()));
        m.includeEmail = o.includeEmail() ? "1" : "0";
        m.includeProfile = o.includeProfile() ? "1" : "0";
        m.forceRefreshToken = true; // authorization codes are single-use
        m.includeGrantedScopes = "1";
        m.setOauth2Prompt(o.forceCodeForRefreshToken ? "consent" : "auto");
        m.setItCaveatTypes("2");
        return m;
    }

    private GoogleSignInAccount performSignIn(int userId, String pkg, SignInOptions o, String email,
                                              boolean permitted) throws IOException {
        AuthManager authManager = getOAuthManager(userId, pkg, o, email);
        if (o.includeUnacceptableScope() || !permitted) {
            authManager.setTokenRequestOptions(consentRequestOptions());
        }
        if (permitted) authManager.isPermitted = true;
        AuthResponse authResponse = authManager.requestAuth(true);

        String consentResult = null;
        if ("remote_consent".equals(authResponse.issueAdvice) && authResponse.resolutionDataBase64 != null) {
            if (!permitted) return null; // consent UI only for interactive sign-in
            consentResult = performConsentView(userId, pkg, email, authResponse.resolutionDataBase64);
            if (consentResult == null) return null;
            authManager.putDynamicFiled(ConsentSignInActivityKt.CONSENT_RESULT, consentResult);
            authResponse = authManager.requestAuth(true);
        }
        if (authResponse.auth == null) {
            Log.w(TAG, "performSignIn: no oauth token for " + pkg + " (" + authResponse.issueAdvice + ")");
            return null;
        }

        AuthResponse idTokenResponse = null;
        AuthManager idMgr = getIdTokenManager(userId, pkg, o, email);
        if (idMgr != null) {
            idMgr.isPermitted = true;
            if (consentResult != null) idMgr.putDynamicFiled(ConsentSignInActivityKt.CONSENT_RESULT, consentResult);
            idTokenResponse = idMgr.requestAuth(true);
        }
        AuthResponse serverAuthTokenResponse = null;
        AuthManager codeMgr = getServerAuthTokenManager(userId, pkg, o, email);
        if (codeMgr != null) {
            codeMgr.isPermitted = true;
            if (consentResult != null) codeMgr.putDynamicFiled(ConsentSignInActivityKt.CONSENT_RESULT, consentResult);
            serverAuthTokenResponse = codeMgr.requestAuth(true);
        }

        AuthManager gms = manager(userId, email, GMS, "ac2dm");
        String googleUserId = authManager.getUserData("GoogleUserId");
        if (googleUserId == null) googleUserId = gms.getUserData("GoogleUserId");

        GoogleSignInAccount acc = new GoogleSignInAccount();
        acc.id = o.includeId() ? googleUserId : null;
        acc.idToken = o.idTokenRequested && idTokenResponse != null ? idTokenResponse.auth : null;
        acc.email = email;
        acc.serverAuthCode = o.serverAuthCodeRequested && serverAuthTokenResponse != null
                ? serverAuthTokenResponse.auth : null;
        long exp = Math.min(orMax(authResponse.expiry), idTokenResponse != null ? orMax(idTokenResponse.expiry) : Long.MAX_VALUE);
        acc.expirationTimeSecs = exp;
        acc.obfuscatedIdentifier = md5Upper(googleUserId + ":" + pkg);

        String granted = authResponse.grantedScopes;
        if (granted == null && idTokenResponse != null) granted = idTokenResponse.grantedScopes;
        if (granted == null && serverAuthTokenResponse != null) granted = serverAuthTokenResponse.grantedScopes;
        acc.grantedScopes = granted != null ? new ArrayList<>(Arrays.asList(granted.split(" "))) : o.sortedScopes();

        if (o.includeProfile()) {
            acc.displayName = gms.getUserData("displayName");
            acc.givenName = gms.getUserData("givenName");
            acc.familyName = gms.getUserData("familyName");
            acc.photoUrl = gms.getUserData("photoUrl");
        }
        // Same rule as Zerax (finishWithAccount): never hand back an account without the
        // credential the game asked for; its backend login would fail anyway.
        if (o.idTokenRequested && acc.idToken == null) {
            Log.w(TAG, "id token requested by " + pkg + " but not issued (check serverClientId / client_sig)");
            return null;
        }
        if (o.serverAuthCodeRequested && (acc.serverAuthCode == null || acc.serverAuthCode.isEmpty())) {
            Log.w(TAG, "server auth code requested by " + pkg + " but not issued");
            return null;
        }
        return acc;
    }

    private Bundle signOut(int userId, String pkg, SignInOptions o) {
        String email = o.accountName != null ? o.accountName : store.getPackageAccount(userId, pkg);
        if (email != null) {
            performSignOut(userId, pkg, o, email);
        }
        store.setPackageAccount(userId, pkg, null);
        return status(GmsConstants.STATUS_SUCCESS);
    }

    private void performSignOut(int userId, String pkg, SignInOptions o, String email) {
        AuthManager m = getOAuthManager(userId, pkg, o, email);
        m.setPermitted(false);
        m.invalidateAuthToken();
        AuthManager id = getIdTokenManager(userId, pkg, o, email);
        if (id != null) { id.setPermitted(false); id.invalidateAuthToken(); }
        AuthManager code = getServerAuthTokenManager(userId, pkg, o, email);
        if (code != null) { code.setPermitted(false); code.invalidateAuthToken(); }
    }

    private Bundle revoke(int userId, String pkg, SignInOptions o) {
        String email = o.accountName != null ? o.accountName : store.getPackageAccount(userId, pkg);
        if (email == null) return status(GmsConstants.STATUS_SUCCESS);
        AuthManager m = getOAuthManager(userId, pkg, o, email);
        String token = m.peekAuthToken();
        if (token != null) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://accounts.google.com/o/oauth2/revoke?token=" + token).openConnection();
                c.setRequestProperty("Authorization", "OAuth " + token);
                c.getResponseCode();
                c.disconnect();
            } catch (IOException e) {
                Log.w(TAG, "revoke: " + e.getMessage());
                return status(GmsConstants.STATUS_INTERNAL_ERROR);
            }
            m.invalidateAuthToken(token);
            m.setPermitted(false);
        }
        performSignOut(userId, pkg, o, email);
        return status(GmsConstants.STATUS_SUCCESS);
    }

    // =====================================================================
    // Consent (microG performConsentView + ConsentSignInActivity)
    // =====================================================================

    private static String consentRequestOptions() {
        try {
            byte[] sid = new byte[16];
            new SecureRandom().nextBytes(sid);
            String sessionId = Base64.encodeToString(sid, Base64.NO_WRAP).trim();
            // RequestOptions { remote = 7; version = 10; sessionId = 11 }
            byte[] proto = new GmsCheckin.Proto()
                    .varint(7, 1)
                    .varint(10, 3)
                    .string(11, sessionId)
                    .toByteArray();
            return Base64.encodeToString(proto, Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private String performConsentView(int userId, String pkg, String email, String dataBase64) throws IOException {
        // ConsentUrlResponse { resultCode = 2; consentUrl = 3; cookie = 4 }
        byte[] consent = Base64.decode(dataBase64, Base64.URL_SAFE);
        String consentUrl = null;
        List<String[]> cookies = new ArrayList<>();
        for (Object[] f : ProtoReader.fields(consent)) {
            int field = (Integer) f[0];
            if (field == 3) consentUrl = new String((byte[]) f[1], StandardCharsets.UTF_8);
            else if (field == 4) cookies.add(ProtoReader.cookie((byte[]) f[1]));
        }
        if (consentUrl == null) return null;

        AuthManager cookiesMgr = manager(userId, email, pkg, "weblogin:url=https://accounts.google.com");
        cookiesMgr.isGmsApp = true;
        String cookiesAuth = cookiesMgr.requestAuth(true).auth;
        if (cookiesAuth == null) return null;
        // ConsentCookiesResponse { consentCookies = 1 } ; ConsentCookies { resultCode = 1; cookies = 2 }
        for (Object[] f : ProtoReader.fields(Base64.decode(cookiesAuth, Base64.URL_SAFE))) {
            if ((Integer) f[0] != 1) continue;
            for (Object[] g : ProtoReader.fields((byte[]) f[1])) {
                if ((Integer) g[0] != 2) continue;
                String[] c = ProtoReader.cookie((byte[]) g[1]);
                if (".google.com".equals(c[2]) || "accounts.google.com".equals(c[3])) cookies.add(c);
            }
        }

        final AtomicReference<String> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        HandlerThread ht = new HandlerThread("GmsConsent");
        ht.start();
        try {
            Messenger messenger = new Messenger(new Handler(ht.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    result.set(msg.getData().getString(ConsentSignInActivityKt.CONSENT_RESULT));
                    latch.countDown();
                }
            });
            Intent intent = new Intent(context, com.mundo.proxy.gms.ConsentSignInActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(ConsentSignInActivityKt.CONSENT_URL, consentUrl);
            intent.putExtra(ConsentSignInActivityKt.CONSENT_MESSENGER, messenger);
            for (int i = 0; i < cookies.size(); i++) {
                String[] c = cookies.get(i);
                intent.putExtra(ConsentSignInActivityKt.CONSENT_KEY_COOKIE + i, c[0] + "=" + c[1] + ";");
            }
            context.startActivity(intent);
            latch.await(10, TimeUnit.MINUTES);
            return result.get();
        } catch (InterruptedException e) {
            return null;
        } finally {
            ht.quitSafely();
        }
    }

    /** Minimal protobuf field iterator for the consent messages. */
    static final class ProtoReader {
        /** Returns [fieldNumber(Integer), value(Long for varint/fixed, byte[] for length-delimited)]. */
        static List<Object[]> fields(byte[] d) {
            List<Object[]> out = new ArrayList<>();
            int[] pos = {0};
            while (pos[0] < d.length) {
                long tag = GmsCheckin.Proto.readVarint(d, pos);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    out.add(new Object[]{field, GmsCheckin.Proto.readVarint(d, pos)});
                } else if (wire == 1) {
                    out.add(new Object[]{field, GmsCheckin.Proto.readFixed64(d, pos)});
                } else if (wire == 2) {
                    int len = (int) GmsCheckin.Proto.readVarint(d, pos);
                    if (len < 0 || pos[0] + len > d.length) break;
                    out.add(new Object[]{field, Arrays.copyOfRange(d, pos[0], pos[0] + len)});
                    pos[0] += len;
                } else if (wire == 5) {
                    pos[0] += 4;
                } else {
                    break;
                }
            }
            return out;
        }

        /** Cookie { cookieName = 1; cookieValue = 2; domain = 3; path = 4 } -> {name, value, domain, path} */
        static String[] cookie(byte[] d) {
            String[] c = new String[4];
            for (Object[] f : fields(d)) {
                int n = (Integer) f[0];
                if (n >= 1 && n <= 4 && f[1] instanceof byte[]) {
                    c[n - 1] = new String((byte[]) f[1], StandardCharsets.UTF_8);
                }
            }
            return c;
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static long orMax(long v) {
        return v >= 0 ? v : Long.MAX_VALUE;
    }

    private static String join(List<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(x);
        }
        return sb.toString();
    }

    private static String md5Upper(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02X", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
    }
}
