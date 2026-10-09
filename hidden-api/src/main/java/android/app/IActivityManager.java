package android.app;

import android.content.ComponentName;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only signatures. Binder implementations come from the device. */
public interface IActivityManager extends IInterface {
    void startDelegateShellPermissionIdentity(int uid, String[] permissions) throws RemoteException;
    void stopDelegateShellPermissionIdentity() throws RemoteException;
    boolean startInstrumentation(ComponentName component, String profileFile, int flags,
            Bundle arguments, IInstrumentationWatcher watcher,
            IUiAutomationConnection connection, int userId, String abiOverride)
            throws RemoteException;

    abstract class Stub extends Binder implements IActivityManager {
        public static IActivityManager asInterface(IBinder binder) {
            throw new UnsupportedOperationException("Framework stub");
        }
    }
}
