package android.hardware;

/** {@hide} */
interface IEpdService
{
    boolean updateModeLock(boolean acquired, String owner);
    void switchWorkMode(String mode, String owner);
    void repaintDisplay();
    void resetDisplay();
    boolean isBusyPainting();
    void switchGrayType(String type);
}
