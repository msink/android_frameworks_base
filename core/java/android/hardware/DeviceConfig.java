package android.hardware;

import android.content.Context;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DeviceConfig {

    public static final String FRONT_LIGHT_VALUES = "front_light_values";
    public static final String STATUS_BAR_HIDE_ID = "status_bar_hide_id";

    private JSONObject mJSONObject;

    private static DeviceConfig globalInstance;

    public static DeviceConfig sharedInstance(Context context) {
        if (globalInstance == null) {
            globalInstance = new DeviceConfig(context);
        }
        return globalInstance;
    }

    private DeviceConfig(Context context) {
        try {
            String model = Build.MODEL;
            int res = context.getResources().getIdentifier(model.toLowerCase(), "raw", "android");
            mJSONObject = RawResourceUtil.objectFromRawResource(context, res);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (mJSONObject == null) {
                int res = context.getResources().getIdentifier("onyx", "raw", "android");
                mJSONObject = RawResourceUtil.objectFromRawResource(context, res);
            }
        }
    }

    public List<String> getStatusBarHideIdList() {
        List<String> hideIDList = null;
        try {
            JSONArray jsonArray = mJSONObject.getJSONArray(STATUS_BAR_HIDE_ID);
            hideIDList = new ArrayList();
            for (int i = 0; i < jsonArray.length(); i++) {
                hideIDList.add(String.valueOf(jsonArray.get(i)));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return hideIDList;
    }

    public List<Integer> getFrontLightValues() {
        List<Integer> values = null;
        try {
            JSONArray jsonArray = mJSONObject.getJSONArray(FRONT_LIGHT_VALUES);
            values = new ArrayList();
            for (int i = 0; i < jsonArray.length(); i++) {
                values.add(Integer.valueOf(String.valueOf(jsonArray.get(i))));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return values;
    }
}
