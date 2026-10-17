package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.domain.SyncStatus;

import org.junit.Test;

public class DutyDomainTest {

    @Test
    public void testShiftTypeEnumResolution() {
        assertEquals(ShiftType.MORNING, ShiftType.fromCode("M"));
        assertEquals(ShiftType.MORNING, ShiftType.fromCode("MORN"));
        assertEquals(ShiftType.EVENING, ShiftType.fromCode("E"));
        assertEquals(ShiftType.EVENING, ShiftType.fromCode("EVEN"));
        assertEquals(ShiftType.NIGHT, ShiftType.fromCode("N"));
        assertEquals(ShiftType.NIGHT, ShiftType.fromCode("NIGHT"));
        assertEquals(ShiftType.GENERAL, ShiftType.fromCode("G"));
        assertEquals(ShiftType.GENERAL, ShiftType.fromCode("GEN"));
        assertEquals(ShiftType.GENERAL, ShiftType.fromCode("D"));
        assertEquals(ShiftType.OFF, ShiftType.fromCode("OFF"));
        assertEquals(ShiftType.OFF, ShiftType.fromCode("WO"));
        assertEquals(ShiftType.LEAVE, ShiftType.fromCode("L"));
        assertEquals(ShiftType.LEAVE, ShiftType.fromCode("LEAVE"));
    }

    @Test
    public void testDutyCreationAndFormattedTime() {
        Duty duty = new Duty();
        duty.setOwnerUid("user_nurse_123");
        duty.setDutyDate("2026-10-15");
        duty.setShiftType(ShiftType.MORNING);
        duty.setShiftName("Morning Shift");
        duty.setStartTime("07:00 AM");
        duty.setEndTime("02:00 PM");
        duty.setOvernight(false);

        assertEquals("07:00 AM – 02:00 PM", duty.getFormattedDutyTime());
        assertEquals(7.0, duty.getDurationHours(), 0.01);
        assertFalse(duty.isOffOrLeave());
    }

    @Test
    public void testOvernightNightShift() {
        Duty duty = new Duty();
        duty.setDutyDate("2026-10-15");
        duty.setShiftType(ShiftType.NIGHT);
        duty.setShiftName("Night Duty");
        duty.setStartTime("08:00 PM");
        duty.setEndTime("08:00 AM");
        duty.setOvernight(true);

        assertEquals("08:00 PM – 08:00 AM (+1 day)", duty.getFormattedDutyTime());
        assertEquals(12.0, duty.getDurationHours(), 0.01);
        assertTrue(duty.isOvernight());
    }

    @Test
    public void testWeeklyOffAndLeaveStatus() {
        Duty offDuty = new Duty();
        offDuty.setShiftType(ShiftType.OFF);
        offDuty.setStatus(DutyStatus.OFF);

        assertTrue(offDuty.isOffOrLeave());
        assertEquals("Weekly Off", offDuty.getFormattedDutyTime());
        assertEquals(0.0, offDuty.getDurationHours(), 0.01);

        Duty leaveDuty = new Duty();
        leaveDuty.setShiftType(ShiftType.LEAVE);
        leaveDuty.setStatus(DutyStatus.LEAVE);

        assertTrue(leaveDuty.isOffOrLeave());
        assertEquals("On Leave", leaveDuty.getFormattedDutyTime());
    }
}
