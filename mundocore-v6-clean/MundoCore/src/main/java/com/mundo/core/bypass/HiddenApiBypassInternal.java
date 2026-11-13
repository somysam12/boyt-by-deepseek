package com.mundo.core.bypass;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Patches ART method access_flags_ via sun.misc.Unsafe (loaded by reflection,
 * not import — avoids compile-time "package sun.misc does not exist" on Android SDK).
 *
 * Algorithm (Mundo x5+w4):
 *   1. Load sun.misc.Unsafe via Class.forName + getDeclaredField("theUnsafe").
 *   2. Compute artMethodOffset = Unsafe.objectFieldOffset(Executable.artMethod).
 *   3. For VMRuntime.getRuntime() and VMRuntime.setHiddenApiExemptions():
 *        a. Read native ArtMethod* via Unsafe.getLong(method, artMethodOffset).
 *        b. OR kAccPublicApi (0x10000000) into access_flags_ at ptr+4.
 *   4. Invoke getRuntime() -> setHiddenApiExemptions(["L"]) to exempt all APIs.
 */
public final class HiddenApiBypassInternal {
    private static final String TAG = "HiddenApiBypassInternal";
    private static final int kAccPublicApi = 0x10000000;

    private static volatile boolean sInstalled = false;

    // Cached Unsafe instance and its methods (loaded once via reflection)
    private static Object  sUnsafe;
    private static Method  sObjectFieldOffset;  // (Field) -> long
    private static Method  sGetLongObj;         // (Object, long) -> long
    private static Method  sGetInt;             // (Object, long) -> int
    private static Method  sPutInt;             // (Object, long, int) -> void

    public static void install() {
        if (sInstalled) return;
        if (Build.VERSION.SDK_INT < 28) { sInstalled = true; return; }
        try {
            if (!initUnsafe()) { Log.w(TAG, "Unsafe init failed"); return; }

            long artMethodOffset = getArtMethodOffset();
            if (artMethodOffset < 0) { Log.w(TAG, "artMethodOffset not found"); return; }

            Class<?> vmRt = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime    = vmRt.getDeclaredMethod("getRuntime");
            Method setExemptions = vmRt.getDeclaredMethod("setHiddenApiExemptions", String[].class);

            unsealMethod(getRuntime,    artMethodOffset);
            unsealMethod(setExemptions, artMethodOffset);

            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});

            sInstalled = true;
            Log.i(TAG, "Installed ok (API " + Build.VERSION.SDK_INT + ")");
        } catch (Throwable t) {
            Log.w(TAG, "install failed: " + t);
        }
    }

    public static boolean isInstalled() { return sInstalled; }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static boolean initUnsafe() {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field f = uc.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sUnsafe             = f.get(null);
            sObjectFieldOffset  = uc.getMethod("objectFieldOffset", Field.class);
            sGetLongObj         = uc.getMethod("getLong", Object.class, long.class);
            sGetInt             = uc.getMethod("getInt",  Object.class, long.class);
            sPutInt             = uc.getMethod("putInt",  Object.class, long.class, int.class);
            return sUnsafe != null;
        } catch (Throwable t) {
            Log.w(TAG, "initUnsafe: " + t);
            return false;
        }
    }

    private static long getArtMethodOffset() {
        // Try real Executable.artMethod first (may be blocked on Android 16 OEM)
        try {
            Field real = java.lang.reflect.Executable.class.getDeclaredField("artMethod");
            return (Long) sObjectFieldOffset.invoke(sUnsafe, real);
        } catch (Throwable ignored) {}
        // Fallback: mirror field in MundoReflectionStub (same ART struct layout)
        try {
            Field mirror = MundoReflectionStub.Executable.class.getDeclaredField("artMethod");
            return (Long) sObjectFieldOffset.invoke(sUnsafe, mirror);
        } catch (Throwable t2) {
            Log.w(TAG, "getArtMethodOffset: " + t2);
            return -1;
        }
    }

    private static void unsealMethod(Method method, long artMethodOffset) {
        try {
            long artMethodPtr = (Long)  sGetLongObj.invoke(sUnsafe, method, artMethodOffset);
            int  flags        = (Integer) sGetInt.invoke(sUnsafe, null, artMethodPtr + 4);
            sPutInt.invoke(sUnsafe, null, artMethodPtr + 4, flags | kAccPublicApi);
        } catch (Throwable t) {
            Log.w(TAG, "unsealMethod(" + method.getName() + "): " + t);
        }
    }
}
