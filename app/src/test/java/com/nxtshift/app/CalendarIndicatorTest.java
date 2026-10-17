package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ui.calendar.CalendarDayAdapter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests verifying calendar indicator resolution for Off, Leave, and working shifts,
 * conflict handling, and distinct state representation.
 */
public class CalendarIndicatorTest {

    @Test
    public void testEmptyAndNullDuties_ResolvesToNone() {
        CalendarDayAdapter.DayVisualResolution resNull = CalendarDayAdapter.resolveDayDuties(null);
        assertEquals(CalendarDayAdapter.DayDutyVisualState.NONE, resNull.state);
        assertNull(resNull.primaryDuty);

        CalendarDayAdapter.DayVisualResolution resEmpty = CalendarDayAdapter.resolveDayDuties(Collections.emptyList());
        assertEquals(CalendarDayAdapter.DayDutyVisualState.NONE, resEmpty.state);
        assertNull(resEmpty.primaryDuty);
    }

    @Test
    public void testOffDuty_ResolvesToOffState() {
        Duty offShiftDuty = new Duty();
        offShiftDuty.setDutyDate("2026-10-01");
        offShiftDuty.setShiftType(ShiftType.OFF);
        offShiftDuty.setStatus(DutyStatus.OFF);

        assertTrue(CalendarDayAdapter.isOffDuty(offShiftDuty));
        assertFalse(CalendarDayAdapter.isLeaveDuty(offShiftDuty));

        CalendarDayAdapter.DayVisualResolution resolution =
                CalendarDayAdapter.resolveDayDuties(Collections.singletonList(offShiftDuty));
        assertEquals(CalendarDayAdapter.DayDutyVisualState.OFF, resolution.state);
        assertEquals(offShiftDuty, resolution.primaryDuty);
    }

    @Test
    public void testOffDutyByStatusOnly_ResolvesToOffState() {
        Duty offStatusDuty = new Duty();
        offStatusDuty.setDutyDate("2026-10-01");
        offStatusDuty.setShiftType(ShiftType.GENERAL);
        offStatusDuty.setStatus(DutyStatus.OFF);

        assertTrue(CalendarDayAdapter.isOffDuty(offStatusDuty));

        CalendarDayAdapter.DayVisualResolution resolution =
                CalendarDayAdapter.resolveDayDuties(Collections.singletonList(offStatusDuty));
        assertEquals(CalendarDayAdapter.DayDutyVisualState.OFF, resolution.state);
    }

    @Test
    public void testLeaveDuty_ResolvesToLeaveState() {
        Duty leaveDuty = new Duty();
        leaveDuty.setDutyDate("2026-10-02");
        leaveDuty.setShiftType(ShiftType.LEAVE);
        leaveDuty.setStatus(DutyStatus.LEAVE);

        assertTrue(CalendarDayAdapter.isLeaveDuty(leaveDuty));
        assertFalse(CalendarDayAdapter.isOffDuty(leaveDuty));

        CalendarDayAdapter.DayVisualResolution resolution =
                CalendarDayAdapter.resolveDayDuties(Collections.singletonList(leaveDuty));
        assertEquals(CalendarDayAdapter.DayDutyVisualState.LEAVE, resolution.state);
        assertEquals(leaveDuty, resolution.primaryDuty);
    }

    @Test
    public void testLeaveDutyByStatusOnly_ResolvesToLeaveState() {
        Duty leaveStatusDuty = new Duty();
        leaveStatusDuty.setDutyDate("2026-10-02");
        leaveStatusDuty.setShiftType(ShiftType.GENERAL);
        leaveStatusDuty.setStatus(DutyStatus.LEAVE);

        assertTrue(CalendarDayAdapter.isLeaveDuty(leaveStatusDuty));

        CalendarDayAdapter.DayVisualResolution resolution =
                CalendarDayAdapter.resolveDayDuties(Collections.singletonList(leaveStatusDuty));
        assertEquals(CalendarDayAdapter.DayDutyVisualState.LEAVE, resolution.state);
    }

    @Test
    public void testWorkingShifts_ResolveToWorkingStateWithCorrectDuty() {
        ShiftType[] workingTypes = new ShiftType[]{
                ShiftType.MORNING,
                ShiftType.EVENING,
                ShiftType.NIGHT,
                ShiftType.GENERAL
        };

        for (ShiftType type : workingTypes) {
            Duty duty = new Duty();
            duty.setDutyDate("2026-10-03");
            duty.setShiftType(type);
            duty.setStatus(DutyStatus.SCHEDULED);

            assertFalse("Must not be off duty: " + type, CalendarDayAdapter.isOffDuty(duty));
            assertFalse("Must not be leave duty: " + type, CalendarDayAdapter.isLeaveDuty(duty));

            CalendarDayAdapter.DayVisualResolution res =
                    CalendarDayAdapter.resolveDayDuties(Collections.singletonList(duty));
            assertEquals("Working type " + type + " must resolve to WORKING state",
                    CalendarDayAdapter.DayDutyVisualState.WORKING, res.state);
            assertEquals(type, res.primaryDuty.getShiftType());
        }
    }

    @Test
    public void testConflictWorkingAndOff_PrioritizesWorkingDuty() {
        Duty workingDuty = new Duty();
        workingDuty.setDutyDate("2026-10-04");
        workingDuty.setShiftType(ShiftType.MORNING);
        workingDuty.setStatus(DutyStatus.SCHEDULED);

        Duty offDuty = new Duty();
        offDuty.setDutyDate("2026-10-04");
        offDuty.setShiftType(ShiftType.OFF);
        offDuty.setStatus(DutyStatus.OFF);

        List<Duty> duties = new ArrayList<>();
        duties.add(offDuty);
        duties.add(workingDuty);

        CalendarDayAdapter.DayVisualResolution resolution = CalendarDayAdapter.resolveDayDuties(duties);
        assertEquals("Working shift must take priority over off entry for calendar indicator",
                CalendarDayAdapter.DayDutyVisualState.WORKING, resolution.state);
        assertEquals(ShiftType.MORNING, resolution.primaryDuty.getShiftType());
    }

    @Test
    public void testConflictWorkingAndLeave_PrioritizesWorkingDuty() {
        Duty workingDuty = new Duty();
        workingDuty.setDutyDate("2026-10-05");
        workingDuty.setShiftType(ShiftType.NIGHT);
        workingDuty.setStatus(DutyStatus.SCHEDULED);

        Duty leaveDuty = new Duty();
        leaveDuty.setDutyDate("2026-10-05");
        leaveDuty.setShiftType(ShiftType.LEAVE);
        leaveDuty.setStatus(DutyStatus.LEAVE);

        List<Duty> duties = new ArrayList<>();
        duties.add(leaveDuty);
        duties.add(workingDuty);

        CalendarDayAdapter.DayVisualResolution resolution = CalendarDayAdapter.resolveDayDuties(duties);
        assertEquals("Working shift must take priority over leave entry for calendar indicator",
                CalendarDayAdapter.DayDutyVisualState.WORKING, resolution.state);
        assertEquals(ShiftType.NIGHT, resolution.primaryDuty.getShiftType());
    }

    @Test
    public void testConflictOffAndLeave_PrioritizesLeaveIndicator() {
        Duty offDuty = new Duty();
        offDuty.setDutyDate("2026-10-06");
        offDuty.setShiftType(ShiftType.OFF);
        offDuty.setStatus(DutyStatus.OFF);

        Duty leaveDuty = new Duty();
        leaveDuty.setDutyDate("2026-10-06");
        leaveDuty.setShiftType(ShiftType.LEAVE);
        leaveDuty.setStatus(DutyStatus.LEAVE);

        List<Duty> duties = new ArrayList<>();
        duties.add(offDuty);
        duties.add(leaveDuty);

        CalendarDayAdapter.DayVisualResolution resolution = CalendarDayAdapter.resolveDayDuties(duties);
        assertEquals("When user has both Off and Leave on same date, Leave indicator 'L' is displayed",
                CalendarDayAdapter.DayDutyVisualState.LEAVE, resolution.state);
    }

    @Test
    public void testOffLeaveAndNoEntry_AreDistinctStates() {
        Duty offDuty = new Duty();
        offDuty.setShiftType(ShiftType.OFF);
        offDuty.setStatus(DutyStatus.OFF);

        Duty leaveDuty = new Duty();
        leaveDuty.setShiftType(ShiftType.LEAVE);
        leaveDuty.setStatus(DutyStatus.LEAVE);

        CalendarDayAdapter.DayVisualResolution resOff =
                CalendarDayAdapter.resolveDayDuties(Collections.singletonList(offDuty));
        CalendarDayAdapter.DayVisualResolution resLeave =
                CalendarDayAdapter.resolveDayDuties(Collections.singletonList(leaveDuty));
        CalendarDayAdapter.DayVisualResolution resNone =
                CalendarDayAdapter.resolveDayDuties(Collections.emptyList());

        assertNotEquals(resOff.state, resLeave.state);
        assertNotEquals(resOff.state, resNone.state);
        assertNotEquals(resLeave.state, resNone.state);

        assertEquals(CalendarDayAdapter.DayDutyVisualState.OFF, resOff.state);
        assertEquals(CalendarDayAdapter.DayDutyVisualState.LEAVE, resLeave.state);
        assertEquals(CalendarDayAdapter.DayDutyVisualState.NONE, resNone.state);
    }

    @Test
    public void testPreserveStoredDataValues() {
        Duty offDuty = new Duty();
        offDuty.setId("test-off-id");
        offDuty.setOwnerUid("user-1");
        offDuty.setDutyDate("2026-10-07");
        offDuty.setShiftType(ShiftType.OFF);
        offDuty.setStatus(DutyStatus.OFF);

        Duty leaveDuty = new Duty();
        leaveDuty.setId("test-leave-id");
        leaveDuty.setOwnerUid("user-1");
        leaveDuty.setDutyDate("2026-10-08");
        leaveDuty.setShiftType(ShiftType.LEAVE);
        leaveDuty.setStatus(DutyStatus.LEAVE);

        // Verify resolution does not mutate underlying duty model fields
        CalendarDayAdapter.resolveDayDuties(Collections.singletonList(offDuty));
        CalendarDayAdapter.resolveDayDuties(Collections.singletonList(leaveDuty));

        assertEquals(ShiftType.OFF, offDuty.getShiftType());
        assertEquals(DutyStatus.OFF, offDuty.getStatus());
        assertEquals("2026-10-07", offDuty.getDutyDate());
        assertEquals("test-off-id", offDuty.getId());

        assertEquals(ShiftType.LEAVE, leaveDuty.getShiftType());
        assertEquals(DutyStatus.LEAVE, leaveDuty.getStatus());
        assertEquals("2026-10-08", leaveDuty.getDutyDate());
        assertEquals("test-leave-id", leaveDuty.getId());
    }
}
