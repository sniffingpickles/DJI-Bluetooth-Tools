package tools.dji.viewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialStore {
    static final class Credentials {
        final String ssid;
        final String password;

        Credentials(String ssid, String password) {
            this.ssid = ssid;
            this.password = password;
        }
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dji-viewer-wifi-v1";
    private static final String PREFS = "secure_wifi";
    private static final String PREF_SSID = "ssid";
    private static final String PREF_SECRET = "secret";
    private static final String PREF_IV = "iv";

    private final SharedPreferences preferences;

    CredentialStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Credentials load() {
        String ssid = preferences.getString(PREF_SSID, null);
        String encrypted = preferences.getString(PREF_SECRET, null);
        String iv = preferences.getString(PREF_IV, null);
        if (ssid == null || encrypted == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(iv)));
            byte[] clear = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            return new Credentials(ssid, new String(clear, StandardCharsets.UTF_8));
        } catch (Exception error) {
            clear();
            return null;
        }
    }

    void save(String ssid, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(PREF_SSID, ssid)
                .putString(PREF_SECRET, Base64.getEncoder().encodeToString(encrypted))
                .putString(PREF_IV, Base64.getEncoder().encodeToString(cipher.getIV()))
                .apply();
    }

    void clear() {
        preferences.edit().clear().apply();
    }

    private SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
