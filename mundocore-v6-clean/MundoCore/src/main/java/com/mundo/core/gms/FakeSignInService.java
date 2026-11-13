package com.mundo.core.gms;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Local Binder implementing com.google.android.gms.auth.api.signin.internal.ISignInService
 * (microG AuthSignInService). Runs in the guest process; all account work is done by
 * {@link GmsAuthService} in the server process via {@link GmsAuthClient}.
 *
 * Codes (AIDL "= n" -> FIRST_CALL_TRANSACTION + n), from microG play-services-auth:
 *   ISignInService:   silentSignIn = 100, signOut = 101, revokeAccess = 102
 *   ISignInCallbacks: onSignIn(account, status) = 100, onSignOut(status) = 101,
 *                     onRevokeAccess(status) = 102
 */
public class FakeSignInService extends Binder {

    private static final String TAG = "MundoGms";
    private static final String DESCRIPTOR = "com.google.android.gms.auth.api.signin.internal.ISignInService";
    private static final String CB_DESCRIPTOR = "com.google.android.gms.auth.api.signin.internal.ISignInCallbacks";

    private static final int TX_SILENT_SIGN_IN = IBinder.FIRST_CALL_TRANSACTION + 100;
    private static final int TX_SIGN_OUT = IBinder.FIRST_CALL_TRANSACTION + 101;
    private static final int TX_REVOKE_ACCESS = IBinder.FIRST_CALL_TRANSACTION + 102;

    private static final int CB_ON_SIGN_IN = IBinder.FIRST_CALL_TRANSACTION + 100;
    private static final int CB_ON_SIGN_OUT = IBinder.FIRST_CALL_TRANSACTION + 101;
    private static final int CB_ON_REVOKE_ACCESS = IBinder.FIRST_CALL_TRANSACTION + 102;

    private final int userId;
    private final String callerPkg;

    public FakeSignInService(int userId, String callerPkg) {
        this.userId = userId;
        this.callerPkg = callerPkg;
        attachInterface((IInterface) null, DESCRIPTOR);
    }

    @Override
    public String getInterfaceDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != TX_SILENT_SIGN_IN && code != TX_SIGN_OUT && code != TX_REVOKE_ACCESS) {
            return super.onTransact(code, data, reply, flags);
        }
        data.enforceInterface(DESCRIPTOR);
        final IBinder cb = data.readStrongBinder();
        SignInOptions opts = new SignInOptions();
        try {
            if (data.readInt() != 0) opts = SignInOptions.readFromParcel(data);
        } catch (Exception e) {
            Log.w(TAG, "GoogleSignInOptions decode failed: " + e);
        }
        final SignInOptions options = opts;
        final int tx = code;
        // Network work: never on the caller's thread.
        new Thread(() -> handle(tx, cb, options), "GmsSignIn").start();
        if (reply != null) reply.writeNoException();
        return true;
    }

    private void handle(int code, IBinder cb, SignInOptions options) {
        try {
            if (code == TX_SILENT_SIGN_IN) {
                GmsAuthClient.SignInResult r = GmsAuthClient.signIn(userId, callerPkg, options, null, false);
                Log.w(TAG, callerPkg + ": silentSignIn -> status " + r.statusCode);
                sendSignIn(cb, r.isSuccess() ? r.account : null, r.statusCode);
            } else if (code == TX_SIGN_OUT) {
                int st = GmsAuthClient.signOut(userId, callerPkg, options);
                sendStatus(cb, CB_ON_SIGN_OUT, st);
            } else {
                int st = GmsAuthClient.revokeAccess(userId, callerPkg, options);
                sendStatus(cb, CB_ON_REVOKE_ACCESS, st);
            }
        } catch (Exception e) {
            Log.w(TAG, "handle failed: " + e);
            if (code == TX_SILENT_SIGN_IN) sendSignIn(cb, null, GmsConstants.STATUS_INTERNAL_ERROR);
            else sendStatus(cb, code == TX_SIGN_OUT ? CB_ON_SIGN_OUT : CB_ON_REVOKE_ACCESS,
                    GmsConstants.STATUS_INTERNAL_ERROR);
        }
    }

    private static void sendSignIn(IBinder cb, GoogleSignInAccount account, int statusCode) {
        if (cb == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CB_DESCRIPTOR);
            if (account != null) {
                data.writeInt(1);
                account.writeSafeParcel(data);
            } else {
                data.writeInt(0);
            }
            data.writeInt(1);
            SafeParcelHelper.writeStatus(data, statusCode, null);
            cb.transact(CB_ON_SIGN_IN, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.w(TAG, "onSignIn callback failed: " + e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void sendStatus(IBinder cb, int txCode, int statusCode) {
        if (cb == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CB_DESCRIPTOR);
            data.writeInt(1);
            SafeParcelHelper.writeStatus(data, statusCode, null);
            cb.transact(txCode, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.w(TAG, "status callback failed: " + e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
