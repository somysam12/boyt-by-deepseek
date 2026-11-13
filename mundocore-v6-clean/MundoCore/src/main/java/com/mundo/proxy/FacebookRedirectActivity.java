package com.mundo.proxy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import com.mundo.MundoCore;
import com.mundo.utils.FileUtils;

/**
 * Real (host-side) activity that receives the Facebook Custom Tab OAuth
 * redirect  ( fbconnect://cct.<APP_ID>/... )  from the system browser and
 * forwards it back INTO the virtual space so that the guest app's
 * com.facebook.CustomTabActivity can complete the login and read the token.
 *
 * Registered in the manifest with:
 *   <intent-filter>
 *     <action  android:name="android.intent.action.VIEW"/>
 *     <category android:name="android.intent.category.DEFAULT"/>
 *     <category android:name="android.intent.category.BROWSABLE"/>
 *     <data     android:scheme="fbconnect"/>
 *   </intent-filter>
 */
public class FacebookRedirectActivity extends Activity {

    public static final String TAG = "FacebookRedirectActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleRedirect(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleRedirect(intent);
        finish();
    }

    private void handleRedirect(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri redirect = intent.getData();

        // Resolve which guest app + user this redirect belongs to.
        String pkg = null;
        int userId = 0;

        String[] target = FacebookLoginHelper.readLoginTarget();
        if (target != null) {
            pkg = target[0];
            try {
                userId = Integer.parseInt(target[1]);
            } catch (NumberFormatException ignored) { }
        }

        // Build the intent that the guest CustomTabActivity expects.
        Intent guestIntent = new Intent(Intent.ACTION_VIEW, redirect);
        guestIntent.addCategory(Intent.CATEGORY_DEFAULT);
        guestIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        // NOTE: deliberately NO FLAG_ACTIVITY_CLEAR_TOP here.
        // ActivityStack.startActivityLocked() marks every activity above the
        // target as finished when CLEAR_TOP is set, which would destroy the
        // running game activity and force a restart. Without it the stack
        // resolves the guest task by taskAffinity and calls moveTaskToFront(),
        // so the game resumes exactly where the user left it.
        guestIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (pkg != null) {
            guestIntent.setPackage(pkg);
        }

        try {
            // Prefer the explicit FB CustomTabActivity if it resolves.
            ResolveInfo info = MundoCore.getBPackageManager().resolveActivity(
                    guestIntent, FileUtils.FileMode.MODE_IWUSR, null, userId);

            if (info == null && pkg != null) {
                // Fallback: target the well-known FB CustomTabActivity directly.
                guestIntent.setComponent(new ComponentName(
                        pkg, FacebookLoginHelper.FB_CCT_ACTIVITY));
            } else if (info != null) {
                guestIntent.setComponent(new ComponentName(
                        info.activityInfo.packageName, info.activityInfo.name));
            }

            MundoCore.getBActivityManager().startActivity(guestIntent, userId);
        } catch (Exception ignored) { }
    }
}
