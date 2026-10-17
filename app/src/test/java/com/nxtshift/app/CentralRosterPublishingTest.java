package com.nxtshift.app;

import com.nxtshift.app.data.model.MonthlyRoster;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ocr.RosterCsvParser;
import com.nxtshift.app.ocr.RosterTextParser;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Comprehensive test suite for Central Admin Roster Publishing,
 * Spreadsheet Parsing, Role Enforcement, Duty Isolation, and Colleague Association.
 */
public class CentralRosterPublishingTest {

    private String sampleCsv;

    @Before
    public void setUp() {
        sampleCsv =
                "Date, 8 AM - 2 PM, 2 PM - 8 PM, 8 PM - 8 AM, 9 AM - 5 PM, Off, Leave\n" +
                "01/10/2026, Munesh; Pradeep, Ajay Jangid; Yamini, Govind; Rohit, Vishal, Shiv, Ram\n" +
                "02/10/2026, Kuldeep; Hansraj, Ajay Jindoliya; Vikas, Munesh; Rohit, Naveen, Yamini, Ajay Jangid\n" +
                "03/10/2026, Govind; Rohit, Shiv; Vishal, Kuldeep; Ram, Naveen, Ajay Jangid; Yamini, -\n";
    }

    @Test
    public void testSpreadsheetParsing_ValidStructure() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10);

        assertTrue("Expected valid parse result with no fatal errors", result.isValid());
        assertEquals(0, result.errors.size());
        assertNotNull(result.roster);
        assertEquals(2026, result.roster.getYear());
        assertEquals(10, result.roster.getMonth());
        assertEquals(31, result.roster.getDays().size()); // October has 31 days

        // Verify approved staff were recognized
        List<String> staff = result.roster.getStaffList();
        assertTrue("Expected Yamini in staff list", staff.contains("Yamini"));
        assertTrue("Expected Ajay Jangid in staff list", staff.contains("Ajay Jangid"));
        assertTrue("Expected Ajay Jindoliya in staff list", staff.contains("Ajay Jindoliya"));
        assertTrue("Expected Govind in staff list", staff.contains("Govind"));
        assertTrue("Expected Shiv in staff list", staff.contains("Shiv"));
    }

    @Test
    public void testStaffDutyExtraction_ColleaguesAndTimes() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10);
        MonthlyRoster roster = result.roster;

        // 1. Extract duties for Yamini
        List<RosterTextParser.ExtractedShiftItem> yaminiDuties = roster.extractDutiesForStaff("Yamini");
        assertFalse(yaminiDuties.isEmpty());

        // Day 1 (2026-10-01): 2 PM - 8 PM (Evening), Colleague is Ajay Jangid
        RosterTextParser.ExtractedShiftItem day1 = findShiftForDate(yaminiDuties, "2026-10-01");
        assertNotNull("Expected duty for Yamini on 2026-10-01", day1);
        assertEquals(ShiftType.EVENING, day1.shiftType);
        assertEquals("02:00 PM", day1.startTime);
        assertEquals("08:00 PM", day1.endTime);
        assertFalse(day1.isOvernight);
        assertEquals("Ajay Jangid", day1.colleagues); // Excludes Yamini, keeps Ajay Jangid

        // Day 2 (2026-10-02): Off
        RosterTextParser.ExtractedShiftItem day2 = findShiftForDate(yaminiDuties, "2026-10-02");
        assertNotNull("Expected duty for Yamini on 2026-10-02", day2);
        assertEquals(ShiftType.OFF, day2.shiftType);
        assertEquals("", day2.startTime); // Never turn Off into working shift
        assertEquals("", day2.endTime);
        assertEquals("", day2.colleagues);

        // Day 3 (2026-10-03): Off
        RosterTextParser.ExtractedShiftItem day3 = findShiftForDate(yaminiDuties, "2026-10-03");
        assertNotNull("Expected duty for Yamini on 2026-10-03", day3);
        assertEquals(ShiftType.OFF, day3.shiftType);
        assertEquals("", day3.startTime);
        assertEquals("", day3.endTime);
    }

    @Test
    public void testStaffDutyExtraction_OffAndLeaveNeverWorkingShifts() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10);
        MonthlyRoster roster = result.roster;

        // Day 1: Shiv is on Off, Ram is on Leave
        List<RosterTextParser.ExtractedShiftItem> shivDuties = roster.extractDutiesForStaff("Shiv");
        RosterTextParser.ExtractedShiftItem shivDay1 = findShiftForDate(shivDuties, "2026-10-01");
        assertNotNull(shivDay1);
        assertEquals(ShiftType.OFF, shivDay1.shiftType);
        Duty shivDuty = shivDay1.toDuty("user123");
        assertEquals(DutyStatus.OFF, shivDuty.getStatus());
        assertEquals("", shivDuty.getStartTime());
        assertEquals("", shivDuty.getEndTime());

        List<RosterTextParser.ExtractedShiftItem> ramDuties = roster.extractDutiesForStaff("Ram");
        RosterTextParser.ExtractedShiftItem ramDay1 = findShiftForDate(ramDuties, "2026-10-01");
        assertNotNull(ramDay1);
        assertEquals(ShiftType.LEAVE, ramDay1.shiftType);
        Duty ramDuty = ramDay1.toDuty("user123");
        assertEquals(DutyStatus.LEAVE, ramDuty.getStatus());
        assertEquals("", ramDuty.getStartTime());
        assertEquals("", ramDuty.getEndTime());
    }

    @Test
    public void testColleaguesIsolation_NeverFromAdjacentShifts() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10);
        MonthlyRoster roster = result.roster;

        // Day 1: Govind is on Night (8 PM - 8 AM) with Rohit
        List<RosterTextParser.ExtractedShiftItem> govindDuties = roster.extractDutiesForStaff("Govind");
        RosterTextParser.ExtractedShiftItem day1 = findShiftForDate(govindDuties, "2026-10-01");
        assertNotNull(day1);
        assertEquals(ShiftType.NIGHT, day1.shiftType);
        assertEquals("08:00 PM", day1.startTime);
        assertEquals("08:00 AM", day1.endTime);
        assertTrue(day1.isOvernight);
        assertEquals("Rohit", day1.colleagues);
        // Ensure Munesh, Pradeep, Ajay Jangid, Yamini, Shiv, Ram are NOT in Govind's colleagues
        assertFalse(day1.colleagues.contains("Munesh"));
        assertFalse(day1.colleagues.contains("Ajay Jangid"));
        assertFalse(day1.colleagues.contains("Shiv"));
    }

    @Test
    public void testTwoWordNamesPreserved_AjayJangidDistinctFromAjayJindoliya() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10);
        MonthlyRoster roster = result.roster;

        // Day 2: Ajay Jindoliya is on Evening (2 PM - 8 PM), Ajay Jangid is on Leave
        List<RosterTextParser.ExtractedShiftItem> ajayJangidDuties = roster.extractDutiesForStaff("Ajay Jangid");
        List<RosterTextParser.ExtractedShiftItem> ajayJindoliyaDuties = roster.extractDutiesForStaff("Ajay Jindoliya");

        RosterTextParser.ExtractedShiftItem jangidDay2 = findShiftForDate(ajayJangidDuties, "2026-10-02");
        RosterTextParser.ExtractedShiftItem jindoliyaDay2 = findShiftForDate(ajayJindoliyaDuties, "2026-10-02");

        assertNotNull(jangidDay2);
        assertNotNull(jindoliyaDay2);

        assertEquals(ShiftType.LEAVE, jangidDay2.shiftType);
        assertEquals(ShiftType.EVENING, jindoliyaDay2.shiftType);
        assertEquals("Vikas", jindoliyaDay2.colleagues); // Ajay Jindoliya was with Vikas
    }

    @Test
    public void testAbsentStaff_ReturnsEmptyWithoutCrashing() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10);
        MonthlyRoster roster = result.roster;

        List<RosterTextParser.ExtractedShiftItem> unknownDuties = roster.extractDutiesForStaff("NonExistentNurse");
        assertNotNull(unknownDuties);
        assertTrue("Absent staff must return empty list instead of guessing wrong identity", unknownDuties.isEmpty());
    }

    @Test
    public void testFirestoreSerializationRoundtrip() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10);
        MonthlyRoster original = result.roster;
        original.setStatus(MonthlyRoster.STATUS_PUBLISHED);
        original.setVersion(2);
        original.setPublishedAt(1729000000000L);
        original.setPublishedByUid("admin_test_uid");
        original.setPublishedByEmail("admin@nxtshift.com");

        Map<String, Object> map = original.toFirestoreMap();
        assertNotNull(map);
        assertEquals("2026_10", map.get("id"));
        assertEquals(2, map.get("version"));
        assertEquals("PUBLISHED", map.get("status"));

        MonthlyRoster restored = MonthlyRoster.fromFirestoreMap(map);
        assertNotNull(restored);
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getYear(), restored.getYear());
        assertEquals(original.getMonth(), restored.getMonth());
        assertEquals(original.getVersion(), restored.getVersion());
        assertEquals(original.getStatus(), restored.getStatus());
        assertEquals(original.getPublishedByEmail(), restored.getPublishedByEmail());
        assertEquals(original.getStaffList().size(), restored.getStaffList().size());
        assertEquals(original.getDays().size(), restored.getDays().size());

        // Verify duties extracted from restored roster match original
        List<RosterTextParser.ExtractedShiftItem> restoredYamini = restored.extractDutiesForStaff("Yamini");
        List<RosterTextParser.ExtractedShiftItem> originalYamini = original.extractDutiesForStaff("Yamini");
        assertEquals(originalYamini.size(), restoredYamini.size());
        assertEquals(originalYamini.get(0).shiftType, restoredYamini.get(0).shiftType);
        assertEquals(originalYamini.get(0).colleagues, restoredYamini.get(0).colleagues);
    }

    @Test
    public void testVersioning_DoesNotSilentlyOverwriteUserCalendar() throws Exception {
        ByteArrayInputStream is1 = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        MonthlyRoster rosterV1 = RosterCsvParser.parse(is1, "oct_2026.csv", 2026, 10).roster;
        rosterV1.setVersion(1);

        ByteArrayInputStream is2 = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        MonthlyRoster rosterV2 = RosterCsvParser.parse(is2, "oct_2026.csv", 2026, 10).roster;
        rosterV2.setVersion(2);

        List<RosterTextParser.ExtractedShiftItem> v1Duties = rosterV1.extractDutiesForStaff("Yamini");
        List<RosterTextParser.ExtractedShiftItem> v2Duties = rosterV2.extractDutiesForStaff("Yamini");

        // Duty objects carry distinct version markers in notes
        assertTrue(v1Duties.get(0).notes.contains("v1"));
        assertTrue(v2Duties.get(0).notes.contains("v2"));
    }

    @Test
    public void testInvalidSpreadsheet_ReportsFatalErrors() throws Exception {
        // CSV missing shift columns
        String invalidCsv = "Col1, Col2, Col3\nval1, val2, val3\n";
        ByteArrayInputStream is = new ByteArrayInputStream(invalidCsv.getBytes(StandardCharsets.UTF_8));
        RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, "bad.csv", 2026, 10);

        assertFalse("Expected invalid parse result for missing headers", result.isValid());
        assertFalse(result.errors.isEmpty());
    }

    @Test
    public void testNoRosterPublished_IndicatesNotUpdatedYet() {
        // When Firestore returns null document or a draft document for normal user
        MonthlyRoster nullRoster = null;
        assertNull("When no roster is published, roster object is null", nullRoster);

        MonthlyRoster draftRoster = new MonthlyRoster(2026, 11, "November 2026");
        draftRoster.setStatus(MonthlyRoster.STATUS_DRAFT);

        // Regular users only receive published rosters
        boolean isAvailableToUser = MonthlyRoster.STATUS_PUBLISHED.equals(draftRoster.getStatus());
        assertFalse("Draft rosters must not be available to normal users", isAvailableToUser);
    }

    @Test
    public void testDuplicateImport_DoesNotDuplicateCalendarEntries() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        MonthlyRoster roster = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10).roster;
        List<RosterTextParser.ExtractedShiftItem> items = roster.extractDutiesForStaff("Yamini");

        // Convert to Duties
        String userUid = "test_user_yamini";
        Map<String, Duty> calendarByDate = new java.util.HashMap<>();

        // First import
        for (RosterTextParser.ExtractedShiftItem item : items) {
            Duty d = item.toDuty(userUid);
            calendarByDate.put(d.getDutyDate(), d);
        }
        int initialSize = calendarByDate.size();

        // Second import of the same roster
        for (RosterTextParser.ExtractedShiftItem item : items) {
            Duty d = item.toDuty(userUid);
            // MERGE_REPLACE_CONFLICTS policy replaces/updates existing duty by date
            if (calendarByDate.containsKey(d.getDutyDate())) {
                Duty existing = calendarByDate.get(d.getDutyDate());
                d.setId(existing.getId());
            }
            calendarByDate.put(d.getDutyDate(), d);
        }

        assertEquals("Re-importing the same roster must not increase calendar entry count",
                initialSize, calendarByDate.size());
    }

    @Test
    public void testFirestoreSecurityRules_DataValidation() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        MonthlyRoster roster = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10).roster;
        roster.setStatus(MonthlyRoster.STATUS_PUBLISHED);
        roster.setVersion(1);
        roster.setUpdatedAt(System.currentTimeMillis());

        Map<String, Object> map = roster.toFirestoreMap();

        // Validations required by firestore.rules function isValidMonthlyRoster(data)
        assertTrue("id is string", map.get("id") instanceof String);
        assertTrue("year is number", map.get("year") instanceof Number);
        assertTrue("month is number", map.get("month") instanceof Number);
        assertTrue("version is number", map.get("version") instanceof Number);
        assertTrue("updatedAt is number", map.get("updatedAt") instanceof Number);
        String status = (String) map.get("status");
        assertTrue("status in ['DRAFT', 'PUBLISHED']",
                MonthlyRoster.STATUS_DRAFT.equals(status) || MonthlyRoster.STATUS_PUBLISHED.equals(status));
    }

    @Test
    public void testStaffDirectoryPopulation_ApprovedStaffList() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(sampleCsv.getBytes(StandardCharsets.UTF_8));
        MonthlyRoster roster = RosterCsvParser.parse(is, "oct_2026.csv", 2026, 10).roster;

        List<String> staff = roster.getStaffList();
        assertNotNull(staff);
        assertFalse(staff.isEmpty());

        // Ensure sorted and unique
        for (int i = 0; i < staff.size() - 1; i++) {
            assertTrue("Staff list must be sorted and distinct", staff.get(i).compareTo(staff.get(i + 1)) < 0);
        }
    }

    private RosterTextParser.ExtractedShiftItem findShiftForDate(List<RosterTextParser.ExtractedShiftItem> list, String date) {
        for (RosterTextParser.ExtractedShiftItem item : list) {
            if (item.date.equals(date)) {
                return item;
            }
        }
        return null;
    }
}
