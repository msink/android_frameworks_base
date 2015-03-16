package android.hardware;

import android.content.Context;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

public class RawResourceUtil {

    public static String contentOfRawResource(Context context, int rawResourceId) {
        BufferedReader bufferedReader = null;
        InputStream inputStream = null;
        try {
            inputStream = context.getResources().openRawResource(rawResourceId);
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder stringBuilder = new StringBuilder();
            String line = null;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeQuietly(bufferedReader);
            closeQuietly(inputStream);
        }
        return null;
    }

    public static JSONObject objectFromRawResource(Context context, int rawResourceId) {
        String content = contentOfRawResource(context, rawResourceId);
        try {
            return new JSONObject(content);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
