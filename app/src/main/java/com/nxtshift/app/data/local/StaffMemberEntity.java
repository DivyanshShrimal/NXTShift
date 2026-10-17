package com.nxtshift.app.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Local Room Entity for staff members discovered automatically from hospital duty rosters.
 * Strictly isolated per user UID.
 */
@Entity(tableName = "staff_members", indices = {
        @Index("ownerUid"),
        @Index("normalizedName")
})
public class StaffMemberEntity implements Serializable {

    @PrimaryKey
    @NonNull
    private String id;

    @NonNull
    private String ownerUid;

    @NonNull
    private String name;

    @NonNull
    private String normalizedName;

    private String aliases;      // Comma-separated list of confirmed aliases / OCR variants
    private String department;
    private String role;
    private int dutyCount;

    private long createdAt;
    private long updatedAt;

    @NonNull
    private String syncStatus;

    public StaffMemberEntity(@NonNull String id, @NonNull String ownerUid, @NonNull String name,
                             @NonNull String normalizedName, String aliases, String department,
                             String role, int dutyCount, long createdAt, long updatedAt,
                             @NonNull String syncStatus) {
        this.id = id;
        this.ownerUid = ownerUid;
        this.name = name;
        this.normalizedName = normalizedName;
        this.aliases = aliases;
        this.department = department;
        this.role = role;
        this.dutyCount = dutyCount;
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
        this.syncStatus = syncStatus;
    }

    @androidx.room.Ignore
    public StaffMemberEntity(@NonNull String ownerUid, @NonNull String name,
                             @NonNull String normalizedName, String department,
                             String aliases, int dutyCount, long createdAt, long updatedAt) {
        this(UUID.randomUUID().toString(), ownerUid, name, normalizedName, aliases,
                department != null ? department : "", "Nurse / Staff", dutyCount,
                createdAt > 0 ? createdAt : System.currentTimeMillis(),
                updatedAt > 0 ? updatedAt : System.currentTimeMillis(), "PENDING_UPLOAD");
    }

    public static StaffMemberEntity createNew(@NonNull String ownerUid, @NonNull String name, String department, int dutyCount) {
        String cleanName = name.trim();
        String normalized = cleanName.toLowerCase().replaceAll("[^a-z0-9]", "");
        return new StaffMemberEntity(
                UUID.randomUUID().toString(),
                ownerUid,
                cleanName,
                normalized,
                "",
                department != null ? department : "",
                "Nurse / Staff",
                dutyCount,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "PENDING_UPLOAD"
        );
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    @NonNull
    public String getOwnerUid() {
        return ownerUid;
    }

    public void setOwnerUid(@NonNull String ownerUid) {
        this.ownerUid = ownerUid;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    @NonNull
    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(@NonNull String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public String getAliases() {
        return aliases;
    }

    public void setAliases(String aliases) {
        this.aliases = aliases;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public int getDutyCount() {
        return dutyCount;
    }

    public void setDutyCount(int dutyCount) {
        this.dutyCount = dutyCount;
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

    @NonNull
    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(@NonNull String syncStatus) {
        this.syncStatus = syncStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StaffMemberEntity that = (StaffMemberEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
