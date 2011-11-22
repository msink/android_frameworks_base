/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import android.os.AsyncResult;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.util.Log;
import android.util.Xml;

import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.AdnRecordLoader;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.cdma.RuimCard;
import com.android.internal.telephony.MccTable;
import com.android.internal.util.XmlUtils;

// can't be used since VoiceMailConstants is not public
//import com.android.internal.telephony.gsm.VoiceMailConstants;
import com.android.internal.telephony.IccException;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneProxy;

import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * {@hide}
 */
public final class RuimRecords extends IccRecords {
    static final String LOG_TAG = "CDMA-RuimRecords";

    private static final boolean DBG = true;
    private boolean  m_ota_commited=false;

    // ***** Instance Variables

    private String mImsi;
    private String mMyMobileNumber;
    private String mMin2Min1;

    private String mPrlVersion;

    // ***** Event Constants

    private static final int EVENT_RUIM_READY = 1;
    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 2;
    private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_DEVICE_IDENTITY_DONE = 4;
    private static final int EVENT_GET_ICCID_DONE = 5;
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_DONE = 10;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;

    private static final int EVENT_SMS_ON_RUIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;

    private static final int EVENT_RUIM_REFRESH = 31;

    CdmaSpnOverride mCdmaSpnOverride;

    public class CdmaSpnOverride {
        final static String LOG_TAG = "CDMA-CdmaSpnOverride";
        final static String PARTNER_SPN_OVERRIDE_PATH = "etc/spn-conf.xml";

        private HashMap<String, String> CarrierSpnMap;

        CdmaSpnOverride() {
            CarrierSpnMap = new HashMap();
            loadSpnOverrides();
        }

        boolean containsCarrier(String carrier) {
            return CarrierSpnMap.containsKey(carrier);
        }

        String getSpn(String carrier) {
            return CarrierSpnMap.get(carrier);
        }

        private void loadSpnOverrides() {
            File spnFile = new File(Environment.getRootDirectory(), PARTNER_SPN_OVERRIDE_PATH);
            FileReader spnReader;
            try {
                spnReader = new FileReader(spnFile);
            } catch (FileNotFoundException e) {
                Log.w(LOG_TAG, "Can't open " + Environment.getRootDirectory()
                                             + "/" + PARTNER_SPN_OVERRIDE_PATH);
                return;
            }

            try {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(spnReader);
                XmlUtils.beginDocument(parser, "spnOverrides");

                for (;;) {
                    XmlUtils.nextElement(parser);
                    String name = parser.getName();
                    if (!"spnOverride".equals(name))
                        break;
                    String numeric = parser.getAttributeValue(null, "numeric");
                    String data = parser.getAttributeValue(null, "spn");
                    CarrierSpnMap.put(numeric, data);
                }

            } catch (XmlPullParserException e) {
                Log.w(LOG_TAG, "Exception in spn-conf parser " + e);
            } catch (IOException e) {
                Log.w(LOG_TAG, "Exception in spn-conf parser " + e);
            }
        }
    }

    RuimRecords(CDMAPhone p) {
        super(p);

        adnCache = new AdnRecordCache(phone);

        recordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        recordsToLoad = 0;

        mCdmaSpnOverride = new CdmaSpnOverride();

        p.mCM.registerForRUIMReady(this, EVENT_RUIM_READY, null);
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        // NOTE the EVENT_SMS_ON_RUIM is not registered
        p.mCM.setOnIccRefresh(this, EVENT_RUIM_REFRESH, null);

        // Start off by setting empty state
        onRadioOffOrNotAvailable();

    }

    public void dispose() {
        //Unregister for all events
        phone.mCM.unregisterForRUIMReady(this);
        phone.mCM.unregisterForOffOrNotAvailable( this);
        phone.mCM.unSetOnIccRefresh(this);
    }

    @Override
    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "RuimRecords finalized");
    }

    @Override
    protected void onRadioOffOrNotAvailable() {
        countVoiceMessages = 0;
        mncLength = UNINITIALIZED;
        iccid = null;

        adnCache.reset();

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        recordsRequested = false;
    }

    public String getMdnNumber() {
        return mMyMobileNumber;
    }

    public String getCdmaMin() {
         return mMin2Min1;
    }

    /** Returns null if RUIM is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    public String getServiceProviderName() {
        return spn;
    }

    String getSIMOperatorNumeric() {
        if (mImsi == null)
            return null;
        return mImsi.substring(0, 5);
    }

    private void setSpnFromConfig(String carrier) {
        if (mCdmaSpnOverride.containsCarrier(carrier)) {
            spn = mCdmaSpnOverride.getSpn(carrier);
            Log.d(LOG_TAG, "setSpnFromConfig  spn=" + spn);
        }
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber, Message onComplete){
        // In CDMA this is Operator/OEM dependent
        AsyncResult.forMessage((onComplete)).exception =
                new IccException("setVoiceMailNumber not implemented");
        onComplete.sendToTarget();
        Log.e(LOG_TAG, "method setVoiceMailNumber is not implemented");
    }

    /**
     * Called by CCAT Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all RUIM records that we cache.
            fetchRuimRecords();
        }
    }

    /**
     * Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the RUIM card. Returns null of RUIM is not yet ready
     */
    public String getRUIMOperatorNumeric() {
        if (mImsi == null) {
            return null;
        }

        if (mncLength != UNINITIALIZED && mncLength != UNKNOWN) {
            // Length = length of MCC + length of MNC
            // length of mcc = 3 (3GPP2 C.S0005 - Section 2.3)
            return mImsi.substring(0, 3 + mncLength);
        }

        // Guess the MNC length based on the MCC if we don't
        // have a valid value in ef[ad]

        int mcc = Integer.parseInt(mImsi.substring(0,3));
        return mImsi.substring(0, 3 + MccTable.smallestDigitsMccForMnc(mcc));
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        byte data[];

        boolean isRecordLoadResponse = false;

        try { switch (msg.what) {
            case EVENT_RUIM_READY:
                onRuimReady();
            break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
            break;

            case EVENT_GET_DEVICE_IDENTITY_DONE:
                Log.d(LOG_TAG, "Event EVENT_GET_DEVICE_IDENTITY_DONE Received");
            break;

            /* IO events */

            case EVENT_GET_CDMA_SUBSCRIPTION_DONE:
                ar = (AsyncResult)msg.obj;
                String localTemp[] = (String[])ar.result;
                if (ar.exception != null) {
                    break;
                }

                mMyMobileNumber = localTemp[0];
                mMin2Min1 = localTemp[3];
                mPrlVersion = localTemp[4];

                Log.d(LOG_TAG, "MDN: " + mMyMobileNumber + " MIN: " + mMin2Min1);

            break;

            case EVENT_GET_ICCID_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                iccid = IccUtils.bcdToString(data, 0, data.length);

                Log.d(LOG_TAG, "iccid: " + iccid);

            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "RuimRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
            case EVENT_MARK_SMS_READ_DONE:
            case EVENT_SMS_ON_RUIM:
            case EVENT_GET_SMS_DONE:
                Log.w(LOG_TAG, "Event not supported: " + msg.what);
                break;

            // TODO: probably EF_CST should be read instead
            case EVENT_GET_SST_DONE:
                Log.d(LOG_TAG, "Event EVENT_GET_SST_DONE Received");
            break;

            case EVENT_RUIM_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleRuimRefresh((int[])(ar.result));
                }
            break;

            case EVENT_GET_IMSI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.e(LOG_TAG, "Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    Log.e(LOG_TAG, "invalid IMSI " + mImsi);
                    mImsi = null;
                }

                Log.d(LOG_TAG, "IMSI: " + mImsi.substring(0, 6) + "xxxxxxxxx");

                if (mncLength == UNKNOWN) {
                    // the SIM has told us all it knows, but it didn't know the mnc length.
                    // guess using the mcc
                    try {
                        int mcc = Integer.parseInt(mImsi.substring(0,3));
                        mncLength = MccTable.smallestDigitsMccForMnc(mcc);
                    } catch (NumberFormatException e) {
                        mncLength = UNKNOWN;
                        Log.e(LOG_TAG, "SIMRecords: Corrupt IMSI!");
                    }
                }
            break;

        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing RUIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            Log.e(LOG_TAG, "RuimRecords: recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
        String operator = getSIMOperatorNumeric();
        Log.d(LOG_TAG, "RuimRecords: record load complete   operator=" + operator);

        // Further records that can be inserted are Operator/OEM dependent

        recordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
        ((CDMAPhone) phone).mRuimCard.broadcastIccStateChangedIntent(
                RuimCard.INTENT_VALUE_ICC_LOADED, null);
        setSpnFromConfig(operator);
    }

    private void onRuimReady() {
        /* broadcast intent ICC_READY here so that we can make sure
          READY is sent before IMSI ready
        */

        ((CDMAPhone) phone).mRuimCard.broadcastIccStateChangedIntent(
                RuimCard.INTENT_VALUE_ICC_READY, null);

        fetchRuimRecords();

        phone.mCM.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_DONE));

    }


    private void fetchRuimRecords() {
        recordsRequested = true;

        Log.v(LOG_TAG, "RuimRecords:fetchRuimRecords " + recordsToLoad);

        phone.mCM.getIMSI(obtainMessage(3));
        recordsToLoad++;

        phone.getIccFileHandler().loadEFTransparent(EF_ICCID,
                obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        // Further records that can be inserted are Operator/OEM dependent
    }

    @Override
    protected int getDisplayRule(String plmn) {
        // TODO together with spn
        return 0;
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        if (line != 1) {
            // only profile 1 is supported
            return;
        }

        // range check
        if (countWaiting < 0) {
            countWaiting = -1;
        } else if (countWaiting > 0xff) {
            // C.S0015-B v2, 4.5.12
            // range: 0-99
            countWaiting = 0xff;
        }
        countVoiceMessages = countWaiting;

        ((CDMAPhone) phone).notifyMessageWaitingIndicator();
    }

    private void handleRuimRefresh(int[] result) {
        if (result == null || result.length == 0) {
            if (DBG) log("handleRuimRefresh without input");
            return;
        }

        switch ((result[0])) {
            case CommandsInterface.SIM_REFRESH_FILE_UPDATED:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_FILE_UPDATED");
                adnCache.reset();
                fetchRuimRecords();
                break;
            case CommandsInterface.SIM_REFRESH_INIT:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                fetchRuimRecords();
                break;
            case CommandsInterface.SIM_REFRESH_RESET:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_RESET");
                phone.mCM.setRadioPower(false, null);
                /* Note: no need to call setRadioPower(true).  Assuming the desired
                * radio power state is still ON (as tracked by ServiceStateTracker),
                * ServiceStateTracker will call setRadioPower when it receives the
                * RADIO_STATE_CHANGED notification for the power off.  And if the
                * desired power state has changed in the interim, we don't want to
                * override it with an unconditional power on.
                */
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleRuimRefresh with unknown operation");
                break;
        }
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[RuimRecords] " + s);
    }

}
