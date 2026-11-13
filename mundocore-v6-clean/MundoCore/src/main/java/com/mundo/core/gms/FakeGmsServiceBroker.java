package com.mundo.core.gms;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Local Binder implementing com.google.android.gms.common.internal.IGmsServiceBroker,
 * handed to the guest in place of a connection to Play services.
 *
 * Transaction codes (AIDL "= n" means FIRST_CALL_TRANSACTION + n), verified against
 * microG play-services-basement IGmsServiceBroker.aidl / IGmsCallbacks.aidl:
 *   getService(IGmsCallbacks, in GetServiceRequest) = 45  -> raw 46
 *   IGmsCallbacks.onPostInitComplete(int, IBinder, in Bundle) (1st method) -> raw 1
 *
 * GetServiceRequest SafeParcel: 1 versionCode, 2 serviceId, 3 gmsVersion, 4 packageName, ...
 * Only GmsService.AUTH_GOOGLE_SIGN_IN (91) is served; everything else gets
 * ConnectionResult.API_UNAVAILABLE.
 */
public class FakeGmsServiceBroker extends Binder {

    private static final String TAG = "MundoGms";

    private static final String DESCRIPTOR = "com.google.android.gms.common.internal.IGmsServiceBroker";
    private static final String CB_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsCallbacks";

    private static final int TRANSACTION_GET_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 45;
    private static final int CB_ON_POST_INIT_COMPLETE = IBinder.FIRST_CALL_TRANSACTION;

    private final int userId;
    private final String guestPackage;

    public FakeGmsServiceBroker(int userId, String guestPackage) {
        this.userId = userId;
        this.guestPackage = guestPackage;
        attachInterface((IInterface) null, DESCRIPTOR);
    }

    @Override
    public String getInterfaceDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != TRANSACTION_GET_SERVICE) {
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                // Legacy getXxxService(IGmsCallbacks callback, ...) calls: answer "unavailable".
                try {
                    data.enforceInterface(DESCRIPTOR);
                    IBinder cb = data.readStrongBinder();
                    if (cb != null) sendPostInitComplete(cb, GmsConstants.CONNECTION_API_UNAVAILABLE, null);
                } catch (Exception ignored) {
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        data.enforceInterface(DESCRIPTOR);
        IBinder callback = data.readStrongBinder();
        int serviceId = -1;
        String callerPkg = null;
        try {
            if (data.readInt() != 0) { // non-null parcelable marker
                int end = SafeParcelHelper.validateObjectHeader(data);
                while (data.dataPosition() < end) {
                    int h = SafeParcelHelper.readHeader(data);
                    switch (SafeParcelHelper.fieldId(h)) {
                        case 2: serviceId = SafeParcelHelper.readInt(data, h); break;
                        case 4: callerPkg = SafeParcelHelper.readString(data, h); break;
                        default: SafeParcelHelper.skip(data, h); break;
                    }
                }
                data.setDataPosition(end);
            }
        } catch (Exception e) {
            Log.w(TAG, "GetServiceRequest parse error: " + e);
        }
        // The request's package name comes from the guest; trust only the package we
        // know is running in this process.
        String pkg = guestPackage != null ? guestPackage : callerPkg;
        Log.w(TAG, "getService id=" + serviceId + " pkg=" + callerPkg);

        if (callback != null) {
            if (serviceId == GmsConstants.SERVICE_ID_GOOGLE_SIGN_IN) {
                sendPostInitComplete(callback, GmsConstants.STATUS_SUCCESS, new FakeSignInService(userId, pkg));
            } else {
                sendPostInitComplete(callback, GmsConstants.CONNECTION_API_UNAVAILABLE, null);
            }
        }
        if (reply != null) reply.writeNoException();
        return true;
    }

    private static void sendPostInitComplete(IBinder cb, int statusCode, IBinder service) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CB_DESCRIPTOR);
            data.writeInt(statusCode);
            data.writeStrongBinder(service);
            data.writeInt(0); // null Bundle
            cb.transact(CB_ON_POST_INIT_COMPLETE, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.w(TAG, "onPostInitComplete failed: " + e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
