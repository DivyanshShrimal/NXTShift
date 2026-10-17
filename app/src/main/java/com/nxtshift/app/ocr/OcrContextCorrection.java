package com.nxtshift.app.ocr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent contextual OCR repair utility for hospital rosters.
 * Corrects digit/letter confusion (2<->Z, 5<->S, 8<->B, 0<->O) in TIME and DATE contexts only,
 * while strictly preserving genuine characters in staff names.
 * Filters administrative headers and non-person text from staff discovery.
 */
public class OcrContextCorrection {

    // Known English month names & abbreviations
    private static final String[] MONTH_NAMES = {
            "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
            "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
    };

    private static final String[] MONTH_ABBR = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "SEPT", "OCT", "NOV", "DEC"
    };

    // Days of week
    private static final Set<String> DAYS_OF_WEEK = new HashSet<>(Arrays.asList(
            "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY",
            "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"
    ));

    // Administrative / Header / Clinical non-person keywords
    private static final Set<String> NON_PERSON_KEYWORDS = new HashSet<>(Arrays.asList(
            "DUTY", "ROSTER", "STAFF", "NURSE", "NURSING", "HOSPITAL", "DEPARTMENT",
            "MONTH", "YEAR", "DATE", "DAY", "DAYS", "TIME", "TIMINGS", "TIMING", "SHIFT", "SHIFTS",
            "MORNING", "EVENING", "NIGHT", "GENERAL", "AFTERNOON", "OFF", "WEEKLY", "LEAVE",
            "CASUAL", "EARNED", "SICK", "HOLIDAY", "SCHEDULE", "POSTING", "ALLOCATION",
            "ICU", "CCU", "NICU", "PICU", "SICU", "MICU", "OT", "OPD", "ER", "EMERGENCY",
            "CASUALTY", "WARD", "UNIT", "FLOOR", "INCHARGE", "SUPERVISOR", "TOTAL", "SIGN",
            "SIGNATURE", "REMARKS", "NOTE", "NOTES", "STATUS", "DESIGNATION", "SNO", "SRNO",
            "SLNO", "NO", "NAME", "POST", "GRADE", "SISTER", "BROTHER", "PAGE", "ROOM",
            "AM", "PM", "TO", "AND", "THE", "FOR", "OF", "IN", "AT", "ON", "WITH"
    ));

    /**
     * Determines whether a candidate string is an administrative title, header,
     * time expression, or other non-person text.
     */
    public static boolean isNonPersonToken(@Nullable String text) {
        if (text == null) return true;
        String clean = text.trim();
        if (clean.length() < 2) return true;

        String upper = clean.toUpperCase(Locale.ENGLISH);

        // 1. Direct keyword match
        if (NON_PERSON_KEYWORDS.contains(upper)) return true;

        // 2. Check if text is a day of week
        if (DAYS_OF_WEEK.contains(upper)) return true;

        // 3. Check if text is or contains month names/abbreviations combined with other tokens
        for (String m : MONTH_NAMES) {
            if (upper.equals(m) || upper.startsWith(m + " ") || upper.endsWith(" " + m) || upper.contains(" " + m + " ")) return true;
        }
        for (String m : MONTH_ABBR) {
            if (upper.equals(m) || upper.startsWith(m + " ") || upper.endsWith(" " + m) || upper.contains(" " + m + " ") || upper.startsWith(m + "-") || upper.endsWith("-" + m)) return true;
        }

        // 4. Multi-word phrase containing any non-person keyword (e.g. "Month Oct", "Month Sam Spm", "ICU Ward")
        String[] words = upper.split("[^A-Z0-9]+");
        int keywordCount = 0;
        for (String w : words) {
            if (NON_PERSON_KEYWORDS.contains(w) || DAYS_OF_WEEK.contains(w)) {
                keywordCount++;
            }
            // Check month abbr
            for (String m : MONTH_ABBR) {
                if (w.equals(m)) keywordCount++;
            }
        }
        if (keywordCount > 0 && (words.length <= 3 || (double) keywordCount / words.length >= 0.5)) {
            return true;
        }

        // 5. Time expressions and misread time tokens (e.g. "Sam Spm", "8am 5pm", "8am", "08:00")
        if (upper.matches(".*(?:SAM|BAM|SPM|ZPM|AM|PM).*") && upper.matches(".*[0-9BSZ]+.*")) {
            return true;
        }
        if (upper.matches(".*\\d{1,2}:\\d{2}.*")) {
            return true;
        }

        // 6. Letter count check (must have at least 3 genuine letters to be a person's name)
        int letterCount = 0;
        for (char c : clean.toCharArray()) {
            if (Character.isLetter(c)) letterCount++;
        }
        if (letterCount < 3) return true;

        // 7. Check if mostly numeric or punctuation
        if (clean.matches(".*\\d{2,}.*")) return true;

        return false;
    }

    /**
     * Determines whether a line belongs to the roster's top header banner or timing legend.
     */
    public static boolean isHeaderOrLegendLine(@Nullable String line) {
        if (line == null) return false;
        String upper = line.toUpperCase(Locale.ENGLISH);

        boolean hasMonth = false;
        for (String m : MONTH_NAMES) {
            if (upper.contains(m)) { hasMonth = true; break; }
        }
        for (String m : MONTH_ABBR) {
            if (upper.contains(m)) { hasMonth = true; break; }
        }

        boolean hasYear = upper.matches(".*20[2-3][0-9].*");
        boolean hasRosterKeyword = upper.contains("ROSTER") || upper.contains("DUTY") || upper.contains("HOSPITAL") || upper.contains("DEPARTMENT");
        boolean hasTimingKeyword = upper.contains("TIMING") || upper.contains("TIMINGS") || upper.contains("TIME:");

        // If line is Roster title or Month/Year header
        if ((hasMonth && hasYear) || (hasRosterKeyword && (hasMonth || hasYear))) {
            return true;
        }

        // If line is timing declaration, e.g. "Morning: 8:00 AM - 2:00 PM"
        if (hasTimingKeyword || (upper.contains("MORN") && upper.contains("EVEN") && upper.contains("NIGHT"))) {
            return true;
        }

        return false;
    }

    /**
     * Contextual time correction for OCR confusion:
     * Corrects Z->2, S->5 (or 8 before AM), B->8, O->0 in time contexts.
     * Never converts staff names.
     */
    @Nullable
    public static String repairTimeString(@Nullable String rawTime) {
        if (rawTime == null || rawTime.trim().isEmpty()) return null;
        String s = rawTime.trim();

        // Specific misread patterns e.g. "Sam" -> "08:00 AM", "Bam" -> "08:00 AM"
        if (s.equalsIgnoreCase("Sam") || s.equalsIgnoreCase("Bam") || s.equalsIgnoreCase("8am")) {
            return "08:00 AM";
        }
        if (s.equalsIgnoreCase("Spm") || s.equalsIgnoreCase("5pm")) {
            return "05:00 PM";
        }
        if (s.equalsIgnoreCase("Zpm") || s.equalsIgnoreCase("2pm")) {
            return "02:00 PM";
        }
        if (s.equalsIgnoreCase("Bpm") || s.equalsIgnoreCase("8pm")) {
            return "08:00 PM";
        }

        // Pattern: [digits/letters] : [digits/letters] [AM/PM optional]
        Pattern pCol = Pattern.compile("([0-9A-Za-z]{1,2})\\s*[:.]\\s*([0-9A-Za-z]{2})\\s*([AaPp][Mm])?");
        Matcher mCol = pCol.matcher(s);
        if (mCol.find()) {
            String rawH = mCol.group(1);
            String rawM = mCol.group(2);
            String ampm = mCol.group(3);

            int h = parseContextualInt(rawH);
            int m = parseContextualInt(rawM);

            if (h >= 0 && h <= 24 && m >= 0 && m <= 59) {
                if (ampm != null) {
                    return String.format(Locale.ENGLISH, "%02d:%02d %s", (h == 0 ? 12 : (h > 12 ? h - 12 : h)), m, ampm.toUpperCase(Locale.ENGLISH));
                } else {
                    // 24-hour to 12-hour
                    String period = h >= 12 ? "PM" : "AM";
                    int displayH = h % 12;
                    if (displayH == 0) displayH = 12;
                    return String.format(Locale.ENGLISH, "%02d:%02d %s", displayH, m, period);
                }
            }
        }

        // Pattern: simple hour with AM/PM e.g. "8 AM" or "B AM" or "Z PM"
        Pattern pHour = Pattern.compile("([0-9A-Za-z]{1,2})\\s*([AaPp][Mm])");
        Matcher mHour = pHour.matcher(s);
        if (mHour.find()) {
            int h = parseContextualInt(mHour.group(1));
            String ampm = mHour.group(2).toUpperCase(Locale.ENGLISH);
            if (h >= 1 && h <= 12) {
                return String.format(Locale.ENGLISH, "%02d:00 %s", h, ampm);
            }
        }

        return null;
    }

    /**
     * Contextual date correction for OCR confusion:
     * Corrects Z->2, S->5, B->8, O->0, I->1 in date contexts and verifies
     * against calendar constraints (1..31, valid month length, valid year).
     */
    @Nullable
    public static String repairDateString(@Nullable String rawDate, int fallbackYear, int fallbackMonth) {
        if (rawDate == null || rawDate.trim().isEmpty()) return null;
        String s = rawDate.trim();

        // 1. Check for standard delimiters: / - .
        Pattern p = Pattern.compile("([0-9A-Za-z]{1,2})[-/.]([0-9A-Za-z]{1,2}|[A-Za-z]{3,9})(?:[-/.]([0-9A-Za-z]{2,4}))?");
        Matcher m = p.matcher(s);
        if (m.find()) {
            String pDay = m.group(1);
            String pMonth = m.group(2);
            String pYear = m.group(3);

            int day = parseContextualInt(pDay);
            int month = parseMonthToken(pMonth, fallbackMonth);
            int year = fallbackYear;

            if (pYear != null) {
                int parsedY = parseContextualInt(pYear);
                if (parsedY >= 2020 && parsedY <= 2035) {
                    year = parsedY;
                } else if (parsedY >= 20 && parsedY <= 35) {
                    year = 2000 + parsedY;
                }
            }

            if (isValidCalendarDate(year, month, day)) {
                return String.format(Locale.ENGLISH, "%04d-%02d-%02d", year, month, day);
            }
        }

        // 2. Check for "14 Oct", "01-Oct-2026", "1 Oct 2026"
        Pattern pMonth = Pattern.compile("([0-9A-Za-z]{1,2})[\\s-]+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[a-z]*(?:[\\s,.-]+(20[2-3][0-9]))?", Pattern.CASE_INSENSITIVE);
        Matcher mM = pMonth.matcher(s);
        if (mM.find()) {
            int day = parseContextualInt(mM.group(1));
            int month = parseMonthToken(mM.group(2), fallbackMonth);
            int year = fallbackYear;
            if (mM.group(3) != null) {
                year = parseContextualInt(mM.group(3));
            }
            if (isValidCalendarDate(year, month, day)) {
                return String.format(Locale.ENGLISH, "%04d-%02d-%02d", year, month, day);
            }
        }

        // 3. Check for day of week: "1 Thu", "01 Thu", "Thu 1", "31 Sat", "1 - Thu"
        Pattern pDow = Pattern.compile("(?:\\b(MON|TUE|WED|THU|FRI|SAT|SUN)[a-z]*[\\s,.-]+)?([0-9A-Za-z]{1,2})(?:[\\s,.-]+(MON|TUE|WED|THU|FRI|SAT|SUN)[a-z]*)?", Pattern.CASE_INSENSITIVE);
        Matcher mDow = pDow.matcher(s);
        if (mDow.find()) {
            String dayStr = mDow.group(2);
            if (dayStr != null && (mDow.group(1) != null || mDow.group(3) != null)) {
                int day = parseContextualInt(dayStr);
                if (isValidCalendarDate(fallbackYear, fallbackMonth, day)) {
                    return String.format(Locale.ENGLISH, "%04d-%02d-%02d", fallbackYear, fallbackMonth, day);
                }
            }
        }

        // 4. Check for day number alone (1..31) with optional dot, parenthesis, or colon: "1", "01", "1.", "31.", "1)", "(1)"
        Pattern pSolo = Pattern.compile("^\\s*[#•]?\\s*\\(?([0-9A-Za-z]{1,2})[.)]?\\s*$");
        Matcher mSolo = pSolo.matcher(s);
        if (mSolo.find()) {
            int day = parseContextualInt(mSolo.group(1));
            if (isValidCalendarDate(fallbackYear, fallbackMonth, day)) {
                return String.format(Locale.ENGLISH, "%04d-%02d-%02d", fallbackYear, fallbackMonth, day);
            }
        }

        // 5. Check if string starts with day number followed by space or delimiter: e.g. "1 Yamini" or "31 Morning"
        Pattern pLeading = Pattern.compile("^\\s*([0-9A-Za-z]{1,2})\\s*[:|\\-\\s]\\s*");
        Matcher mLead = pLeading.matcher(s);
        if (mLead.find()) {
            int day = parseContextualInt(mLead.group(1));
            if (isValidCalendarDate(fallbackYear, fallbackMonth, day)) {
                return String.format(Locale.ENGLISH, "%04d-%02d-%02d", fallbackYear, fallbackMonth, day);
            }
        }

        return null;
    }

    /**
     * Validates if year, month, and day represent a genuine calendar day.
     */
    public static boolean isValidCalendarDate(int year, int month, int day) {
        if (year < 2020 || year > 2035) return false;
        if (month < 1 || month > 12) return false;
        if (day < 1) return false;

        try {
            int maxDays = Month.of(month).length(Year.isLeap(year));
            return day <= maxDays;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converts OCR character tokens into an integer using contextual OCR substitution:
     * Z->2, S->5, B->8, O/o->0, I/l->1
     */
    public static int parseContextualInt(String token) {
        if (token == null || token.isEmpty()) return -1;
        StringBuilder sb = new StringBuilder();
        for (char c : token.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                char up = Character.toUpperCase(c);
                if (up == 'Z') sb.append('2');
                else if (up == 'S') sb.append('5');
                else if (up == 'B') sb.append('8');
                else if (up == 'O') sb.append('0');
                else if (up == 'I' || up == 'L') sb.append('1');
                else return -1; // Unrecognized non-digit character in numeric context
            }
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseMonthToken(String token, int fallback) {
        if (token == null) return fallback;
        int numeric = parseContextualInt(token);
        if (numeric >= 1 && numeric <= 12) return numeric;

        String up = token.toUpperCase(Locale.ENGLISH);
        for (int i = 0; i < 12; i++) {
            if (up.startsWith(MONTH_ABBR[i]) || up.startsWith(MONTH_NAMES[i])) {
                return i + 1;
            }
        }
        return fallback;
    }
}
