package com.nxtshift.app.domain;

import androidx.annotation.NonNull;
import com.nxtshift.app.util.DateTimeUtils;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain model representing an individual duty shift.
 */
public class Duty implements Serializable {

    private String id;
    private String ownerUid;
    private String dutyDate; // Format: yyyy-MM-dd
    private ShiftType shiftType;
    private String shiftName;
    private String startTime; // Format: hh:mm a
    private String endTime;   // Format: hh:mm a
    private boolean isOvernight;
    private String colleagues;
    private String colleagueRoles;
    private String wardOrDepartment;
    private String notes;
    private DutyStatus status;
    private long createdAt;
    private long updatedAt;
    private SyncStatus syncStatus;

    public Duty() {
        this.id = UUID.randomUUID().toString();
        this.shiftType = ShiftType.MORNING;
        this.shiftName = ShiftType.MORNING.getDisplayName();
        this.startTime = ShiftType.MORNING.getDefaultStartTime();
        this.endTime = ShiftType.MORNING.getDefaultEndTime();
        this.isOvernight = ShiftType.MORNING.isDefaultOvernight();
        this.status = DutyStatus.SCHEDULED;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.syncStatus = SyncStatus.PENDING_UPLOAD;
    }

    public Duty(String id, String ownerUid, String dutyDate, ShiftType shiftType,
                String shiftName, String startTime, String endTime, boolean isOvernight,
                String colleagues, String colleagueRoles, String wardOrDepartment,
                String notes, DutyStatus status, long createdAt, long updatedAt,
                SyncStatus syncStatus) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.ownerUid = ownerUid;
        this.dutyDate = dutyDate;
        this.shiftType = shiftType != null ? shiftType : ShiftType.MORNING;
        this.shiftName = shiftName != null ? shiftName : this.shiftType.getDisplayName();
        this.startTime = startTime;
        this.endTime = endTime;
        this.isOvernight = isOvernight;
        this.colleagues = colleagues;
        this.colleagueRoles = colleagueRoles;
        this.wardOrDepartment = wardOrDepartment;
        this.notes = notes;
        this.status = status != null ? status : DutyStatus.SCHEDULED;
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
        this.syncStatus = syncStatus != null ? syncStatus : SyncStatus.PENDING_UPLOAD;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerUid() {
        return ownerUid;
    }

    public void setOwnerUid(String ownerUid) {
        this.ownerUid = ownerUid;
    }

    public String getDutyDate() {
        return dutyDate;
    }

    public void setDutyDate(String dutyDate) {
        this.dutyDate = dutyDate;
    }

    public ShiftType getShiftType() {
        return shiftType;
    }

    public void setShiftType(ShiftType shiftType) {
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

    public DutyStatus getStatus() {
        return status;
    }

    public void setStatus(DutyStatus status) {
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

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(SyncStatus syncStatus) {
        this.syncStatus = syncStatus;
    }

    public boolean isOffOrLeave() {
        return shiftType == ShiftType.OFF || shiftType == ShiftType.LEAVE ||
               status == DutyStatus.OFF || status == DutyStatus.LEAVE;
    }

    public String getFormattedDutyTime() {
        if (isOffOrLeave()) {
            return shiftType.getDisplayName();
        }
        if (startTime == null || startTime.isEmpty()) {
            return "Time unassigned";
        }
        String timeStr = startTime + (endTime != null && !endTime.isEmpty() ? " – " + endTime : "");
        if (isOvernight) {
            timeStr += " (+1 day)";
        }
        return timeStr;
    }

    public double getDurationHours() {
        if (isOffOrLeave()) return 0.0;
        return DateTimeUtils.calculateShiftDurationHours(startTime, endTime, isOvernight);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Duty duty = (Duty) o;
        return Objects.equals(id, duty.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @NonNull
    @Override
    public String toString() {
        return "Duty{" +
                "id='" + id + '\'' +
                ", date='" + dutyDate + '\'' +
                ", shift=" + shiftType +
                ", time='" + getFormattedDutyTime() + '\'' +
                ", ward='" + wardOrDepartment + '\'' +
                '}';
    }
}
