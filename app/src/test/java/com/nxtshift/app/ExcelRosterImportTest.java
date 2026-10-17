package com.nxtshift.app;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.excel.ExcelDayRow;
import com.nxtshift.app.excel.ExcelRosterData;
import com.nxtshift.app.excel.ExcelRosterParser;
import com.nxtshift.app.excel.ExcelShiftColumn;
import com.nxtshift.app.excel.ExcelStaffSchedule;
import com.nxtshift.app.excel.ExcelStaffScheduleItem;
import com.nxtshift.app.ocr.RosterCsvParser;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Comprehensive automated test suite for NXTShift Excel Duty Roster Import (MVP).
 * Validates extraction, date parsing, column mapping, staff matching, colleague calculation,
 * conflict detection, overnight shifts, deduplication, and edge cases.
 */
public class ExcelRosterImportTest {

    private File referenceFile;
    private File autoRecoveredFile;

    @Before
    public void setUp() {
        referenceFile = new File("roster_fixtures/October_2026_Hospital_Roster_OnePage.xlsx");
        if (!referenceFile.exists()) {
            referenceFile = new File("../roster_fixtures/October_2026_Hospital_Roster_OnePage.xlsx");
        }
        if (!referenceFile.exists()) {
            referenceFile = new File("C:/Users/shrim/Documents/NXTShift/roster_fixtures/October_2026_Hospital_Roster_OnePage.xlsx");
        }
        if (!referenceFile.exists()) {
            referenceFile = new File("C:/Users/shrim/Downloads/October_2026_Hospital_Roster_OnePage.xlsx");
        }

        autoRecoveredFile = new File("roster_fixtures/October_2026_Hospital_Roster_OnePage(AutoRecovered).xlsx");
        if (!autoRecoveredFile.exists()) {
            autoRecoveredFile = new File("../roster_fixtures/October_2026_Hospital_Roster_OnePage(AutoRecovered).xlsx");
        }
        if (!autoRecoveredFile.exists()) {
            autoRecoveredFile = new File("C:/Users/shrim/Documents/NXTShift/roster_fixtures/October_2026_Hospital_Roster_OnePage(AutoRecovered).xlsx");
        }
    }

    // --- 1. Real Reference Workbook Parsing Tests ---

    @Test
    public void testCase1_ExtractAllSixShiftColumnsAndOffLeave() throws Exception {
        assertTrue("Reference Excel file must exist in test fixtures", referenceFile.exists());
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        assertTrue("Roster data must be valid without fatal errors", roster.isValid());
        assertEquals("Expected 0 fatal errors", 0, roster.getErrors().size());

        List<ExcelShiftColumn> cols = roster.getColumns();
        // Should have 8 columns: 6 shifts + Off + Leave
        assertEquals("Expected 8 total shift/status columns", 8, cols.size());

        boolean has9to5 = false;
        boolean has10to6 = false;
        boolean has12to8 = false;
        boolean has8to2 = false;
        boolean has2to8 = false;
        boolean has8to8 = false;
        boolean hasOff = false;
        boolean hasLeave = false;

        for (ExcelShiftColumn c : cols) {
            String h = c.getHeaderName();
            if (h.contains("9 AM") || h.contains("9-5")) has9to5 = true;
            if (h.contains("10 AM") || h.contains("10-6")) has10to6 = true;
            if (h.contains("12 PM") || h.contains("12-8")) has12to8 = true;
            if (h.contains("8 AM") && h.contains("2 PM")) has8to2 = true;
            if (h.contains("2 PM") && h.contains("8 PM")) has2to8 = true;
            if (h.contains("8 PM") && h.contains("8 AM")) has8to8 = true;
            if (c.isOff()) hasOff = true;
            if (c.isLeave()) hasLeave = true;
        }

        assertTrue("Must extract 9 AM–5 PM shift", has9to5);
        assertTrue("Must extract 10 AM–6 PM shift", has10to6);
        assertTrue("Must extract 12 PM–8 PM shift", has12to8);
        assertTrue("Must extract 8 AM–2 PM shift", has8to2);
        assertTrue("Must extract 2 PM–8 PM shift", has2to8);
        assertTrue("Must extract 8 PM–8 AM shift", has8to8);
        assertTrue("Must extract Off column", hasOff);
        assertTrue("Must extract Leave column", hasLeave);
    }

    @Test
    public void testCase2_DatesAndOctober2026MonthHandling() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        assertEquals("Expected year 2026", 2026, roster.getYear());
        assertEquals("Expected month 10", 10, roster.getMonth());
        assertTrue("Expected October in month label", roster.getMonthLabel().contains("October"));
        assertEquals("October must have exactly 31 day rows", 31, roster.getDays().size());

        // Verify first and last date format
        assertEquals("2026-10-01", roster.getDays().get(0).getIsoDate());
        assertEquals(1, roster.getDays().get(0).getDayNumber());

        assertEquals("2026-10-31", roster.getDays().get(30).getIsoDate());
        assertEquals(31, roster.getDays().get(30).getDayNumber());
    }

    @Test
    public void testCase3_MultipleCommaSeparatedNamesInOneCell() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // Day 1 (Oct 1) has 9 AM-5 PM column with: "Ram, Vikas, Hansraj, Naveen, Kuldeep"
        ExcelDayRow day1 = roster.getDays().get(0);
        List<String> staff9to5 = null;
        for (ExcelShiftColumn c : roster.getColumns()) {
            if (c.getHeaderName().contains("9 AM")) {
                staff9to5 = day1.getStaffForColumn(c.getColumnIndex());
                break;
            }
        }

        assertNotNull("Expected staff in 9 AM column on day 1", staff9to5);
        assertEquals(5, staff9to5.size());
        assertTrue(staff9to5.contains("Ram"));
        assertTrue(staff9to5.contains("Vikas"));
        assertTrue(staff9to5.contains("Hansraj"));
        assertTrue(staff9to5.contains("Naveen"));
        assertTrue(staff9to5.contains("Kuldeep"));
    }

    @Test
    public void testCase4_ColleagueExtractionForSelectedNurse() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // Extract schedule for Kuldeep
        ExcelStaffSchedule schedule = roster.extractScheduleForStaff("Kuldeep");
        assertEquals("Kuldeep", schedule.getStaffName());

        // On Oct 1: Kuldeep is in 9 AM-5 PM with Ram, Vikas, Hansraj, Naveen
        ExcelStaffScheduleItem itemOct1 = schedule.getItems().get(0);
        assertEquals("2026-10-01", itemOct1.getDate());
        assertEquals(ExcelStaffScheduleItem.StatusType.DUTY, itemOct1.getStatus());
        assertFalse("Kuldeep must NOT be included in his own colleague list",
                itemOct1.getColleagues().contains("Kuldeep"));
        assertTrue("Expected Ram as colleague", itemOct1.getColleagues().contains("Ram"));
        assertTrue("Expected Vikas as colleague", itemOct1.getColleagues().contains("Vikas"));
        assertTrue("Expected Hansraj as colleague", itemOct1.getColleagues().contains("Hansraj"));
        assertTrue("Expected Naveen as colleague", itemOct1.getColleagues().contains("Naveen"));

        // On Oct 2: Kuldeep is in 12 PM-8 PM alone -> colleagues must be empty
        ExcelStaffScheduleItem itemOct2 = schedule.getItems().get(1);
        assertEquals("2026-10-02", itemOct2.getDate());
        assertEquals(ExcelStaffScheduleItem.StatusType.DUTY, itemOct2.getStatus());
        assertTrue("Expected empty colleague list when working solo",
                itemOct2.getColleagues().trim().isEmpty());
    }

    @Test
    public void testCase5_ExtractionOfOffAndLeaveEntries() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // Extract schedule for Shiv
        ExcelStaffSchedule schedule = roster.extractScheduleForStaff("Shiv");

        // On Oct 2: Shiv is listed under Off (col H) and NOT in any shift
        ExcelStaffScheduleItem itemOct2 = schedule.getItems().get(1);
        assertEquals("2026-10-02", itemOct2.getDate());
        assertEquals(ExcelStaffScheduleItem.StatusType.OFF, itemOct2.getStatus());
        assertEquals(ShiftType.OFF, itemOct2.getShiftType());
        assertTrue("Colleagues should be empty for Off day", itemOct2.getColleagues().isEmpty());

        // Extract schedule for Hansraj
        ExcelStaffSchedule scheduleHansraj = roster.extractScheduleForStaff("Hansraj");

        // On Oct 4: Hansraj is on Leave (col I)
        ExcelStaffScheduleItem itemOct4 = scheduleHansraj.getItems().get(3);
        assertEquals("2026-10-04", itemOct4.getDate());
        assertEquals(ExcelStaffScheduleItem.StatusType.LEAVE, itemOct4.getStatus());
        assertEquals(ShiftType.LEAVE, itemOct4.getShiftType());
    }

    @Test
    public void testCase6_NurseInMultipleShiftsOnSameDate_FlagsConflict() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // On Oct 31 (Row 35 in reference workbook):
        // Munesh appears in 8 AM-2 PM (Col E) AND 8 PM-8 AM (Col G)!
        ExcelStaffSchedule scheduleMunesh = roster.extractScheduleForStaff("Munesh");
        ExcelStaffScheduleItem itemOct31 = scheduleMunesh.getItems().get(30);

        assertEquals("2026-10-31", itemOct31.getDate());
        assertTrue("Must flag conflict when nurse appears in multiple shifts on same date", itemOct31.hasConflict());
        assertEquals(ExcelStaffScheduleItem.StatusType.CONFLICT, itemOct31.getStatus());
        assertTrue("Warning must describe multiple shift assignments",
                itemOct31.getCombinedWarnings().toLowerCase().contains("multiple shift"));
    }

    @Test
    public void testCase7_NurseUnderDutyAndLeave_FlagsConflict() {
        // Construct synthetic roster day with nurse in both Morning Duty and Leave
        ExcelRosterData synthetic = new ExcelRosterData(2026, 10, "October 2026", "TestSheet");
        ExcelShiftColumn morningCol = new ExcelShiftColumn("B", 1, "8 AM – 2 PM", ShiftType.MORNING, "08:00 AM", "02:00 PM", false, false, false);
        ExcelShiftColumn leaveCol = new ExcelShiftColumn("C", 2, "Leave", ShiftType.LEAVE, "", "", false, false, true);
        synthetic.getColumns().add(morningCol);
        synthetic.getColumns().add(leaveCol);

        ExcelDayRow day = new ExcelDayRow("2026-10-05", 5);
        day.addStaffToColumn(1, Collections.singletonList("Yamini"));
        day.addStaffToColumn(2, Collections.singletonList("Yamini"));
        synthetic.getDays().add(day);

        ExcelStaffSchedule schedule = synthetic.extractScheduleForStaff("Yamini");
        ExcelStaffScheduleItem item = schedule.getItems().get(0);

        assertTrue("Must flag conflict when nurse is in both Duty and Leave", item.hasConflict());
        assertEquals(ExcelStaffScheduleItem.StatusType.CONFLICT, item.getStatus());
        assertTrue("Warning must mention Leave conflict",
                item.getCombinedWarnings().toLowerCase().contains("leave"));
    }

    @Test
    public void testCase8_NurseUnderOffAndLeave_FlagsConflict() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // On Oct 2 (Row 6 in reference workbook):
        // Ajay Jindoliya appears in Off (Col H) AND Leave (Col I)!
        ExcelStaffSchedule schedule = roster.extractScheduleForStaff("Ajay Jindoliya");
        ExcelStaffScheduleItem itemOct2 = schedule.getItems().get(1);

        assertEquals("2026-10-02", itemOct2.getDate());
        assertTrue("Must flag conflict when nurse is marked in both Off and Leave", itemOct2.hasConflict());
        assertEquals(ExcelStaffScheduleItem.StatusType.CONFLICT, itemOct2.getStatus());
        assertTrue("Warning must describe both Off and Leave conflict",
                itemOct2.getCombinedWarnings().toLowerCase().contains("both off and leave"));
    }

    @Test
    public void testCase9_EmptyCellsAndDatesWithNoAssignment() {
        ExcelRosterData synthetic = new ExcelRosterData(2026, 10, "October 2026", "TestSheet");
        ExcelShiftColumn dutyCol = new ExcelShiftColumn("B", 1, "9 AM – 5 PM", ShiftType.GENERAL, "09:00 AM", "05:00 PM", false, false, false);
        synthetic.getColumns().add(dutyCol);

        // Day with other nurses, but not target nurse
        ExcelDayRow day = new ExcelDayRow("2026-10-10", 10);
        day.addStaffToColumn(1, Arrays.asList("Ram", "Vikas"));
        synthetic.getDays().add(day);

        ExcelStaffSchedule schedule = synthetic.extractScheduleForStaff("Kuldeep");
        ExcelStaffScheduleItem item = schedule.getItems().get(0);

        assertEquals(ExcelStaffScheduleItem.StatusType.UNASSIGNED, item.getStatus());
        assertFalse("Unassigned date is not a conflict, but marked unassigned", item.hasConflict());
        assertTrue("Warning should indicate no duty, off, or leave",
                item.getCombinedWarnings().toLowerCase().contains("no duty") ||
                item.getCombinedWarnings().toLowerCase().contains("no assignment"));
    }

    @Test
    public void testCase10_OvernightShiftTimingAndNextDayEndTime() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // Govind on Oct 1 is in 8 PM-8 AM (Overnight)
        ExcelStaffSchedule schedule = roster.extractScheduleForStaff("Govind");
        ExcelStaffScheduleItem itemOct1 = schedule.getItems().get(0);

        assertEquals("2026-10-01", itemOct1.getDate());
        assertEquals(ExcelStaffScheduleItem.StatusType.DUTY, itemOct1.getStatus());
        assertTrue("Overnight flag must be true for 8 PM-8 AM", itemOct1.isOvernight());
        assertEquals("08:00 PM", itemOct1.getStartTime());
        assertEquals("08:00 AM", itemOct1.getEndTime());
        assertEquals(ShiftType.NIGHT, itemOct1.getShiftType());

        // Verify conversion to NXTShift Duty domain model
        Duty domainDuty = itemOct1.toDuty("test_user_uid");
        assertEquals("2026-10-01", domainDuty.getDutyDate());
        assertTrue("Duty must preserve overnight setting", domainDuty.isOvernight());
        assertEquals(DutyStatus.SCHEDULED, domainDuty.getStatus());
        assertTrue("Formatted time must include next day indicator",
                domainDuty.getFormattedDutyTime().contains("+1 day"));
    }

    @Test
    public void testCase11_CaseWhitespaceAndMultiWordNamePreservation() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // Must keep Ajay Jangid and Ajay Jindoliya strictly separate
        assertTrue("Must contain Ajay Jangid", roster.getUniqueStaffNames().contains("Ajay Jangid"));
        assertTrue("Must contain Ajay Jindoliya", roster.getUniqueStaffNames().contains("Ajay Jindoliya"));

        ExcelStaffSchedule scheduleJangid = roster.extractScheduleForStaff("ajay jangid  ");
        ExcelStaffSchedule scheduleJindoliya = roster.extractScheduleForStaff("Ajay Jindoliya");

        // Verify that on Oct 1 (day 1), Ajay Jangid has 2 PM-8 PM duty, whereas Ajay Jindoliya has no assignment
        ExcelStaffScheduleItem jangidDay1 = scheduleJangid.getItems().get(0);
        ExcelStaffScheduleItem jindoliyaDay1 = scheduleJindoliya.getItems().get(0);

        assertEquals("2026-10-01", jangidDay1.getDate());
        assertEquals(ExcelStaffScheduleItem.StatusType.DUTY, jangidDay1.getStatus());
        assertTrue(jangidDay1.getShiftName().contains("2 PM"));

        assertEquals("2026-10-01", jindoliyaDay1.getDate());
        assertEquals(ExcelStaffScheduleItem.StatusType.UNASSIGNED, jindoliyaDay1.getStatus());

        // Verify that on Oct 2 (day 2), Ajay Jangid is working duty, while Ajay Jindoliya is conflicted (Off & Leave)
        ExcelStaffScheduleItem jangidDay2 = scheduleJangid.getItems().get(1);
        ExcelStaffScheduleItem jindoliyaDay2 = scheduleJindoliya.getItems().get(1);

        assertEquals(ExcelStaffScheduleItem.StatusType.DUTY, jangidDay2.getStatus());
        assertEquals(ExcelStaffScheduleItem.StatusType.CONFLICT, jindoliyaDay2.getStatus());

        // Both schedules cover all 31 days
        assertEquals(31, scheduleJangid.getItems().size());
        assertEquals(31, scheduleJindoliya.getItems().size());
    }

    @Test
    public void testCase12_InvalidOrUnsupportedExcelFiles() {
        // Corrupted / Empty stream
        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        try {
            ExcelRosterData data = ExcelRosterParser.parse(emptyStream, "empty.xlsx");
            assertFalse("Empty file must not be valid", data.isValid());
            assertTrue("Must report error for empty workbook", data.getErrors().size() > 0);
        } catch (Exception e) {
            // Either returns error data or throws IOException
            assertNotNull(e);
        }

        // Non-zip file
        ByteArrayInputStream textStream = new ByteArrayInputStream("Not a zip file".getBytes());
        try {
            ExcelRosterData data = ExcelRosterParser.parse(textStream, "invalid.xlsx");
            assertFalse("Invalid zip must not produce valid roster", data.isValid());
        } catch (Exception e) {
            assertNotNull(e);
        }
    }

    @Test
    public void testCase13_ImportableDutiesExcludesUnresolvedConflictsAndUnassigned() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // Ajay Jindoliya has conflict on Oct 2 (Off and Leave)
        ExcelStaffSchedule schedule = roster.extractScheduleForStaff("Ajay Jindoliya");
        List<Duty> duties = schedule.getImportableDuties("owner_123");

        for (Duty d : duties) {
            assertNotEquals("Must not import conflicted Oct 2 entry without user resolution",
                    "2026-10-02", d.getDutyDate());
            assertEquals("owner_123", d.getOwnerUid());
        }
    }

    @Test
    public void testCase14_NotesSheetDoesNotPolluteRosterOrStaffList() throws Exception {
        ExcelRosterData roster = ExcelRosterParser.parse(referenceFile);

        // Verify that footer names from the Notes sheet (e.g. Major (Dr) Anil Sharma) are excluded
        assertFalse("Footer name 1 from Notes sheet must NOT be in staff list",
                roster.getUniqueStaffNames().contains("Major (Dr) Anil Sharma"));
        assertFalse("Footer name 2 from Notes sheet must NOT be in staff list",
                roster.getUniqueStaffNames().contains("Dr. Manisha Agrawal"));
        assertFalse("Footer name 3 from Notes sheet must NOT be in staff list",
                roster.getUniqueStaffNames().contains("Ms. Shalini Jha"));
        assertFalse("Footer name 4 from Notes sheet must NOT be in staff list",
                roster.getUniqueStaffNames().contains("Mr. Tarun Sharma"));
    }

    @Test
    public void testCase15_CellDateSerialAndTextParsingDirectly() {
        // Excel serial 46296 -> 2026-10-01
        LocalDate d1 = ExcelRosterParser.parseCellDate("46296", 2026, 10);
        assertNotNull(d1);
        assertEquals(2026, d1.getYear());
        assertEquals(10, d1.getMonthValue());
        assertEquals(1, d1.getDayOfMonth());

        // ISO Date
        LocalDate d2 = ExcelRosterParser.parseCellDate("2026-10-15", 2026, 10);
        assertNotNull(d2);
        assertEquals(15, d2.getDayOfMonth());

        // Text date e.g. "02/10/2026"
        LocalDate d3 = ExcelRosterParser.parseCellDate("02/10/2026", 2026, 10);
        assertNotNull(d3);
        assertEquals(2, d3.getDayOfMonth());

        // Text date e.g. "3-Oct-2026"
        LocalDate d4 = ExcelRosterParser.parseCellDate("3-Oct-2026", 2026, 10);
        assertNotNull(d4);
        assertEquals(3, d4.getDayOfMonth());

        // Day integer "25" with fallback
        LocalDate d5 = ExcelRosterParser.parseCellDate("25", 2026, 10);
        assertNotNull(d5);
        assertEquals(25, d5.getDayOfMonth());

        // Non-date string
        LocalDate d6 = ExcelRosterParser.parseCellDate("Footer Note", 2026, 10);
        assertNull(d6);
    }

    // --- 5. Regression & Generic Multi-Month/Year Tests ---

    @Test
    public void testCase16_AutoRecoveredWorkbookParsing_All31DatesOct1ToOct31AndStaff() throws Exception {
        assertTrue("AutoRecovered reference Excel file must exist in test fixtures", autoRecoveredFile.exists());
        ExcelRosterData roster = ExcelRosterParser.parse(autoRecoveredFile, 2026, 10);

        assertTrue("Roster data must be valid without fatal errors", roster.isValid());
        assertEquals("Expected 0 fatal errors", 0, roster.getErrors().size());
        assertEquals("Expected year 2026", 2026, roster.getYear());
        assertEquals("Expected month 10", 10, roster.getMonth());

        // Assert that all 31 October dates are recognized
        List<ExcelDayRow> days = roster.getDays();
        assertEquals("All 31 October dates must be recognized", 31, days.size());

        // Assert October 1 converts to 2026-10-01
        ExcelDayRow firstDay = days.get(0);
        assertEquals("First day must be 1", 1, firstDay.getDayNumber());
        assertEquals("October 1 must convert to 2026-10-01", "2026-10-01", firstDay.getIsoDate());

        // Assert October 31 converts to 2026-10-31
        ExcelDayRow lastDay = days.get(30);
        assertEquals("Last day must be 31", 31, lastDay.getDayNumber());
        assertEquals("October 31 must convert to 2026-10-31", "2026-10-31", lastDay.getIsoDate());

        // Assert all 8 columns (six shifts, Off, Leave) are extracted
        assertEquals("Expected 8 total columns (6 shifts + Off + Leave)", 8, roster.getColumns().size());

        // Assert staff names are extracted
        List<String> staff = roster.getUniqueStaffNames();
        assertTrue("Must extract at least 10 unique clinical staff members", staff.size() >= 10);
        assertTrue("Must contain Ram", staff.contains("Ram"));
        assertTrue("Must contain Vikas", staff.contains("Vikas"));
        assertTrue("Must contain Kuldeep", staff.contains("Kuldeep"));

        // Multi-word name distinction: Ajay Jangid vs Ajay Jindoliya
        assertTrue("Must extract Ajay Jangid", staff.contains("Ajay Jangid"));
        assertTrue("Must extract Ajay Jindoliya", staff.contains("Ajay Jindoliya"));
        assertNotEquals("Ajay Jangid and Ajay Jindoliya must be distinct",
                staff.indexOf("Ajay Jangid"), staff.indexOf("Ajay Jindoliya"));

        // Notes sheet footer names must NOT be included in staff roster
        assertFalse("Notes sheet footer name Major (Dr) Anil Sharma must be excluded",
                staff.contains("Major (Dr) Anil Sharma"));
        assertFalse("Notes sheet footer name Dr. Manisha Agrawal must be excluded",
                staff.contains("Dr. Manisha Agrawal"));
        assertFalse("Notes sheet footer name Ms. Shalini Jha must be excluded",
                staff.contains("Ms. Shalini Jha"));
        assertFalse("Notes sheet footer name Mr. Tarun Sharma must be excluded",
                staff.contains("Mr. Tarun Sharma"));

        // Assert Off and Leave categorization for Ajay Jindoliya on 2026-10-02
        ExcelStaffSchedule jindoliyaSchedule = roster.extractScheduleForStaff("Ajay Jindoliya");
        ExcelStaffScheduleItem itemOct2 = jindoliyaSchedule.getItemForDate("2026-10-02");
        assertNotNull("Schedule item for 2026-10-02 must exist", itemOct2);
        assertTrue("Must flag conflict between Off and Leave on Oct 2", itemOct2.hasConflict());
        assertTrue("Conflicting list must contain Weekly Off",
                itemOct2.getConflictingAssignments().contains("Weekly Off"));
        assertTrue("Conflicting list must contain On Leave",
                itemOct2.getConflictingAssignments().contains("On Leave"));
    }

    @Test
    public void testCase17_GenericDateParsing_AllMonthsYearsAndLeapYears() {
        // October 2026 (standard 31 days)
        LocalDate oct1 = ExcelRosterParser.parseCellDate("46296", 2026, 10);
        assertEquals(LocalDate.of(2026, 10, 1), oct1);
        LocalDate oct31 = ExcelRosterParser.parseCellDate("46326", 2026, 10);
        assertEquals(LocalDate.of(2026, 10, 31), oct31);

        // November 2026 (standard 30 days)
        LocalDate nov1 = ExcelRosterParser.parseCellDate("46327", 2026, 11);
        assertEquals(LocalDate.of(2026, 11, 1), nov1);
        LocalDate nov30 = ExcelRosterParser.parseCellDate("46356", 2026, 11);
        assertEquals(LocalDate.of(2026, 11, 30), nov30);

        // January 2027 (future year, 31 days)
        LocalDate jan1 = ExcelRosterParser.parseCellDate("46388", 2027, 1);
        assertEquals(LocalDate.of(2027, 1, 1), jan1);
        LocalDate jan31 = ExcelRosterParser.parseCellDate("46418", 2027, 1);
        assertEquals(LocalDate.of(2027, 1, 31), jan31);

        // February 2028 (LEAP YEAR: 29 days!)
        LocalDate feb1 = ExcelRosterParser.parseCellDate("46784", 2028, 2);
        assertEquals(LocalDate.of(2028, 2, 1), feb1);
        LocalDate feb29 = ExcelRosterParser.parseCellDate("46812", 2028, 2);
        assertEquals(LocalDate.of(2028, 2, 29), feb29);
        assertTrue("2028 must be recognized as leap year", feb29.isLeapYear());
        assertEquals("Feb 2028 must have 29 days", 29, feb29.lengthOfMonth());

        // Plain text day with leap year context
        LocalDate feb29Text = ExcelRosterParser.parseCellDate("29", 2028, 2);
        assertNotNull(feb29Text);
        assertEquals(LocalDate.of(2028, 2, 29), feb29Text);

        // Plain text day 29 in non-leap year (e.g. Feb 2027) must be rejected
        LocalDate feb29Invalid = ExcelRosterParser.parseCellDate("29", 2027, 2);
        assertNull("Feb 29 in 2027 must be rejected as invalid", feb29Invalid);
    }

    @Test
    public void testCase18_WorkbookDateSystem1900vs1904() {
        // 1900 Date System (Windows standard):
        // Serial 46296 -> 2026-10-01
        LocalDate d1900 = ExcelRosterParser.convertSerialToLocalDate(46296, false);
        assertEquals(LocalDate.of(2026, 10, 1), d1900);

        // 1904 Date System (Mac standard):
        // 1904-01-01 is serial 0
        LocalDate macBase = ExcelRosterParser.convertSerialToLocalDate(0, true);
        assertEquals(LocalDate.of(1904, 1, 1), macBase);

        // In 1904 date system, 2026-10-01 is serial 44834 (46296 - 1462)
        LocalDate macOct1 = ExcelRosterParser.convertSerialToLocalDate(44834, true);
        assertEquals(LocalDate.of(2026, 10, 1), macOct1);
    }

    @Test
    public void testCase19_MonthAndYearFilteringBehavior() throws Exception {
        // Matching month (October 2026) -> 31 days parsed
        ExcelRosterData matching = ExcelRosterParser.parse(autoRecoveredFile, 2026, 10);
        assertTrue("Matching month must be valid", matching.isValid());
        assertEquals(31, matching.getDays().size());

        // Non-matching month (November 2026 selected for October file) -> 0 matching days and error reported
        ExcelRosterData nonMatching = ExcelRosterParser.parse(autoRecoveredFile, 2026, 11);
        assertFalse("Non-matching month must not be valid", nonMatching.isValid());
        assertEquals(0, nonMatching.getDays().size());
        assertTrue("Must report error for no matching dates",
                nonMatching.getErrors().get(0).contains("No valid dates found matching November 2026"));

        // When month/year is omitted (null), infers from sheet name and parses all 31 days
        ExcelRosterData inferred = ExcelRosterParser.parse(autoRecoveredFile, null, null);
        assertTrue("Inferred month must be valid", inferred.isValid());
        assertEquals(2026, inferred.getYear());
        assertEquals(10, inferred.getMonth());
        assertEquals(31, inferred.getDays().size());
    }

    @Test
    public void testCase20_RosterCsvParser_WithAutoRecoveredWorkbook() throws Exception {
        // Verify RosterCsvParser handles the AutoRecovered XLSX file through ExcelRosterParser delegation
        try (InputStream is = new FileInputStream(autoRecoveredFile)) {
            RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, autoRecoveredFile.getName(), 2026, 10);
            assertTrue("RosterCsvParser must successfully parse AutoRecovered workbook", result.isValid());
            assertTrue("Errors must be empty", result.errors.isEmpty());
            assertNotNull("MonthlyRoster must not be null", result.roster);
            assertEquals("MonthlyRoster must contain 31 days", 31, result.roster.getDays().size());
            assertEquals("First day must be 1", 1, result.roster.getDays().get(0).getDayNumber());
            assertEquals("First day date must be 2026-10-01", "2026-10-01", result.roster.getDays().get(0).getDate());
            assertEquals("Last day date must be 2026-10-31", "2026-10-31", result.roster.getDays().get(30).getDate());
        }
    }
}
