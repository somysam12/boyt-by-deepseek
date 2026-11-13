package com.mundo.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

import black.android.app.BRIServiceConnectionBaklava;
import black.android.app.BRIServiceConnectionO;
import com.mundo.utils.compat.BuildCompat;

public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (BuildCompat.isBaklava() && code == IBinder.FIRST_CALL_TRANSACTION) {
            data.enforceInterface("android.app.IServiceConnection");
            ComponentName name = data.readInt() != 0 ? ComponentName.CREATOR.createFromParcel(data) : null;
            IBinder service = data.readStrongBinder();
            data.readStrongBinder(); // IBinderSession — skip, not forwarded to app
            boolean dead = data.readInt() != 0;
            connected(name, service, dead);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        // Real Play services broker: route Google Sign-In (id 91) to the virtual service,
        // forward everything else as the host package (see GmsBrokerProxy).
        IBinder orig = service;
        service = com.mundo.proxy.gms.GmsSignInInterceptor.maybeWrapBroker(mComponentName, name, service);
        android.util.Log.w("MundoGms", "ServiceConnectionDelegate.connected target=" + mComponentName
                + " name=" + name + " wrapped=" + (service != orig));
        try {
            if (BuildCompat.isBaklava()) {
                BRIServiceConnectionBaklava.get(mConn).connected(mComponentName, service, null, dead);
            } else if (BuildCompat.isOreo()) {
                BRIServiceConnectionO.get(mConn).connected(mComponentName, service, dead);
            } else {
                mConn.connected(name, service);
            }
        } catch (SecurityException e) {
            android.util.Log.w("MundoGms", "ServiceConnectionDelegate.connected SecurityException for "
                    + mComponentName + ": " + e.getMessage());
        }
    }
}
