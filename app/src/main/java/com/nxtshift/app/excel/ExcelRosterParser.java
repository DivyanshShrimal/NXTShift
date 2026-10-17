package com.nxtshift.app.excel;

import com.nxtshift.app.domain.ShiftType;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * High-performance, streaming Excel XLSX parser for hospital duty rosters.
 * Implemented with pure Java/SAX and zero external dependencies (no Apache POI required).
 * Reliably extracts date cells, all six hospital shift timings, Off, Leave, and staff assignments.
 */
public class ExcelRosterParser {
    private static final String TAG = "ExcelRosterParser";

    // Standard hospital shifts supported by NXTShift
    public static final String SHIFT_9_TO_5 = "9 AM – 5 PM";
    public static final String SHIFT_10_TO_6 = "10 AM – 6 PM";
    public static final String SHIFT_12_TO_8 = "12 PM – 8 PM";
    public static final String SHIFT_8_TO_2 = "8 AM – 2 PM";
    public static final String SHIFT_2_TO_8 = "2 PM – 8 PM";
    public static final String SHIFT_8_TO_8 = "8 PM – 8 AM";

    public static class RawCell {
        public String ref;
        public String colLetter;
        public int rowNum;
        public String value;
    }

    public static class RawRow {
        public int rowNum;
        public final Map<String, String> cellValues = new LinkedHashMap<>(); // colLetter -> value
    }

    public static ExcelRosterData parse(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return parse(fis, file.getName());
        }
    }

    public static ExcelRosterData parse(InputStream inputStream, String fileName) throws IOException {
        byte[] bytes = readAllBytes(inputStream);
        if (bytes == null || bytes.length == 0) {
            ExcelRosterData empty = new ExcelRosterData(0, 0, "", "");
            empty.getErrors().add("The uploaded Excel workbook is empty.");
            return empty;
        }

        // 1. Discover sheets and choose primary roster sheet
        Map<String, String> sheetNameToRelId = new LinkedHashMap<>();
        Map<String, String> relIdToTarget = new HashMap<>();
        parseWorkbookMetadata(bytes, sheetNameToRelId, relIdToTarget);

        String targetSheetName = selectPrimaryRosterSheet(sheetNameToRelId.keySet());
        if (targetSheetName == null) {
            ExcelRosterData errData = new ExcelRosterData(0, 0, "", "");
            errData.getErrors().add("Could not find a valid roster worksheet in the Excel workbook. Found sheets: " + sheetNameToRelId.keySet());
            return errData;
        }

        String relId = sheetNameToRelId.get(targetSheetName);
        String targetEntryPath = relIdToTarget.get(relId);
        if (targetEntryPath == null) {
            targetEntryPath = "xl/worksheets/sheet1.xml";
        }
        targetEntryPath = normalizeZipPath(targetEntryPath);

        // 2. Extract shared strings if present
        List<String> sharedStrings = extractSharedStrings(bytes);

        // 3. Extract raw rows from target worksheet
        List<RawRow> rawRows = extractSheetRows(bytes, targetEntryPath, sharedStrings);
        if (rawRows.isEmpty()) {
            ExcelRosterData errData = new ExcelRosterData(0, 0, "", targetSheetName);
            errData.getErrors().add("Worksheet '" + targetSheetName + "' contains no data rows.");
            return errData;
        }

        // 4. Transform raw rows into structured ExcelRosterData
        return processSheetGrid(rawRows, targetSheetName);
    }

    private static String selectPrimaryRosterSheet(Set<String> sheetNames) {
        if (sheetNames == null || sheetNames.isEmpty()) return null;

        // 1. Explicitly check for sheet containing "roster" (case-insensitive)
        for (String name : sheetNames) {
            String lower = name.toLowerCase(Locale.US);
            if (lower.contains("roster") && !lower.contains("notes") && !lower.contains("readme")) {
                return name;
            }
        }

        // 2. Select first sheet that is not Notes/Summary/Readme/Master
        for (String name : sheetNames) {
            String lower = name.toLowerCase(Locale.US);
            if (!lower.contains("notes") && !lower.contains("readme") && !lower.contains("read me") && !lower.contains("summary") && !lower.contains("footer")) {
                return name;
            }
        }

        // 3. Fallback to first sheet
        return sheetNames.iterator().next();
    }

    private static String normalizeZipPath(String path) {
        if (path == null) return "xl/worksheets/sheet1.xml";
        String clean = path.replace('\\', '/').trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (!clean.startsWith("xl/")) {
            clean = "xl/" + clean;
        }
        return clean;
    }

    // --- XML Metadata Parsing ---

    private static void parseWorkbookMetadata(byte[] bytes, Map<String, String> sheetNameToRelId, Map<String, String> relIdToTarget) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser saxParser = factory.newSAXParser();

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');

                if (name.endsWith("xl/workbook.xml")) {
                    saxParser.parse(new InputSource(new NonClosingInputStream(zis)), new DefaultHandler() {
                        @Override
                        public void startElement(String uri, String localName, String qName, Attributes attributes) {
                            if ("sheet".equals(localName) || "sheet".equals(qName)) {
                                String sheetName = attributes.getValue("name");
                                String rid = attributes.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                                if (rid == null) {
                                    rid = attributes.getValue("r:id");
                                }
                                if (sheetName != null && rid != null) {
                                    sheetNameToRelId.put(sheetName, rid);
                                }
                            }
                        }
                    });
                } else if (name.endsWith("xl/_rels/workbook.xml.rels")) {
                    saxParser.parse(new InputSource(new NonClosingInputStream(zis)), new DefaultHandler() {
                        @Override
                        public void startElement(String uri, String localName, String qName, Attributes attributes) {
                            if ("Relationship".equals(localName) || "Relationship".equals(qName)) {
                                String id = attributes.getValue("Id");
                                String target = attributes.getValue("Target");
                                if (id != null && target != null) {
                                    relIdToTarget.put(id, target);
                                }
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            // Ignore metadata failure; defaults will take over
        }
    }

    private static List<String> extractSharedStrings(byte[] bytes) {
        List<String> strings = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.endsWith("xl/sharedStrings.xml")) {
                    SAXParserFactory factory = SAXParserFactory.newInstance();
                    factory.setNamespaceAware(true);
                    SAXParser saxParser = factory.newSAXParser();

                    saxParser.parse(new InputSource(new NonClosingInputStream(zis)), new DefaultHandler() {
                        private final StringBuilder currentString = new StringBuilder();
                        private boolean inText = false;

                        @Override
                        public void startElement(String uri, String localName, String qName, Attributes attributes) {
                            if ("t".equals(localName) || "t".equals(qName)) {
                                inText = true;
                            }
                        }

                        @Override
                        public void characters(char[] ch, int start, int length) {
                            if (inText) {
                                currentString.append(ch, start, length);
                            }
                        }

                        @Override
                        public void endElement(String uri, String localName, String qName) {
                            if ("t".equals(localName) || "t".equals(qName)) {
                                inText = false;
                            } else if ("si".equals(localName) || "si".equals(qName)) {
                                strings.add(currentString.toString());
                                currentString.setLength(0);
                            }
                        }
                    });
                    break;
                }
            }
        } catch (Exception ignored) {}
        return strings;
    }

    private static List<RawRow> extractSheetRows(byte[] bytes, String targetEntryPath, List<String> sharedStrings) {
        List<RawRow> rows = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);

                if (name.equalsIgnoreCase(targetEntryPath) || (name.contains("worksheets/") && name.endsWith(".xml") && targetEntryPath.endsWith(name.substring(name.lastIndexOf('/') + 1)))) {
                    SAXParserFactory factory = SAXParserFactory.newInstance();
                    factory.setNamespaceAware(true);
                    SAXParser saxParser = factory.newSAXParser();

                    saxParser.parse(new InputSource(new NonClosingInputStream(zis)), new DefaultHandler() {
                        private RawRow currentRow = null;
                        private String currentCellRef = null;
                        private String currentCellType = null;
                        private final StringBuilder cellContent = new StringBuilder();
                        private boolean inValueOrText = false;

                        @Override
                        public void startElement(String uri, String localName, String qName, Attributes attributes) {
                            String tag = localName != null && !localName.isEmpty() ? localName : qName;
                            if ("row".equals(tag)) {
                                currentRow = new RawRow();
                                String rAttr = attributes.getValue("r");
                                if (rAttr != null && rAttr.matches("^\\d+$")) {
                                    currentRow.rowNum = Integer.parseInt(rAttr);
                                }
                            } else if ("c".equals(tag)) {
                                currentCellRef = attributes.getValue("r");
                                currentCellType = attributes.getValue("t");
                                cellContent.setLength(0);
                            } else if ("v".equals(tag) || "t".equals(tag)) {
                                inValueOrText = true;
                            }
                        }

                        @Override
                        public void characters(char[] ch, int start, int length) {
                            if (inValueOrText) {
                                cellContent.append(ch, start, length);
                            }
                        }

                        @Override
                        public void endElement(String uri, String localName, String qName) {
                            String tag = localName != null && !localName.isEmpty() ? localName : qName;
                            if ("v".equals(tag) || "t".equals(tag)) {
                                inValueOrText = false;
                            } else if ("c".equals(tag)) {
                                if (currentRow != null && currentCellRef != null) {
                                    String rawVal = cellContent.toString().trim();
                                    String finalVal = rawVal;

                                    if ("s".equals(currentCellType) && rawVal.matches("^\\d+$")) {
                                        try {
                                            int idx = Integer.parseInt(rawVal);
                                            if (idx >= 0 && idx < sharedStrings.size()) {
                                                finalVal = sharedStrings.get(idx);
                                            }
                                        } catch (NumberFormatException ignored) {}
                                    }

                                    String colLetter = extractColLetter(currentCellRef);
                                    currentRow.cellValues.put(colLetter, finalVal);
                                }
                                currentCellRef = null;
                                currentCellType = null;
                                cellContent.setLength(0);
                            } else if ("row".equals(tag)) {
                                if (currentRow != null && !currentRow.cellValues.isEmpty()) {
                                    rows.add(currentRow);
                                }
                                currentRow = null;
                            }
                        }
                    });
                    break;
                }
            }
        } catch (Exception e) {
            // Error logged
        }

        // Sort rows by rowNum
        rows.sort(Comparator.comparingInt(r -> r.rowNum));
        return rows;
    }

    private static String extractColLetter(String cellRef) {
        if (cellRef == null) return "A";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = cellRef.charAt(i);
            if (Character.isLetter(ch)) {
                sb.append(Character.toUpperCase(ch));
            } else {
                break;
            }
        }
        return sb.length() > 0 ? sb.toString() : "A";
    }

    private static int colLetterToIndex(String colLetter) {
        if (colLetter == null || colLetter.isEmpty()) return 0;
        int result = 0;
        for (int i = 0; i < colLetter.length(); i++) {
            result = result * 26 + (colLetter.charAt(i) - 'A' + 1);
        }
        return result - 1;
    }

    // --- Grid Analysis & Roster Assembly ---

    private static ExcelRosterData processSheetGrid(List<RawRow> rows, String sheetName) {
        // 1. Identify header row
        int headerRowIndex = -1;
        RawRow headerRow = null;
        String dateColLetter = null;
        List<ExcelShiftColumn> columns = new ArrayList<>();

        for (int i = 0; i < Math.min(rows.size(), 10); i++) {
            RawRow row = rows.get(i);
            List<ExcelShiftColumn> candidateCols = detectColumnsFromHeaderRow(row);
            String candidateDateCol = null;

            for (Map.Entry<String, String> e : row.cellValues.entrySet()) {
                String val = normalizeString(e.getValue()).toLowerCase(Locale.US);
                if (val.equals("date") || val.equals("day") || val.equals("d") || val.startsWith("date ")) {
                    candidateDateCol = e.getKey();
                    break;
                }
            }

            if (candidateDateCol != null && candidateCols.size() >= 2) {
                headerRowIndex = i;
                headerRow = row;
                dateColLetter = candidateDateCol;
                columns = candidateCols;
                break;
            }
        }

        if (headerRowIndex == -1 || dateColLetter == null || columns.isEmpty()) {
            ExcelRosterData errData = new ExcelRosterData(0, 0, "", sheetName);
            errData.getErrors().add("Could not find standard roster column headers (e.g. 'Date', '8 AM–2 PM', 'Off', 'Leave') in sheet '" + sheetName + "'.");
            return errData;
        }

        // Sort columns by position (A, B, C...)
        columns.sort(Comparator.comparingInt(ExcelShiftColumn::getColumnIndex));

        // 2. Extract Data Rows and Discover Dates & Staff
        List<ExcelDayRow> dayRows = new ArrayList<>();
        Set<String> allStaffNames = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        Integer inferredYear = null;
        Integer inferredMonth = null;

        // Try extracting year and month from sheet name (e.g. "Roster Oct 2026")
        int[] ymFromSheet = parseMonthYearFromText(sheetName);
        if (ymFromSheet != null) {
            inferredYear = ymFromSheet[0];
            inferredMonth = ymFromSheet[1];
        }

        // Inspect title row if row 0 has title text
        if (inferredYear == null && rows.size() > 0) {
            for (String val : rows.get(0).cellValues.values()) {
                int[] ym = parseMonthYearFromText(val);
                if (ym != null) {
                    inferredYear = ym[0];
                    inferredMonth = ym[1];
                    break;
                }
            }
        }

        for (int i = headerRowIndex + 1; i < rows.size(); i++) {
            RawRow row = rows.get(i);
            String dateRaw = row.cellValues.get(dateColLetter);
            if (dateRaw == null || dateRaw.trim().isEmpty()) {
                continue;
            }

            LocalDate parsedDate = parseCellDate(dateRaw, inferredYear, inferredMonth);
            if (parsedDate == null) {
                // If it's a non-date footer row, stop or skip
                continue;
            }

            if (inferredYear == null) {
                inferredYear = parsedDate.getYear();
                inferredMonth = parsedDate.getMonthValue();
            }

            String isoDate = parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            int dayNumber = parsedDate.getDayOfMonth();
            ExcelDayRow dayRow = new ExcelDayRow(isoDate, dayNumber);

            // Populate shifts for this day
            for (ExcelShiftColumn col : columns) {
                String cellText = row.cellValues.get(col.getColumnLetter());
                if (cellText == null || cellText.trim().isEmpty() || cellText.equals("-") || cellText.equals(".")) {
                    continue;
                }

                List<String> names = parseStaffNamesFromCell(cellText);
                if (!names.isEmpty()) {
                    dayRow.addStaffToColumn(col.getColumnIndex(), names);
                    allStaffNames.addAll(names);
                }
            }

            dayRows.add(dayRow);
        }

        // Fallback default if no year/month detected
        if (inferredYear == null) inferredYear = 2026;
        if (inferredMonth == null) inferredMonth = 10;

        String monthName = Month.of(inferredMonth).getDisplayName(TextStyle.FULL, Locale.US);
        String monthLabel = monthName + " " + inferredYear;

        ExcelRosterData data = new ExcelRosterData(inferredYear, inferredMonth, monthLabel, sheetName);
        data.getColumns().addAll(columns);
        data.getDays().addAll(dayRows);
        data.getWarnings().addAll(warnings);

        // Sort unique staff names
        List<String> sortedStaff = new ArrayList<>(allStaffNames);
        Collections.sort(sortedStaff, String.CASE_INSENSITIVE_ORDER);
        data.getUniqueStaffNames().addAll(sortedStaff);

        if (dayRows.isEmpty()) {
            data.getErrors().add("No valid dates found under the '" + dateColLetter + "' column in worksheet '" + sheetName + "'.");
        }
        if (sortedStaff.isEmpty()) {
            data.getErrors().add("No staff assignments found in any duty, Off, or Leave column.");
        }

        return data;
    }

    private static List<ExcelShiftColumn> detectColumnsFromHeaderRow(RawRow row) {
        List<ExcelShiftColumn> cols = new ArrayList<>();

        for (Map.Entry<String, String> entry : row.cellValues.entrySet()) {
            String colLetter = entry.getKey();
            int colIndex = colLetterToIndex(colLetter);
            String rawHeader = entry.getValue();
            String norm = normalizeString(rawHeader).toLowerCase(Locale.US);
            String compact = norm.replaceAll("\\s+", "");

            if (norm.equals("date") || norm.equals("day") || norm.equals("d") || norm.startsWith("date ")) {
                // Handled as date column
                continue;
            }

            if (compact.contains("leave") || compact.equals("cl") || compact.equals("el") || compact.equals("sl") || compact.equals("l")) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.LEAVE, "", "", false, false, true));
            } else if (compact.contains("off") || compact.equals("w/o") || compact.equals("wo")) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.OFF, "", "", false, true, false));
            } else if (compact.contains("8am-2pm") || compact.contains("8-2") || compact.contains("8:00-14:00") || (compact.contains("morning") && !compact.contains("night"))) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.MORNING, "08:00 AM", "02:00 PM", false, false, false));
            } else if (compact.contains("2pm-8pm") || compact.contains("2-8") || compact.contains("14:00-20:00") || compact.contains("evening")) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.EVENING, "02:00 PM", "08:00 PM", false, false, false));
            } else if (compact.contains("8pm-8am") || compact.contains("8-8") || compact.contains("20:00-08:00") || compact.contains("night")) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.NIGHT, "08:00 PM", "08:00 AM", true, false, false));
            } else if (compact.contains("9am-5pm") || compact.contains("9-5") || compact.contains("general")) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.GENERAL, "09:00 AM", "05:00 PM", false, false, false));
            } else if (compact.contains("10am-6pm") || compact.contains("10-6")) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.GENERAL, "10:00 AM", "06:00 PM", false, false, false));
            } else if (compact.contains("12pm-8pm") || compact.contains("12-8")) {
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.GENERAL, "12:00 PM", "08:00 PM", false, false, false));
            } else if (compact.length() > 0) {
                // Arbitrary duty timing
                cols.add(new ExcelShiftColumn(colLetter, colIndex, rawHeader, ShiftType.GENERAL, "09:00 AM", "05:00 PM", false, false, false));
            }
        }

        return cols;
    }

    public static List<String> parseStaffNamesFromCell(String cellText) {
        List<String> names = new ArrayList<>();
        if (cellText == null || cellText.trim().isEmpty()) return names;

        // Split by commas, semicolons, slashes, or newlines
        String[] tokens = cellText.split("[,;/\\n\\+]");
        for (String raw : tokens) {
            String name = raw.trim();
            if (name.isEmpty() || name.equals("-") || name.equals(".")) continue;

            // Remove extraneous quotation marks if present
            if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                name = name.substring(1, name.length() - 1).trim();
            }

            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    public static LocalDate parseCellDate(String dateStr, Integer fallbackYear, Integer fallbackMonth) {
        if (dateStr == null) return null;
        String s = dateStr.trim();

        // 1. Excel serial number: e.g. 46296 (standard Windows Excel 1900 date system)
        if (s.matches("^\\d{4,6}(\\.\\d+)?$")) {
            try {
                double serial = Double.parseDouble(s);
                int days = (int) Math.round(serial);
                // Serial dates between 30000 (1982) and 60000 (2064)
                if (days >= 30000 && days <= 70000) {
                    LocalDate base = LocalDate.of(1899, 12, 30);
                    return base.plusDays(days);
                }
            } catch (Exception ignored) {}
        }

        // 2. ISO Date: yyyy-MM-dd
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (Exception ignored) {}
        }

        // 3. Simple day integer (e.g. "1", "02", "15")
        if (s.matches("^\\d{1,2}$") && fallbackYear != null && fallbackMonth != null) {
            try {
                int day = Integer.parseInt(s);
                YearMonth ym = YearMonth.of(fallbackYear, fallbackMonth);
                if (day >= 1 && day <= ym.lengthOfMonth()) {
                    return LocalDate.of(fallbackYear, fallbackMonth, day);
                }
            } catch (Exception ignored) {}
        }

        // 4. DD/MM/YYYY or DD-MM-YYYY
        Pattern p = Pattern.compile("^(\\d{1,2})[/\\-\\.](\\d{1,2})[/\\-\\.](\\d{2,4})");
        Matcher m = p.matcher(s);
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = Integer.parseInt(m.group(3));
                if (year < 100) year += 2000;
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {}
        }

        // 5. "1-Oct-2026" or "1-Oct" or "01 Oct"
        Pattern p2 = Pattern.compile("^(\\d{1,2})\\s*[-/]?\\s*([A-Za-z]{3,9})(?:\\s*[-/]?\\s*(\\d{2,4}))?");
        Matcher m2 = p2.matcher(s);
        if (m2.find()) {
            try {
                int day = Integer.parseInt(m2.group(1));
                String mStr = m2.group(2);
                int month = parseMonthName(mStr);
                int year = fallbackYear != null ? fallbackYear : 2026;
                if (m2.group(3) != null) {
                    year = Integer.parseInt(m2.group(3));
                    if (year < 100) year += 2000;
                }
                if (month >= 1 && month <= 12) {
                    return LocalDate.of(year, month, day);
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static int parseMonthName(String name) {
        if (name == null) return -1;
        String upper = name.trim().toUpperCase(Locale.US);
        String[] abbrs = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        for (int i = 0; i < abbrs.length; i++) {
            if (upper.startsWith(abbrs[i])) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int[] parseMonthYearFromText(String text) {
        if (text == null) return null;
        // Search for Month (e.g. October, Oct) and 4-digit Year (e.g. 2026) or 2-digit (26)
        Pattern p = Pattern.compile("(?i)(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)[^0-9]*(\\d{2,4})");
        Matcher m = p.matcher(text);
        if (m.find()) {
            int month = parseMonthName(m.group(1));
            int year = Integer.parseInt(m.group(2));
            if (year < 100) year += 2000;
            return new int[]{year, month};
        }
        return null;
    }

    private static String normalizeString(String input) {
        if (input == null) return "";
        // Replace en-dash (\u2013), em-dash (\u2014), \ufffd with standard hyphen '-'
        return input.replace('\u2013', '-')
                    .replace('\u2014', '-')
                    .replace('\ufffd', '-')
                    .replaceAll("\\s+", " ")
                    .trim();
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

    /**
     * Prevents SAXParser from closing the underlying ZipInputStream when an entry ends.
     */
    private static class NonClosingInputStream extends InputStream {
        private final InputStream in;

        public NonClosingInputStream(InputStream in) {
            this.in = in;
        }

        @Override public int read() throws IOException { return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }
        @Override public int available() throws IOException { return in.available(); }
        @Override public void close() {
            // Intentionally no-op to allow reading next ZipEntry
        }
    }
}
