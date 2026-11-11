package com.masheeds.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class DiscoverTokenStore {
    private static final String KEY_ALIAS = "masheeds.discover.session";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final int IV_SIZE = 12;

    private final SharedPreferences preferences;
    private final byte[] project;

    DiscoverTokenStore(Context context, String projectUrl) {
        preferences = context.getSharedPreferences("discover_session", Context.MODE_PRIVATE);
        project = projectUrl.getBytes(StandardCharsets.UTF_8);
    }

    String read() {
        String saved = preferences.getString(REFRESH_TOKEN, null);
        if (saved == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(saved, Base64.NO_WRAP);
            if (bytes.length <= IV_SIZE) {
                throw new GeneralSecurityException("Invalid encrypted session");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(bytes, IV_SIZE)));
            cipher.updateAAD(project);
            return new String(cipher.doFinal(bytes, IV_SIZE, bytes.length - IV_SIZE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            clear();
            return null;
        }
    }

    void save(String token) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(project);
        byte[] iv = cipher.getIV();
        if (iv.length != IV_SIZE) {
            throw new GeneralSecurityException("Unexpected session IV length");
        }
        byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = Arrays.copyOf(iv, IV_SIZE + ciphertext.length);
        System.arraycopy(ciphertext, 0, bytes, IV_SIZE, ciphertext.length);
        if (!preferences.edit().putString(REFRESH_TOKEN, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            throw new IOException("Could not save the online session");
        }
    }

    void clear() {
        preferences.edit().remove(REFRESH_TOKEN).apply();
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(KEY_ALIAS, null) instanceof SecretKey existing) {
            return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
