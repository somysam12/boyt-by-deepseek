#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include "JniHook/JniHook.h"
#include "Hook/VMClassLoaderHook.h"
#include "Hook/UnixFileSystemHook.h"
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include "Hook/RuntimeHook.h"
#include "SandHook/oxorany.h"
#include "SandHook/xdl.h"

// Native hidden API bypass — mirrors ZBOX libZBOXCORE.so disable_hidden_api().
// Uses xDL (handles APEX paths: /apex/com.android.art/lib64/libart.so on Android 12+)
// to resolve art::VMRuntime_setHiddenApiExemptions directly, then exempts all "L"-prefixed
// descriptors. Must run in JNI_OnLoad before any JniHook or hidden API access.
static void disable_hidden_api_native(JNIEnv *env) {
    typedef void (*SetHiddenApiExemptionsFn)(JNIEnv *, jclass, jobjectArray);

    void *handle = xdl_open("libart.so", XDL_DEFAULT);
    if (!handle) {
        ALOGD("disable_hidden_api: xdl_open(libart.so) failed");
        return;
    }

    auto fn = (SetHiddenApiExemptionsFn) xdl_sym(
        handle,
        "_ZN3artL32VMRuntime_setHiddenApiExemptionsEP7_JNIEnvP7_jclassP13_jobjectArray",
        nullptr);

    if (fn) {
        jclass stringClass = env->FindClass("java/lang/String");
        jstring exemption  = env->NewStringUTF("L");
        jobjectArray arr   = env->NewObjectArray(1, stringClass, exemption);
        fn(env, nullptr, arr);
        env->DeleteLocalRef(arr);
        env->DeleteLocalRef(exemption);
        env->DeleteLocalRef(stringClass);
        ALOGD("disable_hidden_api: set disableHiddenApi success");
    } else {
        ALOGD("disable_hidden_api: HiddenAPI: Didn't find setHiddenApiExemptions");
    }

    xdl_close(handle);
}

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    int api_level;
} VMEnv;


JNIEnv *getEnv() {
    JNIEnv *env;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        VMEnv.vm->AttachCurrentThread(&env, NULL);
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    env = ensureEnvCreated();
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    VMClassLoaderHook::init(env);
    BinderHook::init(env);
    DexFileHook::init(env);
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

// Suppress PendingIntent FLAG_IMMUTABLE enforcement for guest apps running in
// this virtual container. Firebase IID (and some older SDK code) creates
// PendingIntents without FLAG_IMMUTABLE, which throws IllegalArgumentException
// on Android 12+ when the calling app's targetSdkVersion >= 31.
static JNICALL void pendingIntentCheckFlags_noop(JNIEnv *, jclass, jint, jstring) {}

static void patchPendingIntentCheckFlags(JNIEnv *env) {
    JniHook::HookJavaFun(env, "android/app/PendingIntent", "checkFlags",
                         "(ILjava/lang/String;)V",
                         (void *) pendingIntentCheckFlags_noop, true);
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.getCallingUidId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath","(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath","(Ljava/io/File;)Ljava/io/File;");
    JniHook::InitJniHook(env, api_level);
    if (api_level >= __ANDROID_API_S__) {
        patchPendingIntentCheckFlags(env);
    }
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,jstring relocate_path) {
    ALOGD("set addIORule");
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void enableIO(JNIEnv *env, jclass clazz) {
    ALOGD("set enableIO");
    IO::init(env);
    nativeHook(env);
}

static JNINativeMethod gMethods[] = {
        {"hideXposed", "()V",(void *) hideXposed},
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",   "()V",(void *) enableIO},
        {"init","(I)V",(void *) init},
};

int registerNativeMethods(JNIEnv *env, const char *className,JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    // Native hidden API bypass — MUST run first, before any hidden API access.
    // This is equivalent to ZBOX's libZBOXCORE.so disable_hidden_api().
    disable_hidden_api_native(env);
    registerMethod(env);
    return JNI_VERSION_1_6;
}