package com.mundo.fake.service.context.providers;

import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.util.Log;

import com.mundo.MundoCore;
import com.mundo.utils.compat.ContextCompat;
import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import com.mundo.app.BActivityThread;
import com.mundo.fake.hook.ClassInvocationStub;

/**
 * HARD FINAL FIX
 * Android 10 → 16
 * Solves AttributionSource.enforceCallingUid crash
 */
public class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String TAG = "ContentProviderStub";
    private IInterface mBase;
    private String mAppPkg;

    public IInterface wrapper(final IInterface contentProviderProxy, final String appPkg) {
        mBase = contentProviderProxy;
        mAppPkg = appPkg;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        if (args != null && args.length > 0) {
            Object arg = args[0];
            if (arg instanceof String) {
                args[0] = mAppPkg;
            } else if (arg != null && arg.getClass().getName().equals(BRAttributionSource.getRealClass().getName())) {
                ContextCompat.fixAttributionSourceState(arg, MundoCore.getHostUid());
            }
        }
        try {
            return method.invoke(mBase, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) {
                // Android 16: package-UID mismatch in ContentProvider — return null so caller uses default
                return null;
            }
            throw e;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
