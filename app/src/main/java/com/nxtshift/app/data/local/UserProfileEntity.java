package com.nxtshift.app.data.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity representing local cached profile of the signed-in user.
 */
@Entity(tableName = "user_profiles")
public class UserProfileEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "uid")
    private String uid;

    @ColumnInfo(name = "email")
    private String email;          // Primary identifier — required

    @ColumnInfo(name = "phoneNumber")
    private String phoneNumber;    // Optional profile field only

    @ColumnInfo(name = "displayName")
    private String displayName;

    @ColumnInfo(name = "hospitalName")
    private String hospitalName;

    @ColumnInfo(name = "defaultWard")
    private String defaultWard;

    @ColumnInfo(name = "createdAt")
    private long createdAt;

    @ColumnInfo(name = "updatedAt")
    private long updatedAt;

    public UserProfileEntity(@NonNull String uid, String email, String phoneNumber,
                             String displayName, String hospitalName, String defaultWard,
                             long createdAt, long updatedAt) {
        this.uid = uid;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.displayName = displayName;
        this.hospitalName = hospitalName;
        this.defaultWard = defaultWard;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @NonNull
    public String getUid() {
        return uid;
    }

    public void setUid(@NonNull String uid) {
        this.uid = uid;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getHospitalName() {
        return hospitalName;
    }

    public void setHospitalName(String hospitalName) {
        this.hospitalName = hospitalName;
    }

    public String getDefaultWard() {
        return defaultWard;
    }

    public void setDefaultWard(String defaultWard) {
        this.defaultWard = defaultWard;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
