package com.mundo.core.gms;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Device check-in (androidId + securityToken), adapted from microG
 * CheckinClient.makeRequest / request. Hand-written protobuf, field numbers taken
 * from microG play-services-core-proto checkin.proto / deviceconfig.proto:
 *
 * CheckinRequest: 2 androidId(int64) 3 digest 4 checkin 6 locale 7 loggingId
 *   11 accountCookie 12 timeZone 13 securityToken(fixed64) 14 version
 *   16 serial 18 deviceConfiguration 20 fragment(required)
 * Checkin: 1 build(required) 2 lastCheckinMs 3 event 9 userNumber
 * Build: 1 fingerprint 2 hardware 3 brand 4 radio 5 bootloader 6 clientId 7 time
 *   9 device 10 sdkVersion 11 model 12 manufacturer 13 product 14 otaInstalled
 * CheckinResponse: 4 digest 7 androidId(fixed64) 8 securityToken(fixed64)
 *
 * Runs only in the host process that owns GmsAuthService.
 */
public final class GmsCheckin {

    private static final String TAG = "GmsCheckin";
    private static final String URL_CHECKIN = "https://android.clients.google.com/checkin";
    private static final String PREFS = "gms_checkin_v2";
    private static final long MIN_INTERVAL_MS = 12 * 3600_000L;

    private static final String KEY_ANDROID_ID = "android_id";
    private static final String KEY_SEC_TOKEN = "sec_token";
    private static final String KEY_LAST_TIME = "last_time";
    private static final String KEY_DIGEST = "digest";
    private static final String KEY_LOGGING_ID = "logging_id";

    private GmsCheckin() {}

    public static long getAndroidId(Context ctx) {
        return prefs(ctx).getLong(KEY_ANDROID_ID, 0L);
    }

    /** Check-in if needed (or forced). Returns true when an androidId is available. */
    public static synchronized boolean checkin(Context ctx, boolean force) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        long aid = p.getLong(KEY_ANDROID_ID, 0);
        if (!force && aid != 0 && now - p.getLong(KEY_LAST_TIME, 0) < MIN_INTERVAL_MS) {
            return true;
        }
        try {
            long loggingId = p.getLong(KEY_LOGGING_ID, 0);
            if (loggingId == 0) {
                loggingId = new SecureRandom().nextLong();
                p.edit().putLong(KEY_LOGGING_ID, loggingId).apply();
            }
            byte[] req = buildRequest(ctx, aid, p.getLong(KEY_SEC_TOKEN, 0),
                    p.getString(KEY_DIGEST, "1-929a0dca0eee55513280171a8585da7dcd3700f8"),
                    p.getLong(KEY_LAST_TIME, 0), loggingId);
            byte[] resp = post(req);
            long[] ids = parseResponse(resp);
            if (ids[0] != 0) {
                p.edit()
                        .putLong(KEY_ANDROID_ID, ids[0])
                        .putLong(KEY_SEC_TOKEN, ids[1])
                        .putLong(KEY_LAST_TIME, now)
                        .commit();
                Log.d(TAG, "checkin ok");
                return true;
            }
            Log.w(TAG, "checkin response without androidId");
        } catch (Exception e) {
            Log.w(TAG, "checkin failed: " + e);
        }
        return aid != 0;
    }

    // ---- request ------------------------------------------------------------

    private static byte[] buildRequest(Context ctx, long androidId, long securityToken, String digest,
                                       long lastCheckin, long loggingId) throws IOException {
        Proto build = new Proto()
                .string(1, Build.FINGERPRINT)
                .string(2, Build.HARDWARE)
                .string(3, Build.BRAND)
                .string(4, radio())
                .string(5, Build.BOOTLOADER)
                .string(6, "android-google")
                .varint(7, Build.TIME / 1000)
                .string(9, Build.DEVICE)
                .varint(10, Build.VERSION.SDK_INT)
                .string(11, Build.MODEL)
                .string(12, Build.MANUFACTURER)
                .string(13, Build.PRODUCT)
                .varint(14, 0);

        Proto event = new Proto()
                .string(1, androidId == 0 ? "event_log_start" : "system_update")
                .stringOpt(2, androidId == 0 ? null : "1536,0,-1,NULL")
                .varint(3, System.currentTimeMillis());

        Proto checkin = new Proto()
                .message(1, build)
                .varint(2, lastCheckin)
                .message(3, event)
                .varint(9, 0);

        Proto req = new Proto()
                .varint(2, androidId)
                .string(3, digest)
                .message(4, checkin)
                .string(6, Locale.getDefault().toString())
                .varint(7, loggingId)
                .string(11, "")
                .string(12, TimeZone.getDefault().getID())
                .varint(14, 3)
                .message(18, deviceConfig(ctx))
                .varint(20, securityToken != 0 ? 1 : 0);
        if (securityToken != 0) req.fixed64(13, securityToken);
        return req.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static String radio() {
        String r = Build.getRadioVersion();
        return r != null ? r : "";
    }

    private static Proto deviceConfig(Context ctx) {
        Configuration cfg = ctx.getResources().getConfiguration();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        Proto dc = new Proto()
                .varint(1, cfg.touchscreen)
                .varint(2, cfg.keyboard)
                .varint(3, cfg.navigation)
                .varint(4, cfg.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK)
                .varint(5, cfg.keyboard == Configuration.KEYBOARD_QWERTY ? 1 : 0)
                .varint(6, (cfg.navigation == Configuration.NAVIGATION_DPAD
                        || cfg.navigation == Configuration.NAVIGATION_TRACKBALL) ? 1 : 0)
                .varint(7, dm.densityDpi);
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ConfigurationInfo ci = am.getDeviceConfigurationInfo();
            dc.varint(8, ci.reqGlEsVersion);
        } catch (Exception ignored) {}
        try {
            String[] libs = ctx.getPackageManager().getSystemSharedLibraryNames();
            if (libs != null) for (String l : libs) dc.string(9, l);
            FeatureInfo[] feats = ctx.getPackageManager().getSystemAvailableFeatures();
            if (feats != null) for (FeatureInfo f : feats) if (f.name != null) dc.string(10, f.name);
        } catch (Exception ignored) {}
        String[] abis = Build.VERSION.SDK_INT >= 21 ? Build.SUPPORTED_ABIS : new String[]{Build.CPU_ABI};
        for (String abi : abis) dc.string(11, abi);
        dc.varint(12, dm.widthPixels).varint(13, dm.heightPixels);
        dc.string(14, Locale.getDefault().toString());
        return dc;
    }

    private static byte[] post(byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_CHECKIN).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoInput(true);
            c.setDoOutput(true);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Content-type", "application/x-protobuffer");
            c.setRequestProperty("Content-Encoding", "gzip");
            c.setRequestProperty("Accept-Encoding", "gzip");
            c.setRequestProperty("User-Agent", "Android-Checkin/2.0 (" + Build.DEVICE + " " + Build.ID + "); gzip");
            OutputStream os = new GZIPOutputStream(c.getOutputStream());
            os.write(body);
            os.close();
            if (c.getResponseCode() != 200) {
                throw new IOException("checkin HTTP " + c.getResponseCode() + " " + c.getResponseMessage());
            }
            InputStream is = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) is = new GZIPInputStream(is);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
            is.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Returns {androidId (field 7), securityToken (field 8)}; also stores digest. */
    private static long[] parseResponse(byte[] data) {
        long[] out = new long[2];
        int[] pos = {0};
        while (pos[0] < data.length) {
            long tag = Proto.readVarint(data, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 1) {
                long v = Proto.readFixed64(data, pos);
                if (field == 7) out[0] = v;
                else if (field == 8) out[1] = v;
            } else if (wire == 0) {
                long v = Proto.readVarint(data, pos);
                if (field == 7) out[0] = v; // tolerate non-fixed encoding
                else if (field == 8) out[1] = v;
            } else if (wire == 2) {
                int len = (int) Proto.readVarint(data, pos);
                pos[0] += len;
            } else if (wire == 5) {
                pos[0] += 4;
            } else {
                break;
            }
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- tiny protobuf writer/reader (also used for consent protos) ---------

    static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto varint(int field, long v) {
            tag(field, 0);
            writeVarint(v);
            return this;
        }

        Proto fixed64(int field, long v) {
            tag(field, 1);
            for (int i = 0; i < 8; i++) out.write((int) (v >>> (8 * i)) & 0xFF);
            return this;
        }

        Proto string(int field, String s) {
            return bytes(field, (s != null ? s : "").getBytes(StandardCharsets.UTF_8));
        }

        Proto stringOpt(int field, String s) {
            return s == null ? this : string(field, s);
        }

        Proto bytes(int field, byte[] b) {
            tag(field, 2);
            writeVarint(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Proto message(int field, Proto m) {
            return bytes(field, m.toByteArray());
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private void tag(int field, int wire) {
            writeVarint(((long) field << 3) | wire);
        }

        private void writeVarint(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        static long readVarint(byte[] d, int[] pos) {
            long r = 0;
            int shift = 0;
            while (pos[0] < d.length) {
                int b = d[pos[0]++] & 0xFF;
                r |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return r;
        }

        static long readFixed64(byte[] d, int[] pos) {
            long v = 0;
            for (int i = 0; i < 8 && pos[0] < d.length; i++) {
                v |= (long) (d[pos[0]++] & 0xFF) << (8 * i);
            }
            return v;
        }
    }
}
