package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;

import org.junit.Test;

public class NotificationContentTest {

    @Test
    public void testNotificationContentTomorrowDuty() {
        Duty duty = new Duty();
        duty.setDutyDate("2026-10-15");
        duty.setShiftType(ShiftType.MORNING);
        duty.setShiftName("Morning Shift");
        duty.setStartTime("07:00 AM");
        duty.setEndTime("02:00 PM");
        duty.setColleagues("Priya Sharma");
        duty.setWardOrDepartment("Emergency");

        String content = duty.getShiftName() + " · " + duty.getFormattedDutyTime();
        assertEquals("Morning Shift · 07:00 AM – 02:00 PM", content);

        String bigText = content + "\nWith: " + duty.getColleagues() + "\nWard: " + duty.getWardOrDepartment();
        assertTrue(bigText.contains("With: Priya Sharma"));
        assertTrue(bigText.contains("Ward: Emergency"));
    }

    @Test
    public void testNotificationContentOffDay() {
        Duty duty = new Duty();
        duty.setDutyDate("2026-10-15");
        duty.setShiftType(ShiftType.OFF);
        duty.setStatus(DutyStatus.OFF);

        assertTrue(duty.isOffOrLeave());
        String content = duty.getShiftType().getDisplayName() + " — Rest and recharge!";
        assertEquals("Weekly Off — Rest and recharge!", content);
    }
}
