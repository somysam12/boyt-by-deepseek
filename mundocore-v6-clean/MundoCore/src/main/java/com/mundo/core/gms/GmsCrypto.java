package com.mundo.core.gms;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM encryption of stored credentials with a non-exportable key kept in
 * AndroidKeyStore (API 23+). Values are stored as "k1:" + base64(iv || ciphertext).
 *
 * On API 21/22 AndroidKeyStore has no symmetric keys; values are then stored with
 * a "p0:" prefix (app-private storage only). Decrypt understands both prefixes so
 * an OS upgrade does not lose accounts.
 */
final class GmsCrypto {

    private static final String TAG = "GmsCrypto";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "mundo_gms_account_key_v1";
    private static final String PREFIX_KS = "k1:";
    private static final String PREFIX_PLAIN = "p0:";
    private static final int IV_LEN = 12;

    private static SecretKey sKey;

    private GmsCrypto() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 23;
    }

    private static synchronized SecretKey key() throws Exception {
        if (sKey != null) return sKey;
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            sKey = ((KeyStore.SecretKeyEntry) e).getSecretKey();
            return sKey;
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        sKey = kg.generateKey();
        return sKey;
    }

    static String encrypt(String plain) {
        if (plain == null) return null;
        if (!supported()) return PREFIX_PLAIN + plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX_KS + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            // Never fall back to plaintext silently on a device that supports the keystore.
            throw new IllegalStateException("Keystore encryption failed", e);
        }
    }

    static String decrypt(String stored) {
        if (stored == null) return null;
        if (stored.startsWith(PREFIX_PLAIN)) return stored.substring(PREFIX_PLAIN.length());
        if (!stored.startsWith(PREFIX_KS)) return null;
        try {
            byte[] in = Base64.decode(stored.substring(PREFIX_KS.length()), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 0, IV_LEN));
            byte[] pt = c.doFinal(in, IV_LEN, in.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "decrypt failed (key lost or data corrupted): " + e.getClass().getSimpleName());
            return null;
        }
    }
}
