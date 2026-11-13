############################################
# ========== FAST STARTUP OPTIMIZATION ==========
############################################

# 1. OPTIMIZATION SETTINGS (ADJUSTED FOR STABILITY)
# Disabled aggressive optimizations to prevent ClassNotFoundException/VerifyError
# -optimizationpasses 5
# -overloadaggressively
# -allowaccessmodification

# 2. Inline MundoCore ke small methods
-assumenosideeffects class com.mundo.MundoCore {
    private void initNotificationManager();
    private static java.lang.String getProcessName(android.content.Context);
    public static boolean is64Bit();
    private void startLogcat();
}

# 3. Inline nk class ke quick methods
-assumenosideeffects class android.MetaCore.nk {
    public static boolean getActivatedSdk();
    public static java.lang.String getServerMessage();
    public static boolean GAH();
    public static void ismsg(java.lang.String);
}

# 4. Remove debug/trace calls
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-assumenosideeffects class com.mundo.utils.Slog {
    public static void d(...);
    public static void i(...);
    public static void v(...);
}

# 5. Optimize string operations
-assumenosideeffects class java.lang.String {
    public java.lang.String toLowerCase();
    public java.lang.String toUpperCase();
    public boolean contains(java.lang.CharSequence);
    public boolean endsWith(java.lang.String);
}

############################################
# ========== JNI/NATIVE CODE PROTECTION ==========
############################################

# JniHook class ke sabhi fields preserve karo
-keep class com.mundo.jnihook.jni.JniHook {
    *;
}

# Native offset field specifically preserve karo
-keepclassmembers class com.mundo.jnihook.jni.JniHook {
    public static int NATIVE_OFFSET;
    public static int NATIVE_HANDLE;
    public static int NATIVE_PTR;
}

# VNative class preserve karo
-keep class com.mundo.core.VNative {
    *;
}

# Sab native methods preserve karo
-keepclasseswithmembernames class * {
    native <methods>;
}

############################################
# ========== TYPE INFORMATION PRESERVATION ==========
############################################
-keepattributes Signature, *Annotation*, Exceptions, InnerClasses

############################################
# ========== ELITE INSTALLER CORE ==========
############################################

# Main MundoCore class - NO OBFUSCATION
-keep class com.mundo.MundoCore {
    *;
}

# Keep ALL methods with original signatures
-keepclassmembers class com.mundo.MundoCore {
    public <init>(...);
    public static com.mundo.MundoCore get();
    public android.os.Handler getHandler();
    public static android.content.pm.PackageManager getPackageManager();
    public static java.lang.String getHostPkg();
    public static int getHostUid();
    public static int getHostUserId();
    public static android.content.Context getContext();
    public com.mundo.entity.AppConfig getAppConfig();
    public java.lang.Thread$UncaughtExceptionHandler getExceptionHandler();
    public void setExceptionHandler(java.lang.Thread$UncaughtExceptionHandler);
    
    # RemoteManager settings
    public static void setHideRoot(boolean);
    public static void setHideXposed(boolean);
    public static void setEnableDaemonService(boolean);
    
    # Context methods
    public void doAttachBaseContext(android.content.Context, com.mundo.app.configuration.ClientConfiguration);
    public void doCreate();
    
    # Thread methods
    public static java.lang.Object mainThread();
    
    # Activity methods
    public void startActivity(android.content.Intent, int);
    public void onBeforeMainLaunchApk(java.lang.String, int);
    
    # Manager getters
    public static com.mundo.fake.frameworks.BJobManager getBJobManager();
    public static com.mundo.fake.frameworks.BPackageManager getBPackageManager();
    public static com.mundo.fake.frameworks.BActivityManager getBActivityManager();
    public static com.mundo.fake.frameworks.BStorageManager getBStorageManager();
    
    # Package installation methods
    public boolean launchApk(java.lang.String, int);
    public boolean isInstalled(java.lang.String, int);
    public void uninstallPackageAsUser(java.lang.String, int);
    public void uninstallPackage(java.lang.String);
    public com.mundo.entity.pm.InstallResult installPackageAsUser(java.lang.String, int);
    public com.mundo.entity.pm.InstallResult installPackageAsUser(java.io.File, int);
    public com.mundo.entity.pm.InstallResult installPackageAsUser(android.net.Uri, int);
    
    # Xposed methods
    public com.mundo.entity.pm.InstallResult installXPModule(java.io.File);
    public com.mundo.entity.pm.InstallResult installXPModule(android.net.Uri);
    public com.mundo.entity.pm.InstallResult installXPModule(java.lang.String);
    public void uninstallXPModule(java.lang.String);
    public boolean isXPEnable();
    public void setXPEnable(boolean);
    public boolean isXposedModule(java.io.File);
    public boolean isInstalledXposedModule(java.lang.String);
    public boolean isModuleEnable(java.lang.String);
    public void setModuleEnable(java.lang.String, boolean);
    public java.util.List getInstalledXPModules();
    
    # Application methods
    public java.util.List getInstalledApplications(int, int);
    public java.util.List getInstalledPackages(int, int);
    public void clearPackage(java.lang.String, int);
    public void stopPackage(java.lang.String, int);
    public boolean isAppRunning(java.lang.String, int);
    public android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String);
    
    # User management
    public java.util.List getUsers();
    public com.mundo.core.system.user.BUserInfo createUser(int);
    public void deleteUser(int);
    
    # Lifecycle callbacks
    public java.util.List getAppLifecycleCallbacks();
    public void removeAppLifecycleCallback(com.mundo.app.configuration.AppLifecycleCallback);
    public void addAppLifecycleCallback(com.mundo.app.configuration.AppLifecycleCallback);
    
    # GMS methods
    public boolean isSupportGms();
    public boolean isInstallGms(int);
    public com.mundo.entity.pm.InstallResult installGms(int);
    public boolean uninstallGms(int);
    
    # Service methods
    public android.os.IBinder getService(java.lang.String);
    
    # Process type methods
    public boolean isBlackProcess();
    public boolean isMainProcess();
    public boolean isServerProcess();
    
    # Configuration methods
    public boolean setHideRoot();
    public java.lang.String getHostPackageName();
    public boolean requestInstallPackage(java.io.File);
    
    # Permission methods
    public boolean checkSelfPermission(java.lang.String);
}

# Keep ALL fields (public and private)
-keepclassmembers class com.mundo.MundoCore {
    *** TAG;
    *** sMundoCore;
    *** sContext;
    *** mProcessType;
    *** mServices;
    *** mExceptionHandler;
    *** mClientConfiguration;
    *** mAppLifecycleCallbacks;
    *** mHandler;
    *** mHostUid;
    *** mHostUserId;
    *** appConfig;
}

# Keep ProcessType enum
-keep class com.mundo.MundoCore$ProcessType {
    *;
}

# Keep ALL inner classes
-keep class com.mundo.MundoCore$* {
    *;
}

############################################
# ========== REMOTE MANAGER CORE ==========
############################################

# Main RemoteManager class - NO OBFUSCATION
-keep class android.MetaCore.RemoteManager {
    *;
}

# Keep ALL methods with original signatures
-keepclassmembers class android.MetaCore.RemoteManager {
    public <init>(...);
    public static android.MetaCore.RemoteManager getInstance();
    
    # SDK Activation methods
    public void activateSdk(java.lang.String);
    public boolean getActivatedSdk();
    public java.lang.String getServerMessage();
    public boolean getNetwork();
    
    # Static fields
    public static boolean sEnableDaemonService;
    public static boolean sHideRoot;
    public static boolean sHideXposed;
    public static java.io.File JUNIT_JAR;
    public static java.io.File EMPTY_JAR;
    
    # IRemoteManager.Stub implementation
    public android.os.IBinder asBinder();
    
    # Notification methods
    private void showNotificationSafe(java.lang.String, java.lang.String);
    private void showNotification(android.content.Context, java.lang.String, java.lang.String);
    private void showServerNotification(java.lang.String, java.lang.String, java.lang.String);
    private void showImageNotification(java.lang.String, java.lang.String, java.lang.String, java.lang.String);
    
    # Helper methods
    private boolean iv(java.lang.String);
    private java.lang.String deviceId();
    private java.lang.String getAppName(android.content.Context, java.lang.String);
    private void isDaemon(boolean);
    private void ishideRoot(boolean);
}

# Keep ALL fields (public and private)
-keepclassmembers class android.MetaCore.RemoteManager {
    *** TAG;
    *** instance;
    *** exe;
    *** CHANNEL_ID;
    *** CHANNEL_NAME;
}

# Keep Companion object
-keep class android.MetaCore.RemoteManager$Companion {
    *;
}

# Keep ALL inner classes
-keep class android.MetaCore.RemoteManager$* {
    *;
}

# Keep nk class reference
-keep class android.MetaCore.nk {
    *;
}

-keepclassmembers class android.MetaCore.nk {
    public static java.lang.String Msg;
    public static java.lang.String PREFERENCE_NAME;
    public static void setHidden(java.lang.String);
    public static boolean getActivatedSdk();
    public static java.lang.String getServerMessage();
    public static boolean isSystemApp();
}

############################################
# ========== INTERFACE KEEPING ==========
############################################

# Keep IRemoteManager interface
-keep interface android.Meta.IRemoteManager {
    *;
}

-keep class android.Meta.IRemoteManager$Stub {
    *;
}

-keepclassmembers class * implements android.Meta.IRemoteManager {
    public void activateSdk(java.lang.String);
    public boolean getActivatedSdk();
    public java.lang.String getServerMessage();
    public boolean getNetwork();
}

############################################
# ========== SIMPLE PROXY RULES ==========
############################################
# ========== REFINED PROXY RULES ==========
# 2. Keep Proxy Classes (The classes themselves must exist)
# NOTE: We do NOT use " { *; } " here, so unlisted members can be obfuscated.
-keep class com.mundo.fake.service.*Proxy
-keep class com.mundo.fake.service.*Proxy$*

# 3. Keep Critical Methods in Main Proxy Classes (Overrides from ClassInvocationStub)
-keepclassmembers class com.mundo.fake.service.*Proxy {
    public <init>(...);
    protected java.lang.Object getWho();
    protected void inject(java.lang.Object, java.lang.Object);
    public boolean isBadEnv();
    protected void onBindMethod();
}

# 4. Keep Hook Methods in Inner Proxy Classes (Overrides from MethodHook)
# This ensures 'hook', 'beforeHook', etc. are kept, but helper methods like 'getAuthIndex' are obfuscated.
-keepclassmembers class com.mundo.fake.service.*Proxy$* {
    protected java.lang.Object hook(java.lang.Object, java.lang.reflect.Method, java.lang.Object[]);
    protected java.lang.Object beforeHook(java.lang.Object, java.lang.reflect.Method, java.lang.Object[]);
    protected java.lang.Object afterHook(java.lang.Object);
    protected boolean isEnable();
}

-keepclassmembers class com.mundo.proxy.ProxyManifest {
    public static boolean isProxy(java.lang.String);
    public static java.lang.String getProxyAuthorities(int);
    public static java.lang.String getProxyPendingActivity(int);
}

############################################
# BLACK / STUB / MIRROR / REFLECT PACKAGES
############################################

-keep class black.** { *; }
-keep class mirror.** { *; }
-keep class reflection.** { *; }

-keep class android.app.** { *; }
-keep class android.content.** { *; }
-keep class android.location.** { *; }
-keep class android.os.** { *; }
-keep class net_62v.external.** { *; }
-keep class com.mundo.core.system.** { *; }
-keep class com.mundo.fake.delegate.** { *; }
-keep class com.mundo.fake.frameworks.** { *; }
-keep class com.android.** { *; }
-keep class android.Meta.** { *; }

-keep class top.niunaijun.blackreflection.** {*; }
-keep @top.niunaijun.blackreflection.annotation.BClass class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassName class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassNameNotProcess class * {*;}
-keepclasseswithmembernames class * {
    @top.niunaijun.blackreflection.annotation.BField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldSetNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethodCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructor.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructorNotProcess.* <methods>;
}

############################################
# ========== HIDDEN API BYPASS (Mundo x5/w4) ==========
############################################
# MundoReflectionStub field names and NeverCall method names MUST NOT be renamed.
# They are looked up at runtime via getDeclaredField/getDeclaredMethod for Unsafe offsets.
-keep class com.mundo.core.bypass.MundoReflectionStub { *; }
-keep class com.mundo.core.bypass.MundoReflectionStub$* { *; }
-keep class com.mundo.core.bypass.HiddenApiBypassInternal { *; }
-keepclassmembers class com.mundo.core.bypass.MundoReflectionStub$NeverCall {
    public void a();
    public void b();
}

############################################
# ========== OTHER ESSENTIAL CLASSES ==========
############################################
-keep class android.MetaCore.AdvancedPopupHelper { *; }
-keep class com.mundo.core.env.BEnvironment { *; }
-keep class com.mundo.utils.FileUtils { *; }
-keep class com.mundo.core.HostApp { *; }
-keep class com.mundo.app.LauncherActivity { *; }
-keep class com.mundo.app.configuration.AppLifecycleCallback { *; }
-keep class com.mundo.app.configuration.ClientConfiguration { *; }
-keep class com.mundo.core.system.api.MetaActivationManager { *; }

# === FIX: KEEP ALL PROXY COMPONENTS REFERENCED IN MANIFEST ===
-keep class com.mundo.proxy.** { *; }


# === FIX: KEEP METACORE SERVICES ===
-keep class android.MetaCore.Service.** { *; }

# === FIX: KEEP INSTALL ENTITIES (Only InstallResult as requested, others obfuscated) ===
-keep class com.mundo.entity.pm.InstallResult { *; }

############################################
# ========== REPACKAGING RULES ==========
############################################

# Sab classes ko top.bienvenido.date_24323 mein bhejo (EXCEPT elite classes)
-repackageclasses 'top.bienvenido.date_24323'
-allowaccessmodification
# Elite classes ko original package mein rahne do


############################################
# ========== BASIC SETTINGS ==========
############################################

# Keep debug info
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Basic optimization
-optimizations !code/simplification/arithmetic
-optimizationpasses 1

# Don't warn
-dontwarn **

# IMPORTANT ADDITION: NO SHRINKING OR OPTIMIZATION THAT REMOVES TYPE INFO
-dontshrink
-dontoptimize

# KEEP ATTRIBUTES FOR GENERIC TYPES (ClassCastException fix)
-keepattributes Signature, *Annotation*, Exceptions, InnerClasses, EnclosingMethod
# --- Google Sign-In (microG subset) ---
# WebView JS bridge "mm" methods are called by name from accounts.google.com
-keepclassmembers class com.mundo.proxy.gms.GoogleWebLoginActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.mundo.proxy.gms.GoogleWebLoginActivity$JsBridge

# --- GMS intercept classes — explicit keep for release build ---
# Binder subclasses: Android JNI calls onTransact() via native — R8 can't see this reference
-keep class com.mundo.core.gms.**           { *; }
-keep class com.mundo.proxy.gms.**          { *; }
-keep class com.mundo.fake.delegate.ServiceConnectionDelegate { *; }
-dontwarn com.mundo.**
