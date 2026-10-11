package com.clawuse.android;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONObject;

/**
 * Encrypted storage for sensitive config (PIN, etc).
 * Uses AndroidX EncryptedSharedPreferences with AES256.
 */
public class ConfigStore {
    private static final String PREFS_NAME = "claw_use_config_encrypted";
    private static final String KEY_PIN = "device_pin";
    private static final String KEY_UNLOCK_TYPE = "unlock_type";  // "pin", "password", "pattern"
    private static final String KEY_PATTERN = "device_pattern";   // pattern as "2,5,6,3,9,8"
    private static final String KEY_SCREEN_TIMEOUT = "screen_timeout_sec";
    private static final String KEY_BRIDGE_ENABLED = "bridge_enabled";
    private static final String KEY_PERMISSIONS_ASKED = "permissions_asked";

    private static volatile ConfigStore instance;
    private SharedPreferences prefs;

    public static ConfigStore get(Context context) {
        if (instance == null) {
            synchronized (ConfigStore.class) {
                if (instance == null) {
                    instance = new ConfigStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ConfigStore(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            prefs = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallback to regular SharedPreferences if encryption fails
            prefs = context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
    }

    public void setPin(String pin) {
        prefs.edit().putString(KEY_PIN, pin).apply();
    }

    public String getPin() {
        return prefs.getString(KEY_PIN, null);
    }

    public boolean hasPin() {
        return getPin() != null && !getPin().isEmpty();
    }

    public void clearPin() {
        prefs.edit().remove(KEY_PIN).apply();
    }

    // Pattern unlock support
    public void setUnlockType(String type) {
        prefs.edit().putString(KEY_UNLOCK_TYPE, type).apply();
    }

    public String getUnlockType() {
        // Auto-detect: if pattern is set, it's pattern; if pin is set, it's pin
        String type = prefs.getString(KEY_UNLOCK_TYPE, null);
        if (type != null) return type;
        if (hasPattern()) return "pattern";
        if (hasPin()) return "pin";
        return null;
    }

    public void setPattern(String pattern) {
        prefs.edit().putString(KEY_PATTERN, pattern).apply();
        prefs.edit().putString(KEY_UNLOCK_TYPE, "pattern").apply();
    }

    public String getPattern() {
        return prefs.getString(KEY_PATTERN, null);
    }

    public boolean hasPattern() {
        String p = getPattern();
        return p != null && !p.isEmpty();
    }

    public boolean hasUnlockCredential() {
        return hasPin() || hasPattern();
    }

    public void setScreenTimeout(int seconds) {
        prefs.edit().putInt(KEY_SCREEN_TIMEOUT, seconds).apply();
    }

    public int getScreenTimeout() {
        return prefs.getInt(KEY_SCREEN_TIMEOUT, 30);
    }

    // ── Bridge on/off state ─────────────────────────────────────

    /** Whether the user wants the HTTP bridge running. Default: on (first launch). */
    public boolean isBridgeEnabled() {
        return prefs.getBoolean(KEY_BRIDGE_ENABLED, true);
    }

    public void setBridgeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply();
    }

    // ── Permission onboarding ───────────────────────────────────

    /** Whether the batch permission prompt already ran once (avoid nagging on every launch). */
    public boolean werePermissionsAsked() {
        return prefs.getBoolean(KEY_PERMISSIONS_ASKED, false);
    }

    public void setPermissionsAsked(boolean asked) {
        prefs.edit().putBoolean(KEY_PERMISSIONS_ASKED, asked).apply();
    }

    public JSONObject toJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("hasPin", hasPin());
            j.put("hasPattern", hasPattern());
            j.put("unlockType", getUnlockType());
            j.put("screenTimeout", getScreenTimeout());
            j.put("bridgeEnabled", isBridgeEnabled());
            return j;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
