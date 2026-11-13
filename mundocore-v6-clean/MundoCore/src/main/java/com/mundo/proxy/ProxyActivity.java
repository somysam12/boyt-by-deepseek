package com.mundo.proxy;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import com.mundo.app.BActivityThread;
import com.mundo.fake.hook.HookManager;
import com.mundo.fake.service.HCallbackStub;
import com.mundo.proxy.record.ProxyActivityRecord;
import com.mundo.utils.Slog;

/**
 * ProxyActivity
 * Fixed & Hardened for Android 10–15
 */
public class ProxyActivity extends Activity {

    public static final String TAG = "ProxyActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        finish();
        HookManager.get().checkEnv(HCallbackStub.class);
        ProxyActivityRecord record = ProxyActivityRecord.create(getIntent());
        if (record.mTarget != null) {
            Application app = BActivityThread.getApplication();
            ClassLoader cl = (app != null) ? app.getClassLoader() : getClassLoader();
            record.mTarget.setExtrasClassLoader(cl);
            startActivity(record.mTarget);
            return;
        }
    }
    
    public static class P0 extends ProxyActivity {}
    public static class P1 extends ProxyActivity {}
    public static class P2 extends ProxyActivity {}
    public static class P3 extends ProxyActivity {}
    public static class P4 extends ProxyActivity {}
    public static class P5 extends ProxyActivity {}
    public static class P6 extends ProxyActivity {}
    public static class P7 extends ProxyActivity {}
    public static class P8 extends ProxyActivity {}
    public static class P9 extends ProxyActivity {}
    public static class P10 extends ProxyActivity {}
    public static class P11 extends ProxyActivity {}
    public static class P12 extends ProxyActivity {}
    public static class P13 extends ProxyActivity {}
    public static class P14 extends ProxyActivity {}
    public static class P15 extends ProxyActivity {}
    public static class P16 extends ProxyActivity {}
    public static class P17 extends ProxyActivity {}
    public static class P18 extends ProxyActivity {}
    public static class P19 extends ProxyActivity {}
    public static class P20 extends ProxyActivity {}
    public static class P21 extends ProxyActivity {}
    public static class P22 extends ProxyActivity {}
    public static class P23 extends ProxyActivity {}
    public static class P24 extends ProxyActivity {}
}