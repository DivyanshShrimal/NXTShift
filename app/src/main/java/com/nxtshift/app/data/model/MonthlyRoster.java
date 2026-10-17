package com.nxtshift.app.data.model;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ocr.RosterTextParser;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured central monthly roster model stored in Firestore.
 * Represents an officially published or drafted schedule for hospital clinical staff.
 */
public class MonthlyRoster implements Serializable {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    private String id; // e.g. "2026_10"
    private int year;
    private int month; // 1-12
    private String monthLabel; // e.g. "October 2026"
    private String status; // DRAFT or PUBLISHED
    private int version; // Incremental version number (1, 2, 3...)
    private long publishedAt;
    private long updatedAt;
    private String publishedByUid;
    private String publishedByEmail;
    private String notes;
    private List<String> staffList = new ArrayList<>();
    private List<ShiftColumnDefinition> shiftColumns = new ArrayList<>();
    private List<DaySchedule> days = new ArrayList<>();

    public MonthlyRoster() {}

    public MonthlyRoster(int year, int month, String monthLabel) {
        this.id = formatRosterId(year, month);
        this.year = year;
        this.month = month;
        this.monthLabel = monthLabel;
        this.status = STATUS_DRAFT;
        this.version = 1;
        this.updatedAt = System.currentTimeMillis();
    }

    public static String formatRosterId(int year, int month) {
        return String.format(java.util.Locale.US, "%04d_%02d", year, month);
    }

    // --- Embedded Definition Classes ---

    public static class ShiftColumnDefinition implements Serializable {
        private String columnName;
        private String shiftType; // MORNING, EVENING, NIGHT, GENERAL, OFF, LEAVE
        private String startTime;
        private String endTime;
        private boolean isOvernight;
        private boolean isOff;
        private boolean isLeave;

        public ShiftColumnDefinition() {}

        public ShiftColumnDefinition(String columnName, String shiftType, String startTime,
                                     String endTime, boolean isOvernight, boolean isOff, boolean isLeave) {
            this.columnName = columnName;
            this.shiftType = shiftType;
            this.startTime = startTime != null ? startTime : "";
            this.endTime = endTime != null ? endTime : "";
            this.isOvernight = isOvernight;
            this.isOff = isOff;
            this.isLeave = isLeave;
        }

        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getShiftType() { return shiftType; }
        public void setShiftType(String shiftType) { this.shiftType = shiftType; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }
        public boolean isOvernight() { return isOvernight; }
        public void setOvernight(boolean overnight) { isOvernight = overnight; }
        public boolean isOff() { return isOff; }
        public void setOff(boolean off) { isOff = off; }
        public boolean isLeave() { return isLeave; }
        public void setLeave(boolean leave) { isLeave = leave; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("columnName", columnName);
            map.put("shiftType", shiftType);
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            map.put("isOvernight", isOvernight);
            map.put("isOff", isOff);
            map.put("isLeave", isLeave);
            return map;
        }

        public static ShiftColumnDefinition fromMap(Map<String, Object> map) {
            if (map == null) return null;
            ShiftColumnDefinition def = new ShiftColumnDefinition();
            def.columnName = (String) map.get("columnName");
            def.shiftType = (String) map.get("shiftType");
            def.startTime = (String) map.get("startTime");
            def.endTime = (String) map.get("endTime");
            def.isOvernight = Boolean.TRUE.equals(map.get("isOvernight"));
            def.isOff = Boolean.TRUE.equals(map.get("isOff"));
            def.isLeave = Boolean.TRUE.equals(map.get("isLeave"));
            return def;
        }
    }

    public static class ShiftAssignment implements Serializable {
        private String columnName;
        private String shiftType;
        private String startTime;
        private String endTime;
        private boolean isOvernight;
        private List<String> staff = new ArrayList<>();

        public ShiftAssignment() {}

        public ShiftAssignment(String columnName, String shiftType, String startTime,
                               String endTime, boolean isOvernight, List<String> staff) {
            this.columnName = columnName;
            this.shiftType = shiftType;
            this.startTime = startTime != null ? startTime : "";
            this.endTime = endTime != null ? endTime : "";
            this.isOvernight = isOvernight;
            if (staff != null) this.staff.addAll(staff);
        }

        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getShiftType() { return shiftType; }
        public void setShiftType(String shiftType) { this.shiftType = shiftType; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }
        public boolean isOvernight() { return isOvernight; }
        public void setOvernight(boolean overnight) { isOvernight = overnight; }
        public List<String> getStaff() { return staff; }
        public void setStaff(List<String> staff) { this.staff = staff != null ? staff : new ArrayList<>(); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("columnName", columnName);
            map.put("shiftType", shiftType);
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            map.put("isOvernight", isOvernight);
            map.put("staff", staff);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static ShiftAssignment fromMap(Map<String, Object> map) {
            if (map == null) return null;
            ShiftAssignment sa = new ShiftAssignment();
            sa.columnName = (String) map.get("columnName");
            sa.shiftType = (String) map.get("shiftType");
            sa.startTime = (String) map.get("startTime");
            sa.endTime = (String) map.get("endTime");
            sa.isOvernight = Boolean.TRUE.equals(map.get("isOvernight"));
            List<String> list = (List<String>) map.get("staff");
            if (list != null) sa.staff = new ArrayList<>(list);
            return sa;
        }
    }

    public static class DaySchedule implements Serializable {
        private String date; // YYYY-MM-DD
        private int dayNumber; // 1-31
        private List<ShiftAssignment> shiftAssignments = new ArrayList<>();
        private List<String> offStaff = new ArrayList<>();
        private List<String> leaveStaff = new ArrayList<>();

        public DaySchedule() {}

        public DaySchedule(String date, int dayNumber) {
            this.date = date;
            this.dayNumber = dayNumber;
        }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public int getDayNumber() { return dayNumber; }
        public void setDayNumber(int dayNumber) { this.dayNumber = dayNumber; }
        public List<ShiftAssignment> getShiftAssignments() { return shiftAssignments; }
        public void setShiftAssignments(List<ShiftAssignment> shiftAssignments) {
            this.shiftAssignments = shiftAssignments != null ? shiftAssignments : new ArrayList<>();
        }
        public List<String> getOffStaff() { return offStaff; }
        public void setOffStaff(List<String> offStaff) {
            this.offStaff = offStaff != null ? offStaff : new ArrayList<>();
        }
        public List<String> getLeaveStaff() { return leaveStaff; }
        public void setLeaveStaff(List<String> leaveStaff) {
            this.leaveStaff = leaveStaff != null ? leaveStaff : new ArrayList<>();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("date", date);
            map.put("dayNumber", dayNumber);
            List<Map<String, Object>> assignmentMaps = new ArrayList<>();
            for (ShiftAssignment sa : shiftAssignments) {
                assignmentMaps.add(sa.toMap());
            }
            map.put("shiftAssignments", assignmentMaps);
            map.put("offStaff", offStaff);
            map.put("leaveStaff", leaveStaff);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static DaySchedule fromMap(Map<String, Object> map) {
            if (map == null) return null;
            DaySchedule ds = new DaySchedule();
            ds.date = (String) map.get("date");
            Object dayNum = map.get("dayNumber");
            if (dayNum instanceof Number) ds.dayNumber = ((Number) dayNum).intValue();

            List<Map<String, Object>> assignmentMaps = (List<Map<String, Object>>) map.get("shiftAssignments");
            if (assignmentMaps != null) {
                for (Map<String, Object> aMap : assignmentMaps) {
                    ShiftAssignment sa = ShiftAssignment.fromMap(aMap);
                    if (sa != null) ds.shiftAssignments.add(sa);
                }
            }

            List<String> off = (List<String>) map.get("offStaff");
            if (off != null) ds.offStaff = new ArrayList<>(off);

            List<String> leave = (List<String>) map.get("leaveStaff");
            if (leave != null) ds.leaveStaff = new ArrayList<>(leave);

            return ds;
        }
    }

    // --- Duty Extraction for Individual Staff Member ---

    /**
     * Extracts duties strictly for the requested staff member.
     * Preserves exact dates, shift times, and colleagues found in the exact same cell.
     * Keeps Off and Leave strictly distinct, and never generates working shifts for them.
     */
    public List<RosterTextParser.ExtractedShiftItem> extractDutiesForStaff(String targetStaffName) {
        if (targetStaffName == null || targetStaffName.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String targetNormalized = targetStaffName.trim().toLowerCase();
        List<RosterTextParser.ExtractedShiftItem> result = new ArrayList<>();

        for (DaySchedule day : days) {
            // 1. Check Leave
            if (containsStaffCaseInsensitive(day.getLeaveStaff(), targetNormalized)) {
                RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
                item.date = day.getDate();
                item.shiftType = ShiftType.LEAVE;
                item.startTime = "";
                item.endTime = "";
                item.isOvernight = false;
                item.ward = "Hospital";
                item.colleagues = "";
                item.isAmbiguous = false;
                item.notes = "Roster Leave (Published v" + version + ")";
                result.add(item);
                continue;
            }

            // 2. Check Off
            if (containsStaffCaseInsensitive(day.getOffStaff(), targetNormalized)) {
                RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
                item.date = day.getDate();
                item.shiftType = ShiftType.OFF;
                item.startTime = "";
                item.endTime = "";
                item.isOvernight = false;
                item.ward = "Hospital";
                item.colleagues = "";
                item.isAmbiguous = false;
                item.notes = "Roster Weekly Off (Published v" + version + ")";
                result.add(item);
                continue;
            }

            // 3. Check Shift Assignments
            boolean foundShift = false;
            for (ShiftAssignment assignment : day.getShiftAssignments()) {
                if (containsStaffCaseInsensitive(assignment.getStaff(), targetNormalized)) {
                    RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
                    item.date = day.getDate();
                    item.shiftType = parseShiftType(assignment.getShiftType());
                    item.startTime = assignment.getStartTime();
                    item.endTime = assignment.getEndTime();
                    item.isOvernight = assignment.isOvernight();
                    item.ward = "Hospital";

                    // Build colleague list: all other staff in this exact shift assignment
                    List<String> colleaguesList = new ArrayList<>();
                    for (String name : assignment.getStaff()) {
                        if (!name.trim().equalsIgnoreCase(targetStaffName.trim())) {
                            colleaguesList.add(name.trim());
                        }
                    }
                    item.colleagues = String.join(", ", colleaguesList);
                    item.isAmbiguous = false;
                    item.notes = "Published Roster v" + version + " (" + assignment.getColumnName() + ")";
                    result.add(item);
                    foundShift = true;
                    break; // Hospital staff have at most 1 primary shift per day
                }
            }
        }

        return result;
    }

    private boolean containsStaffCaseInsensitive(List<String> list, String targetNormalized) {
        if (list == null) return false;
        for (String s : list) {
            if (s != null && s.trim().toLowerCase().equals(targetNormalized)) {
                return true;
            }
        }
        return false;
    }

    private ShiftType parseShiftType(String typeStr) {
        if (typeStr == null) return ShiftType.GENERAL;
        try {
            return ShiftType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ShiftType.fromCode(typeStr);
        }
    }

    // --- Serialization for Firestore ---

    public Map<String, Object> toFirestoreMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("year", year);
        map.put("month", month);
        map.put("monthLabel", monthLabel != null ? monthLabel : "");
        map.put("status", status != null ? status : STATUS_DRAFT);
        map.put("version", version);
        map.put("publishedAt", publishedAt);
        map.put("updatedAt", updatedAt);
        map.put("publishedByUid", publishedByUid != null ? publishedByUid : "");
        map.put("publishedByEmail", publishedByEmail != null ? publishedByEmail : "");
        map.put("notes", notes != null ? notes : "");
        map.put("staffList", staffList != null ? staffList : new ArrayList<>());

        List<Map<String, Object>> colMaps = new ArrayList<>();
        if (shiftColumns != null) {
            for (ShiftColumnDefinition sc : shiftColumns) {
                colMaps.add(sc.toMap());
            }
        }
        map.put("shiftColumns", colMaps);

        List<Map<String, Object>> dayMaps = new ArrayList<>();
        if (days != null) {
            for (DaySchedule ds : days) {
                dayMaps.add(ds.toMap());
            }
        }
        map.put("days", dayMaps);

        return map;
    }

    @SuppressWarnings("unchecked")
    public static MonthlyRoster fromFirestoreMap(Map<String, Object> map) {
        if (map == null) return null;
        MonthlyRoster roster = new MonthlyRoster();
        roster.id = (String) map.get("id");
        Object yr = map.get("year");
        if (yr instanceof Number) roster.year = ((Number) yr).intValue();
        Object mo = map.get("month");
        if (mo instanceof Number) roster.month = ((Number) mo).intValue();
        roster.monthLabel = (String) map.get("monthLabel");
        roster.status = (String) map.get("status");
        Object ver = map.get("version");
        if (ver instanceof Number) roster.version = ((Number) ver).intValue();
        Object pubAt = map.get("publishedAt");
        if (pubAt instanceof Number) roster.publishedAt = ((Number) pubAt).longValue();
        Object updAt = map.get("updatedAt");
        if (updAt instanceof Number) roster.updatedAt = ((Number) updAt).longValue();
        roster.publishedByUid = (String) map.get("publishedByUid");
        roster.publishedByEmail = (String) map.get("publishedByEmail");
        roster.notes = (String) map.get("notes");

        List<String> sList = (List<String>) map.get("staffList");
        if (sList != null) roster.staffList = new ArrayList<>(sList);

        List<Map<String, Object>> colMaps = (List<Map<String, Object>>) map.get("shiftColumns");
        if (colMaps != null) {
            for (Map<String, Object> cMap : colMaps) {
                ShiftColumnDefinition sc = ShiftColumnDefinition.fromMap(cMap);
                if (sc != null) roster.shiftColumns.add(sc);
            }
        }

        List<Map<String, Object>> dayMaps = (List<Map<String, Object>>) map.get("days");
        if (dayMaps != null) {
            for (Map<String, Object> dMap : dayMaps) {
                DaySchedule ds = DaySchedule.fromMap(dMap);
                if (ds != null) roster.days.add(ds);
            }
        }

        return roster;
    }

    // --- Getters and Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }
    public int getMonth() { return month; }
    public void setMonth(int month) { this.month = month; }
    public String getMonthLabel() { return monthLabel; }
    public void setMonthLabel(String monthLabel) { this.monthLabel = monthLabel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(long publishedAt) { this.publishedAt = publishedAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public String getPublishedByUid() { return publishedByUid; }
    public void setPublishedByUid(String publishedByUid) { this.publishedByUid = publishedByUid; }
    public String getPublishedByEmail() { return publishedByEmail; }
    public void setPublishedByEmail(String publishedByEmail) { this.publishedByEmail = publishedByEmail; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<String> getStaffList() { return staffList; }
    public void setStaffList(List<String> staffList) { this.staffList = staffList != null ? staffList : new ArrayList<>(); }
    public List<ShiftColumnDefinition> getShiftColumns() { return shiftColumns; }
    public void setShiftColumns(List<ShiftColumnDefinition> shiftColumns) { this.shiftColumns = shiftColumns != null ? shiftColumns : new ArrayList<>(); }
    public List<DaySchedule> getDays() { return days; }
    public void setDays(List<DaySchedule> days) { this.days = days != null ? days : new ArrayList<>(); }
}
