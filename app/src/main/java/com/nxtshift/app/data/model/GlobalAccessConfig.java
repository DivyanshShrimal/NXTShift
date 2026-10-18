package com.nxtshift.app.data.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Data model for global application access control stored at /app_control/global.
 */
public class GlobalAccessConfig {
    private boolean enabled;
    private String message;
    private long updatedAt;
    private String updatedByUid;
    private int minimumSupportedVersion;

    public GlobalAccessConfig() {
        // Required for Firestore deserialization
        this.enabled = true;
        this.message = "";
        this.updatedAt = System.currentTimeMillis();
        this.updatedByUid = "";
        this.minimumSupportedVersion = 1;
    }

    public GlobalAccessConfig(boolean enabled, String message, long updatedAt, String updatedByUid, int minimumSupportedVersion) {
        this.enabled = enabled;
        this.message = message != null ? message : "";
        this.updatedAt = updatedAt;
        this.updatedByUid = updatedByUid != null ? updatedByUid : "";
        this.minimumSupportedVersion = minimumSupportedVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedByUid() {
        return updatedByUid;
    }

    public void setUpdatedByUid(String updatedByUid) {
        this.updatedByUid = updatedByUid;
    }

    public int getMinimumSupportedVersion() {
        return minimumSupportedVersion;
    }

    public void setMinimumSupportedVersion(int minimumSupportedVersion) {
        this.minimumSupportedVersion = minimumSupportedVersion;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", enabled);
        map.put("message", message != null ? message : "");
        map.put("updatedAt", updatedAt);
        map.put("updatedByUid", updatedByUid != null ? updatedByUid : "");
        map.put("minimumSupportedVersion", minimumSupportedVersion);
        return map;
    }

    public static GlobalAccessConfig fromMap(Map<String, Object> map) {
        if (map == null) return null;
        GlobalAccessConfig config = new GlobalAccessConfig();

        Object enabledObj = map.get("enabled");
        if (enabledObj instanceof Boolean) {
            config.setEnabled((Boolean) enabledObj);
        } else {
            // Missing or invalid enabled field -> fail-closed
            config.setEnabled(false);
        }

        Object msgObj = map.get("message");
        if (msgObj instanceof String) {
            config.setMessage((String) msgObj);
        }

        Object updatedObj = map.get("updatedAt");
        if (updatedObj instanceof Number) {
            config.setUpdatedAt(((Number) updatedObj).longValue());
        }

        Object updatedByObj = map.get("updatedByUid");
        if (updatedByObj instanceof String) {
            config.setUpdatedByUid((String) updatedByObj);
        }

        Object minVerObj = map.get("minimumSupportedVersion");
        if (minVerObj instanceof Number) {
            config.setMinimumSupportedVersion(((Number) minVerObj).intValue());
        }

        return config;
    }
}
