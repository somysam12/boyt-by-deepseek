package com.mundo.core.gms;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import com.mundo.MundoCore;
import com.mundo.core.system.ServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client for {@link GmsAuthService}. Usable from any MundoCore process (main,
 * guest, server). All calls block on network: never call on the main thread.
 */
public final class GmsAuthClient {

    private static final String TAG = "MundoGms";

    public static final String PROFILE_DISPLAY_NAME = "displayName";
    public static final String PROFILE_GIVEN_NAME = "givenName";
    public static final String PROFILE_FAMILY_NAME = "familyName";
    public static final String PROFILE_AVATAR = "avatar";

    private GmsAuthClient() {}

    /** Result of a sign-in call. */
    public static final class SignInResult {
        public final int statusCode;
        public final GoogleSignInAccount account;

        SignInResult(int statusCode, GoogleSignInAccount account) {
            this.statusCode = statusCode;
            this.account = account;
        }

        public boolean isSuccess() {
            return statusCode == GmsConstants.STATUS_SUCCESS && account != null;
        }
    }

    private static Bundle call(String method, Bundle args) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, method + " called on the main thread");
        }
        IBinder binder = MundoCore.get().getService(ServiceManager.GMS_AUTH);
        if (binder == null) throw new IllegalStateException("gms_auth service unavailable");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(GmsAuthService.DESCRIPTOR);
            data.writeString(method);
            data.writeBundle(args);
            binder.transact(GmsAuthService.TRANSACTION_CALL, data, reply, 0);
            reply.readException();
            Bundle b = reply.readBundle(GmsAuthClient.class.getClassLoader());
            if (b == null) b = new Bundle();
            String err = b.getString(GmsAuthService.K_ERROR);
            if (err != null) Log.w(TAG, method + ": " + err);
            return b;
        } catch (Exception e) {
            Log.w(TAG, method + " transact failed: " + e);
            Bundle b = new Bundle();
            b.putString(GmsAuthService.K_ERROR, String.valueOf(e));
            return b;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static Bundle base(int userId) {
        Bundle b = new Bundle();
        b.putInt(GmsAuthService.K_USER, userId);
        return b;
    }

    /** Exchanges the EmbeddedSetup oauth_token cookie for an account. Returns the email or null. */
    public static String login(int userId, String oauthToken) {
        Bundle a = base(userId);
        a.putString(GmsAuthService.K_TOKEN, oauthToken);
        return call(GmsAuthService.M_LOGIN, a).getString(GmsAuthService.K_EMAIL);
    }

    public static List<String> getAccounts(int userId) {
        ArrayList<String> l = call(GmsAuthService.M_ACCOUNTS, base(userId))
                .getStringArrayList(GmsAuthService.K_ACCOUNTS);
        return l != null ? l : Collections.<String>emptyList();
    }

    public static void removeAccount(int userId, String email) {
        Bundle a = base(userId);
        a.putString(GmsAuthService.K_EMAIL, email);
        call(GmsAuthService.M_REMOVE_ACCOUNT, a);
    }

    /** displayName / givenName / familyName / avatar (PNG/JPEG bytes, &lt;= 64 KB). */
    public static Bundle getProfile(int userId, String email) {
        Bundle a = base(userId);
        a.putString(GmsAuthService.K_EMAIL, email);
        return call(GmsAuthService.M_PROFILE, a);
    }

    public static long getAndroidId(int userId) {
        return call(GmsAuthService.M_ANDROID_ID, base(userId)).getLong(GmsAuthService.K_ANDROID_ID, 0);
    }

    /**
     * @param email       account to use, or null for the package's / default account
     * @param interactive true from the sign-in UI (user picked the account),
     *                    false for silentSignIn
     */
    public static SignInResult signIn(int userId, String pkg, SignInOptions options,
                                      String email, boolean interactive) {
        Bundle a = base(userId);
        a.putString(GmsAuthService.K_PKG, pkg);
        a.putBundle(GmsAuthService.K_OPTIONS, options.toBundle());
        a.putString(GmsAuthService.K_EMAIL, email);
        a.putBoolean(GmsAuthService.K_INTERACTIVE, interactive);
        Bundle r = call(GmsAuthService.M_SIGN_IN, a);
        int status = r.getInt(GmsAuthService.K_STATUS, GmsConstants.STATUS_INTERNAL_ERROR);
        return new SignInResult(status, GoogleSignInAccount.fromBundle(r.getBundle(GmsAuthService.K_ACCOUNT)));
    }

    public static int signOut(int userId, String pkg, SignInOptions options) {
        Bundle a = base(userId);
        a.putString(GmsAuthService.K_PKG, pkg);
        a.putBundle(GmsAuthService.K_OPTIONS, options.toBundle());
        return call(GmsAuthService.M_SIGN_OUT, a).getInt(GmsAuthService.K_STATUS, GmsConstants.STATUS_INTERNAL_ERROR);
    }

    public static int revokeAccess(int userId, String pkg, SignInOptions options) {
        Bundle a = base(userId);
        a.putString(GmsAuthService.K_PKG, pkg);
        a.putBundle(GmsAuthService.K_OPTIONS, options.toBundle());
        return call(GmsAuthService.M_REVOKE, a).getInt(GmsAuthService.K_STATUS, GmsConstants.STATUS_INTERNAL_ERROR);
    }
}
