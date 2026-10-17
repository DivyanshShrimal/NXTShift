package com.nxtshift.app.excel;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.util.DateTimeUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Individual date entry in a nurse's extracted monthly schedule from the Excel workbook.
 * Encapsulates shift timing, colleagues in the same shift cell, and conflict validation.
 */
public class ExcelStaffScheduleItem implements Serializable {

    public enum StatusType {
        DUTY("Duty"),
        OFF("Weekly Off"),
        LEAVE("On Leave"),
        CONFLICT("Conflict Flagged"),
        UNASSIGNED("Unassigned");

        private final String label;
        StatusType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private String date; // yyyy-MM-dd
    private int dayNumber;
    private StatusType status = StatusType.UNASSIGNED;
    private ShiftType shiftType = ShiftType.GENERAL;
    private String shiftName = "";
    private String startTime = "";
    private String endTime = "";
    private boolean isOvernight = false;
    private String colleagues = "";
    private String wardOrDepartment = "Hospital";
    private final List<String> validationWarnings = new ArrayList<>();
    private boolean hasConflict = false;
    private boolean isSkipped = false;

    // List of candidate assignments if multiple were found on this date
    private final List<String> conflictingAssignments = new ArrayList<>();

    public ExcelStaffScheduleItem() {}

    public ExcelStaffScheduleItem(String date, int dayNumber) {
        this.date = date;
        this.dayNumber = dayNumber;
    }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public int getDayNumber() { return dayNumber; }
    public void setDayNumber(int dayNumber) { this.dayNumber = dayNumber; }

    public StatusType getStatus() { return status; }
    public void setStatus(StatusType status) { this.status = status; }

    public ShiftType getShiftType() { return shiftType; }
    public void setShiftType(ShiftType shiftType) { this.shiftType = shiftType; }

    public String getShiftName() { return shiftName; }
    public void setShiftName(String shiftName) { this.shiftName = shiftName; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public boolean isOvernight() { return isOvernight; }
    public void setOvernight(boolean overnight) { isOvernight = overnight; }

    public String getColleagues() { return colleagues; }
    public void setColleagues(String colleagues) { this.colleagues = colleagues != null ? colleagues : ""; }

    public String getWardOrDepartment() { return wardOrDepartment; }
    public void setWardOrDepartment(String wardOrDepartment) { this.wardOrDepartment = wardOrDepartment; }

    public List<String> getValidationWarnings() { return validationWarnings; }
    public void addWarning(String warning) {
        if (warning != null && !warning.trim().isEmpty() && !validationWarnings.contains(warning.trim())) {
            validationWarnings.add(warning.trim());
        }
    }

    public boolean hasConflict() { return hasConflict; }
    public void setHasConflict(boolean hasConflict) { this.hasConflict = hasConflict; }

    public boolean isSkipped() { return isSkipped; }
    public void setSkipped(boolean skipped) { isSkipped = skipped; }

    public List<String> getConflictingAssignments() { return conflictingAssignments; }
    public void addConflictingAssignment(String assignment) {
        if (assignment != null && !conflictingAssignments.contains(assignment)) {
            conflictingAssignments.add(assignment);
        }
    }

    public String getFormattedDutyText() {
        if (status == StatusType.OFF) return "Weekly Off";
        if (status == StatusType.LEAVE) return "On Leave";
        if (status == StatusType.UNASSIGNED) return "No Assignment";
        if (status == StatusType.CONFLICT) {
            return "Conflict: " + String.join(" vs ", conflictingAssignments);
        }
        if (startTime != null && !startTime.isEmpty() && endTime != null && !endTime.isEmpty()) {
            return (shiftName != null && !shiftName.isEmpty() ? shiftName + " (" : "") +
                    startTime + " – " + endTime + (isOvernight ? " +1d" : "") +
                    (shiftName != null && !shiftName.isEmpty() ? ")" : "");
        }
        return shiftName != null && !shiftName.isEmpty() ? shiftName : "Duty";
    }

    public String getCombinedWarnings() {
        if (validationWarnings.isEmpty()) return "";
        return String.join(" • ", validationWarnings);
    }

    /**
     * Converts to a valid NXTShift Duty domain object for Room database storage.
     */
    public Duty toDuty(String ownerUid) {
        Duty duty = new Duty();
        duty.setId(UUID.randomUUID().toString());
        duty.setOwnerUid(ownerUid);
        duty.setDutyDate(date);
        duty.setShiftType(shiftType != null ? shiftType : ShiftType.GENERAL);
        duty.setShiftName(shiftName != null && !shiftName.isEmpty() ? shiftName : duty.getShiftType().getDisplayName());
        duty.setStartTime(startTime != null && !startTime.isEmpty() ? startTime : duty.getShiftType().getDefaultStartTime());
        duty.setEndTime(endTime != null && !endTime.isEmpty() ? endTime : duty.getShiftType().getDefaultEndTime());
        duty.setOvernight(isOvernight);
        duty.setColleagues(colleagues != null ? colleagues : "");
        duty.setWardOrDepartment(wardOrDepartment != null ? wardOrDepartment : "Hospital");
        duty.setNotes("Imported from Excel Roster");

        if (status == StatusType.OFF || shiftType == ShiftType.OFF) {
            duty.setStatus(DutyStatus.OFF);
            duty.setShiftType(ShiftType.OFF);
        } else if (status == StatusType.LEAVE || shiftType == ShiftType.LEAVE) {
            duty.setStatus(DutyStatus.LEAVE);
            duty.setShiftType(ShiftType.LEAVE);
        } else {
            duty.setStatus(DutyStatus.SCHEDULED);
        }

        return duty;
    }
}
