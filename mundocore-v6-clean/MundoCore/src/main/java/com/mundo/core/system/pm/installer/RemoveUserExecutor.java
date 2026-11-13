package com.mundo.core.system.pm.installer;

import com.mundo.core.env.BEnvironment;
import com.mundo.core.system.pm.BPackageSettings;
import com.mundo.entity.pm.InstallOption;
import com.mundo.utils.FileUtils;

public class RemoveUserExecutor implements Executor {
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        FileUtils.deleteDir(BEnvironment.getDataDir(packageName, userId));
        FileUtils.deleteDir(BEnvironment.getDeDataDir(packageName, userId));
        //FileUtils.deleteDir(BEnvironment.getExternalDataDir(packageName));
        FileUtils.deleteDir(BEnvironment.getExternalObbDir(packageName));
        return 0;
    }
}

