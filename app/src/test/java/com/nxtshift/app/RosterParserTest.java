
package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ocr.RosterTextParser;

import org.junit.Test;

import java.lang.reflect.Method;

public class RosterParserTest {

    @Test
    public void testParseLineMorningShiftWithWard() throws Exception {
        Method method = RosterTextParser.class.getDeclaredMethod("tryParseLine", String.class, int.class, int.class);
        method.setAccessible(true);

        String sampleLine = "14-10-2026 Morning ICU";
        RosterTextParser.ExtractedShiftItem item =
                (RosterTextParser.ExtractedShiftItem) method.invoke(null, sampleLine, 2026, 10);

        assertNotNull(item);
        assertEquals("2026-10-14", item.date);
        assertEquals(ShiftType.MORNING, item.shiftType);
        assertEquals("ICU", item.ward);
        assertEquals("08:00 AM", item.startTime);
        assertEquals("02:00 PM", item.endTime);
        assertFalse(item.isOvernight);
    }

    @Test
    public void testParseLineNightShiftOvernightWithTimes() throws Exception {
        Method method = RosterTextParser.class.getDeclaredMethod("tryParseLine", String.class, int.class, int.class);
        method.setAccessible(true);

        String sampleLine = "15-Oct-2026 Night 08:00 PM - 08:00 AM Emergency";
        RosterTextParser.ExtractedShiftItem item =
                (RosterTextParser.ExtractedShiftItem) method.invoke(null, sampleLine, 2026, 10);

        assertNotNull(item);
        assertEquals("2026-10-15", item.date);
        assertEquals(ShiftType.NIGHT, item.shiftType);
        assertEquals("EMERGENCY", item.ward);
        assertEquals("08:00 PM", item.startTime);
        assertEquals("08:00 AM", item.endTime);
        assertTrue(item.isOvernight);

        Duty duty = item.toDuty("test_uid_nurse");
        assertEquals("08:00 PM – 08:00 AM (+1 day)", duty.getFormattedDutyTime());
    }

    @Test
    public void testParseLineWeeklyOff() throws Exception {
        Method method = RosterTextParser.class.getDeclaredMethod("tryParseLine", String.class, int.class, int.class);
        method.setAccessible(true);

        String sampleLine = "16/10/2026 OFF";
        RosterTextParser.ExtractedShiftItem item =
                (RosterTextParser.ExtractedShiftItem) method.invoke(null, sampleLine, 2026, 10);

        assertNotNull(item);
        assertEquals("2026-10-16", item.date);
        assertEquals(ShiftType.OFF, item.shiftType);
        assertTrue(item.toDuty("test_uid_nurse").isOffOrLeave());
    }

    @Test
    public void testParseLineTableDayNumberWithCode() throws Exception {
        Method method = RosterTextParser.class.getDeclaredMethod("tryParseLine", String.class, int.class, int.class);
        method.setAccessible(true);

        String sampleLine = "17 E CASUALTY";
        RosterTextParser.ExtractedShiftItem item =
                (RosterTextParser.ExtractedShiftItem) method.invoke(null, sampleLine, 2026, 10);

        assertNotNull(item);
        assertEquals("2026-10-17", item.date);
        assertEquals(ShiftType.EVENING, item.shiftType);
        assertEquals("CASUALTY", item.ward);
    }
}
