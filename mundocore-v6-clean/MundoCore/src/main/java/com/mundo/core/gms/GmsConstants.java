package com.mundo.core.gms;

/** Shared constants so every place reports the same Play services identity. */
public final class GmsConstants {
    private GmsConstants() {}

    /** Full 9-digit versionCode (microG: (VERSION_CODE / 1000) * 1000). 25.24.32. */
    public static final int GMS_VERSION_CODE = 263663000; // same as Zerax MicroGAuthProtocol.GMS_VERSION
    public static final String GMS_VERSION_NAME = "26.36.63";

    public static final String GMS_PACKAGE = "com.google.android.gms";
    public static final String GSF_PACKAGE = "com.google.android.gsf";
    public static final String VENDING_PACKAGE = "com.android.vending";

    /** GmsService.AUTH_GOOGLE_SIGN_IN (microG play-services-basement GmsService.java). */
    public static final int SERVICE_ID_GOOGLE_SIGN_IN = 91;

    // CommonStatusCodes / GoogleSignInStatusCodes
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_SIGN_IN_REQUIRED = 4;
    public static final int STATUS_INTERNAL_ERROR = 8;
    public static final int STATUS_DEVELOPER_ERROR = 10;
    public static final int STATUS_NETWORK_ERROR = 7;
    public static final int STATUS_CANCELED = 16;
    public static final int STATUS_SIGN_IN_FAILED = 12500;
    public static final int STATUS_SIGN_IN_CANCELLED = 12501;

    // ConnectionResult
    public static final int CONNECTION_API_UNAVAILABLE = 16;
}
