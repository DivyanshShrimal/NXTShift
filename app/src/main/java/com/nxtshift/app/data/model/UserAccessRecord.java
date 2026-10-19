package com.nxtshift.app.data.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Data model for individual user access control stored at /app_access/{uid}.
 */
public class UserAccessRecord {
    private String uid;
    private boolean enabled;
    private String reason;
    private long updatedAt;
    private String updatedByUid;

    public UserAccessRecord() {
        // Required for Firestore deserialization
        this.enabled = true;
        this.reason = "";
        this.updatedAt = System.currentTimeMillis();
        this.updatedByUid = "";
    }

    public UserAccessRecord(String uid, boolean enabled, String reason, long updatedAt, String updatedByUid) {
        this.uid = uid;
        this.enabled = enabled;
        this.reason = reason != null ? reason : "";
        this.updatedAt = updatedAt;
        this.updatedByUid = updatedByUid != null ? updatedByUid : "";
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("uid", uid);
        map.put("enabled", enabled);
        map.put("reason", reason != null ? reason : "");
        map.put("updatedAt", updatedAt);
        map.put("updatedByUid", updatedByUid != null ? updatedByUid : "");
        return map;
    }

    public static UserAccessRecord fromMap(String uid, Map<String, Object> map) {
        if (map == null) return null;
        UserAccessRecord record = new UserAccessRecord();
        record.setUid(uid);

        Object enabledObj = map.get("enabled");
        if (enabledObj instanceof Boolean) {
            record.setEnabled((Boolean) enabledObj);
        } else {
            // Missing or invalid enabled field -> fail-closed
            record.setEnabled(false);
        }

        Object reasonObj = map.get("reason");
        if (reasonObj instanceof String) {
            record.setReason((String) reasonObj);
        }

        Object updatedObj = map.get("updatedAt");
        if (updatedObj instanceof Number) {
            record.setUpdatedAt(((Number) updatedObj).longValue());
        } else if (updatedObj instanceof com.google.firebase.Timestamp) {
            record.setUpdatedAt(((com.google.firebase.Timestamp) updatedObj).toDate().getTime());
        }

        Object updatedByObj = map.get("updatedByUid");
        if (updatedByObj instanceof String) {
            record.setUpdatedByUid((String) updatedByObj);
        }

        return record;
    }
}
