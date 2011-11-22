/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.ethernet;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;
import android.util.Log;

public class EthernetInfo implements Parcelable {
    private static final String TAG = "EthernetInfo";
    private static void LOG(String msg) {
        Log.d(TAG, msg);
    }

    public static final String LINK_SPEED_UNITS = "Mbps";
    public static final int PHISCIS_LINK_DOWN = 0;
    public static final int PHISCIS_LINK_UP = 1;

    public static final int DUPLEX_HALF = 0;
    public static final int DUPLEX_FULL = 1;
    public static final int DUPLEX_UNKNOWN = -1;

    private String mDevName;
    private int mDuplexingMode;
    private int mIpAddress;
    private int mLinkSpeed;
    private int mLinkageState;
    private String mMacAddress;

    EthernetInfo () {
        mDevName = null;
        mLinkageState = PHISCIS_LINK_DOWN;
        mDuplexingMode = DUPLEX_UNKNOWN;
        mLinkSpeed = 0;
        mIpAddress = 0;
        mMacAddress = null;
    }

    public void setDevName(String name) {
        mDevName = name;
    }

    public String getDevName() {
        return mDevName;
    }

    public void setDuplexingMode(int mode) {
        mDuplexingMode = mode;
    }

    public int getDuplexingMode() {
        return mDuplexingMode;
    }

    public void setIpAddress(int address) {
        mIpAddress = address;
    }

    public int getIpAddress() {
        return mIpAddress;
    }

    public void setLinkSpeed(int speed) {
        mLinkSpeed = speed;
    }

    public int getLinkSpeed() {
        return mLinkSpeed;
    }

    public void setLinkageState(int state) {
        mLinkageState = state;
    }

    public int getLinkageState() {
        return mLinkageState;
    }

    public void setMacAddress(String address) {
        mMacAddress = address;
    }

    public String getMacAddress() {
        return mMacAddress;
    }

    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        String none = "<none>";
        sb.append("DevName: ")
          .append(mDevName == null ? none : mDevName)
          .append(", Link speed: ")
          .append(mLinkSpeed);
        return sb.toString();
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mDevName);
        dest.writeInt(mLinkageState);
        dest.writeInt(mDuplexingMode);
        dest.writeInt(mLinkSpeed);
        dest.writeInt(mIpAddress);
        dest.writeString(mMacAddress);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<EthernetInfo> CREATOR =
        new Creator<EthernetInfo>() {
            public EthernetInfo createFromParcel(Parcel in) {
                EthernetInfo info = new EthernetInfo();
                info.setDevName(in.readString());
                info.setLinkageState(in.readInt());
                info.setDuplexingMode(in.readInt());
                info.setLinkSpeed(in.readInt());
                info.setIpAddress(in.readInt());
                info.setMacAddress(in.readString());
                return info;
            }

            public EthernetInfo[] newArray(int size) {
                return new EthernetInfo[size];
            }
        };

}
