package com.mundo.proxy.gms;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.core.gms.FakeGmsServiceBroker;
import com.mundo.core.gms.GmsBrokerProxy;
import com.mundo.core.gms.SignInOptions;

import java.lang.reflect.Method;

/**
 * Guest-process helper used by the bindService / startActivity hooks.
 *
 * bindService to com.google.android.gms  -> FakeGmsServiceBroker delivered to the
 *                                          guest's ServiceConnection directly.
 * startActivityForResult of GMS's sign-in -> GoogleLoginActivity (host), launched
 * (sent by the guest's own SignInHubActivity)  through the real AMS with the guest's
 *                                          resultTo token, so the result comes back
 *                                          to SignInHubActivity.onActivityResult.
 *
 * Note: SignInHubActivity and GoogleApiActivity belong to the guest APK
 * (play-services-auth / -base) and must NOT be intercepted.
 */
public final class GmsSignInInterceptor {

    private static final String TAG = "MundoGms";

    public static final String GMS_PKG = "com.google.android.gms";

    /** What play-services-auth's SignInHubActivity starts for result. */
    private static final String ACTION_GOOGLE_SIGN_IN = "com.google.android.gms.auth.GOOGLE_SIGN_IN";
    private static final String ACTION_NO_IMPL = "com.google.android.gms.auth.NO_IMPL";
    private static final String ACTION_START_SIGN_IN = "com.google.android.gms.auth.api.signin.ACTION_START_SIGN_IN";
    private static final String EXTRA_CONFIG = "config";

    private GmsSignInInterceptor() {}

    // ---------------------------------------------------------------------
    // bindService
    // ---------------------------------------------------------------------

    public static boolean shouldInterceptBind(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if ("com.google.android.c2dm.intent.REGISTER".equals(action)) return false; // GCM
        boolean intercept = false;
        if (GMS_PKG.equals(intent.getPackage())) intercept = true;
        ComponentName comp = intent.getComponent();
        if (!intercept && comp != null && GMS_PKG.equals(comp.getPackageName())) intercept = true;
        if (intercept) {
            Log.w(TAG, "shouldInterceptBind=true action=" + action + " pkg=" + intent.getPackage() + " comp=" + comp);
        }
        return intercept;
    }

    /**
     * Hands a FakeGmsServiceBroker to the guest's ServiceConnection. Returns false if
     * delivery failed (caller should fall back to the normal bind path).
     */
    public static boolean deliverFakeBroker(Intent intent, IServiceConnection connection) {
        if (connection == null) return false;
        ComponentName comp = intent.getComponent() != null ? intent.getComponent()
                : new ComponentName(GMS_PKG, "com.google.android.gms.chimera.GmsApiService");
        IBinder broker = new FakeGmsServiceBroker(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
        try {
            // API <26: connected(ComponentName, IBinder)
            // API 26+: connected(ComponentName, IBinder, boolean dead)
            // API 36+: connected(ComponentName, IBinder, IBinderSession, boolean dead)
            for (Method m : connection.getClass().getMethods()) {
                if (!"connected".equals(m.getName())) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length < 2 || t[0] != ComponentName.class || t[1] != IBinder.class) continue;
                Object[] a = new Object[t.length];
                a[0] = comp;
                a[1] = broker;
                for (int i = 2; i < t.length; i++) a[i] = t[i] == boolean.class ? Boolean.FALSE : null;
                m.invoke(connection, a);
                return true;
            }
            Log.w(TAG, "IServiceConnection.connected not found");
        } catch (Throwable e) {
            Log.w(TAG, "deliverFakeBroker failed: " + e);
        }
        return false;
    }

    /**
     * Device has real Play services: bind to it for real (as the host package) and
     * let ServiceConnectionDelegate wrap the broker with GmsBrokerProxy.
     *
     * @return the bind result (&gt; 0) or null if the real bind did not happen
     *         (Play services missing/disabled), in which case args are restored.
     */
    public static Object bindRealGms(Object who, Method method, Object[] args, int connIndex,
                                     IServiceConnection delegate) {
        Object[] saved = args.clone();
        String guest = BActivityThread.getAppPackageName();
        String host = MundoCore.getHostPkg();
        try {
            args[connIndex] = delegate;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof String && guest != null && guest.equals(args[i])) args[i] = host;
            }
            Object r = method.invoke(who, args);
            if (r instanceof Number && ((Number) r).intValue() > 0) {
                Log.w(TAG, "GMS bind -> real Play services (broker wrapped) for " + guest);
                return r;
            }
            if (r instanceof Boolean && (Boolean) r) return r;
        } catch (Throwable e) {
            Log.w(TAG, "real GMS bind failed: " + e);
        }
        System.arraycopy(saved, 0, args, 0, args.length);
        return null;
    }

    /** Called from ServiceConnectionDelegate.connected for every bound service. */
    public static IBinder maybeWrapBroker(ComponentName target, ComponentName name, IBinder service) {
        boolean gms = (target != null && GMS_PKG.equals(target.getPackageName()))
                || (name != null && GMS_PKG.equals(name.getPackageName()));
        if (!gms || service == null) return service;
        return GmsBrokerProxy.wrap(service, BActivityThread.getUserId(),
                BActivityThread.getAppPackageName(), MundoCore.getHostPkg());
    }

    // ---------------------------------------------------------------------
    // startActivity
    // ---------------------------------------------------------------------

    public static boolean shouldInterceptSignInUi(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (ACTION_NO_IMPL.equals(action) || ACTION_START_SIGN_IN.equals(action)) {
            Log.w(TAG, "shouldInterceptSignInUi=true (fallback action=" + action + ")");
            return true;
        }
        if (!ACTION_GOOGLE_SIGN_IN.equals(action)) return false;
        String pkg = intent.getPackage();
        ComponentName comp = intent.getComponent();
        boolean intercept = GMS_PKG.equals(pkg) || (comp != null && GMS_PKG.equals(comp.getPackageName()))
                || (pkg == null && comp == null);
        Log.w(TAG, "shouldInterceptSignInUi=" + intercept + " action=" + action + " pkg=" + pkg + " comp=" + comp);
        return intercept;
    }

    /** Builds the host GoogleLoginActivity intent that replaces GMS's sign-in intent. */
    public static Intent buildLoginActivityIntent(Intent original) {
        String guestPkg = BActivityThread.getAppPackageName();
        SignInOptions options = new SignInOptions();
        try {
            Parcelable cfg = readConfig(original);
            if (cfg != null) {
                Parcel p = SignInOptions.marshal(cfg);
                try {
                    Object[] r = SignInOptions.readSignInConfiguration(p);
                    if (r[0] != null && !guestPkg.equals(r[0])) {
                        Log.w(TAG, "SignInConfiguration package " + r[0] + " != " + guestPkg + ", using guest");
                    }
                    options = (SignInOptions) r[1];
                } finally {
                    p.recycle();
                }
            }
            else {
                Object gso = original.getExtras() != null ? original.getExtras().get("googleSignInOptions") : null;
                if (gso instanceof Parcelable) options = SignInOptions.fromGuestObject((Parcelable) gso);
            }
        } catch (Throwable e) {
            Log.w(TAG, "SignInConfiguration decode failed: " + e);
        }
        Intent i = new Intent();
        i.setComponent(new ComponentName(MundoCore.getHostPkg(), GoogleLoginActivity.class.getName()));
        i.putExtra(GoogleLoginActivity.EXTRA_USER_ID, BActivityThread.getUserId());
        i.putExtra(GoogleLoginActivity.EXTRA_PACKAGE, guestPkg);
        i.putExtra(GoogleLoginActivity.EXTRA_OPTIONS, options.toBundle());
        Log.w(TAG, "sign-in UI for " + guestPkg + " -> GoogleLoginActivity " + options);
        return i;
    }

    /** The extra is either the SignInConfiguration itself or a Bundle wrapping it. */
    private static Parcelable readConfig(Intent original) {
        ClassLoader cl = BActivityThread.getApplication() != null
                ? BActivityThread.getApplication().getClassLoader() : null;
        if (cl != null) original.setExtrasClassLoader(cl);
        Bundle extras = original.getExtras();
        if (extras == null) return null;
        Object v = extras.get(EXTRA_CONFIG);
        if (v == null) v = extras.get("signInConfiguration");
        if (v instanceof Bundle) {
            Bundle b = (Bundle) v;
            if (cl != null) b.setClassLoader(cl);
            Object inner = b.get(EXTRA_CONFIG);
            return inner instanceof Parcelable ? (Parcelable) inner : null;
        }
        return v instanceof Parcelable ? (Parcelable) v : null;
    }
}
