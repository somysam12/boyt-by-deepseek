package com.mundo.core.gms;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Wraps the REAL com.google.android.gms IGmsServiceBroker when Play services is
 * installed on the device (same design as Zerax's GmsServiceBrokerProxy):
 *
 *   serviceId 91 (Google Sign-In)  -> answered locally with FakeSignInService
 *                                     (accounts from GmsAuthService)
 *   everything else                -> forwarded to real GMS, with
 *                                     GetServiceRequest.packageName (field 4)
 *                                     rewritten to the host package, because GMS
 *                                     validates it against the calling uid
 *                                     (the host's uid) and would otherwise reject it.
 *
 * Without this, a guest on a device WITH Play services would lose Play Games,
 * Firebase, Integrity, Maps, ... because FakeGmsServiceBroker answers
 * API_UNAVAILABLE for all of them.
 */
public final class GmsBrokerProxy extends Binder {

    private static final String TAG = "MundoGms";
    private static final String DESCRIPTOR = "com.google.android.gms.common.internal.IGmsServiceBroker";
    private static final String CB_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsCallbacks";
    private static final int TX_GET_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 45;
    private static final int OBJECT_HEADER = 0x4F45; // 20293

    private final IBinder base;
    private final int userId;
    private final String guestPkg;
    private final String hostPkg;

    private GmsBrokerProxy(IBinder base, int userId, String guestPkg, String hostPkg) {
        this.base = base;
        this.userId = userId;
        this.guestPkg = guestPkg;
        this.hostPkg = hostPkg;
    }

    /** Wraps {@code binder} if it is a real GMS broker; returns it unchanged otherwise. */
    public static IBinder wrap(IBinder binder, int userId, String guestPkg, String hostPkg) {
        if (binder == null || binder instanceof GmsBrokerProxy || binder instanceof FakeGmsServiceBroker) {
            Log.w(TAG, "wrap: skip (null/already wrapped/fake) binder=" + binder);
            return binder;
        }
        try {
            String desc = binder.getInterfaceDescriptor();
            if (!DESCRIPTOR.equals(desc)) {
                Log.w(TAG, "wrap: skip (descriptor mismatch: " + desc + ")");
                return binder;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "wrap: skip (getInterfaceDescriptor failed: " + e + ")");
            return binder;
        }
        Log.w(TAG, "wrap: creating GmsBrokerProxy for guest=" + guestPkg + " host=" + hostPkg
                + " binderClass=" + binder.getClass().getName());
        return new GmsBrokerProxy(binder, userId, guestPkg, hostPkg);
    }

    @Override
    public String getInterfaceDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean isBinderAlive() {
        return base.isBinderAlive();
    }

    @Override
    public boolean pingBinder() {
        return base.pingBinder();
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) {
        try {
            base.linkToDeath(recipient, flags);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return base.unlinkToDeath(recipient, flags);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Log.w(TAG, "GmsBrokerProxy.onTransact code=" + code + " TX_GET_SERVICE=" + TX_GET_SERVICE
                + " guest=" + guestPkg);
        if (code == TX_GET_SERVICE) {
            try {
                return handleGetService(data, reply, flags);
            } catch (Exception e) {
                Log.w(TAG, "getService rewrite failed, forwarding unchanged: " + e);
                data.setDataPosition(0);
            }
        }
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
        Log.w(TAG, "GmsBrokerProxy: forwarding code=" + code + " to real GMS");
        return base.transact(code, data, reply, flags);
    }

    private boolean handleGetService(Parcel data, Parcel reply, int flags) throws RemoteException {
        data.enforceInterface(DESCRIPTOR);
        IBinder callback = data.readStrongBinder();
        if (data.readInt() == 0) { // null request: nothing to rewrite
            data.setDataPosition(0);
            return base.transact(TX_GET_SERVICE, data, reply, flags);
        }

        // Copy GetServiceRequest field by field, replacing packageName (4).
        int objHeader = data.readInt();
        if ((objHeader & 0xFFFF) != OBJECT_HEADER) throw new IllegalStateException("not a SafeParcel");
        int objSize = (objHeader & 0xFFFF0000) == 0xFFFF0000 ? data.readInt() : (objHeader >>> 16);
        int end = data.dataPosition() + objSize;
        if (objSize < 0 || end > data.dataSize()) throw new IllegalStateException("bad object size");

        Parcel out = Parcel.obtain();
        int serviceId = -1;
        try {
            out.writeInterfaceToken(DESCRIPTOR);
            out.writeStrongBinder(callback);
            out.writeInt(1);
            int start = SafeParcelHelper.beginObject(out);
            while (data.dataPosition() < end) {
                int fieldStart = data.dataPosition();
                int h = data.readInt();
                int id = h & 0xFFFF;
                int size = (h & 0xFFFF0000) == 0xFFFF0000 ? data.readInt() : (h >>> 16);
                int bodyStart = data.dataPosition();
                if (size < 0 || bodyStart + size > end) throw new IllegalStateException("bad field size");
                if (id == 2 && size >= 4) {
                    serviceId = data.readInt();
                }
                if (id == 4) {
                    SafeParcelHelper.writeString(out, 4, hostPkg);
                } else {
                    out.appendFrom(data, fieldStart, (bodyStart + size) - fieldStart);
                }
                data.setDataPosition(bodyStart + size);
            }
            SafeParcelHelper.finishObject(out, start);

            if (serviceId == GmsConstants.SERVICE_ID_GOOGLE_SIGN_IN) {
                Log.w(TAG, "getService id=91 pkg=" + guestPkg + " -> virtual sign-in");
                postInit(callback, GmsConstants.STATUS_SUCCESS, new FakeSignInService(userId, guestPkg));
                if (reply != null) reply.writeNoException();
                return true;
            }
            Log.w(TAG, "getService id=" + serviceId + " pkg=" + guestPkg + " -> real GMS as " + hostPkg);
            out.setDataPosition(0);
            return base.transact(TX_GET_SERVICE, out, reply, flags);
        } finally {
            out.recycle();
        }
    }

    private static void postInit(IBinder cb, int status, IBinder service) {
        if (cb == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(CB_DESCRIPTOR);
            d.writeInt(status);
            d.writeStrongBinder(service);
            d.writeInt(0);
            cb.transact(IBinder.FIRST_CALL_TRANSACTION, d, r, 0);
            r.readException();
        } catch (Exception e) {
            Log.w(TAG, "onPostInitComplete failed: " + e);
        } finally {
            d.recycle();
            r.recycle();
        }
    }
}
