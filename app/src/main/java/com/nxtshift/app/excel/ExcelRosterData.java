package com.nxtshift.app.excel;

import com.nxtshift.app.domain.ShiftType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fully parsed representation of a hospital Excel duty roster.
 */
public class ExcelRosterData implements Serializable {
    private int year;
    private int month;
    private String monthLabel;
    private String sheetName;
    private final List<ExcelShiftColumn> columns = new ArrayList<>();
    private final List<ExcelDayRow> days = new ArrayList<>();
    private final List<String> uniqueStaffNames = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public ExcelRosterData() {}

    public ExcelRosterData(int year, int month, String monthLabel, String sheetName) {
        this.year = year;
        this.month = month;
        this.monthLabel = monthLabel;
        this.sheetName = sheetName;
    }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getMonth() { return month; }
    public void setMonth(int month) { this.month = month; }

    public String getMonthLabel() { return monthLabel; }
    public void setMonthLabel(String monthLabel) { this.monthLabel = monthLabel; }

    public String getSheetName() { return sheetName; }
    public void setSheetName(String sheetName) { this.sheetName = sheetName; }

    public List<ExcelShiftColumn> getColumns() { return columns; }
    public List<ExcelDayRow> getDays() { return days; }
    public List<String> getUniqueStaffNames() { return uniqueStaffNames; }
    public List<String> getWarnings() { return warnings; }
    public List<String> getErrors() { return errors; }

    public boolean isValid() {
        return errors.isEmpty() && !days.isEmpty() && !columns.isEmpty();
    }

    /**
     * Extracts the complete monthly schedule for the specified staff member,
     * identifying duties, shift timings, colleagues from the exact cell, Off, Leave,
     * and strictly detecting any conflicting assignments.
     */
    public ExcelStaffSchedule extractScheduleForStaff(String targetStaffName) {
        ExcelStaffSchedule schedule = new ExcelStaffSchedule(targetStaffName, year, month, monthLabel);
        if (targetStaffName == null || targetStaffName.trim().isEmpty()) {
            return schedule;
        }

        String targetClean = targetStaffName.trim().toLowerCase(Locale.US);

        for (ExcelDayRow dayRow : days) {
            ExcelStaffScheduleItem item = new ExcelStaffScheduleItem(dayRow.getIsoDate(), dayRow.getDayNumber());

            // Collect all matching assignments for this day
            List<ExcelShiftColumn> matchingDutyColumns = new ArrayList<>();
            List<List<String>> matchingDutyCellStaff = new ArrayList<>();
            boolean isOff = false;
            boolean isLeave = false;

            for (ExcelShiftColumn col : columns) {
                List<String> staffInCell = dayRow.getStaffForColumn(col.getColumnIndex());
                if (containsStaffName(staffInCell, targetClean)) {
                    if (col.isOff()) {
                        isOff = true;
                        item.addConflictingAssignment("Weekly Off");
                    } else if (col.isLeave()) {
                        isLeave = true;
                        item.addConflictingAssignment("On Leave");
                    } else {
                        matchingDutyColumns.add(col);
                        matchingDutyCellStaff.add(staffInCell);
                        item.addConflictingAssignment(col.getHeaderName());
                    }
                }
            }

            int totalAssignmentTypes = (matchingDutyColumns.size() > 0 ? 1 : 0) + (isOff ? 1 : 0) + (isLeave ? 1 : 0);

            // 1. Conflict: Multiple shifts on same date
            if (matchingDutyColumns.size() > 1) {
                item.setStatus(ExcelStaffScheduleItem.StatusType.CONFLICT);
                item.setHasConflict(true);
                List<String> colNames = new ArrayList<>();
                for (ExcelShiftColumn col : matchingDutyColumns) {
                    colNames.add(col.getHeaderName());
                }
                item.addWarning("Multiple shift assignments on same day: " + String.join(", ", colNames));
            }

            // 2. Conflict: Duty and Off / Duty and Leave / Off and Leave
            if ((matchingDutyColumns.size() > 0 && isOff) ||
                (matchingDutyColumns.size() > 0 && isLeave) ||
                (isOff && isLeave)) {
                item.setStatus(ExcelStaffScheduleItem.StatusType.CONFLICT);
                item.setHasConflict(true);

                if (isOff && isLeave && matchingDutyColumns.isEmpty()) {
                    item.addWarning("Conflicting assignment: Marked as both Off and Leave on this day");
                } else if (matchingDutyColumns.size() > 0 && isOff && !isLeave) {
                    item.addWarning("Conflicting assignment: Assigned to duty (" + matchingDutyColumns.get(0).getHeaderName() + ") and marked as Off");
                } else if (matchingDutyColumns.size() > 0 && isLeave && !isOff) {
                    item.addWarning("Conflicting assignment: Assigned to duty (" + matchingDutyColumns.get(0).getHeaderName() + ") and marked as On Leave");
                } else {
                    item.addWarning("Conflicting assignment: Multiple simultaneous duty/off/leave assignments");
                }
            }

            // 3. Normal Single Duty Assignment
            if (matchingDutyColumns.size() == 1 && !isOff && !isLeave) {
                ExcelShiftColumn dutyCol = matchingDutyColumns.get(0);
                List<String> cellStaff = matchingDutyCellStaff.get(0);

                item.setStatus(ExcelStaffScheduleItem.StatusType.DUTY);
                item.setShiftType(dutyCol.getShiftType());
                item.setShiftName(dutyCol.getHeaderName());
                item.setStartTime(dutyCol.getStartTime());
                item.setEndTime(dutyCol.getEndTime());
                item.setOvernight(dutyCol.isOvernight());

                // Derive colleagues strictly from this exact shift cell, excluding the target nurse
                List<String> colleagues = new ArrayList<>();
                for (String person : cellStaff) {
                    if (!person.trim().toLowerCase(Locale.US).equals(targetClean)) {
                        colleagues.add(person.trim());
                    }
                }
                item.setColleagues(String.join(", ", colleagues));
            }

            // 4. Normal Single Off Assignment
            else if (isOff && !isLeave && matchingDutyColumns.isEmpty()) {
                item.setStatus(ExcelStaffScheduleItem.StatusType.OFF);
                item.setShiftType(ShiftType.OFF);
                item.setShiftName("Weekly Off");
                item.setColleagues("");
            }

            // 5. Normal Single Leave Assignment
            else if (isLeave && !isOff && matchingDutyColumns.isEmpty()) {
                item.setStatus(ExcelStaffScheduleItem.StatusType.LEAVE);
                item.setShiftType(ShiftType.LEAVE);
                item.setShiftName("On Leave");
                item.setColleagues("");
            }

            // 6. No Assignment Recorded on this date
            else if (matchingDutyColumns.isEmpty() && !isOff && !isLeave) {
                item.setStatus(ExcelStaffScheduleItem.StatusType.UNASSIGNED);
                item.setShiftName("No Assignment");
                item.setColleagues("");
                item.addWarning("No duty, off, or leave assigned on this date");
            }

            schedule.addItem(item);
        }

        return schedule;
    }

    private boolean containsStaffName(List<String> staffList, String targetClean) {
        if (staffList == null || staffList.isEmpty()) return false;
        for (String s : staffList) {
            if (s != null && s.trim().toLowerCase(Locale.US).equals(targetClean)) {
                return true;
            }
        }
        return false;
    }
}
