package com.nxtshift.app.domain;

public enum SyncStatus {
    SYNCED("Synced"),
    PENDING_UPLOAD("Pending Sync"),
    SYNC_ERROR("Sync Error");

    private final String description;

    SyncStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
