package com.mundo.core.system;

import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import com.mundo.MundoCore;
import com.mundo.core.gms.GmsAuthService;
import com.mundo.core.system.accounts.BAccountManagerService;
import com.mundo.core.system.am.BActivityManagerService;
import com.mundo.core.system.am.BJobManagerService;
import com.mundo.core.system.location.BLocationManagerService;
import com.mundo.core.system.notification.BNotificationManagerService;
import com.mundo.core.system.os.BStorageManagerService;
import com.mundo.core.system.pm.BPackageManagerService;
import com.mundo.core.system.pm.BXposedManagerService;
import com.mundo.core.system.user.BUserManagerService;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ServiceManager {
    private static ServiceManager sServiceManager = null;
    public static final String ACTIVITY_MANAGER = "activity_manager";
    public static final String JOB_MANAGER = "job_manager";
    public static final String PACKAGE_MANAGER = "package_manager";
    public static final String STORAGE_MANAGER = "storage_manager";
    public static final String USER_MANAGER = "user_manager";
    public static final String XPOSED_MANAGER = "xposed_manager";
    public static final String ACCOUNT_MANAGER = "account_manager";
    public static final String LOCATION_MANAGER = "location_manager";
    public static final String NOTIFICATION_MANAGER = "notification_manager";
    public static final String GMS_AUTH = "gms_auth";

    private final Map<String, IBinder> mCaches = new HashMap<>();

    public static ServiceManager get() {
        if (sServiceManager == null) {
            synchronized (ServiceManager.class) {
                if (sServiceManager == null) {
                    sServiceManager = new ServiceManager();
                }
            }
        }
        return sServiceManager;
    }

    public static IBinder getService(String name) {
        return get().getServiceInternal(name);
    }

    private ServiceManager() {
        mCaches.put(ACTIVITY_MANAGER, BActivityManagerService.get());
        mCaches.put(JOB_MANAGER, BJobManagerService.get());
        mCaches.put(PACKAGE_MANAGER, BPackageManagerService.get());
        mCaches.put(STORAGE_MANAGER, BStorageManagerService.get());
        mCaches.put(USER_MANAGER, BUserManagerService.get());
        mCaches.put(XPOSED_MANAGER, BXposedManagerService.get());
        mCaches.put(ACCOUNT_MANAGER, BAccountManagerService.get());
        mCaches.put(LOCATION_MANAGER, BLocationManagerService.get());
        mCaches.put(NOTIFICATION_MANAGER, BNotificationManagerService.get());
        mCaches.put(GMS_AUTH, GmsAuthService.get());
    }

    public IBinder getServiceInternal(String name) {
        return mCaches.get(name);
    }

    public static void initBlackManager() {
        MundoCore.get().getService(ACTIVITY_MANAGER);
        MundoCore.get().getService(JOB_MANAGER);
        MundoCore.get().getService(PACKAGE_MANAGER);
        MundoCore.get().getService(STORAGE_MANAGER);
        MundoCore.get().getService(USER_MANAGER);
        MundoCore.get().getService(XPOSED_MANAGER);
        MundoCore.get().getService(ACCOUNT_MANAGER);
        MundoCore.get().getService(LOCATION_MANAGER);
        MundoCore.get().getService(NOTIFICATION_MANAGER);
        MundoCore.get().getService(GMS_AUTH);
    }
}
