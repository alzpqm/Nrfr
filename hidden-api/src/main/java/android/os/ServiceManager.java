package android.os;

/** Compile-only signature. Never packaged in the APK. */
public class ServiceManager {
    public static IBinder getService(String name) {
        throw new UnsupportedOperationException("Framework stub");
    }
}
