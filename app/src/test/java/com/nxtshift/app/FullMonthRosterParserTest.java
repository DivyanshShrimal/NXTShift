package com.nxtshift.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ocr.FullMonthRosterParser;
import com.nxtshift.app.ocr.RosterTextParser;
import com.nxtshift.app.ocr.StaffAllowlistManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class FullMonthRosterParserTest {

    @Before
    public void setUp() {
        StaffAllowlistManager.resetToDefaultAllowlist();
        StaffAllowlistManager.setYaminiEnabled(true);
        StaffAllowlistManager.addConfiguredIdentity("Priya");
    }

    @After
    public void tearDown() {
        StaffAllowlistManager.resetToDefaultAllowlist();
    }

    @Test
    public void testDetectMonthYear_HeaderFormats() {
        // "OCTOBER 2026"
        FullMonthRosterParser.MonthYearResult r1 = FullMonthRosterParser.detectMonthYear("CITY HOSPITAL - DUTY ROSTER\nOCTOBER 2026\nICU DEPARTMENT");
        assertEquals(2026, r1.year);
        assertEquals(10, r1.month);
        assertEquals("October", r1.monthName);
        assertTrue(r1.isConfident);

        // "ROSTER FOR NOV 2026"
        FullMonthRosterParser.MonthYearResult r2 = FullMonthRosterParser.detectMonthYear("ROSTER FOR NOV 2026");
        assertEquals(2026, r2.year);
        assertEquals(11, r2.month);
        assertEquals("November", r2.monthName);

        // "DECEMBER, 2026"
        FullMonthRosterParser.MonthYearResult r3 = FullMonthRosterParser.detectMonthYear("DUTY SCHEDULE: DECEMBER, 2026");
        assertEquals(2026, r3.year);
        assertEquals(12, r3.month);
        assertEquals("December", r3.monthName);

        // Slash format "04/2027"
        FullMonthRosterParser.MonthYearResult r4 = FullMonthRosterParser.detectMonthYear("SCHEDULE FOR 04/2027");
        assertEquals(2027, r4.year);
        assertEquals(4, r4.month);
        assertEquals("April", r4.monthName);
    }

    @Test
    public void testDynamicShiftTimings_WithOcrConfusion() {
        // Header with misread digits e.g. "Sam" (8am) to "Spm" (5pm)
        String headerText = "SHIFTS TIMING:\n" +
                "MORNING: Sam - 2pm\n" +
                "EVENING: 2pm - Bpm\n" +
                "NIGHT: Bpm - Sam\n";
        FullMonthRosterParser.DynamicShiftTimings custom = FullMonthRosterParser.detectShiftTimings(headerText);
        assertEquals("08:00 AM", custom.morningStart);
        assertEquals("02:00 PM", custom.morningEnd);
        assertEquals("02:00 PM", custom.eveningStart);
        assertEquals("08:00 PM", custom.eveningEnd);
        assertEquals("08:00 PM", custom.nightStart);
        assertEquals("08:00 AM", custom.nightEnd);
    }

    @Test
    public void testStaffDiscovery_ExcludesMonthAndHeaderArtifacts() {
        // Real-world bug fixture containing Month Oct and Month Sam Spm
        String rosterContent = "CITY HOSPITAL DUTY ROSTER\n" +
                "Month: Oct 2026\n" +
                "Timings: Month Sam Spm\n" +
                "DATE | MORNING | EVENING | NIGHT | OFF\n" +
                "01/10/2026 | Yamini, Priya | Sunita | Munesh | Rekha\n" +
                "02/10/2026 | Sunita | Yamini | Priya | Munesh\n" +
                "03/10/2026 | Munesh | Priya | Yamini | Sunita\n";

        List<FullMonthRosterParser.DiscoveredStaffMember> staff =
                FullMonthRosterParser.discoverStaffDirectory(null, rosterContent);

        assertNotNull(staff);

        // Verify that NO header text became staff names
        for (FullMonthRosterParser.DiscoveredStaffMember s : staff) {
            assertFalse("Month Oct must NOT be a staff candidate", s.name.equalsIgnoreCase("Month Oct"));
            assertFalse("Month Sam Spm must NOT be a staff candidate", s.name.equalsIgnoreCase("Month Sam Spm"));
            assertFalse("Duty Roster must NOT be a staff candidate", s.name.equalsIgnoreCase("Duty Roster"));
            assertFalse("City Hospital must NOT be a staff candidate", s.name.equalsIgnoreCase("City Hospital"));
        }

        // Verify Yamini discovered
        boolean foundYamini = false;
        for (FullMonthRosterParser.DiscoveredStaffMember s : staff) {
            if (s.name.equalsIgnoreCase("Yamini")) {
                foundYamini = true;
                assertEquals(3, s.dutyCount);
            }
        }
        assertTrue("Yamini must be discovered in roster", foundYamini);
    }

    @Test
    public void testStaffDiscovery_KeepsSimilarNamesSeparate() {
        com.nxtshift.app.ocr.StaffAllowlistManager.addConfiguredIdentity("Anita");
        com.nxtshift.app.ocr.StaffAllowlistManager.addConfiguredIdentity("Sunita");
        com.nxtshift.app.ocr.StaffAllowlistManager.addConfiguredIdentity("Yamini K");
        try {
            // Verify Anita and Sunita, Yamini and Yamini K are NOT merged automatically
            String rosterContent = "01/10/2026 | Yamini | Anita\n" +
                    "02/10/2026 | Yamini K | Sunita\n";

            List<FullMonthRosterParser.DiscoveredStaffMember> staff =
                    FullMonthRosterParser.discoverStaffDirectory(null, rosterContent);

            boolean hasYamini = false;
            boolean hasYaminiK = false;
            boolean hasAnita = false;
            boolean hasSunita = false;

            for (FullMonthRosterParser.DiscoveredStaffMember s : staff) {
                if (s.name.equals("Yamini")) hasYamini = true;
                if (s.name.equals("Yamini K")) hasYaminiK = true;
                if (s.name.equals("Anita")) hasAnita = true;
                if (s.name.equals("Sunita")) hasSunita = true;
            }

            assertTrue("Yamini and Yamini K must remain separate candidates", hasYamini && hasYaminiK);
            assertTrue("Anita and Sunita must remain separate candidates", hasAnita && hasSunita);
        } finally {
            com.nxtshift.app.ocr.StaffAllowlistManager.resetToDefaultAllowlist();
        }
    }

    @Test
    public void testSplitStaffMember() {
        FullMonthRosterParser.DiscoveredStaffMember combined =
                new FullMonthRosterParser.DiscoveredStaffMember("Priya Sunita", "priyasunita", 10);

        List<FullMonthRosterParser.DiscoveredStaffMember> split =
                FullMonthRosterParser.splitStaffMember(combined, "Priya", "Sunita");

        assertEquals(2, split.size());
        assertEquals("Priya", split.get(0).name);
        assertEquals("Sunita", split.get(1).name);
        assertEquals(5, split.get(0).dutyCount);
        assertEquals(5, split.get(1).dutyCount);
    }

    @Test
    public void testStrictCellColleagueIsolation_LayoutA() {
        String rowText = "05/10/2026 | MORNING: Sunita, Anita | EVENING: Yamini, Priya | NIGHT: Kavita\n";

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, rowText, "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        RosterTextParser.ExtractedShiftItem d = duties.get(0);
        assertEquals("2026-10-05", d.date);
        assertEquals(ShiftType.EVENING, d.shiftType);
        assertEquals("Priya", d.colleagues);
        assertFalse("Sunita must not be in Evening cell", d.colleagues.contains("Sunita"));
        assertFalse("Anita must not be in Evening cell", d.colleagues.contains("Anita"));
        assertFalse("Kavita must not be in Evening cell", d.colleagues.contains("Kavita"));
    }

    @Test
    public void testTargetedExtraction_LayoutBStaffRows() {
        // Layout B: Staff row with Day 1..5 columns
        String roster = "Staff Name | 1 | 2 | 3 | 4 | 5\n" +
                "Yamini | M | E | N | OFF | M\n" +
                "Priya | M | N | E | M | OFF\n";

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Yamini", 2026, 10);

        // Day 4 is OFF: Off does not produce a working-duty entry per Requirement 4
        // So 4 working duties are extracted (Day 1, 2, 3, 5)
        assertEquals(4, duties.size());

        // Day 1: Morning, Colleague = Priya
        assertEquals("2026-10-01", duties.get(0).date);
        assertEquals(ShiftType.MORNING, duties.get(0).shiftType);
        assertTrue(duties.get(0).colleagues.contains("Priya"));

        // Day 2: Evening, Priya is Night -> Not colleague
        assertEquals("2026-10-02", duties.get(1).date);
        assertEquals(ShiftType.EVENING, duties.get(1).shiftType);
        assertFalse(duties.get(1).colleagues.contains("Priya"));

        // Day 3: Night, overnight = true
        assertEquals("2026-10-03", duties.get(2).date);
        assertEquals(ShiftType.NIGHT, duties.get(2).shiftType);
        assertTrue(duties.get(2).isOvernight);

        // Day 5: Morning
        assertEquals("2026-10-05", duties.get(3).date);
        assertEquals(ShiftType.MORNING, duties.get(3).shiftType);

        // Verify Day 4 (OFF) is not present as a working duty
        for (RosterTextParser.ExtractedShiftItem item : duties) {
            assertFalse("Day 4 (OFF) must not be created as a working duty", "2026-10-04".equals(item.date));
        }
    }
}
