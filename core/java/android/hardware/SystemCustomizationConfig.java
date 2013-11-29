package android.hardware;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class SystemCustomizationConfig {

    private static final String CONFIG_PATH = "/system/system_customization.conf";
    private static final String TAG_DISABLE_OTA_UPDATE = "DISABLE_OTA_UPDATE";
    private static final String DEFAULT_DISABLE_OTA_UPDATE = "0";
    private static final String TAG_TTS_LANG_CANDIDATES = "TTS_LANG_CANDIDATES";
    private static final String DEFAULT_TTS_LANG_CANDIDATES = "eng";
    private static final String TAG_DEFAULT_TTS_LANG = "DEFAULT_TTS_LANG";
    private static final String DEFAULT_DEFAULT_TTS_LANG = "eng";
    private static final String TAG_DEFAULT_TTS_COUNTRY = "DEFAULT_TTS_COUNTRY";
    private static final String DEFAULT_DEFAULT_TTS_COUNTRY = "GBR";

    private Properties properties = null;
    private static SystemCustomizationConfig sInstance = null;

    public static SystemCustomizationConfig singleton() {
        if (sInstance == null) {
            sInstance = new SystemCustomizationConfig();
        }
        return sInstance;
    }

    private Properties getProperties() throws IOException {
        if (properties == null) {
            properties = getSystemCustomizationConfig();
        }
        return properties;
    }

    private Properties getSystemCustomizationConfig() throws IOException {
        Properties config = new Properties();
        FileInputStream in = new FileInputStream(CONFIG_PATH);
        config.load(in);
        in.close();
        return config;
    }

    public boolean disableOtaUpdate() {
        String disableOtaUpdate = DEFAULT_DISABLE_OTA_UPDATE;
        try {
            String value = getProperties().getProperty
                    (TAG_DISABLE_OTA_UPDATE, DEFAULT_DISABLE_OTA_UPDATE);
            if (!value.isEmpty()) disableOtaUpdate = value;
        } catch (IOException e) { }
        return (Integer.parseInt(disableOtaUpdate) > 0);
    }

    public String[] ttsLangCandidates() {
        String ttsLangCandidates = DEFAULT_TTS_LANG_CANDIDATES;
        try {
            String value = getProperties().getProperty
                    (TAG_TTS_LANG_CANDIDATES, DEFAULT_TTS_LANG_CANDIDATES);
            if (!value.isEmpty()) ttsLangCandidates = value;
        } catch (IOException e) { }
        return ttsLangCandidates.split("[,;]");
    }

    public String defaultTtsLangISO3()
    {
        String langISO3 = DEFAULT_DEFAULT_TTS_LANG;
        try {
            String value = getProperties().getProperty
                    (TAG_DEFAULT_TTS_LANG, DEFAULT_DEFAULT_TTS_LANG);
            if (!value.isEmpty()) langISO3 = value;
        } catch (IOException e) { }
        return langISO3;
    }

    public String defaultTtsCountryISO3() {
        String countryISO3 = DEFAULT_DEFAULT_TTS_COUNTRY;
        try {
            String value = getProperties().getProperty
                    (TAG_DEFAULT_TTS_COUNTRY, DEFAULT_DEFAULT_TTS_COUNTRY);
            if (!value.isEmpty()) countryISO3 = value;
        } catch (IOException e) { }
        return countryISO3;
    }
}
