package com.mundo.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import android.MetaCore.RemoteManager;
import android.webkit.WebView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import black.android.app.ActivityThreadAppBindDataContext;
import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadAppBindData;
import black.android.app.BRActivityThreadNMR1;
import black.android.app.BRActivityThreadQ;
import black.android.app.BRContextImpl;
import black.android.app.BRLoadedApk;
import black.android.app.BRService;
import black.android.content.BRBroadcastReceiver;
import black.android.content.BRContentProviderClient;
import black.android.graphics.BRCompatibility;
import black.android.security.net.config.BRNetworkSecurityConfigProvider;
import black.com.android.internal.content.BRReferrerIntent;
import black.dalvik.system.BRVMRuntime;

import com.mundo.MundoCore;
import com.mundo.app.configuration.AppLifecycleCallback;
import com.mundo.app.dispatcher.AppServiceDispatcher;
import com.mundo.core.CrashHandler;
import com.mundo.core.IBActivityThread;
import com.mundo.core.VCore;
import com.mundo.core.VNative;
import com.mundo.core.env.VirtualRuntime;
import com.mundo.core.system.user.BUserHandle;
import com.mundo.entity.AppConfig;
import com.mundo.entity.am.ReceiverData;
import com.mundo.entity.pm.InstalledModule;
import com.mundo.fake.delegate.AppInstrumentation;
import com.mundo.fake.delegate.ContentProviderDelegate;
import com.mundo.fake.frameworks.BXposedManager;
import com.mundo.fake.hook.HookManager;
import com.mundo.fake.service.HCallbackStub;
import com.mundo.utils.Reflector;
import com.mundo.utils.Slog;
import com.mundo.utils.compat.ActivityManagerCompat;
import com.mundo.utils.compat.BuildCompat;
import com.mundo.utils.compat.ContextCompat;
import com.mundo.utils.compat.StrictModeCompat;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";
    private static final Object mConfigLock = new Object();
    private static volatile BActivityThread sBActivityThread;
    private AppConfig mAppConfig;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = MundoCore.get().getHandler();

    public static class AppBindData {
        ApplicationInfo appInfo;
        Object info;
        String processName;
        List<ProviderInfo> providers;
    }

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) return getAppConfig().processName;
        if (currentActivityThread().mBoundApplication != null) return currentActivityThread().mBoundApplication.processName;
        return null;
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) return getAppConfig().packageName;
        if (currentActivityThread().mInitialApplication != null) return currentActivityThread().mInitialApplication.getPackageName();
        return null;
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(MundoCore.getHostUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? MundoCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (this.mAppConfig != null && !this.mAppConfig.packageName.equals(appConfig.packageName)) {
                throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
            }
            this.mAppConfig = appConfig;
            final IBinder iBinder = asBinder();
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            synchronized (BActivityThread.mConfigLock) {
                                try {
                                    iBinder.linkToDeath(this, 0);
                                } catch (RemoteException e) {
                                    // ignore
                                }
                                BActivityThread.this.mAppConfig = null;
                            }
                        }
                    }, 0);

            } catch (RemoteException ignored) { }
        }
    }

    public boolean isInit() {
        return this.mBoundApplication != null;
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!isInit()) bindApplication(serviceInfo.packageName, serviceInfo.processName);
        try {
            Service service = (Service) BRLoadedApk.get(this.mBoundApplication.info).getClassLoader().loadClass(serviceInfo.name).newInstance();
            Context context = MundoCore.getContext().createPackageContext(serviceInfo.packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(context, MundoCore.mainThread(), serviceInfo.name,token, this.mInitialApplication, BRActivityManagerNative.get().getDefault());
            ContextCompat.fix(context);
            service.onCreate();
            return service;
        } catch (Exception e) {
            throw new RuntimeException("Unable to create service " + serviceInfo.name, e);
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!isInit()) bindApplication(serviceInfo.packageName, serviceInfo.processName);
        try {
            JobService service = (JobService) BRLoadedApk.get(this.mBoundApplication.info).getClassLoader().loadClass(serviceInfo.name).newInstance();
            Context context = MundoCore.getContext().createPackageContext(serviceInfo.packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(context, MundoCore.mainThread(), serviceInfo.name,getActivityThread(), this.mInitialApplication, BRActivityManagerNative.get().getDefault());
            ContextCompat.fix(context);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            throw new RuntimeException("Unable to create JobService " + serviceInfo.name, e);
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            MundoCore.get().getHandler().post(() -> {
                handleBindApplication(packageName, processName);
                conditionVariable.open();
            });
            conditionVariable.block();
        } else {
            handleBindApplication(packageName, processName);
        }
    }

    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }
        Binder.clearCallingIdentity();
        PackageInfo packageInfo = MundoCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        // Clamp targetSdkVersion to R (30) so guest apps running in this virtual space
        // don't trigger PendingIntent FLAG_IMMUTABLE enforcement (Android 12+, API 31+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && applicationInfo.targetSdkVersion >= Build.VERSION_CODES.S) {
            applicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        }
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));
        Object boundApplication = BRActivityThread.get(MundoCore.mainThread()).mBoundApplication();
        Context packageContext = createPackageContext(applicationInfo);
        Object loadedApk = BRContextImpl.get(packageContext).mPackageInfo();
        if (loadedApk != null) {
            BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
            BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);
        }
        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        VirtualRuntime.setupRuntime(processName, applicationInfo);
        try {
            BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        } catch (Throwable ignored) { }
        if (BuildCompat.isS()) {
            BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);
        }
        VNative.init(Build.VERSION.SDK_INT);
        // JniHook is now initialized — re-apply the targetSdkVersion clamp to loadedApk.
        // The earlier call at line ~267 silently failed because BlackReflection (JniHook)
        // was not yet registered at that point. This ensures checkFlags() reads SDK < 31.
        if (loadedApk != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);
        }
        assert packageContext != null;
        VCore.get().enableRedirect(packageContext);
        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;
        if (boundApplication != null) {
            ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
            activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
            activityThreadAppBindData._set_appInfo(bindData.appInfo);
            activityThreadAppBindData._set_info(bindData.info);
            activityThreadAppBindData._set_processName(bindData.processName);
            activityThreadAppBindData._set_providers(bindData.providers);
        }
        mBoundApplication = bindData;
        //ssl适配
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Application application;
        try {
            onBeforeCreateApplication(packageName, processName, packageContext);
            application = makeApplicationCompat(loadedApk);
            ContextCompat.fix(application);
            ContextCompat.fix((Context) BRActivityThread.get(MundoCore.mainThread()).getSystemContext());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "com.tencent.mm:recovery".equals(processName)) {
                fixWeChatRecovery(mInitialApplication);
            }
            mInitialApplication = application;
            BRActivityThread.get(MundoCore.mainThread())._set_mInitialApplication(mInitialApplication);
            List<ProviderInfo> providers;
            installProviders(mInitialApplication, bindData.processName, bindData.providers);
            try {
				fixAiLiaoPhoto(mInitialApplication);
			} catch (Throwable ignored) { }
            onBeforeApplicationOnCreate(packageName, processName, application);
            AppInstrumentation.get().callApplicationOnCreate(application);
            onAfterApplicationOnCreate(packageName, processName, application);
            HookManager.get().checkEnv(HCallbackStub.class);
        } catch (Throwable e) {
            Log.e("BActivityThread", "handleBindApplication: app init failed: " + e);
        }
    }
    
    private Application makeApplicationCompat(Object loadedApk) throws Throwable {
        // Android 13 (API 33) renamed makeApplication() to makeApplicationInner()
        String primary  = Build.VERSION.SDK_INT >= 33 ? "makeApplicationInner" : "makeApplication";
        String fallback = Build.VERSION.SDK_INT >= 33 ? "makeApplication" : "makeApplicationInner";
        Class<?> cls = loadedApk.getClass();
        try {
            java.lang.reflect.Method m = cls.getDeclaredMethod(primary, boolean.class, android.app.Instrumentation.class);
            m.setAccessible(true);
            return (Application) m.invoke(loadedApk, false, null);
        } catch (NoSuchMethodException e) {
            java.lang.reflect.Method m = cls.getDeclaredMethod(fallback, boolean.class, android.app.Instrumentation.class);
            m.setAccessible(true);
            return (Application) m.invoke(loadedApk, false, null);
        }
    }

    private void fixAiLiaoPhoto(Application application) throws Throwable {
		if (application.getPackageName().equals("com.mosheng")) {
			ClassLoader loader = AppInstrumentation.get().getDelegateAppClassLoader();
			Class fileProviderClass = loader.loadClass("androidx.core.content.FileProvider");
			Method parsePathStrategyMethod = fileProviderClass.getDeclaredMethod("getPathStrategy", Context.class, String.class);
			parsePathStrategyMethod.setAccessible(true);
			Object pathStrategy = parsePathStrategyMethod.invoke(null, application, "com.mosheng.provider");
			Field fieldAuthority = pathStrategy.getClass().getDeclaredField("mAuthority");
			fieldAuthority.setAccessible(true);
            String newAuthority = "files." + MundoCore.getHostPkg();
			fieldAuthority.set(pathStrategy, newAuthority);
		}
	}
    
    private void fixWeChatRecovery(Application app) {
        try {
            Field field = app.getClassLoader().loadClass("com.tencent.recovery.Recovery").getField("context");
            field.setAccessible(true);
            if (field.get(null) != null) {
                return;
            }
            field.set(null, app.getBaseContext());
        } catch (Throwable ignored) { }
    }

    public static Context createPackageContext(ApplicationInfo info) {
        try {
            return MundoCore.getContext().createPackageContext(info.packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception ignored) {
            return null;
        }
    }
    
    public Object getPackageInfo() {
        return this.mBoundApplication.info;
    }
    
    private void installProviders(Context context, String processName, List<ProviderInfo> provider) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : provider) {
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(MundoCore.mainThread(), context, providerInfo, null);
                    }
                } catch (Throwable ignored) { }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            ContentProviderDelegate.init();
        }
    }

    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = Reflector.findMethodByFirstName(mainThread.getClass(), "installProvider");
        if (installProvider != null) {
            installProvider.setAccessible(true);
            installProvider.invoke(mainThread, context, holder, providerInfo, false, true, true);
        }
    }
    
    public void loadXposed(Context context) {
        String vPackageName = getAppPackageName();
        String vProcessName = getAppProcessName();
        if (!TextUtils.isEmpty(vPackageName) && !TextUtils.isEmpty(vProcessName) && BXposedManager.get().isXPEnable()) {
            assert vPackageName != null;
            assert vProcessName != null;
            boolean isFirstApplication = vPackageName.equals(vProcessName);
            List<InstalledModule> installedModules = BXposedManager.get().getInstalledModules();
            for (InstalledModule installedModule : installedModules) {
                if (!installedModule.enable) {
                    continue;
                }
                try {
                  //  PineXposed.loadModule(new File(installedModule.getApplication().sourceDir));
                } catch (Throwable ignored) { }
            }
            try {
              //  PineXposed.onPackageLoad(vPackageName, vProcessName, context.getApplicationInfo(), isFirstApplication, context.getClassLoader());
            } catch (Throwable ignored) {
            }
        }
        if (RemoteManager.sHideXposed) {
            VNative.hideXposed();
        }
    }

    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(MundoCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) bindApplication(getAppPackageName(), getAppProcessName());
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void restartJobService(String selfId) {}

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) {
        if (!isInit()) bindApplication(getAppConfig().packageName, getAppConfig().processName);
        for (String auth : providerInfo.authority.split(";")) {
            ContentProviderClient client = MundoCore.getContext().getContentResolver().acquireContentProviderClient(auth);
            IInterface iInterface = BRContentProviderClient.get(client).mContentProvider();
            if (iInterface != null) return iInterface.asBinder();
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(MundoCore.mainThread()).mActivities();
            Object clientRecord = activities.get(token);
            if (clientRecord == null) return;
            Activity activity = getActivityByToken(token);
            while (activity.getParent() != null) activity = activity.getParent();
            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        mH.post(() -> {
            Intent newIntent = BuildCompat.isLollipop_MR1() ? BRReferrerIntent.get()._new(intent, MundoCore.getHostPkg()) : intent;
            Object mainThread = MundoCore.mainThread();
            if (BRActivityThread.get(mainThread)._check_performNewIntents(null, null) != null) {
                BRActivityThread.get(mainThread).performNewIntents(token, Collections.singletonList(newIntent));
            } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                BRActivityThreadNMR1.get(mainThread).performNewIntents(token, Collections.singletonList(newIntent), true);
            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            }
        });
    }

    @Override
    public void scheduleReceiver(final ReceiverData data) {
        if (!isInit()) bindApplication();
        mH.post(() -> {
            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader cl = baseContext.getClassLoader();
                data.intent.setExtrasClassLoader(cl);
                BroadcastReceiver receiver = (BroadcastReceiver) cl.loadClass(data.activityInfo.name).newInstance();
                BRBroadcastReceiver.get(receiver).setPendingResult(data.data.build());
                receiver.onReceive(baseContext, data.intent);
                BroadcastReceiver.PendingResult finish = BRBroadcastReceiver.get(receiver).getPendingResult();
                if (finish != null) finish.finish();
                MundoCore.getBActivityManager().finishBroadcast(data.data);
            } catch (Throwable ignored) { }
        });
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> map = BRActivityThread.get(MundoCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(map.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback cb : MundoCore.get().getAppLifecycleCallbacks()) {
            cb.beforeCreateApplication(packageName, processName, context, getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application app) {
        for (AppLifecycleCallback cb : MundoCore.get().getAppLifecycleCallbacks()) {
            cb.beforeApplicationOnCreate(packageName, processName, app, getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application app) {
        for (AppLifecycleCallback cb : MundoCore.get().getAppLifecycleCallbacks()) {
            cb.afterApplicationOnCreate(packageName, processName, app, getUserId());
        }
    }
}
