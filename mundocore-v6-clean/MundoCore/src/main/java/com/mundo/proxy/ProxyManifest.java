package com.mundo.proxy;

import java.util.Locale;
import com.mundo.MundoCore;

public class ProxyManifest {
    public static final int FREE_COUNT = 50;
    public static final String PROXY_PACKAGE_NAME = "com.mundo";

    public static boolean isProxy(String msg) {
        return getBindProvider().equals(msg) || msg.contains("proxy_content_provider_");
    }

    public static String getBindProvider() {
        return MundoCore.getHostPkg() + ".SystemCallProvider";
    }

    public static String getProxyAuthorities(int index) {
        return String.format(Locale.CHINA, "%s.proxy_content_provider_%d", MundoCore.getHostPkg(), index);
    }

    public static String getProxyPendingActivity(int index) {
        return String.format(Locale.CHINA, PROXY_PACKAGE_NAME + ".proxy.ProxyPendingActivity$P%d", index);
    }

    public static String getProxyActivity(int index) {
        return String.format(Locale.CHINA, PROXY_PACKAGE_NAME + ".proxy.ProxyActivity$P%d", index);
    }

    public static String TransparentProxyActivity(int index) {
        return String.format(Locale.CHINA, PROXY_PACKAGE_NAME + ".proxy.TransparentProxyActivity$P%d", index);
    }

    public static String getProxyService(int index) {
        return String.format(Locale.CHINA, PROXY_PACKAGE_NAME + ".proxy.ProxyService$P%d", index);
    }

    public static String getProxyJobService(int index) {
        return String.format(Locale.CHINA, PROXY_PACKAGE_NAME + ".proxy.ProxyJobService$P%d", index);
    }

    public static String getProxyFileProvider() {
        return MundoCore.getHostPkg() + ".FileProvider";
    }

    public static String getProxyReceiver() {
        return MundoCore.getHostPkg() + ".stub_receiver";
    }

    public static String getProcessName(int bPid) {
        return MundoCore.getHostPkg() + ":p" + bPid;
    }
}
