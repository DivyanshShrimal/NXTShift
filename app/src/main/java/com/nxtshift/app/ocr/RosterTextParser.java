package com.nxtshift.app.ocr;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.text.Text;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.util.DateTimeUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent parser for hospital duty roster screenshots.
 * Translates on-device OCR recognized text blocks into structured Duty records.
 */
public class RosterTextParser {
    private static final String TAG = "RosterTextParser";

    // Known hospital departments / wards
    private static final String[] KNOWN_WARDS = {
            "ICU", "CCU", "NICU", "PICU", "SICU", "MICU",
            "EMERGENCY", "CASUALTY", "ER", "TRAUMA",
            "OT", "OPERATION THEATRE", "OPD",
            "WARD 1", "WARD 2", "WARD 3", "WARD 4", "WARD 5",
            "PEDIATRIC", "CARDIOLOGY", "NEUROLOGY", "ONCOLOGY",
            "DIALYSIS", "LABOUR ROOM", "MATERNITY", "GENERAL WARD"
    };

    public static class ExtractedShiftItem {
        public String date;
        public ShiftType shiftType;
        public String startTime;
        public String endTime;
        public boolean isOvernight;
        public String ward;
        public String colleagues;
        public boolean isAmbiguous;
        public String notes;

        public Duty toDuty(String ownerUid) {
            Duty d = new Duty();
            d.setOwnerUid(ownerUid);
            d.setDutyDate(date);
            d.setShiftType(shiftType);
            d.setShiftName(shiftType.getDisplayName());
            d.setStartTime(startTime != null && !startTime.isEmpty() ? startTime : shiftType.getDefaultStartTime());
            d.setEndTime(endTime != null && !endTime.isEmpty() ? endTime : shiftType.getDefaultEndTime());
            d.setOvernight(isOvernight || shiftType.isDefaultOvernight());
            d.setWardOrDepartment(ward != null ? ward : "");
            d.setColleagues(colleagues != null ? colleagues : "");
            d.setNotes(notes != null ? notes : "Imported via on-device OCR");
            if (shiftType == ShiftType.OFF) {
                d.setStatus(DutyStatus.OFF);
            } else if (shiftType == ShiftType.LEAVE) {
                d.setStatus(DutyStatus.LEAVE);
            } else {
                d.setStatus(DutyStatus.SCHEDULED);
            }
            return d;
        }
    }

    /**
     * Parses ML Kit Vision Text into extracted candidate duties.
     */
    public static List<ExtractedShiftItem> parseMlKitText(Text visionText, int fallbackYear, int fallbackMonth) {
        if (visionText == null || visionText.getTextBlocks().isEmpty()) {
            return Collections.emptyList();
        }

        List<ExtractedShiftItem> results = new ArrayList<>();
        List<Text.Line> allLines = new ArrayList<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            allLines.addAll(block.getLines());
        }

        // Sort lines by vertical position (Y coordinate), then horizontal (X coordinate)
        Collections.sort(allLines, (l1, l2) -> {
            Rect b1 = l1.getBoundingBox();
            Rect b2 = l2.getBoundingBox();
            if (b1 == null || b2 == null) return 0;
            // Lines in same horizontal band (within 15px) sort by X
            if (Math.abs(b1.top - b2.top) < 15) {
                return Integer.compare(b1.left, b2.left);
            }
            return Integer.compare(b1.top, b2.top);
        });

        // Strategy 1: Look for line-by-line entries (e.g. "14-Oct Morning ICU" or "15/10/2026 N Emergency")
        for (Text.Line line : allLines) {
            String text = line.getText();
            ExtractedShiftItem item = tryParseLine(text, fallbackYear, fallbackMonth);
            if (item != null) {
                results.add(item);
            }
        }

        // Strategy 2: If line-by-line yielded few results, scan all text using tokenized regex
        if (results.isEmpty()) {
            String fullText = visionText.getText();
            results = parseFullTextFallback(fullText, fallbackYear, fallbackMonth);
        }

        return deduplicateAndSort(results);
    }

    private static ExtractedShiftItem tryParseLine(String line, int year, int month) {
        if (line == null || line.trim().isEmpty()) return null;
        String upper = line.toUpperCase();

        // 1. Try to find a date in this line
        String foundDate = null;

        // Pattern: dd-MM-yyyy or dd/MM/yyyy
        Pattern pDate = Pattern.compile("\\b(\\d{1,2}[-/]\\d{1,2}(?:[-/]\\d{2,4})?)\\b");
        Matcher mDate = pDate.matcher(line);
        if (mDate.find()) {
            foundDate = DateTimeUtils.tryParseOcrDate(mDate.group(1), year, month);
        }

        // Pattern: dd-MMM or dd MMM e.g. "14 Oct"
        if (foundDate == null) {
            Pattern pMonthName = Pattern.compile("\\b(\\d{1,2}\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[a-z]*)\\b", Pattern.CASE_INSENSITIVE);
            Matcher mMonth = pMonthName.matcher(line);
            if (mMonth.find()) {
                foundDate = DateTimeUtils.tryParseOcrDate(mMonth.group(1), year, month);
            }
        }

        // Pattern: Day number alone (1..31) at start of line followed by shift
        if (foundDate == null) {
            Pattern pDayNum = Pattern.compile("^\\s*([1-9]|[12][0-9]|3[01])\\b");
            Matcher mDay = pDayNum.matcher(line);
            if (mDay.find()) {
                foundDate = DateTimeUtils.tryParseOcrDate(mDay.group(1), year, month);
            }
        }

        if (foundDate == null) return null;

        // 2. Identify Shift Type
        ShiftType shiftType = null;
        if (upper.contains("OFF") || upper.contains("W/O") || upper.contains("WO")) {
            shiftType = ShiftType.OFF;
        } else if (upper.contains("LEAVE") || upper.contains("CL") || upper.contains("EL")) {
            shiftType = ShiftType.LEAVE;
        } else if (upper.contains("MORN") || upper.matches(".*\\bM\\b.*")) {
            shiftType = ShiftType.MORNING;
        } else if (upper.contains("EVEN") || upper.matches(".*\\bE\\b.*")) {
            shiftType = ShiftType.EVENING;
        } else if (upper.contains("NIGHT") || upper.matches(".*\\bN\\b.*")) {
            shiftType = ShiftType.NIGHT;
        } else if (upper.contains("GEN") || upper.matches(".*\\bD\\b.*") || upper.matches(".*\\bG\\b.*")) {
            shiftType = ShiftType.GENERAL;
        } else {
            shiftType = ShiftType.MORNING; // fallback
        }

        // 3. Identify Ward / Unit
        String matchedWard = "";
        for (String w : KNOWN_WARDS) {
            if (upper.contains(w)) {
                matchedWard = w;
                break;
            }
        }

        // 4. Identify Times if present (e.g. 7:00 AM - 2:00 PM or 07:00-14:00)
        String startTime = shiftType.getDefaultStartTime();
        String endTime = shiftType.getDefaultEndTime();
        boolean isOvernight = shiftType.isDefaultOvernight();

        Pattern pTime = Pattern.compile("(\\d{1,2}:\\d{2}(?:\\s*[AP]M)?)\\s*[-–toTO]+\\s*(\\d{1,2}:\\d{2}(?:\\s*[AP]M)?)", Pattern.CASE_INSENSITIVE);
        Matcher mTime = pTime.matcher(line);
        if (mTime.find()) {
            startTime = DateTimeUtils.normalizeTime12Hour(mTime.group(1));
            endTime = DateTimeUtils.normalizeTime12Hour(mTime.group(2));
            isOvernight = DateTimeUtils.calculateShiftDurationHours(startTime, endTime, false) > 16 || shiftType == ShiftType.NIGHT;
        }

        ExtractedShiftItem item = new ExtractedShiftItem();
        item.date = foundDate;
        item.shiftType = shiftType;
        item.startTime = startTime;
        item.endTime = endTime;
        item.isOvernight = isOvernight;
        item.ward = matchedWard;
        item.isAmbiguous = (matchedWard.isEmpty() && shiftType == ShiftType.GENERAL);
        return item;
    }

    private static List<ExtractedShiftItem> parseFullTextFallback(String fullText, int year, int month) {
        List<ExtractedShiftItem> list = new ArrayList<>();
        String[] lines = fullText.split("\\r?\\n");
        for (String l : lines) {
            ExtractedShiftItem item = tryParseLine(l, year, month);
            if (item != null) {
                list.add(item);
            }
        }
        return list;
    }

    private static List<ExtractedShiftItem> deduplicateAndSort(List<ExtractedShiftItem> raw) {
        List<ExtractedShiftItem> clean = new ArrayList<>();
        List<String> seenDates = new ArrayList<>();

        for (ExtractedShiftItem item : raw) {
            if (item.date != null && !seenDates.contains(item.date)) {
                seenDates.add(item.date);
                clean.add(item);
            }
        }

        Collections.sort(clean, Comparator.comparing(a -> a.date));
        return clean;
    }
}
