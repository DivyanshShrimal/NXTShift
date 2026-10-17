package com.nxtshift.app.excel;

import com.nxtshift.app.domain.Duty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the entire month's extracted schedule for a specific selected nurse.
 */
public class ExcelStaffSchedule implements Serializable {
    private String staffName;
    private int year;
    private int month;
    private String monthLabel;
    private final List<ExcelStaffScheduleItem> items = new ArrayList<>();

    private int dutyCount = 0;
    private int offCount = 0;
    private int leaveCount = 0;
    private int conflictCount = 0;
    private int unassignedCount = 0;

    public ExcelStaffSchedule() {}

    public ExcelStaffSchedule(String staffName, int year, int month, String monthLabel) {
        this.staffName = staffName;
        this.year = year;
        this.month = month;
        this.monthLabel = monthLabel;
    }

    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getMonth() { return month; }
    public void setMonth(int month) { this.month = month; }

    public String getMonthLabel() { return monthLabel; }
    public void setMonthLabel(String monthLabel) { this.monthLabel = monthLabel; }

    public List<ExcelStaffScheduleItem> getItems() { return items; }

    public ExcelStaffScheduleItem getItemForDate(String isoDate) {
        if (isoDate == null) return null;
        for (ExcelStaffScheduleItem item : items) {
            if (isoDate.equals(item.getDate())) {
                return item;
            }
        }
        return null;
    }

    public void addItem(ExcelStaffScheduleItem item) {
        items.add(item);
        recomputeCounts();
    }

    public void recomputeCounts() {
        dutyCount = 0;
        offCount = 0;
        leaveCount = 0;
        conflictCount = 0;
        unassignedCount = 0;

        for (ExcelStaffScheduleItem item : items) {
            if (item.hasConflict() || item.getStatus() == ExcelStaffScheduleItem.StatusType.CONFLICT) {
                conflictCount++;
            } else if (item.getStatus() == ExcelStaffScheduleItem.StatusType.OFF) {
                offCount++;
            } else if (item.getStatus() == ExcelStaffScheduleItem.StatusType.LEAVE) {
                leaveCount++;
            } else if (item.getStatus() == ExcelStaffScheduleItem.StatusType.DUTY) {
                dutyCount++;
            } else if (item.getStatus() == ExcelStaffScheduleItem.StatusType.UNASSIGNED) {
                unassignedCount++;
            }
        }
    }

    public int getDutyCount() { return dutyCount; }
    public int getOffCount() { return offCount; }
    public int getLeaveCount() { return leaveCount; }
    public int getConflictCount() { return conflictCount; }
    public int getUnassignedCount() { return unassignedCount; }

    /**
     * Returns valid duties ready for calendar insertion, excluding unresolved conflicts and unassigned days.
     */
    public List<Duty> getImportableDuties(String ownerUid) {
        List<Duty> duties = new ArrayList<>();
        for (ExcelStaffScheduleItem item : items) {
            if (item.isSkipped()) continue;
            if (item.hasConflict() && item.getStatus() == ExcelStaffScheduleItem.StatusType.CONFLICT) {
                // Do not import unresolved conflicts
                continue;
            }
            if (item.getStatus() == ExcelStaffScheduleItem.StatusType.UNASSIGNED) {
                // Do not import unassigned days
                continue;
            }
            duties.add(item.toDuty(ownerUid));
        }
        return duties;
    }
}
