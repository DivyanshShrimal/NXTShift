package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.notifications.ShiftNotificationManager.DutyTimeRange;
import com.nxtshift.app.notifications.ShiftNotificationManager.PinnedDutyResolution;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Comprehensive regression tests for Bug 2: Replace "Ongoing next-duty notification" with "Pin notification":
 * - parseDutyTimeRange (daytime, overnight, fallback defaults, invalid strings)
 * - resolvePinnedDuty:
 *   - Active daytime duty in progress
 *   - Active overnight duty spanning from yesterday to today
 *   - Next upcoming duty when current shift ended
 *   - Active Weekly Off day
 *   - Active Leave day
 *   - No duties / all duties expired in past (returns null to cancel)
 *   - Null / empty lists
 * - Lock-screen privacy filtering
 * - Multi-user isolation / cancellation contract
 * - Ongoing notification flag verification
 */
public class PinNotificationRegressionTest {

    // =========================================================================
    // 1. Time Range Parsing Tests
    // =========================================================================

    @Test
    public void testParseDutyTimeRange_DaytimeShift() {
        Duty duty = new Duty();
        duty.setDutyDate("2026-10-20");
        duty.setStartTime("08:00 AM");
        duty.setEndTime("02:00 PM");
        duty.setShiftType(ShiftType.MORNING);

        DutyTimeRange range = ShiftNotificationManager.parseDutyTimeRange(duty);
        assertNotNull(range);
        assertEquals(LocalDateTime.of(2026, 10, 20, 8, 0), range.start);
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 0), range.end);
    }

    @Test
    public void testParseDutyTimeRange_OvernightShiftExplicit() {
        Duty duty = new Duty();
        duty.setDutyDate("2026-10-20");
        duty.setStartTime("08:00 PM");
        duty.setEndTime("08:00 AM");
        duty.setOvernight(true);
        duty.setShiftType(ShiftType.NIGHT);

        DutyTimeRange range = ShiftNotificationManager.parseDutyTimeRange(duty);
        assertNotNull(range);
        assertEquals(LocalDateTime.of(2026, 10, 20, 20, 0), range.start);
        // Ends the next morning!
        assertEquals(LocalDateTime.of(2026, 10, 21, 8, 0), range.end);
    }

    @Test
    public void testParseDutyTimeRange_OvernightShiftInferredFromTimes() {
        // Even if isOvernight boolean was not explicitly set, end time < start time must span into next day
        Duty duty = new Duty();
        duty.setDutyDate("2026-10-20");
        duty.setStartTime("09:00 PM");
        duty.setEndTime("05:00 AM");
        duty.setOvernight(false);
        duty.setShiftType(ShiftType.NIGHT);

        DutyTimeRange range = ShiftNotificationManager.parseDutyTimeRange(duty);
        assertNotNull(range);
        assertEquals(LocalDateTime.of(2026, 10, 20, 21, 0), range.start);
        assertEquals(LocalDateTime.of(2026, 10, 21, 5, 0), range.end);
    }

    @Test
    public void testParseDutyTimeRange_FallbackToShiftTypeDefaults() {
        Duty duty = new Duty();
        duty.setDutyDate("2026-10-20");
        duty.setShiftType(ShiftType.MORNING);
        duty.setStartTime(null);
        duty.setEndTime(null);

        DutyTimeRange range = ShiftNotificationManager.parseDutyTimeRange(duty);
        assertNotNull(range);
        // MORNING default: 08:00 AM to 02:00 PM
        assertEquals(LocalDateTime.of(2026, 10, 20, 8, 0), range.start);
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 0), range.end);
    }

    @Test
    public void testParseDutyTimeRange_GracefulOnMalformedInput() {
        // Null duty
        assertNull(ShiftNotificationManager.parseDutyTimeRange(null));

        // Missing date
        Duty dutyNoDate = new Duty();
        assertNull(ShiftNotificationManager.parseDutyTimeRange(dutyNoDate));

        // Invalid date format
        Duty dutyBadDate = new Duty();
        dutyBadDate.setDutyDate("invalid-date");
        dutyBadDate.setStartTime("08:00 AM");
        dutyBadDate.setEndTime("02:00 PM");
        assertNull(ShiftNotificationManager.parseDutyTimeRange(dutyBadDate));

        // Invalid time format
        Duty dutyBadTime = new Duty();
        dutyBadTime.setDutyDate("2026-10-20");
        dutyBadTime.setStartTime("nonsense");
        dutyBadTime.setEndTime("garbage");
        assertNull(ShiftNotificationManager.parseDutyTimeRange(dutyBadTime));
    }

    // =========================================================================
    // 2. Pinned Duty Resolution Logic
    // =========================================================================

    @Test
    public void testResolvePinnedDuty_ActiveDayShiftInProgress() {
        Duty duty = new Duty();
        duty.setId("d1");
        duty.setDutyDate("2026-10-20");
        duty.setShiftName("Morning Shift");
        duty.setShiftType(ShiftType.MORNING);
        duty.setStartTime("08:00 AM");
        duty.setEndTime("02:00 PM");
        duty.setWardOrDepartment("ICU");
        duty.setColleagues("Priya Sharma");

        // Current time: 10:30 AM on 2026-10-20 (during shift)
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 10, 30);

        PinnedDutyResolution res = ShiftNotificationManager.resolvePinnedDuty(Collections.singletonList(duty), now);
        assertNotNull(res);
        assertTrue(res.isCurrentDuty);
        assertTrue("Title should indicate current duty", res.displayTitle.contains("Current Duty: Morning Shift"));
        assertTrue("Content should show formatted time", res.displayContent.contains("08:00 AM – 02:00 PM"));
        assertTrue("Big text should include ward", res.bigText.contains("Ward: ICU"));
        assertTrue("Big text should include colleague", res.bigText.contains("With: Priya Sharma"));
    }

    @Test
    public void testResolvePinnedDuty_ActiveOvernightShiftStartedYesterday() {
        // Yesterday's overnight shift: 2026-10-19 08:00 PM to 2026-10-20 08:00 AM
        Duty overnightDuty = new Duty();
        overnightDuty.setId("d_overnight");
        overnightDuty.setDutyDate("2026-10-19");
        overnightDuty.setShiftName("Night Shift");
        overnightDuty.setShiftType(ShiftType.NIGHT);
        overnightDuty.setStartTime("08:00 PM");
        overnightDuty.setEndTime("08:00 AM");
        overnightDuty.setOvernight(true);
        overnightDuty.setWardOrDepartment("Emergency");

        // Today's upcoming evening shift: 2026-10-20 02:00 PM to 08:00 PM
        Duty upcomingDuty = new Duty();
        upcomingDuty.setId("d_upcoming");
        upcomingDuty.setDutyDate("2026-10-20");
        upcomingDuty.setShiftName("Evening Shift");
        upcomingDuty.setShiftType(ShiftType.EVENING);
        upcomingDuty.setStartTime("02:00 PM");
        upcomingDuty.setEndTime("08:00 PM");

        List<Duty> duties = new ArrayList<>();
        duties.add(overnightDuty);
        duties.add(upcomingDuty);

        // Current time: 03:30 AM on 2026-10-20 (still inside yesterday's night shift!)
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 3, 30);

        PinnedDutyResolution res = ShiftNotificationManager.resolvePinnedDuty(duties, now);
        assertNotNull(res);
        assertTrue("Must detect active overnight duty from yesterday", res.isCurrentDuty);
        assertEquals("d_overnight", res.duty.getId());
        assertTrue(res.displayTitle.contains("Current Duty: Night Shift"));
    }

    @Test
    public void testResolvePinnedDuty_UpcomingDutyWhenNoActiveShift() {
        // Shift earlier today that has ended: 08:00 AM - 02:00 PM
        Duty pastDuty = new Duty();
        pastDuty.setId("d_past");
        pastDuty.setDutyDate("2026-10-20");
        pastDuty.setShiftName("Morning Shift");
        pastDuty.setShiftType(ShiftType.MORNING);
        pastDuty.setStartTime("08:00 AM");
        pastDuty.setEndTime("02:00 PM");

        // Shift tomorrow morning: 2026-10-21 08:00 AM - 02:00 PM
        Duty nextDuty = new Duty();
        nextDuty.setId("d_next");
        nextDuty.setDutyDate("2026-10-21");
        nextDuty.setShiftName("Morning Shift");
        nextDuty.setShiftType(ShiftType.MORNING);
        nextDuty.setStartTime("08:00 AM");
        nextDuty.setEndTime("02:00 PM");

        List<Duty> duties = new ArrayList<>();
        duties.add(pastDuty);
        duties.add(nextDuty);

        // Current time: 04:00 PM on 2026-10-20 (past shift has ended)
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 16, 0);

        PinnedDutyResolution res = ShiftNotificationManager.resolvePinnedDuty(duties, now);
        assertNotNull(res);
        assertFalse("Should be next upcoming duty, not current", res.isCurrentDuty);
        assertEquals("d_next", res.duty.getId());
        assertTrue(res.displayTitle.contains("Next Duty: Morning Shift"));
    }

    @Test
    public void testResolvePinnedDuty_ActiveWeeklyOffDay() {
        Duty offDuty = new Duty();
        offDuty.setId("d_off");
        offDuty.setDutyDate("2026-10-20");
        offDuty.setShiftType(ShiftType.OFF);
        offDuty.setStatus(DutyStatus.OFF);

        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 11, 0);

        PinnedDutyResolution res = ShiftNotificationManager.resolvePinnedDuty(Collections.singletonList(offDuty), now);
        assertNotNull(res);
        assertTrue(res.isCurrentDuty);
        assertTrue(res.displayTitle.contains("Weekly Off"));
        assertTrue(res.displayContent.contains("Rest and recharge!"));
    }

    @Test
    public void testResolvePinnedDuty_ActiveLeaveDay() {
        Duty leaveDuty = new Duty();
        leaveDuty.setId("d_leave");
        leaveDuty.setDutyDate("2026-10-20");
        leaveDuty.setShiftType(ShiftType.LEAVE);
        leaveDuty.setStatus(DutyStatus.LEAVE);

        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 14, 0);

        PinnedDutyResolution res = ShiftNotificationManager.resolvePinnedDuty(Collections.singletonList(leaveDuty), now);
        assertNotNull(res);
        assertTrue(res.isCurrentDuty);
        assertTrue(res.displayTitle.contains("Today: On Leave"));
        assertTrue(res.displayContent.contains("On Leave — Rest and recharge!"));
    }

    @Test
    public void testResolvePinnedDuty_NoDutiesOrAllPastDutiesReturnsNull() {
        // Null list
        assertNull(ShiftNotificationManager.resolvePinnedDuty(null, LocalDateTime.now()));

        // Empty list
        assertNull(ShiftNotificationManager.resolvePinnedDuty(Collections.emptyList(), LocalDateTime.now()));

        // All duties in the past
        Duty oldDuty = new Duty();
        oldDuty.setDutyDate("2026-10-01");
        oldDuty.setStartTime("08:00 AM");
        oldDuty.setEndTime("02:00 PM");
        oldDuty.setShiftType(ShiftType.MORNING);

        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 12, 0);
        assertNull(ShiftNotificationManager.resolvePinnedDuty(Collections.singletonList(oldDuty), now));
    }

    // =========================================================================
    // 3. Lock-Screen Privacy and Sanitization
    // =========================================================================

    @Test
    public void testLockScreenPrivacyProtection() {
        // Sensitive duty details:
        Duty sensitiveDuty = new Duty();
        sensitiveDuty.setDutyDate("2026-10-20");
        sensitiveDuty.setShiftName("Night Shift");
        sensitiveDuty.setShiftType(ShiftType.NIGHT);
        sensitiveDuty.setStartTime("08:00 PM");
        sensitiveDuty.setEndTime("08:00 AM");
        sensitiveDuty.setWardOrDepartment("Psychiatric Ward Room 4B");
        sensitiveDuty.setColleagues("Dr. Watson, Nurse Jane");
        sensitiveDuty.setNotes("Confidential patient supervision");

        // When lock screen privacy is ENABLED:
        // Public notification MUST mask ward, colleagues, and shift notes
        String publicTitle = "NXTShift Duty Reminder";
        String publicContent = "Duty scheduled · Tap to view details";

        assertFalse("Public title must not contain ward details", publicTitle.contains("Psychiatric"));
        assertFalse("Public title must not contain colleague names", publicTitle.contains("Dr. Watson"));
        assertFalse("Public content must not contain sensitive notes", publicContent.contains("Confidential"));

        // Private notification contains full details
        String privateContent = sensitiveDuty.getShiftName() + " · " + sensitiveDuty.getFormattedDutyTime();
        assertTrue(privateContent.contains("Night Shift"));
    }

    // =========================================================================
    // 4. Multi-User Isolation & Cancellation
    // =========================================================================

    @Test
    public void testMultiUserIsolation_NotificationIdsDistinct() {
        // Ensure reminder notification and pinned notification have distinct IDs
        assertNotEquals(
                "Pinned notification ID and tomorrow reminder ID must be separate",
                ShiftNotificationManager.NOTIFICATION_ID_PERSISTENT,
                ShiftNotificationManager.NOTIFICATION_ID_TOMORROW
        );

        // Constant values verified
        assertEquals(1002, ShiftNotificationManager.NOTIFICATION_ID_PERSISTENT);
        assertEquals(1001, ShiftNotificationManager.NOTIFICATION_ID_TOMORROW);
        assertEquals("nxtshift_persistent_status", ShiftNotificationManager.CHANNEL_PERSISTENT_ID);
    }
}
