#ifndef VIRTUAL_APP_JNIHOOK_H
#define VIRTUAL_APP_JNIHOOK_H

#include "ArtMethod.h"

#define HOOK_JNI(ret, func, ...) \
  ret (*orig_##func)(__VA_ARGS__); \
  ret new_##func(__VA_ARGS__)

class JniHook {
public:
    static void InitJniHook(JNIEnv *env, int api_level);
    static void HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign, void * new_fun, void ** orig_fun,bool is_static);
    static void HookJniFun(JNIEnv *env, jobject java_method, void *new_fun, void **orig_fun, bool is_static);
    // Hook a pure-Java (non-native) method by forcing kAccNative on its ArtMethod.
    // No orig_fun because the original Java bytecode is permanently replaced.
    static void HookJavaFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign, void *new_fun, bool is_static);
};

#endif //VIRTUAL_APP_JNIHOOK_H
