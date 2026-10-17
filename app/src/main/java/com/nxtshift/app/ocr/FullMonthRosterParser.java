package com.nxtshift.app.ocr;

import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.text.Text;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.util.DateTimeUtils;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Table-aware full-month hospital duty roster OCR parser.
 * Discovers staff members strictly from table data cells, excludes administrative headers,
 * enforces exact-only normalized duplicate matching (keeping distinct persons separate),
 * extracts targeted duties for the selected employee, and isolates coworkers strictly
 * to the exact date-and-shift cell.
 */
public class FullMonthRosterParser {
    private static final String TAG = "FullMonthRosterParser";

    private static final String[] MONTH_NAMES = {
            "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
            "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
    };

    private static final String[] MONTH_ABBR = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP|SEPT", "OCT", "NOV", "DEC"
    };

    public static class MonthYearResult implements Serializable {
        public final int year;
        public final int month; // 1-12
        public final String monthName;
        public final boolean isConfident;

        public MonthYearResult(int year, int month, String monthName, boolean isConfident) {
            this.year = year;
            this.month = month;
            this.monthName = monthName;
            this.isConfident = isConfident;
        }

        public String getDisplayLabel() {
            return monthName + " " + year;
        }
    }

    public static class DiscoveredStaffMember implements Serializable {
        public String name;
        public String normalizedName;
        public int dutyCount;
        public final Set<String> rawVariants = new HashSet<>();
        public boolean isConfirmed = false;
        public boolean isNonPerson = false;

        public DiscoveredStaffMember(String name, String normalizedName, int dutyCount) {
            this.name = name;
            this.normalizedName = normalizedName;
            this.dutyCount = dutyCount;
            this.rawVariants.add(name);
        }

        public void updateName(String newName) {
            this.name = cleanName(newName);
            this.normalizedName = normalizeForComparison(this.name);
            this.rawVariants.add(this.name);
        }

        public void mergeWith(DiscoveredStaffMember other) {
            this.dutyCount += other.dutyCount;
            this.rawVariants.addAll(other.rawVariants);
        }
    }

    public static class ParsedShiftTiming implements Serializable {
        public final ShiftType shiftType;
        public final String startTime;
        public final String endTime;
        public final boolean isOvernight;

        public ParsedShiftTiming(ShiftType shiftType, String startTime, String endTime, boolean isOvernight) {
            this.shiftType = shiftType;
            this.startTime = startTime;
            this.endTime = endTime;
            this.isOvernight = isOvernight;
        }
    }

    public static class TableColumn implements Serializable {
        public int index;
        public String headerText;
        public ShiftType shiftType = ShiftType.GENERAL;
        public String startTime = "";
        public String endTime = "";
        public boolean isOvernight = false;
        public boolean isOff = false;
        public boolean isLeave = false;
        public boolean isDate = false;
        public boolean isAmbiguous = false;
        public int minX = 0;
        public int maxX = Integer.MAX_VALUE;
        public Rect bounds;

        public TableColumn(int index, String headerText, @Nullable Rect bounds) {
            this.index = index;
            this.headerText = headerText != null ? headerText.trim() : "";
            this.bounds = bounds;
            parseDetails(this.headerText);
        }

        private void parseDetails(String text) {
            String up = text.toUpperCase(Locale.ENGLISH).trim();
            if (up.isEmpty()) return;

            // Date column
            if (up.equals("DATE") || up.equals("DAY") || up.equals("DATES") || up.equals("DAYS")
                    || up.startsWith("DATE") || up.equals("S.NO") || up.equals("SNO") || up.equals("SR NO")
                    || up.equals("MONTH") || up.startsWith("MONTH")) {
                this.isDate = true;
                return;
            }

            // Off status
            if (up.equals("OFF") || up.contains("W/O") || up.equals("WO") || up.contains("WEEKLY OFF") || up.startsWith("OFF")) {
                this.isOff = true;
                this.shiftType = ShiftType.OFF;
                return;
            }

            // Leave status
            if (up.equals("LEAVE") || up.contains("ON LEAVE") || up.equals("CL") || up.equals("EL") || up.equals("SL") || up.startsWith("LEAVE")) {
                this.isLeave = true;
                this.shiftType = ShiftType.LEAVE;
                return;
            }

            ParsedShiftTiming pst = parseShiftTiming(up);
            if (pst != null) {
                this.shiftType = pst.shiftType;
                this.startTime = pst.startTime;
                this.endTime = pst.endTime;
                this.isOvernight = pst.isOvernight;
                this.isAmbiguous = false;
            } else if (up.contains("MORN") || up.matches(".*\\bM\\b.*")) {
                this.shiftType = ShiftType.MORNING;
                this.startTime = "08:00 AM";
                this.endTime = "02:00 PM";
            } else if (up.contains("EVEN") || up.matches(".*\\bE\\b.*")) {
                this.shiftType = ShiftType.EVENING;
                this.startTime = "02:00 PM";
                this.endTime = "08:00 PM";
            } else if (up.contains("NIGHT") || up.matches(".*\\bN\\b.*")) {
                this.shiftType = ShiftType.NIGHT;
                this.startTime = "08:00 PM";
                this.endTime = "08:00 AM";
                this.isOvernight = true;
            } else if (up.contains("GEN") || up.matches(".*\\b[GD]\\b.*")) {
                this.shiftType = ShiftType.GENERAL;
                this.startTime = "09:00 AM";
                this.endTime = "05:00 PM";
            } else {
                this.isAmbiguous = true;
                this.shiftType = ShiftType.GENERAL;
            }
        }
    }

    public static class DynamicShiftTimings implements Serializable {
        public String morningStart = ShiftType.MORNING.getDefaultStartTime();
        public String morningEnd = ShiftType.MORNING.getDefaultEndTime();
        public String eveningStart = ShiftType.EVENING.getDefaultStartTime();
        public String eveningEnd = ShiftType.EVENING.getDefaultEndTime();
        public String nightStart = ShiftType.NIGHT.getDefaultStartTime();
        public String nightEnd = ShiftType.NIGHT.getDefaultEndTime();
        public String generalStart = "09:00 AM";
        public String generalEnd = "05:00 PM";
        public String dayStart = "10:00 AM";
        public String dayEnd = "06:00 PM";
        public String midStart = "12:00 PM";
        public String midEnd = "08:00 PM";
    }

    // ── 1. Month and Year Detection ─────────────────────────────────────────────

    @NonNull
    public static MonthYearResult detectMonthYear(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            LocalDate now = LocalDate.now();
            return new MonthYearResult(now.getYear(), now.getMonthValue(),
                    now.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), false);
        }

        String upper = text.toUpperCase(Locale.ENGLISH);

        // Pattern 1: e.g. "OCTOBER 2026", "OCTOBER, 2026", "OCT-2026"
        for (int i = 0; i < 12; i++) {
            String fullM = MONTH_NAMES[i];
            String abbrM = MONTH_ABBR[i];

            Pattern p = Pattern.compile("\\b(?:" + fullM + "|" + abbrM + ")[a-z]*[\\s,.-]+(20[2-3][0-9])\\b");
            Matcher m = p.matcher(upper);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                return new MonthYearResult(year, i + 1, capitalize(fullM), true);
            }

            Pattern pInv = Pattern.compile("\\b(20[2-3][0-9])[\\s,.-]+(?:" + fullM + "|" + abbrM + ")[a-z]*\\b");
            Matcher mInv = pInv.matcher(upper);
            if (mInv.find()) {
                int year = Integer.parseInt(mInv.group(1));
                return new MonthYearResult(year, i + 1, capitalize(fullM), true);
            }
        }

        // Pattern 2: Month alone without year, assume current or next year
        for (int i = 0; i < 12; i++) {
            String fullM = MONTH_NAMES[i];
            Pattern pSolo = Pattern.compile("\\b" + fullM + "\\b");
            if (pSolo.matcher(upper).find()) {
                int currYear = LocalDate.now().getYear();
                return new MonthYearResult(currYear, i + 1, capitalize(fullM), true);
            }
        }

        // Pattern 3: Numeric date formats like "10/2026" or "10-2026"
        Pattern pNum = Pattern.compile("\\b(0?[1-9]|1[0-2])[/-](20[2-3][0-9])\\b");
        Matcher mNum = pNum.matcher(upper);
        if (mNum.find()) {
            int month = Integer.parseInt(mNum.group(1));
            int year = Integer.parseInt(mNum.group(2));
            String name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            return new MonthYearResult(year, month, name, true);
        }

        LocalDate now = LocalDate.now();
        return new MonthYearResult(now.getYear(), now.getMonthValue(),
                now.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), false);
    }

    // ── 2. Dynamic Shift Timing Header Detection ───────────────────────────────

    @NonNull
    public static DynamicShiftTimings detectShiftTimings(@Nullable String text) {
        DynamicShiftTimings timings = new DynamicShiftTimings();
        if (text == null) return timings;

        String[] lines = text.split("\\r?\\n");
        Pattern timeRangePattern = Pattern.compile("([0-9A-Za-z]{1,5}(?::[0-9A-Za-z]{2})?(?:\\s*[AP]M)?)\\s*(?:[-–—]|\\bto\\b)\\s*([0-9A-Za-z]{1,5}(?::[0-9A-Za-z]{2})?(?:\\s*[AP]M)?)", Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            String up = line.toUpperCase(Locale.ENGLISH);
            Matcher m = timeRangePattern.matcher(line);
            if (m.find()) {
                String rawStart = m.group(1);
                String rawEnd = m.group(2);

                String start = OcrContextCorrection.repairTimeString(rawStart);
                if (start == null) start = DateTimeUtils.normalizeTime12Hour(rawStart);

                String end = OcrContextCorrection.repairTimeString(rawEnd);
                if (end == null) end = DateTimeUtils.normalizeTime12Hour(rawEnd);

                if (start != null && end != null) {
                    if (up.contains("MORN") || up.contains(" M ")) {
                        timings.morningStart = start;
                        timings.morningEnd = end;
                    } else if (up.contains("EVEN") || up.contains(" E ")) {
                        timings.eveningStart = start;
                        timings.eveningEnd = end;
                    } else if (up.contains("NIGHT") || up.contains(" N ")) {
                        timings.nightStart = start;
                        timings.nightEnd = end;
                    }
                }
            }
        }
        return timings;
    }

    // ── 3. Table-Aware Automatic Staff Directory Discovery ─────────────────────

    /**
     * Automatically scans table data cells to discover genuine staff members.
     * Enforces the strict hospital staff allowlist (13 canonical members + configured Yamini).
     * Excludes top title banners, month headers, time legends, and unapproved names.
     */
    @NonNull
    public static List<DiscoveredStaffMember> discoverStaffDirectory(@Nullable Text visionText, @Nullable String fullText) {
        Map<String, DiscoveredStaffMember> candidateMap = new LinkedHashMap<>();

        List<String> rawTokens = extractPotentialNameTokens(visionText, fullText);

        for (String raw : rawTokens) {
            String clean = cleanName(raw);
            if (!isValidStaffName(clean)) continue;

            StaffAllowlistManager.StaffResolutionResult res = StaffAllowlistManager.resolveCandidate(raw);
            if (!res.isApproved()) {
                // Skip unapproved, unknown, or non-person entries
                continue;
            }

            for (String canonical : res.canonicalNames) {
                String normalized = normalizeForComparison(canonical);
                DiscoveredStaffMember matched = candidateMap.get(normalized);
                if (matched != null) {
                    matched.dutyCount++;
                    matched.rawVariants.add(clean);
                } else {
                    candidateMap.put(normalized, new DiscoveredStaffMember(canonical, normalized, 1));
                }
            }
        }

        List<DiscoveredStaffMember> result = new ArrayList<>(candidateMap.values());
        // Sort by occurrence count descending, then alphabetically
        Collections.sort(result, (a, b) -> {
            int cmp = Integer.compare(b.dutyCount, a.dutyCount);
            if (cmp != 0) return cmp;
            return a.name.compareToIgnoreCase(b.name);
        });

        return result;
    }

    /**
     * Splits an incorrectly combined staff entry into two distinct staff members.
     */
    @NonNull
    public static List<DiscoveredStaffMember> splitStaffMember(@NonNull DiscoveredStaffMember target,
                                                               @NonNull String name1,
                                                               @NonNull String name2) {
        List<DiscoveredStaffMember> list = new ArrayList<>();
        String clean1 = cleanName(name1);
        String clean2 = cleanName(name2);

        int count1 = Math.max(1, (target.dutyCount + 1) / 2);
        int count2 = Math.max(1, target.dutyCount - count1);

        if (isValidStaffName(clean1)) {
            list.add(new DiscoveredStaffMember(clean1, normalizeForComparison(clean1), count1));
        }
        if (isValidStaffName(clean2)) {
            list.add(new DiscoveredStaffMember(clean2, normalizeForComparison(clean2), count2));
        }
        return list;
    }

    // ── 4. Targeted Employee Duty Extraction ────────────────────────────────────

    public static int getBoxCenterX(@Nullable Rect box) {
        return box != null ? (box.left + box.right) / 2 : 0;
    }

    public static int getBoxCenterY(@Nullable Rect box) {
        return box != null ? (box.top + box.bottom) / 2 : 0;
    }

    public static Rect createRect(int left, int top, int right, int bottom) {
        Rect r = new Rect();
        r.left = left;
        r.top = top;
        r.right = right;
        r.bottom = bottom;
        return r;
    }

    public static Rect copyRect(@Nullable Rect src) {
        if (src == null) return null;
        Rect r = new Rect();
        r.left = src.left;
        r.top = src.top;
        r.right = src.right;
        r.bottom = src.bottom;
        return r;
    }

    public static Rect unionRect(@Nullable Rect a, @Nullable Rect b) {
        if (a == null && b == null) return null;
        if (a == null) return copyRect(b);
        if (b == null) return copyRect(a);
        Rect r = new Rect();
        r.left = Math.min(a.left, b.left);
        r.top = Math.min(a.top, b.top);
        r.right = Math.max(a.right, b.right);
        r.bottom = Math.max(a.bottom, b.bottom);
        return r;
    }

    public static class DateAnchor {
        public final int day;
        public final String dateStr;
        public final Rect boundingBox;
        public final int centerY;

        public DateAnchor(int day, String dateStr, Rect boundingBox) {
            this.day = day;
            this.dateStr = dateStr;
            this.boundingBox = boundingBox;
            this.centerY = getBoxCenterY(boundingBox);
        }
    }

    public static int parseDayNumberFromDate(String dateStr) {
        if (dateStr == null || dateStr.length() < 10) return -1;
        try {
            return Integer.parseInt(dateStr.substring(8));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Extracts duties across the full month specifically for the chosen employee.
     * Validates dates against real calendar days.
     * Combines spatial grid analysis and robust text parsing so no dates are missed.
     */
    @NonNull
    public static List<RosterTextParser.ExtractedShiftItem> extractDutiesForEmployee(
            @Nullable Text visionText,
            @Nullable String fullText,
            @NonNull String targetEmployeeName,
            int year,
            int month) {

        if (targetEmployeeName.trim().isEmpty()) {
            return Collections.emptyList();
        }

        DynamicShiftTimings timings = detectShiftTimings(fullText != null ? fullText : (visionText != null ? visionText.getText() : null));
        String normalizedTarget = normalizeForComparison(targetEmployeeName);

        List<RosterTextParser.ExtractedShiftItem> spatialDuties = new ArrayList<>();
        if (visionText != null && !visionText.getTextBlocks().isEmpty()) {
            spatialDuties = parseSpatialGrid(visionText, normalizedTarget, targetEmployeeName, year, month, timings);
        }

        List<RosterTextParser.ExtractedShiftItem> fallbackDuties = new ArrayList<>();
        if (fullText != null) {
            fallbackDuties = parseLineBlocksFallback(fullText, normalizedTarget, targetEmployeeName, year, month, timings);
        }

        // Merge spatial and fallback duties safely:
        // Spatial duties have 2D cell-level isolation, so for any date where a spatial duty was found,
        // spatial duty takes precedence and prevents cross-column coworker contamination.
        Set<String> spatialDates = new HashSet<>();
        for (RosterTextParser.ExtractedShiftItem item : spatialDuties) {
            if (item != null && item.date != null) {
                spatialDates.add(item.date);
            }
        }

        Map<String, RosterTextParser.ExtractedShiftItem> dateShiftMap = new LinkedHashMap<>();

        // Add fallback duties only for dates NOT already captured by spatial parsing
        for (RosterTextParser.ExtractedShiftItem item : fallbackDuties) {
            if (item != null && item.date != null && !spatialDates.contains(item.date)) {
                String dutyKey = item.date + "#" + item.shiftType + "#" + (item.startTime != null ? item.startTime : "");
                dateShiftMap.put(dutyKey, item);
            }
        }

        // Spatial duties take precedence (cell-isolated colleagues & verified column timing)
        for (RosterTextParser.ExtractedShiftItem item : spatialDuties) {
            if (item != null && item.date != null) {
                String dutyKey = item.date + "#" + item.shiftType + "#" + (item.startTime != null ? item.startTime : "");
                dateShiftMap.put(dutyKey, item);
            }
        }

        List<RosterTextParser.ExtractedShiftItem> duties = new ArrayList<>(dateShiftMap.values());
        Collections.sort(duties, Comparator.comparing(a -> a.date));

        safeLogInfo(TAG, "=== OCR Diagnostic Report for Employee: " + targetEmployeeName + " ===");
        safeLogInfo(TAG, "Total OCR TextBlocks: " + (visionText != null ? visionText.getTextBlocks().size() : 0));
        safeLogInfo(TAG, "Spatial duties found: " + spatialDuties.size());
        safeLogInfo(TAG, "Fallback duties found: " + fallbackDuties.size());
        safeLogInfo(TAG, "Total merged distinct duties: " + duties.size());

        return duties;
    }

    private static void safeLogInfo(String tag, String msg) {
        try {
            android.util.Log.i(tag, msg);
        } catch (Throwable t) {
            System.out.println(tag + ": " + msg);
        }
    }

    // ── 5. Spatial Grid & Cell Understanding ────────────────────────────────────

    public static class CellElement {
        public final String text;
        public final Rect box;

        public CellElement(String text, Rect box) {
            this.text = text != null ? text.trim() : "";
            this.box = box;
        }
    }

    public static List<String> extractApprovedStaffFromCellElements(List<CellElement> cellElements) {
        if (cellElements == null || cellElements.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort elements in cell by top then left (reading order: top-to-bottom, left-to-right)
        cellElements.sort((e1, e2) -> {
            int top1 = e1.box != null ? e1.box.top : 0;
            int top2 = e2.box != null ? e2.box.top : 0;
            if (Math.abs(top1 - top2) > 15) {
                return Integer.compare(top1, top2);
            }
            int left1 = e1.box != null ? e1.box.left : 0;
            int left2 = e2.box != null ? e2.box.left : 0;
            return Integer.compare(left1, left2);
        });

        List<String> rawTokens = new ArrayList<>();
        for (CellElement el : cellElements) {
            if (el.text != null && !el.text.isEmpty()) {
                rawTokens.add(el.text);
            }
        }
        return extractApprovedStaffFromTokens(rawTokens);
    }

    public static List<String> extractApprovedStaffFromTokens(List<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Expand tokens by delimiters (commas, slashes, semicolons, pipes, plus, tabs, newlines, "AND", spaces)
        List<String> words = new ArrayList<>();
        for (String token : rawTokens) {
            String stripped = stripPrefixes(token);
            String[] parts = stripped.split("[,/;&+|\\n\\r\\t]|\\s+and\\s+|\\s+AND\\s+|\\s+");
            for (String p : parts) {
                String clean = cleanName(p);
                if (!clean.isEmpty()) {
                    words.add(clean);
                }
            }
        }

        if (words.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        int n = words.size();
        boolean[] consumed = new boolean[n];

        // 2. Identify two-word approved identities (e.g. "Ajay Jangid", "Ajay Jindoliya")
        Map<Integer, String> twoWordMatches = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            if (consumed[i] || consumed[i + 1]) continue;
            String twoWord = words.get(i) + " " + words.get(i + 1);
            StaffAllowlistManager.StaffResolutionResult res = StaffAllowlistManager.resolveCandidate(twoWord);
            if (res.isApproved() && res.canonicalNames.size() == 1
                    && res.status != StaffAllowlistManager.MatchStatus.COMPOUND_SPLIT) {
                twoWordMatches.put(i, res.canonicalNames.get(0));
                consumed[i] = true;
                consumed[i + 1] = true;
            }
        }

        // 3. Iterate through words in cell order
        for (int i = 0; i < n; i++) {
            if (twoWordMatches.containsKey(i)) {
                String canonical = twoWordMatches.get(i);
                if (!result.contains(canonical)) {
                    result.add(canonical);
                }
                i++; // skip next word as it was part of this two-word identity
                continue;
            }

            if (consumed[i]) continue;

            String w = words.get(i);
            StaffAllowlistManager.StaffResolutionResult res = StaffAllowlistManager.resolveCandidate(w);
            if (res.isApproved()) {
                for (String canonical : res.canonicalNames) {
                    if (!result.contains(canonical)) {
                        result.add(canonical);
                    }
                }
                consumed[i] = true;
            } else if (res.status == StaffAllowlistManager.MatchStatus.AMBIGUOUS) {
                safeLogInfo(TAG, "Ambiguous staff token in cell skipped: " + w);
            }
        }

        return result;
    }

    public static List<String> extractApprovedStaffFromCellText(String cellText) {
        if (cellText == null || cellText.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return extractApprovedStaffFromTokens(Collections.singletonList(cellText));
    }

    public static final double[] HOSPITAL_COLUMN_FRACTIONS = {
            0.05, // Col 0: Month / Date (5%)
            0.23, // Col 1: 9 AM - 5 PM (23%)
            0.06, // Col 2: 10 AM - 6 PM (6%)
            0.06, // Col 3: 12 PM - 8 PM (6%)
            0.12, // Col 4: 8 AM - 2 PM (12%)
            0.15, // Col 5: 2 PM - 8 PM (15%)
            0.10, // Col 6: 8 PM - 8 AM (10%)
            0.12, // Col 7: Off (12%)
            0.11  // Col 8: Leave (11%)
    };

    public static final String[] HOSPITAL_COLUMN_NAMES = {
            "Month",
            "9 AM - 5 PM",
            "10 AM - 6 PM",
            "12 PM - 8 PM",
            "8 AM - 2 PM",
            "2 PM - 8 PM",
            "8 PM - 8 AM",
            "Off",
            "Leave"
    };

    public static class DetectedHeader {
        public final int columnType;
        public final String text;
        public final Rect bounds;
        public final int centerX;

        public DetectedHeader(int columnType, String text, Rect bounds) {
            this.columnType = columnType;
            this.text = text;
            this.bounds = bounds;
            this.centerX = getBoxCenterX(bounds);
        }
    }

    public static int identifyColumnType(@Nullable String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toUpperCase(Locale.ENGLISH);
        if (s.isEmpty()) return -1;

        if (s.contains("OCT") || s.contains("2026") || s.contains("ROSTER") || s.contains("IMPORTANT") || s.contains("GUIDELINE")) {
            return -1;
        }

        // Col 0: Date / Month
        if (s.equals("MONTH") || s.equals("DATE") || s.equals("DAY") || s.equals("DATES") || s.equals("DAYS")
                || s.equals("S.NO") || s.equals("SR NO") || s.equals("SNO") || s.equals("MONTH:") || s.equals("DATE:")) {
            return 0;
        }

        // Col 7: Off
        if (s.equals("OFF") || s.equals("W/O") || s.equals("WO") || s.equals("WEEKLY OFF") || s.equals("OFF.") || s.equals("OFF:")) {
            return 7;
        }

        // Col 8: Leave
        if (s.equals("LEAVE") || s.equals("ON LEAVE") || s.equals("CL") || s.equals("EL") || s.equals("SL") || s.equals("LEAVE.") || s.equals("LEAVE:")) {
            return 8;
        }

        String rep = s.replace('Z', '2').replace('S', '5').replace('B', '8');
        if (rep.contains("OFF") || rep.contains("LEAVE") || rep.contains("MONTH") || rep.contains("DATE")) {
            return -1;
        }

        // 1. 12 PM - 8 PM (Col 3)
        if (rep.matches("(?:.*\\b)?12(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8(?::00)?\\s*(?:PM)?(?:\\b.*)?")
                || rep.matches(".*\\b12\\s*[-–—toTO]+\\s*8\\b.*")
                || (rep.contains("12:00") && rep.contains("20:00"))) {
            if (!rep.contains("9") && !rep.contains("10") && !rep.contains("6")) {
                return 3;
            }
        }

        // 2. 10 AM - 6 PM (Col 2)
        if (rep.matches("(?:.*\\b)?10(?::00)?\\s*(?:AM)?\\s*[-–—toTO]+\\s*6(?::00)?\\s*(?:PM)?(?:\\b.*)?")
                || rep.matches(".*\\b10\\s*[-–—toTO]+\\s*6\\b.*")
                || (rep.contains("10:00") && rep.contains("18:00"))) {
            if (!rep.contains("9") && !rep.contains("12") && !rep.contains("8")) {
                return 2;
            }
        }

        // 3. 9 AM - 5 PM (Col 1)
        if (rep.matches("(?:.*\\b)?9(?::00)?\\s*(?:AM)?\\s*[-–—toTO]+\\s*5(?::00)?\\s*(?:PM)?(?:\\b.*)?")
                || rep.matches(".*\\b9\\s*[-–—toTO]+\\s*5\\b.*")
                || (rep.contains("09:00") && rep.contains("17:00"))) {
            if (!rep.contains("10") && !rep.contains("6") && !rep.contains("12") && !rep.contains("8") && !rep.contains("2")) {
                return 1;
            }
        }

        // 4. 8 AM - 2 PM (Col 4 - Morning)
        if (rep.matches("(?:.*\\b)?8(?::00)?\\s*(?:AM)?\\s*[-–—toTO]+\\s*2(?::00)?\\s*(?:PM)?(?:\\b.*)?")
                || (rep.contains("08:00") && rep.contains("14:00"))
                || (rep.contains("8") && rep.contains("2") && (rep.contains("AM") || rep.contains("MORN") || rep.startsWith("8")))) {
            if (!rep.contains("9") && !rep.contains("10") && !rep.contains("6") && !rep.contains("12")) {
                if (!rep.startsWith("2") && !rep.matches(".*\\b2(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+.*") && !rep.matches(".*8(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8.*")) {
                    return 4;
                }
            }
        }

        // 5. 2 PM - 8 PM (Col 5 - Evening)
        if (rep.matches("(?:.*\\b)?2(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8(?::00)?\\s*(?:PM)?(?:\\b.*)?")
                || (rep.contains("14:00") && rep.contains("20:00"))
                || (rep.contains("2") && rep.contains("8") && (rep.contains("PM") || rep.contains("EVEN") || rep.startsWith("2")))) {
            if (!rep.contains("9") && !rep.contains("10") && !rep.contains("6") && !rep.contains("12")) {
                if (!rep.startsWith("8") && !rep.matches(".*\\b8(?::00)?\\s*(?:AM)?\\s*[-–—toTO]+.*") && !rep.matches(".*8(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8.*")) {
                    return 5;
                }
            }
        }

        // 6. 8 PM - 8 AM (Col 6 - Night)
        if (rep.matches("(?:.*\\b)?8(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8(?::00)?\\s*(?:AM)?(?:\\b.*)?")
                || (rep.contains("20:00") && rep.contains("08:00"))
                || rep.matches(".*\\b8\\s*[-–—toTO]+\\s*8\\b.*")
                || (rep.contains("8") && rep.contains("NIGHT"))) {
            if (!rep.contains("9") && !rep.contains("10") && !rep.contains("6") && !rep.contains("12") && !rep.contains("2")) {
                return 6;
            }
        }

        return -1;
    }

    private static List<RosterTextParser.ExtractedShiftItem> parseSpatialGrid(
            Text visionText,
            String normalizedTarget,
            String targetEmployeeName,
            int year,
            int month,
            DynamicShiftTimings timings) {

        List<Text.Line> allLines = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            allLines.addAll(block.getLines());
        }

        if (allLines.isEmpty()) return Collections.emptyList();

        List<DateAnchor> dateAnchors = new ArrayList<>();
        Map<Integer, DateAnchor> dayToAnchor = new HashMap<>();
        for (Text.Line l : allLines) {
            String text = l.getText().trim();
            String repairedDate = OcrContextCorrection.repairDateString(text, year, month);
            if (repairedDate != null) {
                int d = parseDayNumberFromDate(repairedDate);
                if (d >= 1 && d <= 31 && !dayToAnchor.containsKey(d)) {
                    DateAnchor da = new DateAnchor(d, repairedDate, l.getBoundingBox());
                    dateAnchors.add(da);
                    dayToAnchor.put(d, da);
                }
            } else if (l.getElements() != null) {
                for (Text.Element el : l.getElements()) {
                    String elDate = OcrContextCorrection.repairDateString(el.getText(), year, month);
                    if (elDate != null) {
                        int d = parseDayNumberFromDate(elDate);
                        if (d >= 1 && d <= 31 && !dayToAnchor.containsKey(d)) {
                            DateAnchor da = new DateAnchor(d, elDate, el.getBoundingBox());
                            dateAnchors.add(da);
                            dayToAnchor.put(d, da);
                            break;
                        }
                    }
                }
            }
        }
        dateAnchors.sort(Comparator.comparingInt(a -> a.centerY));

        List<TableColumn> columns = detectColumnsFromVisionText(visionText, dateAnchors);

        // Calculate table horizontal reference for tilt compensation
        int minAnchorLeft = Integer.MAX_VALUE;
        for (DateAnchor da : dateAnchors) {
            if (da.boundingBox != null && da.boundingBox.left < minAnchorLeft) {
                minAnchorLeft = da.boundingBox.left;
            }
        }
        int tableLeftRef = (minAnchorLeft != Integer.MAX_VALUE) ? Math.max(0, minAnchorLeft) : 0;

        // Estimate table tilt (slope) across wide lines
        double slopeSum = 0.0;
        int slopeCount = 0;
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line l : block.getLines()) {
                Rect box = l.getBoundingBox();
                if (box != null && (box.right - box.left) > 300) {
                    List<Text.Element> elements = l.getElements();
                    if (elements != null && elements.size() >= 2) {
                        Text.Element e1 = elements.get(0);
                        Text.Element e2 = elements.get(elements.size() - 1);
                        if (e1.getBoundingBox() != null && e2.getBoundingBox() != null) {
                            int dx = getBoxCenterX(e2.getBoundingBox()) - getBoxCenterX(e1.getBoundingBox());
                            int dy = getBoxCenterY(e2.getBoundingBox()) - getBoxCenterY(e1.getBoundingBox());
                            if (dx > 200) {
                                slopeSum += (double) dy / dx;
                                slopeCount++;
                            }
                        }
                    }
                }
            }
        }
        double tableSlope = (slopeCount > 0) ? (slopeSum / slopeCount) : 0.0;

        // Group all text elements into their (dateStr + "#" + colIndex) cells
        Map<String, List<CellElement>> cellElementsMap = new LinkedHashMap<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                List<Text.Element> elements = line.getElements();
                if (elements != null && !elements.isEmpty()) {
                    for (Text.Element el : elements) {
                        Rect box = el.getBoundingBox();
                        if (box == null) box = line.getBoundingBox();
                        if (box == null) continue;

                        int cx = getBoxCenterX(box);
                        int cy = getBoxCenterY(box);
                        int effectiveY = (int) (cy - tableSlope * (cx - tableLeftRef));

                        DateAnchor closestAnchor = null;
                        int minDiff = Integer.MAX_VALUE;
                        for (DateAnchor da : dateAnchors) {
                            int diff = Math.abs(da.centerY - effectiveY);
                            if (diff < minDiff) {
                                minDiff = diff;
                                closestAnchor = da;
                            }
                        }
                        if (closestAnchor == null) continue;

                        TableColumn matchedCol = null;
                        for (TableColumn c : columns) {
                            if (cx >= c.minX && cx < c.maxX) {
                                matchedCol = c;
                                break;
                            }
                        }
                        if (matchedCol == null && !columns.isEmpty()) {
                            matchedCol = columns.get(columns.size() - 1);
                        }
                        if (matchedCol == null || matchedCol.isDate) continue;

                        String cellKey = closestAnchor.dateStr + "#" + matchedCol.index;
                        cellElementsMap.computeIfAbsent(cellKey, k -> new ArrayList<>())
                                .add(new CellElement(el.getText(), box));
                    }
                } else {
                    Rect box = line.getBoundingBox();
                    if (box == null) continue;

                    int cx = getBoxCenterX(box);
                    int cy = getBoxCenterY(box);
                    int effectiveY = (int) (cy - tableSlope * (cx - tableLeftRef));

                    DateAnchor closestAnchor = null;
                    int minDiff = Integer.MAX_VALUE;
                    for (DateAnchor da : dateAnchors) {
                        int diff = Math.abs(da.centerY - effectiveY);
                        if (diff < minDiff) {
                            minDiff = diff;
                            closestAnchor = da;
                        }
                    }
                    if (closestAnchor == null) continue;

                    TableColumn matchedCol = null;
                    for (TableColumn c : columns) {
                        if (cx >= c.minX && cx < c.maxX) {
                            matchedCol = c;
                            break;
                        }
                    }
                    if (matchedCol == null && !columns.isEmpty()) {
                        matchedCol = columns.get(columns.size() - 1);
                    }
                    if (matchedCol == null || matchedCol.isDate) continue;

                    String cellKey = closestAnchor.dateStr + "#" + matchedCol.index;
                    cellElementsMap.computeIfAbsent(cellKey, k -> new ArrayList<>())
                            .add(new CellElement(line.getText(), box));
                }
            }
        }

        // Extract approved staff members for each cell
        Map<String, List<String>> cellStaffMap = new LinkedHashMap<>();
        Map<String, List<TableColumn>> targetDateColumns = new LinkedHashMap<>();

        for (Map.Entry<String, List<CellElement>> entry : cellElementsMap.entrySet()) {
            String cellKey = entry.getKey();
            List<CellElement> cellElements = entry.getValue();

            List<String> staffInCell = extractApprovedStaffFromCellElements(cellElements);
            cellStaffMap.put(cellKey, staffInCell);

            String[] parts = cellKey.split("#");
            String dateStr = parts[0];
            int colIndex = Integer.parseInt(parts[1]);

            TableColumn matchedCol = null;
            for (TableColumn c : columns) {
                if (c.index == colIndex) {
                    matchedCol = c;
                    break;
                }
            }
            if (matchedCol == null) continue;

            for (String staff : staffInCell) {
                safeLogInfo(TAG, "Assigned: " + staff + " -> " + dateStr + " Col " + matchedCol.index + " [" + matchedCol.headerText + "] (" + matchedCol.shiftType + ")");

                if (matchesEmployee(staff, normalizedTarget, targetEmployeeName)) {
                    List<TableColumn> list = targetDateColumns.computeIfAbsent(dateStr, k -> new ArrayList<>());
                    if (!list.contains(matchedCol)) {
                        list.add(matchedCol);
                    }
                }
            }
        }

        List<RosterTextParser.ExtractedShiftItem> extracted = new ArrayList<>();

        for (Map.Entry<String, List<TableColumn>> entry : targetDateColumns.entrySet()) {
            String dateStr = entry.getKey();
            List<TableColumn> cols = entry.getValue();

            for (TableColumn col : cols) {
                // If in Off or Leave status: skip! (Off and Leave entries do not become duties)
                if (col.isOff || col.isLeave) {
                    safeLogInfo(TAG, "[RosterDiagnostic] Date: " + dateStr + " | Status Col: " + col.index + " [" + col.headerText + "] (" + (col.isOff ? "Off" : "Leave") + ")");
                    safeLogInfo(TAG, "[RosterDiagnostic]   Target employee " + targetEmployeeName + " has " + (col.isOff ? "Off" : "Leave") + " status. Working duty NOT created (status skipped, not imported).");
                    continue;
                }

                List<String> colleaguesInCell = new ArrayList<>();
                List<String> allStaffInCell = cellStaffMap.get(dateStr + "#" + col.index);
                List<String> removedTargetList = new ArrayList<>();
                if (allStaffInCell != null) {
                    for (String staff : allStaffInCell) {
                        if (!matchesEmployee(staff, normalizedTarget, targetEmployeeName)
                                && !normalizeForComparison(staff).equals(normalizedTarget)) {
                            if (!colleaguesInCell.contains(staff)) {
                                colleaguesInCell.add(staff);
                            }
                        } else {
                            removedTargetList.add(staff);
                        }
                    }
                }

                safeLogInfo(TAG, "[RosterDiagnostic] Date: " + dateStr + " | Shift Col: " + col.index + " [" + col.headerText + "] (" + col.shiftType + ")");
                safeLogInfo(TAG, "[RosterDiagnostic]   All approved staff in cell: " + allStaffInCell);
                safeLogInfo(TAG, "[RosterDiagnostic]   Selected employee removed: " + (!removedTargetList.isEmpty() ? removedTargetList : targetEmployeeName));
                safeLogInfo(TAG, "[RosterDiagnostic]   Final colleague list: " + colleaguesInCell);

                RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
                item.date = dateStr;
                item.shiftType = col.shiftType;
                item.startTime = !col.startTime.isEmpty() ? col.startTime : col.shiftType.getDefaultStartTime();
                item.endTime = !col.endTime.isEmpty() ? col.endTime : col.shiftType.getDefaultEndTime();
                item.isOvernight = col.isOvernight;
                item.ward = "Hospital";
                item.colleagues = String.join(", ", colleaguesInCell);
                item.isAmbiguous = col.isAmbiguous;
                item.notes = col.isAmbiguous ? "Unrecognized shift timing - flagged for review" : "Imported from hospital roster";

                safeLogInfo(TAG, "Duty extracted: Date=" + dateStr + ", Shift=" + col.shiftType + " (" + item.startTime + " - " + item.endTime + "), Colleagues=" + item.colleagues);
                extracted.add(item);
            }
        }

        return extracted;
    }

    public static List<TableColumn> detectColumnsFromVisionText(Text visionText, List<DateAnchor> dateAnchors) {
        int firstAnchorY = Integer.MAX_VALUE;
        int lastAnchorY = 0;
        int minAnchorLeft = Integer.MAX_VALUE;

        for (DateAnchor da : dateAnchors) {
            int cy = da.centerY;
            if (cy < firstAnchorY) firstAnchorY = cy;
            if (cy > lastAnchorY) lastAnchorY = cy;
            if (da.boundingBox != null && da.boundingBox.left < minAnchorLeft) {
                minAnchorLeft = da.boundingBox.left;
            }
        }

        int maxTableRight = 0;
        int minTableLeft = (minAnchorLeft != Integer.MAX_VALUE) ? minAnchorLeft : Integer.MAX_VALUE;

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line l : block.getLines()) {
                Rect box = l.getBoundingBox();
                if (box == null) continue;
                int cy = getBoxCenterY(box);
                if (cy <= lastAnchorY + 50) {
                    if (box.left < minTableLeft) minTableLeft = box.left;
                    if (box.right > maxTableRight) maxTableRight = box.right;
                }
            }
        }

        int tableLeft = (minTableLeft != Integer.MAX_VALUE) ? Math.max(0, minTableLeft) : 0;
        int tableRight = (maxTableRight > tableLeft + 500) ? maxTableRight : (tableLeft + 1080);
        int tableWidth = tableRight - tableLeft;

        Map<Integer, DetectedHeader> detectedHeaders = new HashMap<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line l : block.getLines()) {
                Rect box = l.getBoundingBox();
                if (box == null) continue;
                int cy = getBoxCenterY(box);

                if (cy < firstAnchorY && box.bottom >= firstAnchorY - 200) {
                    List<Text.Element> elements = l.getElements();
                    if (elements != null && !elements.isEmpty()) {
                        int numEl = elements.size();
                        boolean[] consumed = new boolean[numEl];

                        for (int w = 1; w <= 4; w++) {
                            for (int i = 0; i <= numEl - w; i++) {
                                boolean anyConsumed = false;
                                for (int k = 0; k < w; k++) {
                                    if (consumed[i + k]) {
                                        anyConsumed = true;
                                        break;
                                    }
                                }
                                if (anyConsumed) continue;

                                StringBuilder sb = new StringBuilder();
                                Rect combinedBox = null;
                                for (int k = 0; k < w; k++) {
                                    Text.Element el = elements.get(i + k);
                                    if (sb.length() > 0) sb.append(" ");
                                    sb.append(el.getText());
                                    if (el.getBoundingBox() != null) {
                                        combinedBox = unionRect(combinedBox, el.getBoundingBox());
                                    }
                                }

                                int colType = identifyColumnType(sb.toString());
                                if (colType >= 0 && colType <= 8 && combinedBox != null) {
                                    if (!detectedHeaders.containsKey(colType)) {
                                        detectedHeaders.put(colType, new DetectedHeader(colType, sb.toString(), combinedBox));
                                        for (int k = 0; k < w; k++) {
                                            consumed[i + k] = true;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    int colType = identifyColumnType(l.getText());
                    if (colType >= 0 && colType <= 8 && !detectedHeaders.containsKey(colType) && l.getBoundingBox() != null) {
                        detectedHeaders.put(colType, new DetectedHeader(colType, l.getText(), l.getBoundingBox()));
                    }
                }
            }
        }

        safeLogInfo(TAG, "=== Table Column Detection Diagnostics ===");
        safeLogInfo(TAG, "Detected table bounds: [" + tableLeft + " .. " + tableRight + "], width=" + tableWidth);
        safeLogInfo(TAG, "Headers detected: " + detectedHeaders.size() + "/9");
        for (Map.Entry<Integer, DetectedHeader> entry : detectedHeaders.entrySet()) {
            safeLogInfo(TAG, "  Col " + entry.getKey() + " [" + HOSPITAL_COLUMN_NAMES[entry.getKey()] + "]: text='" + entry.getValue().text + "', cx=" + entry.getValue().centerX + ", bounds=" + entry.getValue().bounds);
        }

        if (detectedHeaders.size() >= 3) {
            int[] centers = new int[9];
            Arrays.fill(centers, -1);

            for (Map.Entry<Integer, DetectedHeader> entry : detectedHeaders.entrySet()) {
                centers[entry.getKey()] = entry.getValue().centerX;
            }

            if (centers[0] == -1) {
                centers[0] = tableLeft + (int) (HOSPITAL_COLUMN_FRACTIONS[0] * 0.5 * tableWidth);
            }
            if (centers[8] == -1) {
                centers[8] = tableRight - (int) (HOSPITAL_COLUMN_FRACTIONS[8] * 0.5 * tableWidth);
            }

            for (int i = 0; i < 9; i++) {
                if (centers[i] == -1) {
                    int leftIdx = i - 1;
                    while (leftIdx >= 0 && centers[leftIdx] == -1) leftIdx--;

                    int rightIdx = i + 1;
                    while (rightIdx < 9 && centers[rightIdx] == -1) rightIdx++;

                    if (leftIdx >= 0 && rightIdx < 9) {
                        double fracSpan = 0.0;
                        for (int k = leftIdx + 1; k <= rightIdx; k++) {
                            fracSpan += HOSPITAL_COLUMN_FRACTIONS[k];
                        }
                        double fracToI = 0.0;
                        for (int k = leftIdx + 1; k <= i; k++) {
                            fracToI += HOSPITAL_COLUMN_FRACTIONS[k];
                        }
                        centers[i] = centers[leftIdx] + (int) ((centers[rightIdx] - centers[leftIdx]) * (fracToI / Math.max(0.01, fracSpan)));
                    } else if (leftIdx >= 0) {
                        centers[i] = centers[leftIdx] + (int) (HOSPITAL_COLUMN_FRACTIONS[i] * tableWidth);
                    } else if (rightIdx < 9) {
                        centers[i] = centers[rightIdx] - (int) (HOSPITAL_COLUMN_FRACTIONS[i] * tableWidth);
                    }
                }
            }

            for (int i = 1; i < 9; i++) {
                if (centers[i] <= centers[i - 1]) {
                    centers[i] = centers[i - 1] + 20;
                }
            }

            List<TableColumn> cols = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                DetectedHeader dh = detectedHeaders.get(i);
                TableColumn col = new TableColumn(i, dh != null ? dh.text : HOSPITAL_COLUMN_NAMES[i], dh != null ? dh.bounds : null);
                col.minX = (i == 0) ? 0 : (centers[i - 1] + centers[i]) / 2;
                col.maxX = (i == 8) ? Integer.MAX_VALUE : (centers[i] + centers[i + 1]) / 2;

                // For Off (Col 7) and Leave (Col 8), ensure clean, independent partitioning using detected header geometry
                if (i == 7 && detectedHeaders.containsKey(8)) {
                    DetectedHeader dh7 = detectedHeaders.get(7);
                    DetectedHeader dh8 = detectedHeaders.get(8);
                    if (dh7 != null && dh7.bounds != null && dh8 != null && dh8.bounds != null) {
                        int split78 = (dh7.bounds.right + dh8.bounds.left) / 2;
                        col.maxX = split78;
                    }
                } else if (i == 8 && detectedHeaders.containsKey(8) && detectedHeaders.containsKey(7)) {
                    DetectedHeader dh7 = detectedHeaders.get(7);
                    DetectedHeader dh8 = detectedHeaders.get(8);
                    if (dh7 != null && dh7.bounds != null && dh8 != null && dh8.bounds != null) {
                        int split78 = (dh7.bounds.right + dh8.bounds.left) / 2;
                        col.minX = split78;
                    }
                }
                cols.add(col);
                safeLogInfo(TAG, "Calculated Col " + i + " [" + col.headerText + "]: span=[" + col.minX + " .. " + col.maxX + "], shift=" + col.shiftType + ", times=" + col.startTime + "-" + col.endTime);
            }
            return cols;
        }

        safeLogInfo(TAG, "Header detection found < 3 headers. Using measured hospital roster column proportions.");
        List<TableColumn> fallbackCols = createDefaultHospitalColumns(tableLeft, tableRight);
        for (TableColumn c : fallbackCols) {
            safeLogInfo(TAG, "Fallback Col " + c.index + " [" + c.headerText + "]: span=[" + c.minX + " .. " + c.maxX + "], shift=" + c.shiftType + ", times=" + c.startTime + "-" + c.endTime);
        }
        return fallbackCols;
    }

    public static List<TableColumn> createDefaultHospitalColumns(int tableLeft, int tableRight) {
        int width = Math.max(600, tableRight - tableLeft);
        List<TableColumn> list = new ArrayList<>();
        double currFrac = 0.0;
        for (int i = 0; i < HOSPITAL_COLUMN_NAMES.length; i++) {
            TableColumn col = new TableColumn(i, HOSPITAL_COLUMN_NAMES[i], null);
            int startX = (i == 0) ? 0 : (int) (tableLeft + currFrac * width);
            currFrac += HOSPITAL_COLUMN_FRACTIONS[i];
            int endX = (i == HOSPITAL_COLUMN_NAMES.length - 1) ? Integer.MAX_VALUE : (int) (tableLeft + currFrac * width);
            col.minX = startX;
            col.maxX = endX;
            list.add(col);
        }
        return list;
    }

    public static List<TableColumn> detectColumnsFromDelimitedHeader(String headerLine) {
        List<TableColumn> cols = new ArrayList<>();
        if (headerLine == null) return cols;

        String[] parts = headerLine.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            cols.add(new TableColumn(i, parts[i].trim(), null));
        }
        return cols;
    }

    @Nullable
    public static ParsedShiftTiming parseShiftTiming(@Nullable String text) {
        if (text == null) return null;
        String s = text.toUpperCase(Locale.ENGLISH)
                .replace('Z', '2')
                .replace('S', '5')
                .replace('B', '8')
                .replaceAll("\\s+", " ")
                .trim();

        // 1. 12 PM - 8 PM (must be checked BEFORE 2 PM - 8 PM to avoid 12 matching 2)
        if (s.matches(".*\\b12(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8(?::00)?\\s*(?:PM)?.*")
                || s.matches(".*\\b12\\s*[-–—toTO]+\\s*8\\b.*")
                || s.matches(".*12:00.*20:00.*")) {
            return new ParsedShiftTiming(ShiftType.EVENING, "12:00 PM", "08:00 PM", false);
        }

        // 2. 10 AM - 6 PM
        if (s.matches(".*\\b10(?::00)?\\s*(?:AM)?\\s*[-–—toTO]+\\s*6(?::00)?\\s*(?:PM)?.*")
                || s.matches(".*\\b10\\s*[-–—toTO]+\\s*6\\b.*")
                || s.matches(".*10:00.*18:00.*")) {
            return new ParsedShiftTiming(ShiftType.GENERAL, "10:00 AM", "06:00 PM", false);
        }

        // 3. 9 AM - 5 PM
        if (s.matches(".*\\b9(?::00)?\\s*(?:AM)?\\s*[-–—toTO]+\\s*5(?::00)?\\s*(?:PM)?.*")
                || s.matches(".*\\b9\\s*[-–—toTO]+\\s*5\\b.*")
                || s.matches(".*09:00.*17:00.*")) {
            return new ParsedShiftTiming(ShiftType.GENERAL, "09:00 AM", "05:00 PM", false);
        }

        // 4. 8 AM - 2 PM (Morning)
        if (s.matches(".*\\b8(?::00)?\\s*(?:AM)?\\s*[-–—toTO]+\\s*2(?::00)?\\s*(?:PM)?.*")
                || s.matches(".*08:00.*14:00.*")
                || (s.contains("8") && s.contains("2") && !s.contains("12") && (s.contains("AM") || s.contains("MORN") || s.startsWith("8")))) {
            return new ParsedShiftTiming(ShiftType.MORNING, "08:00 AM", "02:00 PM", false);
        }

        // 5. 2 PM - 8 PM (Evening)
        if (s.matches(".*\\b2(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8(?::00)?\\s*(?:PM)?.*")
                || s.matches(".*14:00.*20:00.*")
                || (s.contains("2") && s.contains("8") && !s.contains("12") && (s.contains("PM") || s.contains("EVEN") || s.startsWith("2")))) {
            return new ParsedShiftTiming(ShiftType.EVENING, "02:00 PM", "08:00 PM", false);
        }

        // 6. 8 PM - 8 AM (Night)
        if (s.matches(".*\\b8(?::00)?\\s*(?:PM)?\\s*[-–—toTO]+\\s*8(?::00)?\\s*(?:AM)?.*")
                || s.matches(".*20:00.*08:00.*")
                || s.matches(".*\\b8\\s*[-–—toTO]+\\s*8\\b.*")
                || (s.contains("8") && s.contains("NIGHT"))) {
            return new ParsedShiftTiming(ShiftType.NIGHT, "08:00 PM", "08:00 AM", true);
        }

        return null;
    }

    // ── 6. Multiline & Delimited Text Block Parsing ───────────────────────────

    private static List<RosterTextParser.ExtractedShiftItem> parseLineBlocksFallback(
            String fullText,
            String normalizedTarget,
            String targetEmployeeName,
            int year,
            int month,
            DynamicShiftTimings timings) {

        List<RosterTextParser.ExtractedShiftItem> list = new ArrayList<>();
        String[] lines = fullText.split("\\r?\\n");

        List<TableColumn> headerColumns = new ArrayList<>();
        String currentDate = null;

        // Check if layout is Layout B (Staff rows with Day 1..31 columns)
        if (isLayoutB(lines)) {
            return parseLayoutBStaffRows(lines, normalizedTarget, targetEmployeeName, year, month, timings);
        }

        // 1. Scan for header line defining columns
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.contains("|")) {
                String up = line.toUpperCase(Locale.ENGLISH);
                if (up.contains("9-5") || up.contains("8-2") || up.contains("2-8") || up.contains("8-8")
                        || up.contains("MORN") || up.contains("EVEN") || up.contains("NIGHT") || up.contains("OFF")) {
                    headerColumns = detectColumnsFromDelimitedHeader(line);
                    break;
                }
            }
        }

        // Layout A: Date rows with shift columns
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            String dateInLine = extractDateFromText(line, year, month);
            if (dateInLine != null) {
                currentDate = dateInLine;
            }

            if (!matchesEmployee(line, normalizedTarget, targetEmployeeName)) {
                continue;
            }

            String dutyDate = dateInLine != null ? dateInLine : currentDate;
            if (dutyDate == null) {
                dutyDate = extractDayNumberFromLine(line, year, month);
            }
            if (dutyDate == null) continue;

            if (line.contains("|")) {
                String[] cells = line.split("\\|", -1);
                for (int c = 0; c < cells.length; c++) {
                    String cell = cells[c];
                    if (matchesEmployee(cell, normalizedTarget, targetEmployeeName)) {
                        TableColumn col = (c < headerColumns.size()) ? headerColumns.get(c) : null;

                        ParsedShiftTiming pst = null;
                        if (col != null) {
                            if (col.isOff || col.isLeave) {
                                safeLogInfo(TAG, "[RosterDiagnostic] Date: " + dutyDate + " | Status Col: " + col.index + " [" + col.headerText + "] (" + (col.isOff ? "Off" : "Leave") + ")");
                                safeLogInfo(TAG, "[RosterDiagnostic]   Target employee " + targetEmployeeName + " has " + (col.isOff ? "Off" : "Leave") + " status. Working duty NOT created (status skipped, not imported).");
                                continue;
                            }
                            if (!col.isAmbiguous) {
                                pst = new ParsedShiftTiming(col.shiftType, col.startTime, col.endTime, col.isOvernight);
                            }
                        }

                        if (pst == null) {
                            pst = parseShiftTiming(cell);
                            if (cell.toUpperCase(Locale.ENGLISH).contains("OFF") || cell.toUpperCase(Locale.ENGLISH).contains("LEAVE")) {
                                safeLogInfo(TAG, "[RosterDiagnostic] Date: " + dutyDate + " | Fallback Cell contains Off/Leave. Working duty NOT created (status skipped).");
                                continue; // Status only
                            }
                        }

                        ShiftType shiftType = pst != null ? pst.shiftType : ShiftType.GENERAL;
                        String startTime = pst != null ? pst.startTime : "";
                        String endTime = pst != null ? pst.endTime : "";
                        boolean isOvernight = pst != null && pst.isOvernight;
                        boolean isAmbiguous = pst == null;

                        // Colleagues strictly from this cell ONLY
                        List<String> colleagues = extractColleaguesFromLine(cell, normalizedTarget);

                        safeLogInfo(TAG, "[RosterDiagnostic] Date: " + dutyDate + " | Shift Col: " + (col != null ? col.index + " [" + col.headerText + "]" : "Cell") + " (" + shiftType + ")");
                        safeLogInfo(TAG, "[RosterDiagnostic]   Raw cell: " + cell);
                        safeLogInfo(TAG, "[RosterDiagnostic]   Selected employee removed: " + targetEmployeeName);
                        safeLogInfo(TAG, "[RosterDiagnostic]   Final colleague list: " + colleagues);

                        RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
                        item.date = dutyDate;
                        item.shiftType = shiftType;
                        item.startTime = !startTime.isEmpty() ? startTime : shiftType.getDefaultStartTime();
                        item.endTime = !endTime.isEmpty() ? endTime : shiftType.getDefaultEndTime();
                        item.isOvernight = isOvernight;
                        item.ward = "Hospital";
                        item.colleagues = String.join(", ", colleagues);
                        item.isAmbiguous = isAmbiguous;
                        item.notes = isAmbiguous ? "Unrecognized shift timing - flagged for review" : "Imported from hospital roster";

                        list.add(item);
                    }
                }
            } else {
                List<RosterTextParser.ExtractedShiftItem> parsedSegments = parseShiftSegmentsFromLine(line, dutyDate, normalizedTarget, targetEmployeeName);
                if (!parsedSegments.isEmpty()) {
                    list.addAll(parsedSegments);
                }
            }
        }

        return list;
    }

    private static List<RosterTextParser.ExtractedShiftItem> parseShiftSegmentsFromLine(
            String line,
            String dutyDate,
            String normalizedTarget,
            String targetEmployeeName) {

        List<RosterTextParser.ExtractedShiftItem> list = new ArrayList<>();
        Pattern pMarker = Pattern.compile("(?:\\b(?:9\\s*AM\\s*[-–—toTO]+\\s*5\\s*PM|10\\s*AM\\s*[-–—toTO]+\\s*6\\s*PM|12\\s*PM\\s*[-–—toTO]+\\s*8\\s*PM|8\\s*AM\\s*[-–—toTO]+\\s*2\\s*PM|2\\s*PM\\s*[-–—toTO]+\\s*8\\s*PM|8\\s*PM\\s*[-–—toTO]+\\s*8\\s*AM|MORNING|EVENING|NIGHT|GENERAL|OFF|LEAVE)\\s*[:\\-–—])", Pattern.CASE_INSENSITIVE);
        Matcher m = pMarker.matcher(line);

        List<Integer> startIndices = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        while (m.find()) {
            startIndices.add(m.start());
            markers.add(m.group());
        }

        if (startIndices.isEmpty()) {
            if (matchesEmployee(line, normalizedTarget, targetEmployeeName)) {
                RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
                item.date = dutyDate;
                item.shiftType = ShiftType.GENERAL;
                item.startTime = "";
                item.endTime = "";
                item.isOvernight = false;
                item.ward = "Hospital";
                item.colleagues = "";
                item.isAmbiguous = true;
                item.notes = "Unrecognized shift timing - flagged for review";
                list.add(item);
            }
            return list;
        }

        for (int i = 0; i < startIndices.size(); i++) {
            int start = startIndices.get(i);
            int end = (i < startIndices.size() - 1) ? startIndices.get(i + 1) : line.length();
            String segment = line.substring(start, end).trim();

            if (matchesEmployee(segment, normalizedTarget, targetEmployeeName)) {
                String marker = markers.get(i).toUpperCase(Locale.ENGLISH);
                if (marker.contains("OFF") || marker.contains("LEAVE")) {
                    // Status only: do not create duty
                    continue;
                }

                ParsedShiftTiming pst = parseShiftTiming(marker);
                ShiftType shiftType = pst != null ? pst.shiftType : ShiftType.GENERAL;
                String startTime = pst != null ? pst.startTime : "";
                String endTime = pst != null ? pst.endTime : "";
                boolean isOvernight = pst != null && pst.isOvernight;
                boolean isAmbiguous = pst == null;

                // Colleagues strictly from this segment ONLY
                List<String> colleagues = extractColleaguesFromLine(segment, normalizedTarget);

                RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
                item.date = dutyDate;
                item.shiftType = shiftType;
                item.startTime = !startTime.isEmpty() ? startTime : shiftType.getDefaultStartTime();
                item.endTime = !endTime.isEmpty() ? endTime : shiftType.getDefaultEndTime();
                item.isOvernight = isOvernight;
                item.ward = "Hospital";
                item.colleagues = String.join(", ", colleagues);
                item.isAmbiguous = isAmbiguous;
                item.notes = isAmbiguous ? "Unrecognized shift timing - flagged for review" : "Imported from hospital roster";
                list.add(item);
            }
        }

        return list;
    }

    private static boolean isLayoutB(String[] lines) {
        for (String line : lines) {
            if (line.matches(".*\\b1\\b.*\\b2\\b.*\\b3\\b.*\\b4\\b.*\\b5\\b.*") && line.contains("|")) {
                return true;
            }
        }
        return false;
    }

    private static List<RosterTextParser.ExtractedShiftItem> parseLayoutBStaffRows(
            String[] lines,
            String normalizedTarget,
            String targetEmployeeName,
            int year,
            int month,
            DynamicShiftTimings timings) {

        List<RosterTextParser.ExtractedShiftItem> list = new ArrayList<>();
        Map<Integer, List<String>> dutyDayStaff = new HashMap<>();

        String[] targetCells = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.contains("|")) continue;

            String[] cells = line.split("\\|");
            if (cells.length < 5) continue;

            String staffCell = cells[0].trim();
            if (matchesEmployee(staffCell, normalizedTarget, targetEmployeeName)) {
                targetCells = cells;
            } else {
                String cleanStaff = cleanName(staffCell);
                if (isValidStaffName(cleanStaff)) {
                    for (int d = 1; d < cells.length && d <= 31; d++) {
                        String code = cells[d].trim().toUpperCase(Locale.ENGLISH);
                        if (!code.isEmpty() && !code.equals("-")) {
                            dutyDayStaff.computeIfAbsent(d, k -> new ArrayList<>()).add(cleanStaff + ":" + code);
                        }
                    }
                }
            }
        }

        if (targetCells != null) {
            for (int d = 1; d < targetCells.length && d <= 31; d++) {
                if (!OcrContextCorrection.isValidCalendarDate(year, month, d)) continue;

                String code = targetCells[d].trim().toUpperCase(Locale.ENGLISH);
                if (code.isEmpty() || code.equals("-")) continue;

                ShiftType shiftType = ShiftType.fromCode(code);
                String date = String.format(Locale.ENGLISH, "%04d-%02d-%02d", year, month, d);

                if (shiftType == ShiftType.OFF || shiftType == ShiftType.LEAVE) {
                    safeLogInfo(TAG, "[RosterDiagnostic] Date: " + date + " | Status: " + shiftType);
                    safeLogInfo(TAG, "[RosterDiagnostic]   Target employee " + targetEmployeeName + " has " + shiftType + " status. Working duty NOT created (status skipped, not imported).");
                    continue;
                }

                // Find coworkers on same shift on this day
                List<String> colleagues = new ArrayList<>();
                List<String> dayStaff = dutyDayStaff.get(d);
                if (dayStaff != null) {
                    for (String sc : dayStaff) {
                        String[] p = sc.split(":");
                        if (p.length == 2 && ShiftType.fromCode(p[1]) == shiftType) {
                            colleagues.add(p[0]);
                        }
                    }
                }

                safeLogInfo(TAG, "[RosterDiagnostic] Date: " + date + " | Shift: " + shiftType);
                safeLogInfo(TAG, "[RosterDiagnostic]   Selected employee removed: " + targetEmployeeName);
                safeLogInfo(TAG, "[RosterDiagnostic]   Final colleague list: " + colleagues);

                RosterTextParser.ExtractedShiftItem item = createShiftItem(date, shiftType, colleagues, timings);
                list.add(item);
            }
        }

        return list;
    }

    @Nullable
    private static String extractDateFromText(String text, int year, int month) {
        // Use contextual date repair (handles 2->Z, 5->S, 8->B and calendar validation)
        String repaired = OcrContextCorrection.repairDateString(text, year, month);
        if (repaired != null) return repaired;

        return DateTimeUtils.tryParseOcrDate(text, year, month);
    }

    @Nullable
    private static String extractDayNumberFromLine(String line, int year, int month) {
        Pattern pDay = Pattern.compile("^\\s*([0-9A-Za-z]{1,2})\\b");
        Matcher m = pDay.matcher(line);
        if (m.find()) {
            int d = OcrContextCorrection.parseContextualInt(m.group(1));
            if (OcrContextCorrection.isValidCalendarDate(year, month, d)) {
                return String.format(Locale.ENGLISH, "%04d-%02d-%02d", year, month, d);
            }
        }
        return null;
    }

    private static List<String> extractColleaguesFromLine(String line, String normalizedTarget) {
        List<String> colleagues = new ArrayList<>();
        // Strip out date and shift codes first
        String stripped = line.replaceAll("\\b\\d{1,2}[-/]\\d{1,2}(?:[-/]\\d{2,4})?\\b", " ")
                .replaceAll("\\b(?:MORNING|EVENING|NIGHT|GENERAL|OFF|LEAVE)\\b", " ")
                .replaceAll("\\b(?:08:00|02:00|08:30|01:30|07:00|09:00|05:00|AM|PM|am|pm|Sam|Spm|Bam|Zpm)\\b", " ");

        List<String> allStaff = extractApprovedStaffFromCellText(stripped);
        for (String staff : allStaff) {
            if (!normalizeForComparison(staff).equals(normalizedTarget)) {
                if (!colleagues.contains(staff)) {
                    colleagues.add(staff);
                }
            }
        }
        return colleagues;
    }

    private static RosterTextParser.ExtractedShiftItem createShiftItem(
            String date,
            ShiftType shiftType,
            List<String> colleaguesList,
            DynamicShiftTimings timings) {

        RosterTextParser.ExtractedShiftItem item = new RosterTextParser.ExtractedShiftItem();
        item.date = date;
        item.shiftType = shiftType;

        if (shiftType == ShiftType.MORNING) {
            item.startTime = timings.morningStart;
            item.endTime = timings.morningEnd;
            item.isOvernight = false;
        } else if (shiftType == ShiftType.EVENING) {
            item.startTime = timings.eveningStart;
            item.endTime = timings.eveningEnd;
            item.isOvernight = false;
        } else if (shiftType == ShiftType.NIGHT) {
            item.startTime = timings.nightStart;
            item.endTime = timings.nightEnd;
            item.isOvernight = true;
        } else if (shiftType == ShiftType.GENERAL) {
            item.startTime = "09:00 AM";
            item.endTime = "05:00 PM";
            item.isOvernight = false;
        } else {
            item.startTime = "";
            item.endTime = "";
            item.isOvernight = false;
        }

        item.ward = "Hospital";
        item.colleagues = String.join(", ", colleaguesList);
        item.isAmbiguous = false;
        item.notes = "Imported from hospital roster";
        return item;
    }

    private static final Pattern SHIFT_OR_LABEL_PREFIX = Pattern.compile(
            "^(?:MORNING|EVENING|NIGHT|GENERAL|OFF|LEAVE|DUTY|STAFF|NAME)\\s*[:\\-–—]?\\s+|^[MENGD]\\s*[:\\-–—]\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HONORIFIC_PREFIX = Pattern.compile(
            "^(?:DR|DOCTOR|SR|SISTER|BRO|BROTHER|NURSE|STAFF)\\.?\\s+",
            Pattern.CASE_INSENSITIVE);

    public static String stripPrefixes(String name) {
        if (name == null) return "";
        String s = name.trim();
        s = SHIFT_OR_LABEL_PREFIX.matcher(s).replaceFirst("").trim();
        s = HONORIFIC_PREFIX.matcher(s).replaceFirst("").trim();
        return s;
    }

    // ── 7. Name Matching & Identity Safety ────────────────────────────────────

    public static boolean matchesEmployee(String text, String normalizedTarget, String targetDisplayName) {
        if (text == null || text.isEmpty() || normalizedTarget == null || normalizedTarget.isEmpty()) return false;

        // 1. Direct match after cleaning and stripping prefixes
        String directClean = cleanName(text);
        String directStripped = stripPrefixes(text);
        if (normalizeForComparison(directClean).equals(normalizedTarget) ||
                normalizeForComparison(cleanName(directStripped)).equals(normalizedTarget)) {
            return true;
        }

        // 2. Allowlist normalization (e.g. Yarmini -> Yamini, Hansraj Naveen -> Hansraj, Naveen)
        StaffAllowlistManager.StaffResolutionResult res = StaffAllowlistManager.resolveCandidate(directStripped);
        if (res.isApproved()) {
            for (String canon : res.canonicalNames) {
                if (normalizeForComparison(canon).equals(normalizedTarget)) {
                    return true;
                }
            }
        }

        // 3. Multi-person delimiter split (handles "Yamini, Priya", "Yamini / Anita", "Yamini & Priya", "Yamini and Priya", newlines, pipes)
        String[] personSegments = text.split("[,/;&+\\n|]|\\s+and\\s+");
        if (personSegments.length > 1) {
            for (String segment : personSegments) {
                String segClean = cleanName(segment);
                String segStripped = stripPrefixes(segment);
                if (normalizeForComparison(segClean).equals(normalizedTarget) ||
                        normalizeForComparison(cleanName(segStripped)).equals(normalizedTarget)) {
                    return true;
                }
                StaffAllowlistManager.StaffResolutionResult segRes = StaffAllowlistManager.resolveCandidate(segStripped);
                if (segRes.isApproved()) {
                    for (String canon : segRes.canonicalNames) {
                        if (normalizeForComparison(canon).equals(normalizedTarget)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    // ── 8. Text Sanitization Helpers ──────────────────────────────────────────

    private static List<String> extractPotentialNameTokens(Text visionText, String fullText) {
        List<String> list = new ArrayList<>();
        if (visionText != null) {
            for (Text.TextBlock block : visionText.getTextBlocks()) {
                String blockText = block.getText();
                // Exclude entire block if it is a title, month header, or timing legend
                if (OcrContextCorrection.isHeaderOrLegendLine(blockText)) {
                    continue;
                }

                for (Text.Line line : block.getLines()) {
                    String lineText = line.getText();
                    if (OcrContextCorrection.isHeaderOrLegendLine(lineText)) {
                        continue;
                    }

                    String[] words = lineText.split("[,/;&+|]|\t|\\s{2,}");
                    for (String w : words) {
                        String t = w.trim();
                        if (!OcrContextCorrection.isNonPersonToken(t)) {
                            list.add(t);
                        }
                    }
                }
            }
        } else if (fullText != null) {
            String[] lines = fullText.split("\\r?\\n");
            for (String l : lines) {
                if (OcrContextCorrection.isHeaderOrLegendLine(l)) continue;

                String[] words = l.split("[,/;&+|]|\t|\\s{2,}");
                for (String w : words) {
                    String t = w.trim();
                    if (!OcrContextCorrection.isNonPersonToken(t)) {
                        list.add(t);
                    }
                }
            }
        }
        return list;
    }

    public static String cleanName(String raw) {
        if (raw == null) return "";
        // Remove parenthesized text, e.g. (ICU), (Nurse), (Sister)
        String s = raw.replaceAll("\\([^)]*\\)", " ");
        // Remove numbers, special symbols, leading bullets, pipes
        s = s.replaceAll("[0-9#*•\\-()_.:|]", " ").trim();
        // Normalize multiple spaces into single space
        s = s.replaceAll("\\s+", " ");
        return toTitleCase(s);
    }

    public static boolean isValidStaffName(String name) {
        if (name == null || name.length() < 3) return false;
        if (OcrContextCorrection.isNonPersonToken(name)) return false;

        // Must contain at least 3 genuine letters
        int letterCount = 0;
        for (char c : name.toCharArray()) {
            if (Character.isLetter(c)) letterCount++;
        }
        if (letterCount < 3) return false;

        // Discard if contains digits
        if (name.matches(".*\\d+.*")) return false;

        return true;
    }

    public static String normalizeForComparison(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    private static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return "";
        String[] words = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                sb.append(w.substring(1).toLowerCase(Locale.ENGLISH));
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toUpperCase(Locale.ENGLISH) + str.substring(1).toLowerCase(Locale.ENGLISH);
    }
}
