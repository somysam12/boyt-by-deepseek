package com.mundo.proxy;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.mundo.MundoCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Facebook Login helper for the virtual space.
 *
 * The FB SDK (>= 8.2.0) performs login through a Chrome Custom Tab. After the
 * user authenticates, Facebook redirects to  fbconnect://cct.<APP_ID>  (see
 * com.facebook.CustomTabActivity intent-filter). Inside a virtual space the
 * guest app is NOT a real installed package, so the host system cannot route
 * that redirect back to it and the login silently fails on a blank tab.
 *
 * We solve this by:
 *   1. Recording which guest (package + userId) started an FB login when the
 *      guest launches the oauth Custom Tab  (written from the GUEST process).
 *   2. Catching the  fbconnect://  redirect in a real host activity
 *      (FacebookRedirectActivity) and forwarding it back into the virtual
 *      space to the guest's com.facebook.CustomTabActivity
 *      (read from the HOST process).
 *
 * Because the guest process and the host process are the same host UID, they
 * share the host filesDir, so a tiny file is used to pass the target across
 * processes.
 */
public class FacebookLoginHelper {

    public static final String TAG = "FacebookLoginHelper";

    // FB custom-tab redirect scheme (deterministic, host = cct.<APP_ID>)
    public static final String FB_CCT_SCHEME = "fbconnect";
    // FB CustomTabActivity that must receive the redirect inside the guest
    public static final String FB_CCT_ACTIVITY = "com.facebook.CustomTabActivity";

    private static final String TARGET_FILE = "fb_login_target";

    private FacebookLoginHelper() { }

    private static File targetFile() {
        return new File(MundoCore.getContext().getFilesDir(), TARGET_FILE);
    }

    /** Called from the GUEST process when an FB oauth Custom Tab is launched. */
    public static void recordLoginTarget(String packageName, int userId) {
        try (FileOutputStream fos = new FileOutputStream(targetFile())) {
            fos.write((packageName + "|" + userId).getBytes());
            fos.flush();
        } catch (Exception ignored) { }
    }

    /** Returns {packageName, userIdString} or null. Read from HOST process. */
    public static String[] readLoginTarget() {
        File f = targetFile();
        if (!f.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            String data = new String(buf).trim();
            int sep = data.lastIndexOf('|');
            if (sep <= 0) return null;
            return new String[]{ data.substring(0, sep), data.substring(sep + 1) };
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Detects whether an outgoing intent is a Facebook OAuth Custom Tab / dialog
     * that we want to record before it reaches the real browser.
     */
    public static boolean isFacebookOAuthIntent(Intent intent) {
        if (intent == null) return false;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        Uri data = intent.getData();
        if (data == null) return false;
        String host = data.getHost();
        String scheme = data.getScheme();
        if (host == null || scheme == null) return false;
        boolean isHttp = scheme.equals("http") || scheme.equals("https");
        boolean isFbHost = host.endsWith("facebook.com") || host.endsWith("fb.gg")
                || host.endsWith("m.facebook.com") || host.endsWith("free.facebook.com");
        if (!isHttp || !isFbHost) return false;
        String full = data.toString();
        // FB login/oauth dialog OR a redirect_uri pointing back to fbconnect
        return full.contains("/dialog/oauth")
                || full.contains("oauth")
                || full.contains("fbconnect")
                || full.contains("login");
    }

    /** Returns just the stored guest package name, or null if none recorded. */
    public static String getLoginTargetPackage() {
        String[] target = readLoginTarget();
        return target != null ? target[0] : null;
    }

    /** True if this is the fbconnect:// redirect coming back from the browser. */
    public static boolean isFacebookRedirect(Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        return data != null && FB_CCT_SCHEME.equals(data.getScheme());
    }

    /** Returns the best Custom-Tab-capable browser installed, or null. Chrome preferred. */
    public static String getBestCustomTabBrowser(Context context) {
        if (context == null) return null;
        android.content.pm.PackageManager pm = context.getPackageManager();
        String[] browsers = {
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
            "com.chrome.canary",
            "com.sec.android.app.sbrowser",   // Samsung Internet
            "com.mi.globalbrowser",            // Xiaomi
            "com.miui.browser",                // MIUI browser
            "com.huawei.browser",              // Huawei browser
            "com.coloros.browser",             // ColorOS (OnePlus/OPPO)
            "com.iqoo.browser",                // iQOO / Vivo
            "org.mozilla.firefox",
            "com.opera.browser", "com.opera.mini.native"
        };
        for (String pkg : browsers) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (Exception ignored) { }
        }
        // Fallback: ask the system for any app that provides Custom Tabs service
        try {
            android.content.Intent ctsIntent = new android.content.Intent(
                    "android.support.customtabs.action.CustomTabsService");
            java.util.List<android.content.pm.ResolveInfo> services =
                    pm.queryIntentServices(ctsIntent, 0);
            if (services != null && !services.isEmpty()) {
                return services.get(0).serviceInfo.packageName;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
