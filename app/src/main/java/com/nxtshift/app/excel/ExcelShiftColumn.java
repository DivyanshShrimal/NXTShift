package com.nxtshift.app.excel;

import com.nxtshift.app.domain.ShiftType;

import java.io.Serializable;
import java.util.Locale;

/**
 * Definition of a shift column identified in the hospital Excel roster.
 */
public class ExcelShiftColumn implements Serializable {
    private String columnLetter;
    private int columnIndex;
    private String headerName;
    private ShiftType shiftType;
    private String startTime;
    private String endTime;
    private boolean isOvernight;
    private boolean isOff;
    private boolean isLeave;

    public ExcelShiftColumn() {}

    public ExcelShiftColumn(String columnLetter, int columnIndex, String headerName,
                            ShiftType shiftType, String startTime, String endTime,
                            boolean isOvernight, boolean isOff, boolean isLeave) {
        this.columnLetter = columnLetter;
        this.columnIndex = columnIndex;
        this.headerName = headerName;
        this.shiftType = shiftType;
        this.startTime = startTime != null ? startTime : "";
        this.endTime = endTime != null ? endTime : "";
        this.isOvernight = isOvernight;
        this.isOff = isOff;
        this.isLeave = isLeave;
    }

    public String getColumnLetter() { return columnLetter; }
    public void setColumnLetter(String columnLetter) { this.columnLetter = columnLetter; }

    public int getColumnIndex() { return columnIndex; }
    public void setColumnIndex(int columnIndex) { this.columnIndex = columnIndex; }

    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }

    public ShiftType getShiftType() { return shiftType; }
    public void setShiftType(ShiftType shiftType) { this.shiftType = shiftType; }

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

    public String getFormattedTiming() {
        if (isOff) return "Weekly Off";
        if (isLeave) return "On Leave";
        if (startTime != null && !startTime.isEmpty() && endTime != null && !endTime.isEmpty()) {
            return startTime + " – " + endTime + (isOvernight ? " (+1 day)" : "");
        }
        return headerName != null ? headerName : "";
    }
}
