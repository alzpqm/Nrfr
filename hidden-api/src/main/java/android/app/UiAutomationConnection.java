package android.app;

import android.os.IBinder;

/** Compile-only signature. The framework supplies the actual connection. */
public class UiAutomationConnection implements IUiAutomationConnection {
    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException("Framework stub");
    }
}
