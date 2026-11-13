package com.mundo.core.gms;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Account + token storage for virtual Google accounts.
 *
 * IMPORTANT: this class is only used inside {@link GmsAuthService}, i.e. in the
 * single host process that owns MundoCore's system services. Guest processes and
 * GoogleLoginActivity never touch it directly; they go through {@link GmsAuthClient}.
 * That keeps one coherent copy of the data (SharedPreferences is not multi-process
 * safe, and guest processes would otherwise get their own redirected data dir).
 *
 * Everything is namespaced by virtual userId, so each virtual user has its own
 * Google accounts. Secrets (master token, auth tokens, SID/LSID, any userData) are
 * encrypted with an AndroidKeyStore AES-GCM key (see {@link GmsCrypto}).
 *
 * Layout (prefs file "gms_accounts_v2"):
 *   u<uid>.accounts               -> Set<String> of emails
 *   u<uid>.default                -> email last used
 *   u<uid>.a.<email>.master       -> encrypted master (ac2dm) token
 *   u<uid>.a.<email>.ud.<key>     -> encrypted user data (AuthManager tokens, expiry, perms, profile)
 */
final class GmsTokenStore {

    private static final String PREFS = "gms_accounts_v2";

    private final SharedPreferences prefs;

    GmsTokenStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String u(int userId) {
        return "u" + userId + ".";
    }

    private static String a(int userId, String email) {
        return u(userId) + "a." + email + ".";
    }

    // ---- accounts -----------------------------------------------------------

    synchronized void addAccount(int userId, String email, String masterToken) {
        Set<String> accounts = new HashSet<>(prefs.getStringSet(u(userId) + "accounts", Collections.<String>emptySet()));
        accounts.add(email);
        prefs.edit()
                .putStringSet(u(userId) + "accounts", accounts)
                .putString(a(userId, email) + "master", GmsCrypto.encrypt(masterToken))
                .putString(u(userId) + "default", email)
                .commit();
    }

    synchronized void removeAccount(int userId, String email) {
        Set<String> accounts = new HashSet<>(prefs.getStringSet(u(userId) + "accounts", Collections.<String>emptySet()));
        accounts.remove(email);
        SharedPreferences.Editor ed = prefs.edit().putStringSet(u(userId) + "accounts", accounts);
        String prefix = a(userId, email);
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith(prefix)) ed.remove(k);
        }
        if (email.equals(prefs.getString(u(userId) + "default", null))) {
            ed.remove(u(userId) + "default");
        }
        ed.commit();
    }

    synchronized List<String> getAccounts(int userId) {
        List<String> list = new ArrayList<>(prefs.getStringSet(u(userId) + "accounts", Collections.<String>emptySet()));
        Collections.sort(list);
        return list;
    }

    synchronized boolean hasAccount(int userId, String email) {
        return email != null && prefs.contains(a(userId, email) + "master");
    }

    synchronized String getMasterToken(int userId, String email) {
        return GmsCrypto.decrypt(prefs.getString(a(userId, email) + "master", null));
    }

    synchronized String getDefaultAccount(int userId) {
        String def = prefs.getString(u(userId) + "default", null);
        if (def != null && hasAccount(userId, def)) return def;
        List<String> all = getAccounts(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    synchronized void setDefaultAccount(int userId, String email) {
        prefs.edit().putString(u(userId) + "default", email).commit();
    }

    // ---- account selected by each guest package (microG SignInConfigurationService) ----

    synchronized String getPackageAccount(int userId, String packageName) {
        String email = prefs.getString(u(userId) + "pkg." + packageName, null);
        return hasAccount(userId, email) ? email : null;
    }

    synchronized void setPackageAccount(int userId, String packageName, String email) {
        String k = u(userId) + "pkg." + packageName;
        if (email == null) prefs.edit().remove(k).commit();
        else prefs.edit().putString(k, email).commit();
    }

    // ---- per-account user data (AuthManager's AccountManager.getUserData) ---

    synchronized String getUserData(int userId, String email, String key) {
        if (email == null || key == null) return null;
        return GmsCrypto.decrypt(prefs.getString(a(userId, email) + "ud." + key, null));
    }

    synchronized void setUserData(int userId, String email, String key, String value) {
        if (email == null || key == null) return;
        String k = a(userId, email) + "ud." + key;
        if (value == null) {
            prefs.edit().remove(k).commit();
        } else {
            prefs.edit().putString(k, GmsCrypto.encrypt(value)).commit();
        }
    }

    /** One-time cleanup of the old plaintext store from the previous implementation. */
    static void deleteLegacyPlaintextStore(Context ctx) {
        SharedPreferences old = ctx.getApplicationContext().getSharedPreferences("gms_tokens", Context.MODE_PRIVATE);
        Map<String, ?> all = old.getAll();
        if (!all.isEmpty()) old.edit().clear().commit();
    }
}
