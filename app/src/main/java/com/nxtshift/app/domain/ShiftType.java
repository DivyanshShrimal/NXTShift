package com.nxtshift.app.domain;

import androidx.annotation.ColorRes;
import com.nxtshift.app.R;

/**
 * Standard hospital shift types tailored for nurses and clinical staff.
 */
public enum ShiftType {
    MORNING("Morning", "08:00 AM", "02:00 PM", false, R.color.shift_morning, R.color.shift_morning_bg, R.color.shift_morning_text),
    EVENING("Evening", "02:00 PM", "08:00 PM", false, R.color.shift_evening, R.color.shift_evening_bg, R.color.shift_evening_text),
    NIGHT("Night", "08:00 PM", "08:00 AM", true, R.color.shift_night, R.color.shift_night_bg, R.color.shift_night_text),
    GENERAL("General", "09:00 AM", "05:00 PM", false, R.color.shift_general, R.color.shift_general_bg, R.color.shift_general_text),
    OFF("Weekly Off", "", "", false, R.color.shift_off, R.color.shift_off_bg, R.color.shift_off_text),
    LEAVE("On Leave", "", "", false, R.color.shift_off, R.color.shift_off_bg, R.color.shift_off_text);

    private final String displayName;
    private final String defaultStartTime;
    private final String defaultEndTime;
    private final boolean defaultOvernight;
    private final int colorRes;
    private final int bgRes;
    private final int textRes;

    ShiftType(String displayName, String defaultStartTime, String defaultEndTime,
              boolean defaultOvernight, @ColorRes int colorRes, @ColorRes int bgRes, @ColorRes int textRes) {
        this.displayName = displayName;
        this.defaultStartTime = defaultStartTime;
        this.defaultEndTime = defaultEndTime;
        this.defaultOvernight = defaultOvernight;
        this.colorRes = colorRes;
        this.bgRes = bgRes;
        this.textRes = textRes;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultStartTime() {
        return defaultStartTime;
    }

    public String getDefaultEndTime() {
        return defaultEndTime;
    }

    public boolean isDefaultOvernight() {
        return defaultOvernight;
    }

    public int getColorRes() {
        return colorRes;
    }

    public int getBgRes() {
        return bgRes;
    }

    public int getTextRes() {
        return textRes;
    }

    public static ShiftType fromCode(String code) {
        if (code == null) return GENERAL;
        String clean = code.trim().toUpperCase();
        if (clean.equals("M") || clean.contains("MORN")) return MORNING;
        if (clean.equals("E") || clean.contains("EVEN")) return EVENING;
        if (clean.equals("N") || clean.contains("NIGHT")) return NIGHT;
        if (clean.equals("G") || clean.contains("GEN") || clean.equals("D") || clean.contains("DAY")) return GENERAL;
        if (clean.equals("OFF") || clean.equals("WO") || clean.contains("OFF")) return OFF;
        if (clean.equals("L") || clean.contains("LEAVE") || clean.equals("CL") || clean.equals("EL")) return LEAVE;
        return GENERAL;
    }
}
