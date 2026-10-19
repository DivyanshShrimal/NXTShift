package com.nxtshift.app.data.model;

/**
 * Represents the current remote access state evaluated by the AccessControlRepository.
 */
public enum AccessState {
    /**
     * Remote access is fully authorized. Cloud synchronization and online features are permitted.
     */
    ALLOWED,

    /**
     * Application access is globally disabled (maintenance mode or emergency shutdown).
     * Cloud mutations are blocked. Offline calendar viewing remains available.
     */
    GLOBAL_DISABLED,

    /**
     * The specific user's access has been individually revoked by an administrator.
     * Cloud mutations are blocked. Offline calendar viewing remains available.
     */
    USER_REVOKED,

    /**
     * Device is offline and access state cannot be verified against the cloud.
     * Cloud mutations are held/rejected; local Room database remains accessible.
     */
    OFFLINE_UNVERIFIED,

    /**
     * Failed to evaluate access due to permission denied or unexpected error. Fail closed.
     */
    ERROR
}
