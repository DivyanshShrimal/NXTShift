package com.nxtshift.app.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public final class DateTimeUtils {

    public static final String DATE_FORMAT_ISO = "yyyy-MM-dd";
    public static final String TIME_FORMAT_12H = "hh:mm a";
    public static final String TIME_FORMAT_24H = "HH:mm";
    public static final String DATE_DISPLAY_FORMAT = "EEE, MMM d, yyyy";
    public static final String MONTH_YEAR_FORMAT = "MMMM yyyy";

    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT_ISO, Locale.US);
    private static final DateTimeFormatter TIME_12H_FORMATTER = DateTimeFormatter.ofPattern(TIME_FORMAT_12H, Locale.US);
    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_DISPLAY_FORMAT, Locale.US);
    private static final DateTimeFormatter MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern(MONTH_YEAR_FORMAT, Locale.US);

    private DateTimeUtils() {
        // Utility class
    }

    public static String getTodayIsoDate() {
        return LocalDate.now().format(ISO_DATE_FORMATTER);
    }

    public static String getTomorrowIsoDate() {
        return LocalDate.now().plusDays(1).format(ISO_DATE_FORMATTER);
    }

    public static String getNextDayIsoDate(String isoDate) {
        try {
            LocalDate date = LocalDate.parse(isoDate, ISO_DATE_FORMATTER);
            return date.plusDays(1).format(ISO_DATE_FORMATTER);
        } catch (Exception e) {
            return isoDate;
        }
    }

    public static String formatDisplayDate(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        try {
            LocalDate date = LocalDate.parse(isoDate, ISO_DATE_FORMATTER);
            LocalDate today = LocalDate.now();
            if (date.isEqual(today)) {
                return "Today (" + date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + ")";
            } else if (date.isEqual(today.plusDays(1))) {
                return "Tomorrow (" + date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + ")";
            } else if (date.isEqual(today.minusDays(1))) {
                return "Yesterday (" + date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + ")";
            }
            return date.format(DISPLAY_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }

    public static String formatMonthYear(LocalDate date) {
        return date.format(MONTH_YEAR_FORMATTER);
    }

    public static LocalDate parseIsoDate(String isoDate) {
        try {
            return LocalDate.parse(isoDate, ISO_DATE_FORMATTER);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    public static String toIsoDate(LocalDate date) {
        return date.format(ISO_DATE_FORMATTER);
    }

    public static String normalizeTime12Hour(String rawTime) {
        if (rawTime == null || rawTime.trim().isEmpty()) return "";
        String clean = rawTime.trim().toUpperCase();
        try {
            // Check if already 12h with AM/PM
            if (clean.endsWith("AM") || clean.endsWith("PM")) {
                if (!clean.contains(" ")) {
                    int idx = clean.indexOf("AM");
                    if (idx == -1) idx = clean.indexOf("PM");
                    clean = clean.substring(0, idx).trim() + " " + clean.substring(idx);
                }
                DateTimeFormatter parse12h = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
                LocalTime time = LocalTime.parse(clean, parse12h);
                return time.format(TIME_12H_FORMATTER);
            }
            // Parse 24 hour "HH:mm" or "H:mm"
            LocalTime time = LocalTime.parse(clean, DateTimeFormatter.ofPattern("H:m", Locale.US));
            return time.format(TIME_12H_FORMATTER);
        } catch (Exception e) {
            return rawTime;
        }
    }

    /**
     * Calculates shift duration in hours, supporting overnight transitions.
     */
    public static double calculateShiftDurationHours(String startTimeStr, String endTimeStr, boolean isOvernight) {
        if (startTimeStr == null || endTimeStr == null || startTimeStr.isEmpty() || endTimeStr.isEmpty()) {
            return 0.0;
        }
        try {
            LocalTime start = LocalTime.parse(normalizeTime12Hour(startTimeStr), TIME_12H_FORMATTER);
            LocalTime end = LocalTime.parse(normalizeTime12Hour(endTimeStr), TIME_12H_FORMATTER);

            long minutes;
            if (isOvernight || end.isBefore(start)) {
                minutes = ChronoUnit.MINUTES.between(start, LocalTime.MAX) + 1 +
                          ChronoUnit.MINUTES.between(LocalTime.MIN, end);
            } else {
                minutes = ChronoUnit.MINUTES.between(start, end);
            }
            return Math.round((minutes / 60.0) * 10.0) / 10.0;
        } catch (Exception e) {
            return 8.0; // fallback default
        }
    }

    /**
     * Tries to parse date strings found in OCR tables into yyyy-MM-dd.
     */
    public static String tryParseOcrDate(String raw, int currentYear, int currentMonth) {
        if (raw == null) return null;
        String trimmed = raw.trim();

        // 1. Check direct ISO YYYY-MM-DD
        if (trimmed.matches("^\\d{4}-\\d{1,2}-\\d{1,2}$")) {
            try {
                LocalDate d = LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-M-d", Locale.US));
                return d.format(ISO_DATE_FORMATTER);
            } catch (Exception ignored) {}
        }

        // 2. dd-MM-yyyy or dd/MM/yyyy
        if (trimmed.matches("^\\d{1,2}[-/]\\d{1,2}[-/]\\d{4}$")) {
            String sanitized = trimmed.replace('/', '-');
            try {
                LocalDate d = LocalDate.parse(sanitized, DateTimeFormatter.ofPattern("d-M-yyyy", Locale.US));
                return d.format(ISO_DATE_FORMATTER);
            } catch (Exception ignored) {}
        }

        // 3. dd-MMM-yyyy e.g. 15-Oct-2026 or 15-OCT-2026
        try {
            LocalDate d = LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US));
            return d.format(ISO_DATE_FORMATTER);
        } catch (Exception ignored) {}

        // 4. dd-MMM or dd MMM e.g. "15 Oct"
        try {
            String withYear = trimmed + "-" + currentYear;
            LocalDate d = LocalDate.parse(withYear, DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US));
            return d.format(ISO_DATE_FORMATTER);
        } catch (Exception ignored) {}

        // 5. Raw day of month number (1..31) in roster column
        if (trimmed.matches("^([1-9]|[12][0-9]|3[01])$")) {
            int day = Integer.parseInt(trimmed);
            try {
                LocalDate d = LocalDate.of(currentYear, currentMonth, day);
                return d.format(ISO_DATE_FORMATTER);
            } catch (Exception ignored) {}
        }

        return null;
    }

    public static String formatTimestampToIso(long timestamp) {
        if (timestamp <= 0) return "";
        try {
            return java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .format(ISO_DATE_FORMATTER);
        } catch (Exception e) {
            return "";
        }
    }
}
