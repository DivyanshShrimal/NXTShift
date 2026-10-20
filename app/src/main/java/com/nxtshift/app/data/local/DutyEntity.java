package com.nxtshift.app.data.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.domain.SyncStatus;

import java.util.Locale;

/**
 * Room entity representing an isolated duty record stored locally on the device.
 * Indexed by ownerUid and dutyDate for immediate access and multi-account isolation.
 */
@Entity(
        tableName = "duties",
        indices = {
                @Index(value = {"ownerUid", "dutyDate"}),
                @Index(value = {"ownerUid"}),
                @Index(value = {"dutyDate"})
        }
)
public class DutyEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    private String id;

    @NonNull
    @ColumnInfo(name = "ownerUid")
    private String ownerUid;

    @NonNull
    @ColumnInfo(name = "dutyDate")
    private String dutyDate; // Format: yyyy-MM-dd

    @NonNull
    @ColumnInfo(name = "shiftType")
    private String shiftType; // Enum name

    @ColumnInfo(name = "shiftName")
    private String shiftName;

    @ColumnInfo(name = "startTime")
    private String startTime;

    @ColumnInfo(name = "endTime")
    private String endTime;

    @ColumnInfo(name = "isOvernight")
    private boolean isOvernight;

    @ColumnInfo(name = "colleagues")
    private String colleagues;

    @ColumnInfo(name = "colleagueRoles")
    private String colleagueRoles;

    @ColumnInfo(name = "wardOrDepartment")
    private String wardOrDepartment;

    @ColumnInfo(name = "notes")
    private String notes;

    @NonNull
    @ColumnInfo(name = "status")
    private String status;

    @ColumnInfo(name = "createdAt")
    private long createdAt;

    @ColumnInfo(name = "updatedAt")
    private long updatedAt;

    @NonNull
    @ColumnInfo(name = "syncStatus")
    private String syncStatus;

    public DutyEntity(@NonNull String id, @NonNull String ownerUid, @NonNull String dutyDate,
                      @NonNull String shiftType, String shiftName, String startTime,
                      String endTime, boolean isOvernight, String colleagues,
                      String colleagueRoles, String wardOrDepartment, String notes,
                      @NonNull String status, long createdAt, long updatedAt,
                      @NonNull String syncStatus) {
        this.id = id;
        this.ownerUid = ownerUid;
        this.dutyDate = dutyDate;
        this.shiftType = shiftType;
        this.shiftName = shiftName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.isOvernight = isOvernight;
        this.colleagues = colleagues;
        this.colleagueRoles = colleagueRoles;
        this.wardOrDepartment = wardOrDepartment;
        this.notes = notes;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.syncStatus = syncStatus;
    }

    public static DutyEntity fromDomain(Duty duty, String ownerUid) {
        return new DutyEntity(
                duty.getId(),
                ownerUid,
                duty.getDutyDate(),
                duty.getShiftType().name(),
                duty.getShiftName(),
                duty.getStartTime(),
                duty.getEndTime(),
                duty.isOvernight(),
                duty.getColleagues(),
                duty.getColleagueRoles(),
                duty.getWardOrDepartment(),
                duty.getNotes(),
                duty.getStatus().name(),
                duty.getCreatedAt(),
                duty.getUpdatedAt(),
                duty.getSyncStatus().name()
        );
    }

    public Duty toDomain() {
        ShiftType type;
        try {
            type = ShiftType.valueOf(shiftType);
        } catch (Exception e) {
            type = ShiftType.MORNING;
        }

        DutyStatus dutyStatus;
        try {
            dutyStatus = DutyStatus.valueOf(status);
        } catch (Exception e) {
            dutyStatus = DutyStatus.SCHEDULED;
        }

        SyncStatus sync;
        try {
            sync = SyncStatus.valueOf(syncStatus);
        } catch (Exception e) {
            sync = SyncStatus.SYNCED;
        }

        return new Duty(
                id,
                ownerUid,
                dutyDate,
                type,
                shiftName,
                startTime,
                endTime,
                isOvernight,
                colleagues,
                colleagueRoles,
                wardOrDepartment,
                notes,
                dutyStatus,
                createdAt,
                updatedAt,
                sync
        );
    }

    // Getters and Setters
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
    public String getDutyDate() {
        return dutyDate;
    }

    public void setDutyDate(@NonNull String dutyDate) {
        this.dutyDate = dutyDate;
    }

    @NonNull
    public String getShiftType() {
        return shiftType;
    }

    public void setShiftType(@NonNull String shiftType) {
        this.shiftType = shiftType;
    }

    public String getShiftName() {
        return shiftName;
    }

    public void setShiftName(String shiftName) {
        this.shiftName = shiftName;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public boolean isOvernight() {
        return isOvernight;
    }

    public void setOvernight(boolean overnight) {
        isOvernight = overnight;
    }

    public String getColleagues() {
        return colleagues;
    }

    public void setColleagues(String colleagues) {
        this.colleagues = colleagues;
    }

    public String getColleagueRoles() {
        return colleagueRoles;
    }

    public void setColleagueRoles(String colleagueRoles) {
        this.colleagueRoles = colleagueRoles;
    }

    public String getWardOrDepartment() {
        return wardOrDepartment;
    }

    public void setWardOrDepartment(String wardOrDepartment) {
        this.wardOrDepartment = wardOrDepartment;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    @NonNull
    public String getStatus() {
        return status;
    }

    public void setStatus(@NonNull String status) {
        this.status = status;
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

    public static boolean isRosterGenerated(String notes) {
        if (notes == null || notes.trim().isEmpty()) {
            return false;
        }
        String lower = notes.toLowerCase(Locale.US);
        return lower.contains("imported") || lower.contains("published roster")
                || lower.contains("unrecognized shift timing") || lower.contains("roster");
    }

    public boolean isRosterGenerated() {
        return isRosterGenerated(notes);
    }
}
