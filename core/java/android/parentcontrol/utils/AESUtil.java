package android.parentcontrol.utils;

import android.util.Base64;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class AESUtil {
    private static final String AES_STR = "AES";
    private static final int AES_LENGTH = 256;
    private static final String KEY_STORE = "XL3SVnAIpKyMhSuhm5E2vLeiv8m9eap";
    private static final String CHAR_CODE_UTF8 = "UTF8";

    public static String encryptStr(String plainText) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(AES_STR);
            keyGenerator.init(AES_LENGTH, new SecureRandom(KEY_STORE.getBytes()));
            SecretKey secretKey = keyGenerator.generateKey();
            byte[] enCodeFormat = secretKey.getEncoded();
            SecretKeySpec secretKeySpec = new SecretKeySpec(enCodeFormat, AES_STR);
            Cipher cipher = Cipher.getInstance(AES_STR);
            byte[] byteContent = plainText.getBytes(CHAR_CODE_UTF8);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
            byte[] resultByte = cipher.doFinal(byteContent);
            resultByte = Base64.encode(resultByte, Base64.DEFAULT);
            return new String(resultByte, CHAR_CODE_UTF8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String decryptStr(String cipherText) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(AES_STR);
            keyGenerator.init(AES_LENGTH, new SecureRandom(KEY_STORE.getBytes()));
            SecretKey secretKey = keyGenerator.generateKey();
            byte[] enCodeFormat = secretKey.getEncoded();
            SecretKeySpec secretKeySpec = new SecretKeySpec(enCodeFormat, AES_STR);
            Cipher cipher = Cipher.getInstance(AES_STR);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
            byte[] resultByte = Base64.decode(cipherText, Base64.DEFAULT);
            resultByte = cipher.doFinal(resultByte);
            return new String(resultByte, CHAR_CODE_UTF8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
