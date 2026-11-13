package com.mundo.core.bypass;

/**
 * Mirror ART internal C++ struct layouts in Java so that sun.misc.Unsafe can
 * compute correct field offsets into native ART objects.
 *
 * Field ORDER and TYPE must match the ART C++ layout exactly.
 * These are never instantiated — they exist solely for Unsafe.objectFieldOffset().
 *
 * Matches Mundo AAR's MundoReflectionStub$* classes (x5/w4 bypass).
 */
public final class MundoReflectionStub {
    private MundoReflectionStub() {}

    /**
     * Mirror of java.lang.reflect.Executable's native peer.
     * The artMethod field holds a pointer to the native ArtMethod struct.
     */
    public static class Executable {
        public long artMethod;
    }

    /**
     * Mirror of java.lang.invoke.MethodHandle's native peer.
     */
    public static class MethodHandle {
        public long artFieldOrMethod;
    }

    /**
     * Two adjacent methods — stride = |b_artMethodPtr - a_artMethodPtr|.
     * Both methods must be declared HERE, adjacent in the dex file, so ART
     * lays out their native ArtMethod entries consecutively in memory.
     */
    public static class NeverCall {
        public void a() {}
        public void b() {}
    }

    /**
     * Partial mirror of ART mirror::Class layout (arm64, API 28+).
     * Only the fields needed to compute offsets are listed.
     */
    public static class Class {
        public int classFlags;
        public int padding0;
        public long classLoader;
        public long componentType;
        public long dexCache;
        public long extData;
        public long iftable;
        public long name;
        public long superClass;
        public long vtable;
        public long ifields;
        public long methods;
        public long sfields;
        public int accessFlags;
    }
}
