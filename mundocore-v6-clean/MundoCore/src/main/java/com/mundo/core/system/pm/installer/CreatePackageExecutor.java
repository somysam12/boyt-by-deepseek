package com.mundo.core.system.pm.installer;

import com.mundo.core.env.BEnvironment;
import com.mundo.core.system.pm.BPackageSettings;
import com.mundo.entity.pm.InstallOption;
import com.mundo.utils.FileUtils;


public class CreatePackageExecutor implements Executor {
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        FileUtils.deleteDir(BEnvironment.getAppDir(ps.pkg.packageName));
        FileUtils.mkdirs(BEnvironment.getAppDir(ps.pkg.packageName));
        FileUtils.mkdirs(BEnvironment.getAppLibDir(ps.pkg.packageName));
        return 0;
    }
}
