package android.os;

/** {@hide} */
interface IOnyxWifiLockManagerService
{
    void lock(String className);
    void unLock(String className);
    void clear();
    Map getLockMap();
    void setTimeout(long ms);
}
