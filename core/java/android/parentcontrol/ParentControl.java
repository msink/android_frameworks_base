package android.parentcontrol;

import android.content.Context;
import android.parentcontrol.utils.AESUtil;
import android.provider.Settings.Secure;

public class ParentControl {
    private static final String SEPARATOR = "#";
    private Context mContext = null;

    public ParentControl(Context context) {
        this.mContext = context;
    }

    public String getPassword(Context context, String lockType, String lockList) {
        if (lockType.isEmpty() && lockList.isEmpty())
            return null;
        String typeAndPasswordStr = null;
        String dbLockType = null;
        String password = null;
        typeAndPasswordStr = Secure.getString(context.getContentResolver(), lockList);
        int symbolIndex = typeAndPasswordStr.lastIndexOf("#");
        dbLockType = typeAndPasswordStr.substring(0, symbolIndex);
        if (!lockType.equals(dbLockType))
            return null;
        password = typeAndPasswordStr.substring("#".length() + symbolIndex,
            typeAndPasswordStr.length());
        return AESUtil.decryptStr(password);
    }

    public String getLockType(Context context, String lockList) {
        if (lockList.isEmpty())
            return null;
        String typeAndPasswordStr = null;
        String lockType = null;
        typeAndPasswordStr = Secure.getString(context.getContentResolver(), lockList);
        int symbolIndex = typeAndPasswordStr.lastIndexOf("#");
        lockType = typeAndPasswordStr.substring(0, symbolIndex);
        return lockType;
    }

    public boolean savePassword(Context context, String lockType, String lockList,
                                String password) {
        if (lockList.isEmpty())
            return false;
        StringBuilder typeAndPasswordStr = new StringBuilder();
        typeAndPasswordStr.append(lockType);
        typeAndPasswordStr.append("#");
        typeAndPasswordStr.append(AESUtil.encryptStr(password));
        Secure.putString(context.getContentResolver(), lockList, typeAndPasswordStr.toString());
        return true;
    }
}
