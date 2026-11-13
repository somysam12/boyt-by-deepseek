package com.mundo.core;

import android.MetaCore.RemoteManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import com.mundo.app.configuration.ClientConfiguration;
import java.io.File;

public class HostApp extends ClientConfiguration {

    private Context base;

    public HostApp(Context base) {
        this.base = base;
    }

    @Override
    public String getHostPackageName() {
        return base.getPackageName();
    }

    @Override
    public boolean setHideRoot() {
        return RemoteManager.sHideRoot;
    }

    @Override
    public boolean isEnableDaemonService() {
        return RemoteManager.sEnableDaemonService;
    }

    public boolean requestInstallPackage(File file){
        PackageInfo packageInfo = base.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),0);
		return false;
    }

}