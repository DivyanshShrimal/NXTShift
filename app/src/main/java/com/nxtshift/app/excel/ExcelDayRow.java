package com.nxtshift.app.excel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a single date row in the parsed Excel roster.
 */
public class ExcelDayRow implements Serializable {
    private String isoDate;
    private int dayNumber;
    private final Map<Integer, List<String>> cellAssignments = new HashMap<>();

    public ExcelDayRow() {}

    public ExcelDayRow(String isoDate, int dayNumber) {
        this.isoDate = isoDate;
        this.dayNumber = dayNumber;
    }

    public String getIsoDate() { return isoDate; }
    public void setIsoDate(String isoDate) { this.isoDate = isoDate; }

    public int getDayNumber() { return dayNumber; }
    public void setDayNumber(int dayNumber) { this.dayNumber = dayNumber; }

    public void addStaffToColumn(int columnIndex, List<String> staffNames) {
        if (staffNames == null || staffNames.isEmpty()) return;
        List<String> list = cellAssignments.computeIfAbsent(columnIndex, k -> new ArrayList<>());
        for (String name : staffNames) {
            if (name != null && !name.trim().isEmpty() && !list.contains(name.trim())) {
                list.add(name.trim());
            }
        }
    }

    public List<String> getStaffForColumn(int columnIndex) {
        List<String> list = cellAssignments.get(columnIndex);
        return list != null ? list : Collections.emptyList();
    }

    public Map<Integer, List<String>> getAllCellAssignments() {
        return cellAssignments;
    }
}
