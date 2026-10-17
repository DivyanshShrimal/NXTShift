package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.util.DateTimeUtils;

import org.junit.Test;

import java.time.LocalDate;

public class DateTimeUtilsTest {

    @Test
    public void testIsoDateConversions() {
        LocalDate date = LocalDate.of(2026, 10, 14);
        String iso = DateTimeUtils.toIsoDate(date);
        assertEquals("2026-10-14", iso);

        LocalDate parsed = DateTimeUtils.parseIsoDate("2026-10-14");
        assertEquals(date, parsed);
    }

    @Test
    public void testNextDayCalculation() {
        String next = DateTimeUtils.getNextDayIsoDate("2026-10-14");
        assertEquals("2026-10-15", next);

        // Leap day or month end transition
        String monthEndNext = DateTimeUtils.getNextDayIsoDate("2026-10-31");
        assertEquals("2026-11-01", monthEndNext);
    }

    @Test
    public void test12HourTimeNormalization() {
        assertEquals("07:00 AM", DateTimeUtils.normalizeTime12Hour("7:00 AM"));
        assertEquals("07:00 AM", DateTimeUtils.normalizeTime12Hour("7:00AM"));
        assertEquals("02:30 PM", DateTimeUtils.normalizeTime12Hour("14:30"));
        assertEquals("08:00 PM", DateTimeUtils.normalizeTime12Hour("20:00"));
        assertEquals("08:00 AM", DateTimeUtils.normalizeTime12Hour("08:00"));
    }

    @Test
    public void testShiftDurationDaytime() {
        // Morning shift 7:00 AM to 2:00 PM = 7 hours
        double duration = DateTimeUtils.calculateShiftDurationHours("07:00 AM", "02:00 PM", false);
        assertEquals(7.0, duration, 0.01);

        // General shift 9:00 AM to 5:00 PM = 8 hours
        double genDuration = DateTimeUtils.calculateShiftDurationHours("09:00 AM", "05:00 PM", false);
        assertEquals(8.0, genDuration, 0.01);
    }

    @Test
    public void testShiftDurationOvernight() {
        // Night shift 8:00 PM to 8:00 AM next day = 12 hours
        double nightDuration = DateTimeUtils.calculateShiftDurationHours("08:00 PM", "08:00 AM", true);
        assertEquals(12.0, nightDuration, 0.01);

        // Night shift 9:00 PM to 7:00 AM = 10 hours
        double night10h = DateTimeUtils.calculateShiftDurationHours("09:00 PM", "07:00 AM", true);
        assertEquals(10.0, night10h, 0.01);
    }

    @Test
    public void testOcrDateParsingFormats() {
        // 1. Standard ISO
        assertEquals("2026-10-14", DateTimeUtils.tryParseOcrDate("2026-10-14", 2026, 10));

        // 2. dd-MM-yyyy
        assertEquals("2026-10-14", DateTimeUtils.tryParseOcrDate("14-10-2026", 2026, 10));

        // 3. dd/MM/yyyy
        assertEquals("2026-10-14", DateTimeUtils.tryParseOcrDate("14/10/2026", 2026, 10));

        // 4. dd-MMM-yyyy
        assertEquals("2026-10-14", DateTimeUtils.tryParseOcrDate("14-Oct-2026", 2026, 10));

        // 5. Day of month number
        assertEquals("2026-10-14", DateTimeUtils.tryParseOcrDate("14", 2026, 10));
    }
}
