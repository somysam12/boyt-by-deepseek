package com.mundo.core.gms;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain-Java view of com.google.android.gms.auth.api.signin.GoogleSignInOptions.
 *
 * The guest app holds the real (obfuscated) Google class; we never load it. Instead
 * we marshal the guest's Parcelable to bytes inside the guest process and decode
 * the SafeParcel fields here:
 *   1 versionCode, 2 scopes (List<Scope>), 3 account (android.accounts.Account),
 *   4 idTokenRequested, 5 serverAuthCodeRequested, 6 forceCodeForRefreshToken,
 *   7 serverClientId, 8 hostedDomain, 9 extensions, 10 logSessionId
 *
 * SignInConfiguration (the "config" extra sent to GMS): 2 packageName, 5 options.
 *
 * Also contains the scope helpers from microG auth/signin/extensions.kt.
 */
public class SignInOptions {

    private static final String TAG = "GmsSignInOptions";

    public static final String SCOPE_OPENID = "openid";
    public static final String SCOPE_EMAIL = "email";
    public static final String SCOPE_PROFILE = "profile";
    public static final String SCOPE_USERINFO_EMAIL = "https://www.googleapis.com/auth/userinfo.email";
    public static final String SCOPE_USERINFO_PROFILE = "https://www.googleapis.com/auth/userinfo.profile";
    public static final String SCOPE_GAMES = "https://www.googleapis.com/auth/games";
    public static final String SCOPE_GAMES_LITE = "https://www.googleapis.com/auth/games_lite";

    public List<String> scopes = new ArrayList<>();
    public String accountName;
    public boolean idTokenRequested;
    public boolean serverAuthCodeRequested;
    public boolean forceCodeForRefreshToken;
    public String serverClientId;
    public String hostedDomain;

    // ---- microG extensions.kt equivalents ----------------------------------

    public List<String> sortedScopes() {
        List<String> s = new ArrayList<>(scopes);
        Collections.sort(s);
        return s;
    }

    public boolean includeId() {
        return scopes.contains(SCOPE_OPENID) || scopes.contains(SCOPE_GAMES_LITE);
    }

    public boolean includeEmail() {
        return scopes.contains(SCOPE_EMAIL) || scopes.contains(SCOPE_GAMES_LITE);
    }

    public boolean includeProfile() {
        return scopes.contains(SCOPE_PROFILE);
    }

    public boolean includeGame() {
        for (String s : scopes) if (s.contains(SCOPE_GAMES)) return true;
        return false;
    }

    public boolean includeUnacceptableScope() {
        for (String s : scopes) {
            if (!SCOPE_OPENID.equals(s) && !SCOPE_EMAIL.equals(s) && !SCOPE_PROFILE.equals(s)
                    && !SCOPE_USERINFO_EMAIL.equals(s) && !SCOPE_USERINFO_PROFILE.equals(s)
                    && !SCOPE_GAMES_LITE.equals(s)) {
                return true;
            }
        }
        return false;
    }

    // ---- Bundle transport (guest <-> host) ----------------------------------

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putStringArrayList("scopes", new ArrayList<>(scopes));
        b.putString("account", accountName);
        b.putBoolean("idToken", idTokenRequested);
        b.putBoolean("serverAuthCode", serverAuthCodeRequested);
        b.putBoolean("forceCode", forceCodeForRefreshToken);
        b.putString("serverClientId", serverClientId);
        b.putString("hostedDomain", hostedDomain);
        return b;
    }

    public static SignInOptions fromBundle(Bundle b) {
        SignInOptions o = new SignInOptions();
        if (b == null) return o;
        ArrayList<String> sc = b.getStringArrayList("scopes");
        if (sc != null) o.scopes = sc;
        o.accountName = b.getString("account");
        o.idTokenRequested = b.getBoolean("idToken");
        o.serverAuthCodeRequested = b.getBoolean("serverAuthCode");
        o.forceCodeForRefreshToken = b.getBoolean("forceCode");
        o.serverClientId = b.getString("serverClientId");
        o.hostedDomain = b.getString("hostedDomain");
        return o;
    }

    // ---- SafeParcel decoding ------------------------------------------------

    /** Decodes a GoogleSignInOptions object at the parcel's current position. */
    public static SignInOptions readFromParcel(Parcel p) {
        SignInOptions o = new SignInOptions();
        int end = SafeParcelHelper.validateObjectHeader(p);
        while (p.dataPosition() < end) {
            int h = SafeParcelHelper.readHeader(p);
            switch (SafeParcelHelper.fieldId(h)) {
                case 2:
                    o.scopes = SafeParcelHelper.readScopeList(p, h);
                    break;
                case 3: {
                    int[] blk = SafeParcelHelper.readBlock(p, h);
                    if (blk != null) {
                        int after = p.dataPosition();
                        p.setDataPosition(blk[0]);
                        o.accountName = p.readString(); // Account.writeToParcel: name, type, ...
                        p.setDataPosition(after);
                    }
                    break;
                }
                case 4: o.idTokenRequested = SafeParcelHelper.readBoolean(p, h); break;
                case 5: o.serverAuthCodeRequested = SafeParcelHelper.readBoolean(p, h); break;
                case 6: o.forceCodeForRefreshToken = SafeParcelHelper.readBoolean(p, h); break;
                case 7: o.serverClientId = SafeParcelHelper.readString(p, h); break;
                case 8: o.hostedDomain = SafeParcelHelper.readString(p, h); break;
                default: SafeParcelHelper.skip(p, h); break;
            }
        }
        p.setDataPosition(end);
        return o;
    }

    /**
     * Decodes a SignInConfiguration object (field 2 packageName, field 5 options).
     * Returns {packageName, options}.
     */
    public static Object[] readSignInConfiguration(Parcel p) {
        String pkg = null;
        SignInOptions opts = null;
        int end = SafeParcelHelper.validateObjectHeader(p);
        while (p.dataPosition() < end) {
            int h = SafeParcelHelper.readHeader(p);
            int id = SafeParcelHelper.fieldId(h);
            if (id == 2) {
                pkg = SafeParcelHelper.readString(p, h);
            } else if (id == 5) {
                int size = SafeParcelHelper.readSize(p, h);
                int start = p.dataPosition();
                if (size > 0) opts = readFromParcel(p);
                p.setDataPosition(start + size);
            } else {
                SafeParcelHelper.skip(p, h);
            }
        }
        p.setDataPosition(end);
        return new Object[]{pkg, opts != null ? opts : new SignInOptions()};
    }

    /**
     * Marshals a guest-side Parcelable (whose class we cannot load here) to a Parcel
     * positioned at the start of its SafeParcel body. Must run in the guest process.
     */
    public static Parcel marshal(Parcelable obj) {
        Parcel p = Parcel.obtain();
        obj.writeToParcel(p, 0);
        p.setDataPosition(0);
        return p;
    }

    /** Decodes the guest's GoogleSignInOptions Parcelable (may be null). */
    public static SignInOptions fromGuestObject(Parcelable gso) {
        if (gso == null) return new SignInOptions();
        Parcel p = marshal(gso);
        try {
            return readFromParcel(p);
        } catch (Exception e) {
            Log.w(TAG, "GoogleSignInOptions decode failed", e);
            return new SignInOptions();
        } finally {
            p.recycle();
        }
    }

    @Override
    public String toString() {
        return "SignInOptions{scopes=" + scopes + ", account=" + accountName
                + ", idToken=" + idTokenRequested + ", serverAuthCode=" + serverAuthCodeRequested
                + ", serverClientId=" + serverClientId + "}";
    }
}
