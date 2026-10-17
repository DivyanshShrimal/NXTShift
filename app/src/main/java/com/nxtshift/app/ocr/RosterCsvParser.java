package com.nxtshift.app.ocr;

import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.nxtshift.app.data.model.MonthlyRoster;
import com.nxtshift.app.domain.ShiftType;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Intelligent parser for hospital duty rosters uploaded as CSV or Excel (XLSX).
 * Accurately extracts shift column definitions, daily shift cell assignments,
 * approved staff members, and keeps Off and Leave strictly separated.
 */
public class RosterCsvParser {
    private static final String TAG = "RosterCsvParser";

    public static class ParseResult {
        public final MonthlyRoster roster;
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int parsedRowCount = 0;
        public int totalStaffIdentified = 0;

        public ParseResult(MonthlyRoster roster) {
            this.roster = roster;
        }

        public boolean isValid() {
            return errors.isEmpty() && roster != null && !roster.getDays().isEmpty();
        }
    }

    private static class ColumnInfo {
        int index;
        String headerName;
        ShiftType shiftType;
        String startTime;
        String endTime;
        boolean isOvernight;
        boolean isDateCol;
        boolean isOffCol;
        boolean isLeaveCol;
    }

    /**
     * Parses an input stream (CSV or XLSX) for the specified target year and month.
     */
    public static ParseResult parse(InputStream inputStream, String fileName, int targetYear, int targetMonth) throws IOException {
        String ext = "";
        if (fileName != null && fileName.contains(".")) {
            ext = fileName.substring(fileName.lastIndexOf(".")).toLowerCase(Locale.US);
        }

        if (ext.equals(".xlsx")) {
            com.nxtshift.app.excel.ExcelRosterData excelData = com.nxtshift.app.excel.ExcelRosterParser.parse(inputStream, fileName, targetYear, targetMonth);
            if (!excelData.isValid()) {
                String monthName = Month.of(targetMonth).getDisplayName(TextStyle.FULL, Locale.US);
                String monthLabel = monthName + " " + targetYear;
                MonthlyRoster roster = new MonthlyRoster(targetYear, targetMonth, monthLabel);
                ParseResult result = new ParseResult(roster);
                result.errors.addAll(excelData.getErrors());
                result.warnings.addAll(excelData.getWarnings());
                return result;
            }
            MonthlyRoster roster = excelData.toMonthlyRoster();
            ParseResult result = new ParseResult(roster);
            result.warnings.addAll(excelData.getWarnings());
            result.parsedRowCount = excelData.getDays().size();
            result.totalStaffIdentified = excelData.getUniqueStaffNames().size();
            return result;
        }

        List<List<String>> rawRows = parseCsvStream(inputStream);
        return parseTableRows(rawRows, targetYear, targetMonth);
    }

    /**
     * Core tabular grid processor.
     */
    public static ParseResult parseTableRows(List<List<String>> rows, int targetYear, int targetMonth) {
        String monthName = Month.of(targetMonth).getDisplayName(TextStyle.FULL, Locale.US);
        String monthLabel = monthName + " " + targetYear;
        MonthlyRoster roster = new MonthlyRoster(targetYear, targetMonth, monthLabel);
        ParseResult result = new ParseResult(roster);

        if (rows == null || rows.isEmpty()) {
            result.errors.add("The uploaded spreadsheet is empty.");
            return result;
        }

        // 1. Detect header row
        int headerRowIndex = -1;
        List<ColumnInfo> columns = new ArrayList<>();
        int dateColIndex = -1;

        for (int r = 0; r < Math.min(rows.size(), 10); r++) {
            List<String> row = rows.get(r);
            List<ColumnInfo> candidateCols = identifyColumns(row);
            int dateIdx = -1;
            int shiftCount = 0;

            for (ColumnInfo ci : candidateCols) {
                if (ci.isDateCol) dateIdx = ci.index;
                if (!ci.isDateCol && (ci.shiftType != null || ci.isOffCol || ci.isLeaveCol)) {
                    shiftCount++;
                }
            }

            if (dateIdx != -1 && shiftCount >= 2) {
                headerRowIndex = r;
                columns = candidateCols;
                dateColIndex = dateIdx;
                break;
            }
        }

        if (headerRowIndex == -1 || dateColIndex == -1) {
            result.errors.add("Could not detect standard roster header columns. Expected columns for Date and Shifts (e.g. 'Date', '8 AM - 2 PM', '2 PM - 8 PM', '8 PM - 8 AM', 'Off', 'Leave').");
            return result;
        }

        // Populate ShiftColumnDefinition in roster
        for (ColumnInfo ci : columns) {
            if (!ci.isDateCol) {
                roster.getShiftColumns().add(new MonthlyRoster.ShiftColumnDefinition(
                        ci.headerName,
                        ci.shiftType != null ? ci.shiftType.name() : (ci.isOffCol ? ShiftType.OFF.name() : ShiftType.LEAVE.name()),
                        ci.startTime,
                        ci.endTime,
                        ci.isOvernight,
                        ci.isOffCol,
                        ci.isLeaveCol
                ));
            }
        }

        // 2. Process data rows
        Set<String> allIdentifiedStaff = new LinkedHashSet<>();
        YearMonth ym = YearMonth.of(targetYear, targetMonth);
        int daysInMonth = ym.lengthOfMonth();
        Map<Integer, MonthlyRoster.DaySchedule> dayMap = new LinkedHashMap<>();

        // Initialize all days of the month
        for (int d = 1; d <= daysInMonth; d++) {
            String isoDate = String.format(Locale.US, "%04d-%02d-%02d", targetYear, targetMonth, d);
            dayMap.put(d, new MonthlyRoster.DaySchedule(isoDate, d));
        }

        int validDateRows = 0;

        for (int r = headerRowIndex + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row == null || row.isEmpty() || dateColIndex >= row.size()) continue;

            String dateCell = row.get(dateColIndex).trim();
            if (dateCell.isEmpty()) continue;

            Integer dayNum = parseDayNumber(dateCell, targetYear, targetMonth);
            if (dayNum == null || dayNum < 1 || dayNum > daysInMonth) {
                // Ignore non-date metadata rows (e.g., summary rows at bottom)
                continue;
            }

            validDateRows++;
            MonthlyRoster.DaySchedule daySchedule = dayMap.get(dayNum);
            if (daySchedule == null) continue;

            // Process shift columns for this day
            for (ColumnInfo ci : columns) {
                if (ci.isDateCol || ci.index >= row.size()) continue;

                String cellContent = row.get(ci.index).trim();
                if (cellContent.isEmpty() || cellContent.equals("-") || cellContent.equals(".")) continue;

                List<String> names = extractStaffNamesFromCell(cellContent, result.warnings, dayNum, ci.headerName);

                if (names.isEmpty()) continue;

                allIdentifiedStaff.addAll(names);

                if (ci.isLeaveCol) {
                    daySchedule.getLeaveStaff().addAll(names);
                } else if (ci.isOffCol) {
                    daySchedule.getOffStaff().addAll(names);
                } else {
                    daySchedule.getShiftAssignments().add(new MonthlyRoster.ShiftAssignment(
                            ci.headerName,
                            ci.shiftType != null ? ci.shiftType.name() : ShiftType.GENERAL.name(),
                            ci.startTime,
                            ci.endTime,
                            ci.isOvernight,
                            names
                    ));
                }
            }
        }

        result.parsedRowCount = validDateRows;
        result.totalStaffIdentified = allIdentifiedStaff.size();

        if (validDateRows == 0) {
            result.errors.add("No valid dates found matching " + monthLabel + " in the date column.");
            return result;
        }

        // Add verified days to roster
        List<MonthlyRoster.DaySchedule> finalDays = new ArrayList<>();
        for (int d = 1; d <= daysInMonth; d++) {
            MonthlyRoster.DaySchedule ds = dayMap.get(d);
            if (ds != null) {
                finalDays.add(ds);
                // Check if day has no staff at all
                if (ds.getShiftAssignments().isEmpty() && ds.getOffStaff().isEmpty() && ds.getLeaveStaff().isEmpty()) {
                    result.warnings.add("Day " + d + " (" + ds.getDate() + ") has no staff assigned to any shift, off, or leave.");
                }
            }
        }
        roster.setDays(finalDays);

        // Sort and store staff list
        List<String> sortedStaff = new ArrayList<>(allIdentifiedStaff);
        Collections.sort(sortedStaff);
        roster.setStaffList(sortedStaff);

        if (sortedStaff.isEmpty()) {
            result.errors.add("No approved hospital staff members were identified in the spreadsheet.");
        }

        return result;
    }

    /**
     * Extracts individual staff members from cell text, resolving aliases and handling delimiters.
     */
    private static List<String> extractStaffNamesFromCell(String cellText, List<String> warnings, int dayNum, String colHeader) {
        List<String> result = new ArrayList<>();
        if (cellText == null || cellText.trim().isEmpty()) return result;

        // Split by commas, semicolons, slashes, newlines, or '+'
        String[] tokens = cellText.split("[,;/\\n\\+]");
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (token.isEmpty()) continue;

            // Resolve using StaffAllowlistManager
            StaffAllowlistManager.StaffResolutionResult res = StaffAllowlistManager.resolveCandidate(token);

            if (res.isApproved()) {
                result.addAll(res.canonicalNames);
            } else if (res.status == StaffAllowlistManager.MatchStatus.AMBIGUOUS) {
                // Ambiguous prefix like "Ajay"
                warnings.add("Ambiguous staff name '" + token + "' on day " + dayNum + " in column '" + colHeader + "'. Matches multiple staff members.");
                result.add(token); // Retain original to prevent data loss
            } else {
                // Check if token matches standard staff allowing case insensitivity
                String matched = findCaseInsensitiveStaff(token);
                if (matched != null) {
                    result.add(matched);
                } else {
                    warnings.add("Unrecognized name or entry '" + token + "' on day " + dayNum + " in column '" + colHeader + "'.");
                    result.add(token);
                }
            }
        }

        return result;
    }

    private static String findCaseInsensitiveStaff(String name) {
        String clean = name.trim().toLowerCase(Locale.US);
        for (String approved : StaffAllowlistManager.DEFAULT_13_CANONICAL_STAFF) {
            if (approved.toLowerCase(Locale.US).equals(clean)) {
                return approved;
            }
        }
        if (clean.equals("yamini") || clean.equals("yarmini")) {
            return "Yamini";
        }
        return null;
    }

    /**
     * Identifies column types from header cells.
     */
    private static List<ColumnInfo> identifyColumns(List<String> headerRow) {
        List<ColumnInfo> cols = new ArrayList<>();

        for (int i = 0; i < headerRow.size(); i++) {
            String h = headerRow.get(i).trim();
            if (h.isEmpty()) continue;

            String lower = h.toLowerCase(Locale.US);
            ColumnInfo ci = new ColumnInfo();
            ci.index = i;
            ci.headerName = h;

            if (lower.equals("date") || lower.equals("day") || lower.equals("d") || lower.startsWith("date ") || lower.contains("date")) {
                ci.isDateCol = true;
            } else if (lower.contains("leave") || lower.equals("cl") || lower.equals("el") || lower.equals("sl") || lower.equals("l")) {
                ci.isLeaveCol = true;
                ci.shiftType = ShiftType.LEAVE;
            } else if (lower.contains("off") || lower.equals("w/o") || lower.equals("wo")) {
                ci.isOffCol = true;
                ci.shiftType = ShiftType.OFF;
            } else if (lower.contains("8 am - 2 pm") || lower.contains("8-2") || lower.contains("08:00 - 14:00")
                    || (lower.contains("morning") && !lower.contains("night"))) {
                ci.shiftType = ShiftType.MORNING;
                ci.startTime = "08:00 AM";
                ci.endTime = "02:00 PM";
                ci.isOvernight = false;
            } else if (lower.contains("2 pm - 8 pm") || lower.contains("2-8") || lower.contains("14:00 - 20:00")
                    || lower.contains("evening")) {
                ci.shiftType = ShiftType.EVENING;
                ci.startTime = "02:00 PM";
                ci.endTime = "08:00 PM";
                ci.isOvernight = false;
            } else if (lower.contains("8 pm - 8 am") || lower.contains("8-8") || lower.contains("20:00 - 08:00")
                    || lower.contains("night")) {
                ci.shiftType = ShiftType.NIGHT;
                ci.startTime = "08:00 PM";
                ci.endTime = "08:00 AM";
                ci.isOvernight = true;
            } else if (lower.contains("9 am - 5 pm") || lower.contains("9-5") || lower.contains("general")) {
                ci.shiftType = ShiftType.GENERAL;
                ci.startTime = "09:00 AM";
                ci.endTime = "05:00 PM";
                ci.isOvernight = false;
            } else if (lower.contains("10 am - 6 pm") || lower.contains("10-6")) {
                ci.shiftType = ShiftType.GENERAL;
                ci.startTime = "10:00 AM";
                ci.endTime = "06:00 PM";
                ci.isOvernight = false;
            } else if (lower.contains("12 pm - 8 pm") || lower.contains("12-8")) {
                ci.shiftType = ShiftType.GENERAL;
                ci.startTime = "12:00 PM";
                ci.endTime = "08:00 PM";
                ci.isOvernight = false;
            } else {
                // Try parsing arbitrary timing e.g. "7 AM - 3 PM"
                ci.shiftType = ShiftType.GENERAL;
                ci.startTime = "09:00 AM";
                ci.endTime = "05:00 PM";
                ci.isOvernight = false;
            }

            cols.add(ci);
        }

        return cols;
    }

    /**
     * Parses a day number from a date cell string.
     */
    private static Integer parseDayNumber(String dateStr, int targetYear, int targetMonth) {
        if (dateStr == null) return null;
        String s = dateStr.trim();

        // 1. Single day number e.g. "1", "02", "15"
        if (s.matches("^\\d{1,2}$")) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // 1b. Excel serial date number e.g. 46296
        if (s.matches("^\\d{4,6}(\\.\\d+)?$")) {
            try {
                double serial = Double.parseDouble(s);
                LocalDate d = com.nxtshift.app.excel.ExcelRosterParser.convertSerialToLocalDate(serial, false);
                if (d.getYear() == targetYear && d.getMonthValue() == targetMonth) {
                    return d.getDayOfMonth();
                }
            } catch (Exception ignored) {}
        }

        // 2. ISO format YYYY-MM-DD
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                LocalDate ld = LocalDate.parse(s.substring(0, 10));
                if (ld.getYear() == targetYear && ld.getMonthValue() == targetMonth) {
                    return ld.getDayOfMonth();
                }
            } catch (Exception ignored) {}
        }

        // 3. DD/MM/YYYY or DD-MM-YYYY
        Pattern p = Pattern.compile("^(\\d{1,2})[/\\-\\.](\\d{1,2})[/\\-\\.](\\d{2,4})");
        Matcher m = p.matcher(s);
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = Integer.parseInt(m.group(3));
                if (year < 100) year += 2000;
                if (year == targetYear && month == targetMonth) {
                    return day;
                }
            } catch (Exception ignored) {}
        }

        // 4. "1-Oct" or "1 Oct 2026"
        Pattern p2 = Pattern.compile("^(\\d{1,2})\\s*[-/]?\\s*([A-Za-z]{3})");
        Matcher m2 = p2.matcher(s);
        if (m2.find()) {
            try {
                int day = Integer.parseInt(m2.group(1));
                String mStr = m2.group(2).toUpperCase(Locale.US);
                int monthVal = parseMonthAbbr(mStr);
                if (monthVal == targetMonth) {
                    return day;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static int parseMonthAbbr(String abbr) {
        String[] months = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        for (int i = 0; i < months.length; i++) {
            if (months[i].equals(abbr)) return i + 1;
        }
        return -1;
    }

    // --- CSV File Stream Parsing ---

    public static List<List<String>> parseCsvStream(InputStream is) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;

        // Auto-detect delimiter from first line
        reader.mark(4096);
        String firstLine = reader.readLine();
        reader.reset();

        char delimiter = ',';
        if (firstLine != null) {
            int commas = countOccurrences(firstLine, ',');
            int semicolons = countOccurrences(firstLine, ';');
            int tabs = countOccurrences(firstLine, '\t');
            if (semicolons > commas && semicolons > tabs) delimiter = ';';
            else if (tabs > commas && tabs > semicolons) delimiter = '\t';
        }

        StringBuilder currentCell = new StringBuilder();
        List<String> currentRow = new ArrayList<>();
        boolean insideQuotes = false;
        int ch;

        while ((ch = reader.read()) != -1) {
            char c = (char) ch;

            if (c == '"') {
                insideQuotes = !insideQuotes;
            } else if (c == delimiter && !insideQuotes) {
                currentRow.add(currentCell.toString().trim());
                currentCell.setLength(0);
            } else if ((c == '\n' || c == '\r') && !insideQuotes) {
                if (c == '\r') {
                    // Check if next is '\n'
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.reset();
                    }
                }
                currentRow.add(currentCell.toString().trim());
                currentCell.setLength(0);

                // Add row if non-empty
                boolean hasContent = false;
                for (String cell : currentRow) {
                    if (!cell.isEmpty()) {
                        hasContent = true;
                        break;
                    }
                }
                if (hasContent) {
                    rows.add(currentRow);
                }
                currentRow = new ArrayList<>();
            } else {
                currentCell.append(c);
            }
        }

        // Add trailing cell/row
        if (currentCell.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(currentCell.toString().trim());
            boolean hasContent = false;
            for (String cell : currentRow) {
                if (!cell.isEmpty()) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) {
                rows.add(currentRow);
            }
        }

        return rows;
    }

    private static int countOccurrences(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) count++;
        }
        return count;
    }

    // --- Native Excel XLSX Parsing (Zero External Dependencies) ---

    public static List<List<String>> parseXlsxStream(InputStream is) throws IOException {
        byte[] bytes = readAllBytes(is);
        List<String> sharedStrings = extractSharedStrings(new ByteArrayInputStream(bytes));
        return extractWorksheetRows(new ByteArrayInputStream(bytes), sharedStrings);
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private static List<String> extractSharedStrings(InputStream zipStream) {
        List<String> strings = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("sharedStrings.xml")) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(zis, "UTF-8");
                    int eventType = parser.getEventType();
                    StringBuilder currentText = new StringBuilder();
                    boolean inText = false;

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        String name = parser.getName();
                        if (eventType == XmlPullParser.START_TAG) {
                            if ("t".equals(name)) {
                                inText = true;
                                currentText.setLength(0);
                            }
                        } else if (eventType == XmlPullParser.TEXT) {
                            if (inText) {
                                currentText.append(parser.getText());
                            }
                        } else if (eventType == XmlPullParser.END_TAG) {
                            if ("t".equals(name)) {
                                inText = false;
                            } else if ("si".equals(name)) {
                                strings.add(currentText.toString());
                                currentText.setLength(0);
                            }
                        }
                        eventType = parser.next();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing XLSX shared strings: " + e.getMessage());
        }
        return strings;
    }

    private static List<List<String>> extractWorksheetRows(InputStream zipStream, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().contains("worksheets/sheet1.xml")) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(zis, "UTF-8");
                    int eventType = parser.getEventType();

                    List<String> currentRow = new ArrayList<>();
                    String cellType = null;
                    StringBuilder cellValue = new StringBuilder();
                    boolean inValue = false;
                    int currentColIndex = 0;

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        String name = parser.getName();
                        if (eventType == XmlPullParser.START_TAG) {
                            if ("row".equals(name)) {
                                currentRow = new ArrayList<>();
                                currentColIndex = 0;
                            } else if ("c".equals(name)) {
                                cellType = parser.getAttributeValue(null, "t");
                                String cellRef = parser.getAttributeValue(null, "r");
                                int targetCol = getColumnIndexFromRef(cellRef);
                                while (currentColIndex < targetCol) {
                                    currentRow.add("");
                                    currentColIndex++;
                                }
                                cellValue.setLength(0);
                            } else if ("v".equals(name) || "t".equals(name)) {
                                inValue = true;
                            }
                        } else if (eventType == XmlPullParser.TEXT) {
                            if (inValue) {
                                cellValue.append(parser.getText());
                            }
                        } else if (eventType == XmlPullParser.END_TAG) {
                            if ("v".equals(name) || "t".equals(name)) {
                                inValue = false;
                            } else if ("c".equals(name)) {
                                String val = cellValue.toString().trim();
                                if ("s".equals(cellType)) {
                                    // Shared string lookup
                                    try {
                                        int sIdx = Integer.parseInt(val);
                                        if (sIdx >= 0 && sIdx < sharedStrings.size()) {
                                            val = sharedStrings.get(sIdx);
                                        }
                                    } catch (NumberFormatException ignored) {}
                                }
                                currentRow.add(val);
                                currentColIndex++;
                            } else if ("row".equals(name)) {
                                boolean hasContent = false;
                                for (String c : currentRow) {
                                    if (!c.isEmpty()) {
                                        hasContent = true;
                                        break;
                                    }
                                }
                                if (hasContent) {
                                    rows.add(currentRow);
                                }
                            }
                        }
                        eventType = parser.next();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing XLSX worksheet: " + e.getMessage());
        }
        return rows;
    }

    private static int getColumnIndexFromRef(String ref) {
        if (ref == null) return 0;
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (Character.isLetter(ch)) {
                col = col * 26 + (Character.toUpperCase(ch) - 'A' + 1);
            } else {
                break;
            }
        }
        return Math.max(0, col - 1);
    }
}
